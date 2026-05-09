-- ============================================================
-- 智能运维问答与执行系统 - RBAC 权限管理数据库迁移
-- ============================================================
--
-- 版本：V2
-- 用途：
--     - 新建RBAC相关表（角色、权限、关联表）
--     - 新建权限审批工作流表
--     - 新建操作审计日志表
--     - 修改sys_user表增加安全字段
--     - 初始化默认角色和权限数据
--
-- 作者：刘一舟
-- 更新时间：2026-05-07
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 修改 sys_user 表：新增安全相关字段
-- ============================================================
ALTER TABLE `sys_user`
    ADD COLUMN `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL' AFTER `phone`,
    ADD COLUMN `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间' AFTER `status`,
    ADD COLUMN `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP' AFTER `last_login_at`,
    ADD COLUMN `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数' AFTER `last_login_ip`,
    ADD COLUMN `locked_until` DATETIME DEFAULT NULL COMMENT '账户锁定截止时间' AFTER `login_fail_count`,
    ADD COLUMN `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除' AFTER `locked_until`;

-- 移除旧的role字段（改用关联表）
-- 注：保留role字段做兼容，后续可删除
-- ALTER TABLE `sys_user` DROP COLUMN `role`;

-- ============================================================
-- 角色表
-- ============================================================
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `role_code` VARCHAR(50) NOT NULL COMMENT '角色编码',
    `role_name` VARCHAR(100) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '角色描述',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父角色ID（支持角色继承）',
    `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序号',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

-- ============================================================
-- 权限表
-- ============================================================
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    `permission_code` VARCHAR(100) NOT NULL COMMENT '权限编码（module:action）',
    `permission_name` VARCHAR(100) NOT NULL COMMENT '权限名称',
    `module` VARCHAR(50) NOT NULL COMMENT '所属模块',
    `action` VARCHAR(50) NOT NULL COMMENT '操作类型',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '权限描述',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT '风险等级：low/medium/high',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_permission_code` (`permission_code`),
    KEY `idx_module` (`module`),
    KEY `idx_risk_level` (`risk_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限表';

-- ============================================================
-- 用户角色关联表
-- ============================================================
DROP TABLE IF EXISTS `user_role`;
CREATE TABLE `user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `granted_by` BIGINT DEFAULT NULL COMMENT '授权人ID',
    `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '过期时间（临时授权）',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_expires_at` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- ============================================================
-- 角色权限关联表
-- ============================================================
DROP TABLE IF EXISTS `role_permission`;
CREATE TABLE `role_permission` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    `permission_id` BIGINT NOT NULL COMMENT '权限ID',
    `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ============================================================
