USE netdata_ops;
ALTER TABLE sys_user ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除';
