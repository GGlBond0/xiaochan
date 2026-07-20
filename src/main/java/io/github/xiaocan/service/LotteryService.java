package io.github.xiaocan.service;

import io.github.xiaocan.model.vo.LotteryDrawResultVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;

/**
 * 霸王餐刷浏览任务服务
 */
public interface LotteryService {

    /**
     * 一键刷霸王餐浏览任务（完成未完成的浏览类任务，攒抽奖机会）。
     */
    LotteryTaskResultVO runTask(Integer authId);

    /**
     * 开红包：用攒到的抽奖机会（lottery_count）循环执行抽奖，直到抽完或失败。
     *
     * @param authId 登录态 id
     */
    LotteryDrawResultVO draw(Integer authId);
}
