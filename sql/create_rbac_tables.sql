SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 角色表
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码',
    `role_name` VARCHAR(100) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '角色描述',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父角色ID',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

-- 权限表
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `permission_code` VARCHAR(100) NOT NULL COMMENT '权限编码',
    `permission_name` VARCHAR(100) NOT NULL COMMENT '权限名称',
    `module` VARCHAR(50) NOT NULL COMMENT '所属模块',
    `action` VARCHAR(50) NOT NULL COMMENT '操作类型',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '权限描述',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT '风险等级',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限表';

-- 用户角色关联表
DROP TABLE IF EXISTS `user_role`;
CREATE TABLE `user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `granted_by` BIGINT DEFAULT NULL COMMENT '授权人ID',
    `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- 角色权限关联表
DROP TABLE IF EXISTS `role_permission`;
CREATE TABLE `role_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- 初始化角色数据
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `parent_id`, `sort_order`) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '系统最高权限', NULL, 1),
(2, 'ADMIN', '管理员', '系统管理权限', 1, 2),
(3, 'OPERATOR', '运维操作员', '运维操作权限', 2, 3),
(4, 'VIEWER', '只读观察者', '只读权限', 3, 4);

-- 初始化权限数据
INSERT INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `module`, `action`, `description`, `risk_level`) VALUES
(1, 'user:read', '查看用户', 'user', 'read', '查看用户列表', 'low'),
(2, 'user:write', '编辑用户', 'user', 'write', '创建和编辑用户', 'medium'),
(3, 'user:delete', '删除用户', 'user', 'delete', '删除用户', 'high'),
(4, 'user:role_assign', '分配角色', 'user', 'role_assign', '分配角色', 'high'),
(5, 'knowledge:read', '查看知识库', 'knowledge', 'read', '查看知识库', 'low'),
(6, 'knowledge:write', '编辑知识库', 'knowledge', 'write', '编辑知识库', 'medium'),
(7, 'knowledge:delete', '删除文档', 'knowledge', 'delete', '删除文档', 'medium'),
(8, 'alert:read', '查看告警', 'alert', 'read', '查看告警', 'low'),
(9, 'alert:write', '编辑告警规则', 'alert', 'write', '编辑告警规则', 'medium'),
(10, 'alert:resolve', '解决告警', 'alert', 'resolve', '标记告警已解决', 'low'),
(11, 'execution:read', '查看执行记录', 'execution', 'read', '查看执行记录', 'low'),
(12, 'execution:request', '发起执行请求', 'execution', 'request', '发起执行请求', 'medium'),
(13, 'execution:approve', '审批执行请求', 'execution', 'approve', '审批请求', 'high'),
(14, 'execution:execute', '执行命令', 'execution', 'execute', '执行命令', 'high'),
(15, 'chat:use', '使用AI问答', 'chat', 'use', '使用AI问答功能', 'low'),
(16, 'system:config', '系统配置', 'system', 'config', '修改配置', 'high'),
(17, 'system:monitor', '系统监控', 'system', 'monitor', '查看监控', 'low'),
(18, 'approval:read', '查看审批', 'approval', 'read', '查看审批', 'low'),
(19, 'approval:process', '处理审批', 'approval', 'process', '处理审批', 'medium');

-- 初始化角色权限关联
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
-- VIEWER
(4, 1), (4, 5), (4, 8), (4, 11), (4, 15), (4, 17), (4, 18),
-- OPERATOR
(3, 1), (3, 5), (3, 6), (3, 7), (3, 8), (3, 10), (3, 11), (3, 12), (3, 15), (3, 17), (3, 18),
-- ADMIN
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 9), (2, 10),
(2, 11), (2, 12), (2, 13), (2, 14), (2, 15), (2, 16), (2, 17), (2, 18), (2, 19),
-- SUPER_ADMIN
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10),
(1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16), (1, 17), (1, 18), (1, 19);

-- 为管理员分配角色
INSERT INTO `user_role` (`user_id`, `role_id`, `granted_by`) VALUES
(1, 1, 1);

SET FOREIGN_KEY_CHECKS = 1;
