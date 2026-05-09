-- ============================================================
-- 智能运维问答与执行系统 - MySQL 初始化脚本
-- ============================================================
--
-- 用途：
--     - 创建数据库表结构
--     - 初始化基础数据
--     - 创建必要的索引
--
-- 使用方法：
--     1. Docker 首次启动时自动执行
--     2. 或手动执行：mysql -u root -p netdata_ops < sql/init.sql
--
-- 作者：刘一舟
-- 更新时间：2026-04-03
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
    `role` VARCHAR(20) NOT NULL DEFAULT 'viewer' COMMENT '角色：admin/operator/viewer',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_role` (`role`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- 插入默认管理员账户
-- 密码：admin123（BCrypt加密后）
INSERT INTO `sys_user` (`username`, `password`, `nickname`, `role`) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 'admin');

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
