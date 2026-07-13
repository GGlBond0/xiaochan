package io.github.xiaocan.tasks;

import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.enums.MonitorConfigStatusEnums;
import io.github.xiaocan.service.GrabService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 抢单 cron 动态调度器（仿 MonitorCronScheduler）。
 * 支持 cron 周期与 execute_at 一次性两种触发。
 */
@Slf4j
@Component
public class GrabCronScheduler {

    @Resource
    private TaskScheduler taskScheduler;
    @Resource
    private GrabService grabService;

    private final Map<Integer, ScheduledFuture<?>> scheduledFutureMap = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        refreshAll();
    }

    public void refreshAll() {
        log.info("开始初始化抢单调度任务");
        grabService.lambdaQuery()
                .eq(GrabConfigEntity::getStatus, MonitorConfigStatusEnums.ENABLE)
                .and(w -> w.isNotNull(GrabConfigEntity::getCron).ne(GrabConfigEntity::getCron, "")
                        .or().isNotNull(GrabConfigEntity::getExecuteAt))
                .list()
                .forEach(this::schedule);
        log.info("抢单调度任务初始化完成，共 {} 个", scheduledFutureMap.size());
    }

    public void refresh(Integer configId) {
        if (configId == null) return;
        cancel(configId);
        GrabConfigEntity config = grabService.getById(configId);
        if (config != null) schedule(config);
    }

    public void schedule(GrabConfigEntity config) {
        if (config == null || config.getId() == null) return;
        if (config.getStatus() != MonitorConfigStatusEnums.ENABLE) return;
        cancel(config.getId());
        String cron = config.getCron();
        LocalDateTime executeAt = config.getExecuteAt();
        try {
            ScheduledFuture<?> future = null;
            if (StringUtils.hasText(cron)) {
                CronTrigger trigger = new CronTrigger(cron.trim());
                future = taskScheduler.schedule(() -> execute(config, "CRON"), trigger);
            } else if (executeAt != null) {
                long leadMs = config.getLeadMs() == null ? 0 : config.getLeadMs();
                Instant instant = executeAt.minusNanos(leadMs * 1_000_000L)
                        .atZone(ZoneId.systemDefault()).toInstant();
                if (instant.isAfter(Instant.now())) {
                    future = taskScheduler.schedule(() -> execute(config, "ONESHOT"), instant);
                } else {
                    log.info("抢单配置 {} 的 executeAt 已过期，跳过", config.getId());
                }
            }
            if (future != null) {
                scheduledFutureMap.put(config.getId(), future);
                log.info("已注册抢单调度 configId:{}, cron:{}, executeAt:{}", config.getId(), cron, executeAt);
            }
        } catch (Exception e) {
            log.error("注册抢单调度失败 configId:{}", config.getId(), e);
        }
    }

    public void cancel(Integer configId) {
        ScheduledFuture<?> future = scheduledFutureMap.remove(configId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.info("已取消抢单调度 configId:{}", configId);
        }
    }

    private void execute(GrabConfigEntity config, String triggerType) {
        GrabConfigEntity latest = grabService.getById(config.getId());
        if (latest == null || latest.getStatus() != MonitorConfigStatusEnums.ENABLE) {
            log.info("抢单配置 {} 当前不可执行，取消调度", config.getId());
            cancel(config.getId());
            return;
        }
        try {
            grabService.doGrab(latest, triggerType);
        } catch (Exception e) {
            log.error("抢单调度执行异常 configId:{}", latest.getId(), e);
        }
    }
}
