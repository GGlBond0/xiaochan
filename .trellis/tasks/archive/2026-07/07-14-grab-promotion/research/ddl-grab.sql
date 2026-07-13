-- 抢单与定时抢单 增量DDL (2026-07-14)

ALTER TABLE `user`
    ADD COLUMN `xc_user_id`          INT          NULL DEFAULT NULL COMMENT '小蚕用户id(X-Teemo/Vayne)' AFTER `spt`,
    ADD COLUMN `xc_sivir`            VARCHAR(800) NULL DEFAULT NULL COMMENT '登录JWT(X-Sivir)' AFTER `xc_user_id`,
    ADD COLUMN `xc_session_id`       VARCHAR(64)  NULL DEFAULT NULL COMMENT '会话id(X-Session-Id)' AFTER `xc_sivir`,
    ADD COLUMN `xc_nami`             VARCHAR(32)  NULL DEFAULT NULL COMMENT 'X-Nami(可选,默认随机生成)' AFTER `xc_session_id`,
    ADD COLUMN `xc_login_update_time` DATETIME    NULL DEFAULT NULL COMMENT '登录态录入时间' AFTER `xc_nami`;

CREATE TABLE IF NOT EXISTS `grab_config` (
    `id`               INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`          INT NOT NULL COMMENT '用户ID',
    `location_id`      BIGINT NULL DEFAULT NULL COMMENT '位置信息ID',
    `promotion_id`     INT NOT NULL COMMENT '要抢的活动id(当天有效)',
    `silk_id`          INT NULL DEFAULT 0 COMMENT 'silk_id',
    `store_platform`   INT NOT NULL DEFAULT 1 COMMENT '平台',
    `if_advance_order` TINYINT NOT NULL DEFAULT 0 COMMENT '是否预售',
    `cron`             VARCHAR(50) NULL DEFAULT NULL COMMENT '定时抢单cron(6位含秒)',
    `execute_at`       DATETIME(3) NULL DEFAULT NULL COMMENT '一次性精确执行时间',
    `lead_ms`          INT NOT NULL DEFAULT 0 COMMENT '提前量(毫秒)',
    `enable_retry`     TINYINT NOT NULL DEFAULT 1 COMMENT 'code4是否重试',
    `max_retry`        INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `retry_interval_ms` INT NOT NULL DEFAULT 500 COMMENT '重试间隔(毫秒)',
    `status`           VARCHAR(20) NOT NULL DEFAULT 'ENABLE' COMMENT 'ENABLE-启用, DISABLE-停用',
    `last_result`      VARCHAR(200) NULL DEFAULT NULL COMMENT '最近一次结果',
    `last_grab_time`   DATETIME NULL DEFAULT NULL COMMENT '最近抢单时间',
    `promotion_order_id` BIGINT NULL DEFAULT NULL COMMENT '抢到的订单id',
    `create_time`      DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '抢单配置';

CREATE TABLE IF NOT EXISTS `grab_history` (
    `id`                 INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`            INT NOT NULL COMMENT '用户ID',
    `grab_config_id`    INT NOT NULL COMMENT '抢单配置ID',
    `promotion_id`       INT NOT NULL COMMENT '活动ID',
    `start_time`         DATETIME(3) NOT NULL COMMENT '开始时间',
    `end_time`           DATETIME(3) NOT NULL COMMENT '结束时间',
    `success`            TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否成功',
    `resp_code`          INT NULL DEFAULT NULL COMMENT '小蚕返回code',
    `resp_msg`           VARCHAR(200) NULL DEFAULT NULL COMMENT '小蚕返回msg',
    `promotion_order_id` BIGINT NULL DEFAULT NULL COMMENT '抢到的订单id',
    `attempt`            INT NOT NULL DEFAULT 1 COMMENT '第几次重试',
    `trigger_type`       VARCHAR(16) NULL DEFAULT NULL COMMENT 'MANUAL/CRON/ONESHOT',
    PRIMARY KEY (`id`),
    INDEX `idx_grab_config_id` (`grab_config_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '抢单执行记录';
