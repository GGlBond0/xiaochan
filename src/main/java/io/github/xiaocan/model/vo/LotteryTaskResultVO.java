package io.github.xiaocan.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 一键刷霸王餐浏览任务结果
 */
@Data
public class LotteryTaskResultVO {
    /**
     * 登录态别名
     */
    private String authName;
    /**
     * 刷前抽奖机会数（GetLotteryProgress.lottery_count）
     */
    private Integer beforeCount;
    /**
     * 刷后抽奖机会数
     */
    private Integer afterCount;
    /**
     * 当日累计已获机会数（LotteryInfo.day_num，刷前后对比）
     */
    private Integer beforeDayNum;
    private Integer afterDayNum;
    /**
     * 完成的任务清单
     */
    private List<TaskItem> tasks;
    /**
     * 失败信息（整体）
     */
    private String error;

    /**
     * 单个任务执行状态：已完成跳过 / 本次成功 / 本次失败
     */
    public enum TaskStatus {
        /** 已完成，跳过未调用 */
        SKIPPED,
        /** 本次调用成功 */
        OK,
        /** 本次调用失败 */
        FAIL
    }

    @Data
    public static class TaskItem {
        /**
         * 浏览任务类型（AddLotteryTimes.type）
         */
        private Integer type;
        /**
         * 任务描述（如 浏览霸王餐页/浏览福利页）
         */
        private String desc;
        /**
         * 执行状态
         */
        private TaskStatus status;
        /**
         * 是否成功（= status == OK，保留兼容旧消费方）
         */
        private Boolean ok;
        /**
         * 原因/消息：SKIPPED="已完成"；FAIL=失败原因（友好化）；OK 留空
         */
        private String msg;
    }
}
