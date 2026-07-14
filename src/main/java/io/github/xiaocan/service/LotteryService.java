package io.github.xiaocan.service;

import io.github.xiaocan.model.dto.LotteryAuthDTO;
import io.github.xiaocan.model.vo.LotteryAuthVO;
import io.github.xiaocan.model.vo.LotteryTaskResultVO;

import java.util.List;

/**
 * 霸王餐刷浏览任务服务
 */
public interface LotteryService {

    /**
     * 新增/更新 mini 登录态（解析粘贴的抓包 header）。id 为空则新增。
     */
    Integer saveAuth(LotteryAuthDTO dto, Integer id);

    /**
     * 登录态列表
     */
    List<LotteryAuthVO> listAuth();

    /**
     * 删除登录态
     */
    void deleteAuth(Integer id);

    /**
     * 一键刷霸王餐浏览任务（完成未完成的浏览类任务，攒抽奖机会）。
     */
    LotteryTaskResultVO runTask(Integer authId);
}
