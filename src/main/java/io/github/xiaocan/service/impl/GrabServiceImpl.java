package io.github.xiaocan.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.github.xiaocan.config.BusinessException;
import io.github.xiaocan.http.GrabAuth;
import io.github.xiaocan.http.MerchantBlacklistHolder;
import io.github.xiaocan.http.XiaochanHttp;
import io.github.xiaocan.mapper.GrabConfigMapper;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.entity.GrabHistoryEntity;
import io.github.xiaocan.model.entity.LocationEntity;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.entity.UserEntity;
import io.github.xiaocan.model.StoreInfo;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.vo.GrabCardCountVO;
import io.github.xiaocan.model.vo.GrabCardVO;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;
import io.github.xiaocan.service.GrabService;
import io.github.xiaocan.service.LocationService;
import io.github.xiaocan.service.UserService;
import io.github.xiaocan.tasks.GrabCronScheduler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 抢单服务实现
 */
@Slf4j
@Service
public class GrabServiceImpl extends ServiceImpl<GrabConfigMapper, GrabConfigEntity> implements GrabService {

    @Resource
    private UserService userService;
    @Resource
    private LocationService locationService;
    @Resource
    private io.github.xiaocan.service.PushService pushService;
    private final XiaochanHttp xiaochanHttp = new XiaochanHttp();
    @Resource
    @Lazy
    private GrabCronScheduler grabCronScheduler;
    @Resource
    private io.github.xiaocan.mapper.GrabHistoryMapper grabHistoryMapper;
    @Resource
    private io.github.xiaocan.service.LoginStateService loginStateService;


    // ==================== 抢单配置 ====================

    @Override
    public void addUpdateConfig(GrabConfigDTO dto) {
        log.info("保存抢单配置请求: configId={}, loginStateId={}, promotionId={}, silkId={}, cron={}, executeAt={}",
                dto.getId(), dto.getLoginStateId(), dto.getPromotionId(), dto.getSilkId(), dto.getCron(), dto.getExecuteAt());
        String cron = dto.getCron();
        if (StringUtils.hasText(cron)) {
            String trimmed = cron.trim();
            if (!CronExpression.isValidExpression(trimmed)) {
                throw new BusinessException("cron 表达式格式不正确");
            }
            dto.setCron(trimmed);
        } else {
            dto.setCron(null);
        }
        UserEntity user = userService.getByCurrentRequest();
        // 校验登录态归属（统一池 login_state）
        if (dto.getLoginStateId() != null) {
            LoginStateEntity ls = loginStateService.getEntity(dto.getLoginStateId());
            if (ls == null) {
                throw new BusinessException("登录态不存在或无权使用");
            }
        }
        GrabConfigEntity entity;
        if (dto.getId() != null) {
            entity = getById(dto.getId());
            if (entity == null || !entity.getUserId().equals(user.getId())) {
                throw new BusinessException("无权修改该抢单配置");
            }
        } else {
            entity = new GrabConfigEntity();
            entity.setUserId(user.getId());
            entity.setStatus(MonitorConfigStatusEnums.ENABLE);
        }
        BeanUtils.copyProperties(dto, entity);
        if (entity.getStorePlatform() == null) entity.setStorePlatform(1);
        if (entity.getIfAdvanceOrder() == null) entity.setIfAdvanceOrder(false);
        if (entity.getLeadMs() == null) entity.setLeadMs(0);
        if (entity.getEnableRetry() == null) entity.setEnableRetry(true);
        if (entity.getMaxRetry() == null) entity.setMaxRetry(3);
        if (entity.getRetryIntervalMs() == null) entity.setRetryIntervalMs(500);
        if (entity.getSilkId() == null) entity.setSilkId(0);
        saveOrUpdate(entity);
        grabCronScheduler.refresh(entity.getId());
    }

    @Override
    public List<GrabConfigVO> listByUserId() {
        Integer uid = userService.getByCurrentRequest().getId();
        // 过滤监控自动抢单(立即抢)产生的占位记录(auto=1)，它们不展示在前端抢单列表中
        return this.lambdaQuery().eq(GrabConfigEntity::getUserId, uid)
                .ne(GrabConfigEntity::getAuto, true)
                .orderByDesc(GrabConfigEntity::getId).list().stream().map(e -> {
                    GrabConfigVO vo = new GrabConfigVO();
                    BeanUtils.copyProperties(e, vo);
                    return vo;
                }).toList();
    }

