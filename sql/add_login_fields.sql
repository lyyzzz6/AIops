USE netdata_ops;
ALTER TABLE sys_user ADD COLUMN `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数';
ALTER TABLE sys_user ADD COLUMN `locked_until` DATETIME DEFAULT NULL COMMENT '账户锁定截止时间';
ALTER TABLE sys_user ADD COLUMN `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间';
ALTER TABLE sys_user ADD COLUMN `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP';
