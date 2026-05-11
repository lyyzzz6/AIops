-- ============================================================
-- 智能运维问答与执行系统 - MySQL 初始化脚本
-- ============================================================
--
-- 用途：
--     - 创建数据库表结构
--     - 初始化基础数据
--     - 创建必要的索引
--     - RBAC权限管理相关表
--
-- 使用方法：
--     1. Docker 首次启动时自动执行
--     2. 或手动执行：mysql -u root -p netdata_ops < sql/init.sql
--
-- 作者：刘一舟
-- 更新时间：2026-05-10
-- ============================================================

-- 设置字符集
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 用户表（系统用户）
-- ============================================================
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码（BCrypt加密）',
    `nickname` VARCHAR(100) DEFAULT NULL COMMENT '昵称',
    `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `phone` VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    `avatar` VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    `role` VARCHAR(20) NOT NULL DEFAULT 'viewer' COMMENT '角色：admin/operator/viewer（兼容旧字段）',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip` VARCHAR(50) DEFAULT NULL COMMENT '最后登录IP',
    `login_fail_count` INT NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    `locked_until` DATETIME DEFAULT NULL COMMENT '账户锁定截止时间',
    `deleted` TINYINT(1) DEFAULT 0 COMMENT '逻辑删除：0正常 1已删除',
    `password_changed_at` DATETIME DEFAULT NULL COMMENT '密码最后修改时间',
    `password_expire_at` DATETIME DEFAULT NULL COMMENT '密码过期时间',
    `is_first_login` TINYINT(1) DEFAULT 1 COMMENT '是否首次登录，1=是，0=否',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role` (`role`),
    KEY `idx_status` (`status`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 插入默认管理员账户
-- 密码：admin123（BCrypt加密后），首次登录必须修改
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `status`, `login_fail_count`, `deleted`, `is_first_login`, `created_at`, `updated_at`) VALUES
('admin', '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG', '系统管理员', 'admin@netdata.ops', 1, 0, 0, 1, NOW(), NOW());

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

-- 插入默认角色
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `parent_id`, `sort_order`) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '系统最高权限，可管理所有功能和用户', NULL, 1),
(2, 'ADMIN', '管理员', '系统管理权限，可管理用户和配置', 1, 2),
(3, 'OPERATOR', '运维操作员', '运维操作权限，可执行命令和管理知识库', 2, 3),
(4, 'VIEWER', '只读观察者', '只读权限，仅可查看系统信息', 3, 4);

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

-- 插入默认权限
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
(10, 'alert:handle', '处理告警', 'alert', 'handle', '标记告警为已处理', 'low'),
-- 执行管理模块
(11, 'execution:read', '查看执行记录', 'execution', 'read', '查看命令执行历史', 'low'),
(12, 'execution:request', '发起执行请求', 'execution', 'request', '发起命令执行请求', 'medium'),
(13, 'execution:approve', '审批执行请求', 'execution', 'approve', '审批他人的执行请求', 'high'),
-- 审批模块
(14, 'approval:read', '查看审批', 'approval', 'read', '查看审批请求列表', 'low'),
(15, 'approval:process', '处理审批', 'approval', 'process', '审批或拒绝请求', 'medium'),
-- 系统管理模块
(16, 'system:config', '系统配置', 'system', 'config', '修改系统配置参数', 'high'),
(17, 'system:read', '系统查看', 'system', 'read', '查看系统监控和指标', 'low');

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

-- 为admin用户分配SUPER_ADMIN角色
INSERT INTO `user_role` (`user_id`, `role_id`, `granted_by`) VALUES
(1, 1, 1);

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

-- 为SUPER_ADMIN分配所有权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5), (1, 6), (1, 7), (1, 8), (1, 9), (1, 10),
(1, 11), (1, 12), (1, 13), (1, 14), (1, 15), (1, 16), (1, 17);

-- 为ADMIN分配大部分权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(2, 1), (2, 2), (2, 4), (2, 5), (2, 6), (2, 7), (2, 8), (2, 9), (2, 10),
(2, 11), (2, 12), (2, 14), (2, 16), (2, 17);

-- 为OPERATOR分配基础权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(3, 1), (3, 5), (3, 6), (3, 8), (3, 10), (3, 11), (3, 12), (3, 14);

-- 为VIEWER分配只读权限
INSERT INTO `role_permission` (`role_id`, `permission_id`) VALUES
(4, 1), (4, 5), (4, 8), (4, 11), (4, 14), (4, 17);

-- ============================================================
-- 知识库文档表
-- ============================================================
DROP TABLE IF EXISTS `knowledge_document`;
CREATE TABLE `knowledge_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档ID',
    `title` VARCHAR(256) NOT NULL COMMENT '文档标题',
    `source` VARCHAR(512) NOT NULL COMMENT '文档来源（URL或文件路径）',
    `content_type` VARCHAR(50) DEFAULT 'markdown' COMMENT '内容类型',
    `category` VARCHAR(100) DEFAULT NULL COMMENT '文档分类',
    `word_count` INT DEFAULT 0 COMMENT '字数',
    `chunk_count` INT DEFAULT 0 COMMENT '切片数量',
    `milvus_ids` TEXT DEFAULT NULL COMMENT 'Milvus向量ID列表（JSON）',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0处理中 1已入库 2失败',
    `error_message` VARCHAR(500) DEFAULT NULL COMMENT '错误信息',
    `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_source` (`source`(255)),
    KEY `idx_category` (`category`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库文档表';

-- ============================================================
-- 对话历史表
-- ============================================================
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE `chat_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '对话ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    `title` VARCHAR(256) DEFAULT NULL COMMENT '对话标题',
    `intent` VARCHAR(50) DEFAULT NULL COMMENT '意图类型',
    `agent_used` VARCHAR(50) DEFAULT NULL COMMENT '使用的Agent',
    `message_count` INT DEFAULT 0 COMMENT '消息数量',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话历史表';

-- ============================================================
-- 对话消息表
-- ============================================================
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    `conversation_id` BIGINT NOT NULL COMMENT '对话ID',
    `role` VARCHAR(20) NOT NULL COMMENT '角色：user/assistant/system',
    `content` TEXT NOT NULL COMMENT '消息内容',
    `tokens` INT DEFAULT 0 COMMENT 'Token数量',
    `sources` TEXT DEFAULT NULL COMMENT '引用来源（JSON）',
    `metadata` TEXT DEFAULT NULL COMMENT '元数据（JSON）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_conversation_id` (`conversation_id`),
    KEY `idx_created_at` (`created_at`),
    CONSTRAINT `fk_message_conversation` FOREIGN KEY (`conversation_id`) REFERENCES `chat_conversation` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息表';

-- ============================================================
-- 命令执行审计表
-- ============================================================
DROP TABLE IF EXISTS `execution_audit`;
CREATE TABLE `execution_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计ID',
    `request_id` VARCHAR(64) NOT NULL COMMENT '请求ID',
    `user_id` BIGINT DEFAULT NULL COMMENT '执行用户ID',
    `command` TEXT NOT NULL COMMENT '执行命令',
    `command_type` VARCHAR(50) DEFAULT NULL COMMENT '命令类型',
    `target_host` VARCHAR(255) DEFAULT NULL COMMENT '目标主机',
    `risk_level` VARCHAR(20) NOT NULL COMMENT '风险等级：low/medium/high/critical',
    `risk_score` INT DEFAULT 0 COMMENT '风险分数（1-100）',
    `status` VARCHAR(20) NOT NULL COMMENT '状态：pending/approved/rejected/executing/completed/failed',
    `approver_id` BIGINT DEFAULT NULL COMMENT '审批人ID',
    `approved_at` DATETIME DEFAULT NULL COMMENT '审批时间',
    `execution_result` TEXT DEFAULT NULL COMMENT '执行结果',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `execution_time_ms` INT DEFAULT NULL COMMENT '执行耗时（毫秒）',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_request_id` (`request_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_status` (`status`),
    KEY `idx_risk_level` (`risk_level`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命令执行审计表';

-- ============================================================
-- 命令模板表
-- ============================================================
DROP TABLE IF EXISTS `command_template`;
CREATE TABLE `command_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    `name` VARCHAR(100) NOT NULL COMMENT '模板名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '模板描述',
    `command_template` TEXT NOT NULL COMMENT '命令模板（支持变量替换）',
    `category` VARCHAR(50) DEFAULT NULL COMMENT '命令分类',
    `risk_level` VARCHAR(20) NOT NULL DEFAULT 'medium' COMMENT '默认风险等级',
    `is_whitelisted` TINYINT DEFAULT 0 COMMENT '是否白名单命令',
    `is_active` TINYINT DEFAULT 1 COMMENT '是否启用',
    `created_by` BIGINT DEFAULT NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_category` (`category`),
    KEY `idx_is_whitelisted` (`is_whitelisted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命令模板表';

-- 插入常用命令模板
INSERT INTO `command_template` (`name`, `description`, `command_template`, `category`, `risk_level`, `is_whitelisted`) VALUES
('查看服务状态', '查看系统服务运行状态', 'systemctl status {{service_name}}', 'status', 'low', 1),
('查看日志', '查看服务日志', 'journalctl -u {{service_name}} -n {{lines:100}}', 'logs', 'low', 1),
('查看进程', '查看系统进程', 'ps aux | grep {{process_name}}', 'status', 'low', 1),
('查看端口', '查看端口占用', 'netstat -tulnp | grep {{port}}', 'status', 'low', 1),
('查看磁盘', '查看磁盘使用情况', 'df -h', 'status', 'low', 1),
('查看内存', '查看内存使用情况', 'free -h', 'status', 'low', 1),
('重启服务', '重启指定服务', 'systemctl restart {{service_name}}', 'operation', 'medium', 0),
('清理日志', '清理旧日志文件', 'find {{log_path}} -name "*.log" -mtime +{{days:7}} -delete', 'cleanup', 'medium', 0);

-- ============================================================
-- 告警记录表
-- ============================================================
DROP TABLE IF EXISTS `alert_record`;
CREATE TABLE `alert_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '告警ID',
    `alert_id` VARCHAR(64) NOT NULL COMMENT '告警唯一标识',
    `source` VARCHAR(50) DEFAULT 'netdata' COMMENT '告警来源',
    `severity` VARCHAR(20) NOT NULL COMMENT '严重程度：info/warning/critical',
    `alert_name` VARCHAR(255) NOT NULL COMMENT '告警名称',
    `message` TEXT NOT NULL COMMENT '告警消息',
    `host` VARCHAR(255) DEFAULT NULL COMMENT '告警主机',
    `metric_name` VARCHAR(100) DEFAULT NULL COMMENT '指标名称',
    `metric_value` VARCHAR(100) DEFAULT NULL COMMENT '指标值',
    `threshold` VARCHAR(100) DEFAULT NULL COMMENT '阈值',
    `status` VARCHAR(20) NOT NULL DEFAULT 'firing' COMMENT '状态：firing/resolved',
    `diagnosis_result` TEXT DEFAULT NULL COMMENT '诊断结果（JSON）',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '解决时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_id` (`alert_id`),
    KEY `idx_severity` (`severity`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警记录表';

-- ============================================================
-- 异常检测结果表
-- ============================================================
DROP TABLE IF EXISTS `anomaly_detection`;
CREATE TABLE `anomaly_detection` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '检测ID',
    `host` VARCHAR(255) DEFAULT NULL COMMENT '主机',
    `metric_name` VARCHAR(100) NOT NULL COMMENT '指标名称',
    `metric_value` DOUBLE DEFAULT NULL COMMENT '指标值',
    `anomaly_score` DOUBLE NOT NULL COMMENT '异常分数',
    `is_anomaly` TINYINT NOT NULL COMMENT '是否异常',
    `detector_type` VARCHAR(50) DEFAULT NULL COMMENT '检测器类型',
    `detection_time` DATETIME NOT NULL COMMENT '检测时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_host` (`host`),
    KEY `idx_metric_name` (`metric_name`),
    KEY `idx_is_anomaly` (`is_anomaly`),
    KEY `idx_detection_time` (`detection_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异常检测结果表';

-- ============================================================
-- 系统配置表
-- ============================================================
DROP TABLE IF EXISTS `sys_config`;
CREATE TABLE `sys_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    `config_key` VARCHAR(100) NOT NULL COMMENT '配置键',
    `config_value` TEXT NOT NULL COMMENT '配置值',
    `config_type` VARCHAR(50) DEFAULT 'string' COMMENT '配置类型',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '配置描述',
    `updated_by` BIGINT DEFAULT NULL COMMENT '更新人',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- 插入默认配置
INSERT INTO `sys_config` (`config_key`, `config_value`, `config_type`, `description`) VALUES
('llm.provider', 'deepseek', 'string', 'LLM 提供商：deepseek/ollama'),
('llm.model', 'deepseek-chat', 'string', 'LLM 模型名称'),
('llm.temperature', '0.7', 'number', 'LLM 温度参数'),
('llm.max_tokens', '4096', 'number', 'LLM 最大 Token 数'),
('rag.top_k', '5', 'number', 'RAG 检索 Top-K'),
('rag.similarity_threshold', '0.7', 'number', 'RAG 相似度阈值'),
('execution.auto_approve_low_risk', 'true', 'boolean', '是否自动批准低风险命令'),
('execution.max_wait_time', '3600', 'number', '命令执行最大等待时间（秒）');

-- ============================================================
-- Agent审计日志表
-- ============================================================
DROP TABLE IF EXISTS `agent_audit_log`;
CREATE TABLE `agent_audit_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计日志ID',
    `trace_id` VARCHAR(64) NOT NULL COMMENT '链路追踪ID',
    `user_id` VARCHAR(64) DEFAULT NULL COMMENT '用户ID',
    `agent_name` VARCHAR(100) NOT NULL COMMENT 'Agent名称',
    `intent_type` VARCHAR(50) DEFAULT NULL COMMENT '意图类型',
    `query` VARCHAR(2000) DEFAULT NULL COMMENT '用户查询内容',
    `success` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '执行是否成功：0失败 1成功',
    `duration_ms` BIGINT NOT NULL COMMENT '执行耗时（毫秒）',
    `tool_calls` TEXT DEFAULT NULL COMMENT '调用的工具列表（逗号分隔）',
    `error_message` VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_trace_id` (`trace_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_agent_name` (`agent_name`),
    KEY `idx_intent_type` (`intent_type`),
    KEY `idx_created_at` (`created_at`),
    KEY `idx_success` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent审计日志表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 创建视图：告警统计视图
-- ============================================================
CREATE OR REPLACE VIEW `v_alert_statistics` AS
SELECT
    DATE(created_at) as alert_date,
    severity,
    COUNT(*) as total_count,
    SUM(CASE WHEN status = 'resolved' THEN 1 ELSE 0 END) as resolved_count,
    AVG(TIMESTAMPDIFF(MINUTE, created_at, COALESCE(resolved_at, NOW()))) as avg_resolution_minutes
FROM alert_record
GROUP BY DATE(created_at), severity;

-- ============================================================
-- 创建视图：执行统计视图
-- ============================================================
CREATE OR REPLACE VIEW `v_execution_statistics` AS
SELECT
    DATE(created_at) as exec_date,
    risk_level,
    COUNT(*) as total_count,
    SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) as success_count,
    AVG(execution_time_ms) as avg_execution_time_ms
FROM execution_audit
WHERE status IN ('completed', 'failed')
GROUP BY DATE(created_at), risk_level;

-- ============================================================
-- 创建视图：Agent审计统计视图
-- ============================================================
CREATE OR REPLACE VIEW `v_agent_audit_statistics` AS
SELECT
    DATE(created_at) as audit_date,
    agent_name,
    intent_type,
    COUNT(*) as total_count,
    SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) as success_count,
    AVG(duration_ms) as avg_duration_ms
FROM agent_audit_log
GROUP BY DATE(created_at), agent_name, intent_type;
