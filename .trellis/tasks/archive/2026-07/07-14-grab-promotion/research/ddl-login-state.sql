-- 抢单登录态多组化 增量DDL (2026-07-14)

CREATE TABLE IF NOT EXISTS `grab_login_state` (
    `id`            INT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`       INT NOT NULL COMMENT '系统用户ID',
    `name`          VARCHAR(64) NOT NULL COMMENT '别名',
    `xc_user_id`    INT NULL DEFAULT NULL COMMENT '小蚕用户id',
    `xc_sivir`      VARCHAR(800) NULL DEFAULT NULL COMMENT '登录JWT',
    `xc_session_id` VARCHAR(64) NULL DEFAULT NULL COMMENT '会话id',
    `xc_nami`       VARCHAR(32) NULL DEFAULT NULL COMMENT 'X-Nami(可选)',
    `silk_id`       INT NULL DEFAULT 0 COMMENT 'silk_id',
    `expire_at`     DATETIME NULL DEFAULT NULL COMMENT 'JWT过期时间',
    `create_time`   DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '小蚕抢单登录态(多组)';

ALTER TABLE `grab_config`
    ADD COLUMN `login_state_id` INT NULL DEFAULT NULL COMMENT '绑定的登录态id' AFTER `user_id`;
