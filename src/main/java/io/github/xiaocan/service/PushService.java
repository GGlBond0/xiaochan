package io.github.xiaocan.service;

import java.util.List;

/**
 * 推送服务：按地址(location)路由多 spt 推送，user.spt 兜底。
 * 所有方法显式接收参数，不依赖 HTTP 请求上下文（定时任务可安全调用）。
 */
public interface PushService {

    /**
     * 取某地址的推送目标 spt 列表（启用的去重）。
     * 地址无 spt 配置时回退该地址所属用户的 user.spt。
     *
     * @param locationId 地址id，null 返回空列表
     * @return 去重后的 spt 列表，可能为空
     */
    List<String> getPushTargets(Long locationId);

    /**
     * 按地址推送：遍历该地址所有推送目标逐个发送，单个失败记日志不中断。
     *
     * @param locationId 地址id
     * @param content   正文
     * @param summary   摘要
     */
    void pushToLocation(Long locationId, String content, String summary);

    /**
     * 兜底推送：无地址语境时按用户 user.spt 推送。
     *
     * @param userId  系统用户id
     * @param content 正文
     * @param summary 摘要
     */
    void pushToUser(Integer userId, String content, String summary);

    /**
     * 测试推送：向某地址所有启用 spt 发一条测试消息，不写业务历史。
     *
     * @param locationId 地址id
     */
    void testPush(Long locationId);
}
