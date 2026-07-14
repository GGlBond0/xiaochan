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
         * 是否成功（AddLotteryTimes 返回 code==0）
         */
        private Boolean ok;
        /**
         * 原始返回消息（失败时）
         */
        private String msg;
    }
}
