package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.mapper.NotifyConfigMapper;
import io.github.xiaocan.model.MinimumPayExtNotifyConfig;
import io.github.xiaocan.model.StoreExtNotifyConfig;
import io.github.xiaocan.model.StoreKeywordExtNotifyConfig;
import io.github.xiaocan.model.dto.monitorConfigDTO;
import io.github.xiaocan.model.entity.MonitorConfigEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.enums.MonitorTypeEnums;
import io.github.xiaocan.model.vo.NotifyConfigVO;
import io.github.xiaocan.service.LoginStateService;
import io.github.xiaocan.service.MonitoryConfigService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.tasks.MonitorCronScheduler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 通知服务实现类
 *
 * @author xiaochan
 */
@Slf4j
@Service
public class MonitoryConfigServiceImpl extends ServiceImpl<NotifyConfigMapper, MonitorConfigEntity> implements MonitoryConfigService {

    @Resource
    private UserService userService;
    @Resource
    @Lazy
    private MonitorCronScheduler monitorCronScheduler;
    @Resource
    @Lazy
    private LoginStateService loginStateService;

    /**
     * scheduler 副作用（refresh/cancel）推迟到当前事务提交后执行，确保 refresh 内 getById
     * 读到已提交配置；无事务上下文时降级立即执行（防御，正常路径均在事务内）。
     * 事务回滚时 afterCommit 不执行 → scheduler 不被改动，与 DB 回滚保持一致。
     */
    private void afterCommit(Runnable schedulerAction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        schedulerAction.run();
                    } catch (Exception e) {
                        log.error("afterCommit scheduler 副作用执行异常", e);
                    }
                }
            });
        } else {
            schedulerAction.run();
        }
    }

    @Override
    public List<NotifyConfigVO> listByUserId() {
        return this.lambdaQuery()
                .eq(MonitorConfigEntity::getUserId, userService.getByCurrentRequest().getId())
                .list().stream().map(entity->{
                    NotifyConfigVO vo = new NotifyConfigVO();
                    BeanUtils.copyProperties(entity, vo);
                    if (entity.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
                        vo.setStoreExtNotifyConfig(JSONObject.parseObject(entity.getExtConfig(), StoreExtNotifyConfig.class));
                    } else if (entity.getType() == MonitorTypeEnums.STORE_KEYWORD) {
                        vo.setStoreKeywordExtNotifyConfig(JSONObject.parseObject(entity.getExtConfig(), StoreKeywordExtNotifyConfig.class));
                    } else {
                        vo.setMinimumPayExtNotifyConfig(JSONObject.parseObject(entity.getExtConfig(), MinimumPayExtNotifyConfig.class));
                    }
                    return vo;
        }).toList();
    }

    @Override
    public List<MonitorConfigEntity> list(MonitorTypeEnums type, MonitorConfigStatusEnums enums) {
        return this.lambdaQuery()
                .eq(MonitorConfigEntity::getType, type)
                .eq(MonitorConfigEntity::getStatus, enums)
                .list();
    }

    @Override
    public List<MonitorConfigEntity> listAllWithCron(MonitorConfigStatusEnums status) {
        return this.lambdaQuery()
                .eq(MonitorConfigEntity::getStatus, status)
                .isNotNull(MonitorConfigEntity::getCron)
                .ne(MonitorConfigEntity::getCron, "")
                .list();
    }

    @Override
    public List<MonitorConfigEntity> listWithoutCron(MonitorTypeEnums type, MonitorConfigStatusEnums enums) {
        return this.lambdaQuery()
                .eq(MonitorConfigEntity::getType, type)
                .eq(MonitorConfigEntity::getStatus, enums)
                .apply("(cron IS NULL OR cron = '')")
                .list();
    }

    @Override
    public List<MonitorConfigEntity> listWithoutCron(List<MonitorTypeEnums> types, MonitorConfigStatusEnums enums) {
        return this.lambdaQuery()
                .in(MonitorConfigEntity::getType, types)
                .eq(MonitorConfigEntity::getStatus, enums)
                .apply("(cron IS NULL OR cron = '')")
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUpdateConfig(monitorConfigDTO dto) {
        log.info("保存通知配置请求: {}", dto);
        // cron 表达式校验
        String cron = dto.getCron();
        if (StringUtils.hasText(cron)) {
            String trimmedCron = cron.trim();
            if (!CronExpression.isValidExpression(trimmedCron)) {
                throw new BusinessException("cron 表达式格式不正确");
            }
            dto.setCron(trimmedCron);
        } else {
            dto.setCron(null);
            // 未填写 cron 时，时间字段必填
            if (dto.getStartHour() == null || dto.getEndHour() == null || !StringUtils.hasText(dto.getWeeks())) {
                throw new BusinessException("未填写 cron 表达式时，开始时间、结束时间、运行星期必须填写");
            }
        }

        UserEntity user = userService.getByCurrentRequest();
        // 自动抢单校验：开启时必须绑定属于当前用户的登录态（支持多账号优先级）
        if (Boolean.TRUE.equals(dto.getAutoGrab())) {
            // 解析账号优先级列表：优先 grabLoginStateIds，空则回退 grabLoginStateId 单值
            java.util.List<Integer> accountIds = parseAccountIds(dto.getGrabLoginStateIds(), dto.getGrabLoginStateId());
            if (accountIds.isEmpty()) {
                throw new BusinessException("开启自动抢单时必须选择抢单账号");
            }
            // 去重保序校验：每个 id 必须存在且属于当前用户
            java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
            for (Integer id : accountIds) {
                if (id == null) continue;
                if (loginStateService.getEntity(id) == null) {
                    throw new BusinessException("所选抢单账号不存在或无权使用: id=" + id);
                }
                seen.add(id);
            }
            if (seen.isEmpty()) {
                throw new BusinessException("开启自动抢单时必须选择抢单账号");
            }
            // 规整化：回写去重保序的 ids 串，并把 grabLoginStateId 回填为第一个（兼容旧读路径）
            dto.setGrabLoginStateIds(joinIds(seen));
            dto.setGrabLoginStateId(seen.iterator().next());
            // 平台至少一个（顺序即优先级，由前端保证，这里只校验非空）
            if (!StringUtils.hasText(dto.getGrabPlatforms())) {
                throw new BusinessException("开启自动抢单时至少选择一个抢单平台");
            }
            // 模式空默认 SINGLE
            if (dto.getGrabMode() == null) {
                dto.setGrabMode(io.github.xiaocan.model.enums.GrabModeEnums.SINGLE);
            }
        } else {
            // 未开启自动抢单：清空抢单相关字段，避免脏数据
            dto.setGrabLoginStateId(null);
            dto.setGrabLoginStateIds(null);
            dto.setGrabPlatforms(null);
            dto.setGrabMode(null);
        }
        MonitorConfigEntity entity;
        if (dto.getId() != null) {
            entity = getById(dto.getId());
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                throw new BusinessException("无权修改该通知配置");
            }
        }else{
            entity = new MonitorConfigEntity();
            entity.setUserId(user.getId());
        }
        BeanUtils.copyProperties(dto, entity);
        if (dto.getType() == MonitorTypeEnums.STORE_ACTIVITY) {
            entity.setExtConfig(JSONObject.toJSONString(dto.getStoreExtNotifyConfig()));
        } else if (dto.getType() == MonitorTypeEnums.STORE_KEYWORD) {
            entity.setExtConfig(JSONObject.toJSONString(dto.getStoreKeywordExtNotifyConfig()));
        } else {
            entity.setExtConfig(JSONObject.toJSONString(dto.getMinimumPayExtNotifyConfig()));
        }
        saveOrUpdate(entity);
        // 配置变更后刷新 cron 调度：推迟到事务提交后，确保 refresh 内 getById 读到已提交配置
        afterCommit(() -> monitorCronScheduler.refresh(entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(int id, MonitorConfigStatusEnums statusEnums, String remark) {
        this.lambdaUpdate()
                .eq(MonitorConfigEntity::getId, id)
                .set(MonitorConfigEntity::getStatus, statusEnums)
                .set(MonitorConfigEntity::getRemark, remark)
                .update();
        afterCommit(() -> monitorCronScheduler.refresh(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Integer configId) {
        this.lambdaUpdate()
                .eq(MonitorConfigEntity::getId, configId)
                .eq(MonitorConfigEntity::getUserId, userService.getByCurrentRequest().getId())
                .remove();
        afterCommit(() -> monitorCronScheduler.cancel(configId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByLocationId(Integer locationId) {
        // 事务内读出待删配置 id，删除后 afterCommit 取消调度（cancel 不读 DB，只取消内存调度）
        java.util.List<Integer> configIds = this.lambdaQuery()
                .select(MonitorConfigEntity::getId)
                .eq(MonitorConfigEntity::getLocationId, locationId)
                .list()
                .stream()
                .map(MonitorConfigEntity::getId)
                .toList();
        this.lambdaUpdate()
                .eq(MonitorConfigEntity::getLocationId, locationId)
                .remove();
        afterCommit(() -> configIds.forEach(id -> monitorCronScheduler.cancel(id)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Integer configId, MonitorConfigStatusEnums status) {
        this.lambdaUpdate()
                .eq(MonitorConfigEntity::getId, configId)
                .set(MonitorConfigEntity::getStatus, status)
                .update();
        afterCommit(() -> monitorCronScheduler.refresh(configId));
    }

    /**
     * 解析账号优先级列表：优先 grabLoginStateIds（逗号串），空则回退 grabLoginStateId 单值。
     * 保留输入顺序，不去重（去重在调用处做）。
     */
    private java.util.List<Integer> parseAccountIds(String grabLoginStateIds, Integer grabLoginStateId) {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        if (grabLoginStateIds != null && !grabLoginStateIds.isBlank()) {
            for (String s : grabLoginStateIds.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    try {
                        list.add(Integer.parseInt(t));
                    } catch (NumberFormatException ignore) { /* 跳过非法 */ }
                }
            }
        }
        if (list.isEmpty() && grabLoginStateId != null) {
            list.add(grabLoginStateId);
        }
        return list;
    }

    /** 有序 id 集合拼成逗号串。 */
    private String joinIds(java.util.Collection<Integer> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }
}