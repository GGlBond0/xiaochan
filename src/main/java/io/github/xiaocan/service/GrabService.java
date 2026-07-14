package io.github.xiaocan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import io.github.xiaocan.model.dto.GrabConfigDTO;
import io.github.xiaocan.model.entity.GrabConfigEntity;
import io.github.xiaocan.model.vo.GrabCardCountVO;
import io.github.xiaocan.model.vo.GrabCardVO;
import io.github.xiaocan.model.vo.GrabConfigVO;
import io.github.xiaocan.model.vo.GrabHistoryVO;
import io.github.xiaocan.model.vo.GrabResultVO;

import java.util.List;

public interface GrabService extends IService<GrabConfigEntity> {

    /**
     * 保存/更新抢单配置
     */
    void addUpdateConfig(GrabConfigDTO dto);

    /**
     * 当前用户抢单配置列表
     */
    List<GrabConfigVO> listByUserId();

    /**
     * 删除抢单配置
     */
    void deleteById(Integer configId);

    /**
     * 启用/停用
     */
    void toggleStatus(Integer configId, io.github.xiaocan.model.enums.MonitorConfigStatusEnums status);

    /**
     * 手动立即抢一次（按配置id）
     */
    GrabResultVO executeManual(Integer configId);

    /**
     * 执行一次抢单（手动/定时共用），含重试。返回最终结果。
     */
    GrabResultVO doGrab(GrabConfigEntity config, String triggerType);

    /**
     * 抢单历史记录查询
     */
    List<GrabHistoryVO> listHistoryByUserId(Integer limit);

    /**
     * 卡券查询：按登录态查该账号的卡券列表
     */
    List<GrabCardVO> listCards(Integer loginStateId, Integer number, Integer offset, Integer status);

    /**
     * 卡券数量汇总：按登录态分页拉取卡券并按 cardId 聚合计数（含饭票 cardId==1）。
     */
    GrabCardCountVO countCards(Integer loginStateId);

    /**
     * 饭票数量：按登录态查询饭票(cardId==1)张数。供抢单前校验复用。
     */
    Integer getTicketCount(Integer loginStateId);
}