-- 权限审批请求表
-- ============================================================
DROP TABLE IF EXISTS `permission_request`;
CREATE TABLE `permission_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '请求ID',
    `request_no` VARCHAR(64) NOT NULL COMMENT '请求编号',
    `requester_id` BIGINT NOT NULL COMMENT '申请人ID',
    `request_type` VARCHAR(30) NOT NULL COMMENT '类型：ROLE_ASSIGN/PERMISSION_GRANT/TEMP_ELEVATION',
    `target_user_id` BIGINT DEFAULT NULL COMMENT '目标用户ID',
    `target_role_id` BIGINT DEFAULT NULL COMMENT '目标角色ID',
    `target_permission_ids` TEXT DEFAULT NULL COMMENT '目标权限ID列表（JSON数组）',
    `reason` VARCHAR(500) NOT NULL COMMENT '申请理由',
    `duration_hours` INT DEFAULT NULL COMMENT '临时授权持续时长（小时）',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT '系统评估风险等级',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/REVIEWING/APPROVED/REJECTED/EXPIRED',
    `current_approver_id` BIGINT DEFAULT NULL COMMENT '当前审批人ID',
    `approved_by` BIGINT DEFAULT NULL COMMENT '最终审批人ID',
    `reject_reason` VARCHAR(500) DEFAULT NULL COMMENT '拒绝理由',
    `approved_at` DATETIME DEFAULT NULL COMMENT '审批完成时间',
    `expires_at` DATETIME DEFAULT NULL COMMENT '授权过期时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_no` (`request_no`),
    KEY `idx_requester_id` (`requester_id`),
    KEY `idx_status` (`status`),
    KEY `idx_current_approver` (`current_approver_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限审批请求表';

-- ============================================================
-- 审批流程记录表
-- ============================================================
DROP TABLE IF EXISTS `approval_flow`;
CREATE TABLE `approval_flow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '流程ID',
    `request_id` BIGINT NOT NULL COMMENT '关联请求ID',
    `step_order` INT NOT NULL COMMENT '审批步骤顺序',
    `approver_id` BIGINT NOT NULL COMMENT '审批人ID',
    `action` VARCHAR(20) DEFAULT NULL COMMENT '审批动作：APPROVE/REJECT/TRANSFER',
    `comment` VARCHAR(500) DEFAULT NULL COMMENT '审批意见',
    `acted_at` DATETIME DEFAULT NULL COMMENT '操作时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_request_id` (`request_id`),
    KEY `idx_approver_id` (`approver_id`),
    KEY `idx_step_order` (`request_id`, `step_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批流程记录表';

-- ============================================================
-- 操作审计日志表
-- ============================================================
DROP TABLE IF EXISTS `operation_log`;
CREATE TABLE `operation_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    `trace_id` VARCHAR(64) DEFAULT NULL COMMENT '链路追踪ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '操作人ID',
    `username` VARCHAR(50) DEFAULT NULL COMMENT '操作人用户名',
    `module` VARCHAR(50) NOT NULL COMMENT '操作模块',
    `action` VARCHAR(50) NOT NULL COMMENT '操作类型',
    `target` VARCHAR(200) DEFAULT NULL COMMENT '操作对象',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '操作描述',
    `request_method` VARCHAR(10) DEFAULT NULL COMMENT 'HTTP方法',
    `request_url` VARCHAR(500) DEFAULT NULL COMMENT '请求URL',
    `request_params` TEXT DEFAULT NULL COMMENT '请求参数（脱敏后）',
    `response_code` INT DEFAULT NULL COMMENT '响应状态码',
    `ip_address` VARCHAR(50) DEFAULT NULL COMMENT '客户端IP',
    `user_agent` VARCHAR(500) DEFAULT NULL COMMENT '用户代理',
    `execution_time_ms` BIGINT DEFAULT NULL COMMENT '执行耗时(ms)',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '操作结果：0失败 1成功',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_module_action` (`module`, `action`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志表';

-- ============================================================
-- 初始化默认角色数据
-- ============================================================
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `parent_id`, `sort_order`) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '系统最高权限，可管理所有功能和用户', NULL, 1),
(2, 'ADMIN', '管理员', '系统管理权限，可管理用户和配置', 1, 2),
(3, 'OPERATOR', '运维操作员', '运维操作权限，可执行命令和管理知识库', 2, 3),
(4, 'VIEWER', '只读观察者', '只读权限，仅可查看系统信息', 3, 4);

-- ============================================================
-- 初始化默认权限数据
-- ============================================================
INSERT INTO `sys_permission` (`id`, `permission_code`, `permission_name`, `module`, `action`, `description`, `risk_level`) VALUES
-- 用户管理模块
(1, 'user:read', '查看用户', 'user', 'read', '查看用户列表和详情', 'low'),
(2, 'user:write', '编辑用户', 'user', 'write', '创建和编辑用户信息', 'medium'),
(3, 'user:delete', '删除用户', 'user', 'delete', '删除用户账户', 'high'),
(4, 'user:role_assign', '分配角色', 'user', 'role_assign', '为用户分配或撤销角色', 'high'),
-- 知识库管理模块
(5, 'knowledge:read', '查看知识库', 'knowledge', 'read', '查看知识库文档列表', 'low'),
(6, 'knowledge:write', '编辑知识库', 'knowledge', 'write', '上传和编辑知识库文档', 'medium'),
(7, 'knowledge:delete', '删除文档', 'knowledge', 'delete', '从知识库中删除文档', 'medium'),
-- 告警管理模块
(8, 'alert:read', '查看告警', 'alert', 'read', '查看告警列表和详情', 'low'),
(9, 'alert:write', '编辑告警规则', 'alert', 'write', '创建和编辑告警规则', 'medium'),
(10, 'alert:resolve', '解决告警', 'alert', 'resolve', '标记告警为已解决', 'low'),
-- 执行管理模块
(11, 'execution:read', '查看执行记录', 'execution', 'read', '查看命令执行历史', 'low'),
(12, 'execution:request', '发起执行请求', 'execution', 'request', '发起命令执行请求', 'medium'),
(13, 'execution:approve', '审批执行请求', 'execution', 'approve', '审批他人的执行请求', 'high'),
(14, 'execution:execute', '执行命令', 'execution', 'execute', '实际执行运维命令', 'high'),
-- 对话模块
(15, 'chat:use', '使用AI问答', 'chat', 'use', '使用AI智能问答功能', 'low'),
-- 系统管理模块
(16, 'system:config', '系统配置', 'system', 'config', '修改系统配置参数', 'high'),
(17, 'system:monitor', '系统监控', 'system', 'monitor', '查看系统监控和指标', 'low'),
-- 审批模块
(18, 'approval:read', '查看审批', 'approval', 'read', '查看审批请求列表', 'low'),
(19, 'approval:process', '处理审批', 'approval', 'process', '审批或拒绝请求', 'medium');

-- ============================================================
-- 初始化角色权限关联（角色继承：上级拥有下级所有权限）
-- ============================================================
-- VIEWER (只读): 所有 read 权限 + chat:use
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(4, 1), (4, 5), (4, 8), (4, 11), (4, 15), (4, 17), (4, 18);

-- OPERATOR (运维): VIEWER权限 + write/execute 权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(3, 1), (3, 5), (3, 6), (3, 7), (3, 8), (3, 10), (3, 11), (3, 12),
(3, 15), (3, 17), (3, 18);

-- ADMIN (管理): OPERATOR权限 + approve/delete + 用户管理
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(2, 1), (2, 2), (2, 3), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 9), (2, 10),
(2, 11), (2, 12), (2, 13), (2, 14), (2, 15), (2, 16), (2, 17), (2, 18), (2, 19);

-- SUPER_ADMIN: 所有权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10),
(1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16), (1, 17), (1, 18), (1, 19);

-- ============================================================
-- 为默认管理员分配SUPER_ADMIN角色
-- ============================================================
INSERT INTO `user_role` (`user_id`, `role_id`, `granted_by`) VALUES
(1, 1, 1);

SET FOREIGN_KEY_CHECKS = 1;
