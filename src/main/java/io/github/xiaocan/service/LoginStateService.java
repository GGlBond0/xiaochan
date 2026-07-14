package io.github.xiaocan.service;

import io.github.xiaocan.http.GrabAuth;
import io.github.xiaocan.http.LotteryAuth;
import io.github.xiaocan.model.dto.LoginStateDTO;
import io.github.xiaocan.model.entity.LoginStateEntity;
import io.github.xiaocan.model.vo.LoginStateVO;

import java.util.List;

/**
 * 小蚕 App 账号登录态统一服务（抢单 / 霸王餐刷任务 / 卡券查询共用）。
 * 历史上 grab_login_state 与 lottery_auth 两套解析逻辑在此合并为单池。
 */
public interface LoginStateService {

    /**
     * 新增/更新登录态（解析抓包 header 原文）。id 为空则新增，否则更新。返回实体。
     */
    LoginStateEntity save(LoginStateDTO dto, Integer id);

    /**
     * 当前用户登录态列表（多组）
     */
    List<LoginStateVO> list();

    /**
     * 删除登录态
     */
    void delete(Integer id);

    /**
     * 按 id 取登录态实体（归属当前请求用户）。id 为 null 或不属于当前用户时返回 null（不抛异常），
     * 供抢单/卡券等"未绑定即放行失败"的场景使用。
     */
    LoginStateEntity getEntity(Integer id);

    /**
     * 按 id 取登录态实体（归属校验用显式传入的 userId，不依赖 HTTP 请求上下文）。
     * 供定时任务（doGrab 等）在无请求线程里安全调用。id 为 null 或不属于该用户时返回 null（不抛异常）。
     */
    LoginStateEntity getEntityByIdAndUser(Integer id, Integer userId);

    /**
     * 按 id 取登录态（含所属校验，归属当前请求用户）。无权时抛 BusinessException。
     */
    LoginStateEntity getByIdAndOwner(Integer id);

    /**
     * 转为抢单登录态（GrabAuth）。null 表示不存在或无权。
     */
    GrabAuth toGrabAuth(Integer id);

    /**
     * 转为霸王餐刷任务登录态（LotteryAuth）。null 表示不存在或无权。
     */
    LotteryAuth toLotteryAuth(Integer id);
}