    @Override
    public void deleteById(Integer configId) {
        Integer uid = userService.getByCurrentRequest().getId();
        GrabConfigEntity entity = getById(configId);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作");
        }
        removeById(configId);
        grabCronScheduler.cancel(configId);
    }

    @Override
    public void toggleStatus(Integer configId, MonitorConfigStatusEnums status) {
        Integer uid = userService.getByCurrentRequest().getId();
        GrabConfigEntity entity = getById(configId);
        if (entity == null || !entity.getUserId().equals(uid)) {
            throw new BusinessException("无权操作");
        }
        this.lambdaUpdate().eq(GrabConfigEntity::getId, configId)
                .set(GrabConfigEntity::getStatus, status).update();
        grabCronScheduler.refresh(configId);
    }

    @Override
    public GrabResultVO executeManual(Integer configId) {
        UserEntity user = userService.getByCurrentRequest();
        GrabConfigEntity config = getById(configId);
        if (config == null || !config.getUserId().equals(user.getId())) {
            throw new BusinessException("无权操作");
        }
        return doGrab(config, "MANUAL");
    }

    @Override
    public GrabResultVO doGrab(GrabConfigEntity config, String triggerType) {
        UserEntity user = userService.getById(config.getUserId());
        GrabResultVO result = new GrabResultVO();
        if (user == null) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("用户不存在");
            return result;
        }
        // 登录态：按 config.loginStateId 取（统一池 login_state）
        // doGrab 会被定时任务调用（无 HTTP 请求上下文），用显式 userId 重载避免 getByCurrentRequest 抛错
        LoginStateEntity loginState = loginStateService.getEntityByIdAndUser(config.getLoginStateId(), user.getId());
        GrabAuth auth = loginState == null ? null : GrabAuth.builder()
                .sivir(loginState.getSivir())
                .userId(loginState.getUserVayne())
                .sessionId(loginState.getSessionId())
                .nami(loginState.getNami())
                .silkId(loginState.getSilkId())
                .build();
        if (auth == null || !auth.isComplete()) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("该配置未绑定有效登录态（或已过期）");
            saveHistory(config, user.getId(), false, -1, "未绑定有效登录态", null, 1, triggerType, null, null);
            return result;
        }
        // JWT 过期校验
        if (loginState.getExpireAt() != null && loginState.getExpireAt().isBefore(LocalDateTime.now())) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("登录态 JWT 已过期");
            saveHistory(config, user.getId(), false, -1, "登录态JWT已过期", null, 1, triggerType, null, null);
            return result;
        }
        // 饭票校验：饭票(cardId==1)为 0 时不发上游请求，直接失败推送，规避 WAF 风控
        // 查询失败(null)不阻断抢单（宁可放行也不误杀）；直接用已构造的 auth，避免依赖 HTTP 上下文（定时任务安全）
        Integer ticketCount = null;
        try {
            ticketCount = countTicketByAuth(auth);
        } catch (Exception e) {
            log.warn("抢单前饭票查询失败 configId={}: {}", config.getId(), e.getMessage());
        }
        if (ticketCount != null && ticketCount <= 0) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("饭票不足，请先领取");
            saveHistory(config, user.getId(), false, -1, "饭票不足，请先领取", null, 1, triggerType, null, null);
            push(config, user, "抢单失败", "活动" + config.getPromotionId() + " 饭票不足，请先领取");
            return result;
        }
        // 位置
        String lat, lng; Integer cityCode;
        Optional<LocationEntity> loc = locationService.getOptById(config.getLocationId());
        if (loc.isEmpty()) {
            result.setSuccess(false);
            result.setCode(-1);
            result.setMsg("位置信息不存在");
            saveHistory(config, user.getId(), false, -1, "位置信息不存在", null, 1, triggerType, null, null);
            return result;
        }
        LocationEntity l = loc.get();
        lat = l.getLatitude();
        lng = l.getLongitude();
        cityCode = l.getCityCode();

        // 活动信息快照（商家名 + 优惠明细）。detail 不随重试变化，循环外只查一次；
        // 查询失败不影响抢单主流程，留空即可。
        StoreInfo promoSnapshot = null;
        try {
            promoSnapshot = xiaochanHttp.getStorePromotionDetail(config.getPromotionId());
        } catch (Exception e) {
            log.warn("查询活动详情失败 promotionId={}: {}", config.getPromotionId(), e.getMessage());
        }
        String storeName = promoSnapshot == null ? null : promoSnapshot.getName();
        String promoDetail = promoSnapshot == null ? null : buildPromoDetail(promoSnapshot);

        // 商家黑名单拦截：拿到 storeName 后、重试循环前判断；命中则不发抢单请求，记历史 + 推失败通知 + return。
        // storeName==null（promoSnapshot 查询失败）时 isBlacklisted 返回 false，走原逻辑不误伤。
        if (MerchantBlacklistHolder.isBlacklisted(storeName)) {
            saveHistory(config, user.getId(), false, -1, "商家黑名单拦截", null, 1, triggerType, storeName, promoDetail);
            push(config, user, "抢单拦截", "活动" + config.getPromotionId() + " 商家\"" + storeName + "\"命中黑名单，已拦截");
            return fail(-1, "商家黑名单拦截");
        }

        boolean retry = Boolean.TRUE.equals(config.getEnableRetry());
        int maxRetry = retry ? Math.max(1, config.getMaxRetry() == null ? 1 : config.getMaxRetry()) : 1;
        int interval = config.getRetryIntervalMs() == null ? 500 : config.getRetryIntervalMs();

        GrabResultVO finalResult = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            JSONObject resp;
            try {
                resp = xiaochanHttp.grabPromotionQuota(auth, cityCode, lat, lng, config.getPromotionId());
            } catch (Exception e) {
                log.error("抢单请求异常 configId={}", config.getId(), e);
                saveHistory(config, user.getId(), false, -1, "请求异常:" + e.getMessage(), null, attempt, triggerType, storeName, promoDetail);
                finalResult = fail(-1, "请求异常:" + e.getMessage());
                if (attempt < maxRetry && retry) sleep(interval); else break;
                continue;
            }
            JSONObject status = resp.getJSONObject("status");
            int code = status != null ? status.getIntValue("code") : -1;
            String msg = status != null ? status.getString("msg") : "";
            Long orderId = resp.getLong("promotion_order_id");
            boolean success = (code == 0 && orderId != null);
            saveHistory(config, user.getId(), success, code, msg, orderId, attempt, triggerType, storeName, promoDetail);

            finalResult = new GrabResultVO();
            finalResult.setCode(code);
            finalResult.setMsg(msg);
            finalResult.setPromotionOrderId(orderId);
            finalResult.setSuccess(success);

            if (success) {
                this.lambdaUpdate().eq(GrabConfigEntity::getId, config.getId())
                        .set(GrabConfigEntity::getPromotionOrderId, orderId)
                        .set(GrabConfigEntity::getLastResult, "成功 orderId=" + orderId)
                        .set(GrabConfigEntity::getLastGrabTime, LocalDateTime.now())
                        .set(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.DISABLE)
                        .update();
                grabCronScheduler.cancel(config.getId());
                push(config, user, "抢单成功", "活动" + config.getPromotionId() + " 抢到，订单号 " + orderId);
                break;
            }
            if (code != 4) {
                push(config, user, "抢单失败", "活动" + config.getPromotionId() + " 失败：" + msg + "(code=" + code + ")");
                break;
            }
            if (attempt < maxRetry && retry) {
                sleep(interval);
            } else {
                push(config, user, "抢单失败", "活动" + config.getPromotionId() + " 重试" + maxRetry + "次仍为未开始/失败");
            }
        }
        if (finalResult == null) finalResult = fail(-1, "未知失败");
        return finalResult;
    }

    @Override
    public List<GrabHistoryVO> listHistoryByUserId(Integer limit) {
        Integer uid = userService.getByCurrentRequest().getId();
        List<GrabConfigEntity> configs = this.lambdaQuery()
                .eq(GrabConfigEntity::getUserId, uid).select(GrabConfigEntity::getId).list();
        if (configs.isEmpty()) return List.of();
        List<Integer> ids = configs.stream().map(GrabConfigEntity::getId).toList();
        int lim = (limit == null || limit <= 0) ? 50 : limit;
        List<GrabHistoryEntity> list = grabHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<GrabHistoryEntity>()
                        .in(GrabHistoryEntity::getGrabConfigId, ids)
                        .orderByDesc(GrabHistoryEntity::getId)
                        .last("limit " + lim));
        return list.stream().map(e -> {
            GrabHistoryVO vo = new GrabHistoryVO();
            BeanUtils.copyProperties(e, vo);
            return vo;
        }).toList();
    }

    @Override
    public List<GrabCardVO> listCards(Integer loginStateId, Integer number, Integer offset, Integer status) {
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity loginState = loginStateService.getEntity(loginStateId);
        if (loginState == null || !loginState.getUserId().equals(user.getId())) {
            throw new BusinessException("登录态不存在或无权使用");
        }
        if (loginState.getExpireAt() != null && loginState.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("该登录态 JWT 已过期，请更新");
        }
        GrabAuth auth = GrabAuth.builder()
                .sivir(loginState.getSivir())
                .userId(loginState.getUserVayne())
                .sessionId(loginState.getSessionId())
                .nami(loginState.getNami())
                .silkId(loginState.getSilkId())
                .build();
        if (auth == null || !auth.isComplete()) {
            throw new BusinessException("登录态不完整");
        }
        int num = number == null || number <= 0 ? 15 : Math.min(number, 50);
        int off = offset == null || offset < 0 ? 0 : offset;
        int st = status == null ? 0 : status;
        JSONObject resp = xiaochanHttp.getUserCardList(auth, num, off, st);
        JSONObject statusObj = resp.getJSONObject("status");
        if (statusObj == null || statusObj.getIntValue("code") != 0) {
            throw new BusinessException("查询卡券失败:" + (statusObj == null ? "无响应" : statusObj.getString("msg")));
        }
        JSONArray list = resp.getJSONArray("list");
        if (list == null) return List.of();
        java.time.ZoneId zone = ZoneId.systemDefault();
        return list.stream().map(o -> {
            JSONObject item = (JSONObject) o;
            JSONObject card = item.getJSONObject("card");
            GrabCardVO vo = new GrabCardVO();
            vo.setId(item.getLong("id"));
            if (card != null) {
                vo.setCardId(card.getInteger("id"));
                vo.setCardType(card.getInteger("card_type"));
                vo.setName(card.getString("name"));
                vo.setDesc(card.getString("desc"));
                vo.setPic(card.getString("pic"));
            }
            Long exp = item.getLong("expire_time");
            Long cre = item.getLong("created_at");
            if (exp != null) vo.setExpireTime(java.time.Instant.ofEpochSecond(exp).atZone(zone).toLocalDateTime());
            if (cre != null) vo.setCreatedAt(java.time.Instant.ofEpochSecond(cre).atZone(zone).toLocalDateTime());
            return vo;
        }).toList();
    }

    /** 饭票卡券名称（小蚕不同账号饭票 cardId 不固定：183 账号 cardId=1，153 账号 cardId=5。
     *  故饭票按 name=="饭票" 判断，不能写死 cardId。 */
    private static final String TICKET_CARD_NAME = "饭票";
    /** 卡券翻页单页大小 */
    private static final int CARD_PAGE_SIZE = 50;
    /** 卡券翻页累计上限（best-effort，防止异常无限翻页） */
    private static final int CARD_FETCH_MAX = 200;

    @Override
    public GrabCardCountVO countCards(Integer loginStateId) {
        GrabAuth auth = resolveAuth(loginStateId);
        // 翻页拉取全部卡券，按 (cardId, name) 聚合计数
        java.util.LinkedHashMap<Integer, GrabCardCountVO.CardCountDetail> detailMap = new java.util.LinkedHashMap<>();
        int offset = 0;
        int total = 0;
        int ticketCount = 0;
        while (total < CARD_FETCH_MAX) {
            JSONObject resp = xiaochanHttp.getUserCardList(auth, CARD_PAGE_SIZE, offset, 0);
            JSONObject statusObj = resp.getJSONObject("status");
            if (statusObj == null || statusObj.getIntValue("code") != 0) {
                throw new BusinessException("查询卡券失败:" + (statusObj == null ? "无响应" : statusObj.getString("msg")));
            }
            JSONArray list = resp.getJSONArray("list");
            if (list == null || list.isEmpty()) break;
            for (Object o : list) {
                JSONObject item = (JSONObject) o;
                JSONObject card = item.getJSONObject("card");
                Integer cardId = card == null ? null : card.getInteger("id");
                String name = card == null ? null : card.getString("name");
                if (cardId == null) continue;
                GrabCardCountVO.CardCountDetail d = detailMap.computeIfAbsent(cardId, k -> {
                    GrabCardCountVO.CardCountDetail x = new GrabCardCountVO.CardCountDetail();
                    x.setCardId(k);
                    x.setName(name);
                    x.setCount(0);
                    return x;
                });
                d.setCount(d.getCount() + 1);
                if (name != null && d.getName() == null) d.setName(name);
                if (TICKET_CARD_NAME.equals(name)) ticketCount++;
                total++;
            }
            if (list.size() < CARD_PAGE_SIZE) break; // 不满页 → 已到末尾
            offset += CARD_PAGE_SIZE;
        }
        GrabCardCountVO vo = new GrabCardCountVO();
        vo.setTicketCount(ticketCount);
        vo.setDetails(new java.util.ArrayList<>(detailMap.values()));
        return vo;
    }

    @Override
    public Integer getTicketCount(Integer loginStateId) {
        return countTicketByAuth(resolveAuth(loginStateId));
    }

    /**
     * 按已构造的 GrabAuth 翻页统计饭票(cardId==1)张数。
     * 查询失败返回 null（抢单前校验宁可放行也不误杀）。
     * 不依赖 HTTP 请求上下文，可在定时任务（doGrab）中安全调用。
     */
    private Integer countTicketByAuth(GrabAuth auth) {
        int offset = 0;
        int total = 0;
        int ticketCount = 0;
        while (total < CARD_FETCH_MAX) {
            JSONObject resp = xiaochanHttp.getUserCardList(auth, CARD_PAGE_SIZE, offset, 0);
            JSONObject statusObj = resp.getJSONObject("status");
            if (statusObj == null || statusObj.getIntValue("code") != 0) {
                // 查询失败按未知处理：返回 null 表示未知，抢单前校验放行
                return null;
            }
            JSONArray list = resp.getJSONArray("list");
            if (list == null || list.isEmpty()) break;
            for (Object o : list) {
                JSONObject item = (JSONObject) o;
                JSONObject card = item.getJSONObject("card");
                String name = card == null ? null : card.getString("name");
                if (TICKET_CARD_NAME.equals(name)) ticketCount++;
                total++;
            }
            if (list.size() < CARD_PAGE_SIZE) break;
            offset += CARD_PAGE_SIZE;
        }
        return ticketCount;
    }

    /** 复用 listCards 的登录态校验逻辑，返回 GrabAuth */
    private GrabAuth resolveAuth(Integer loginStateId) {
        UserEntity user = userService.getByCurrentRequest();
        LoginStateEntity loginState = loginStateService.getEntity(loginStateId);
        if (loginState == null || !loginState.getUserId().equals(user.getId())) {
            throw new BusinessException("登录态不存在或无权使用");
        }
        if (loginState.getExpireAt() != null && loginState.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("该登录态 JWT 已过期，请更新");
        }
        GrabAuth auth = GrabAuth.builder()
                .sivir(loginState.getSivir())
                .userId(loginState.getUserVayne())
                .sessionId(loginState.getSessionId())
                .nami(loginState.getNami())
                .silkId(loginState.getSilkId())
                .build();
        if (auth == null || !auth.isComplete()) {
            throw new BusinessException("登录态不完整");
        }
        return auth;
    }

    private void saveHistory(GrabConfigEntity config, Integer userId, boolean success, int code,
                             String msg, Long orderId, int attempt, String triggerType,
                             String storeName, String promoDetail) {
        GrabHistoryEntity h = new GrabHistoryEntity();
        h.setUserId(userId);
        h.setGrabConfigId(config.getId());
        h.setPromotionId(config.getPromotionId());
        h.setStartTime(LocalDateTime.now());
        h.setEndTime(LocalDateTime.now());
        h.setSuccess(success);
        h.setRespCode(code);
        h.setRespMsg(msg);
        h.setPromotionOrderId(orderId);
        h.setAttempt(attempt);
        h.setTriggerType(triggerType);
        h.setStoreName(storeName);
        h.setPromoDetail(promoDetail);
        grabHistoryMapper.insert(h);
    }

    /** 优惠明细：满X返Y；金额为空时返回 null。 */
    private String buildPromoDetail(StoreInfo s) {
        if (s == null) return null;
        if (s.getPrice() != null && s.getRebatePrice() != null) {
            return "满" + s.getPrice().stripTrailingZeros().toPlainString()
                    + "返" + s.getRebatePrice().stripTrailingZeros().toPlainString();
        }
        return null;
    }

    private void push(GrabConfigEntity config, UserEntity user, String summary, String body) {
        try {
            Long locationId = config.getLocationId();
            if (locationId != null) {
                pushService.pushToLocation(locationId, body, summary);
            } else {
                // 老配置无地址：回退 user.spt
                pushService.pushToUser(user.getId(), body, summary);
            }
        } catch (Exception e) {
            log.error("推送抢单结果失败", e);
        }
    }

    private GrabResultVO fail(int code, String msg) {
        GrabResultVO r = new GrabResultVO();
        r.setSuccess(false);
        r.setCode(code);
        r.setMsg(msg);
        return r;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
