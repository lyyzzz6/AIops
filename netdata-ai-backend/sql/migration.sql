-- ============================================================
-- 权限控制与数据访问管理系统 - 数据库初始化脚本
-- ============================================================
-- 执行说明：
-- 1. 执行此脚本创建/修改数据表结构
-- 2. 系统启动时会自动初始化超管角色和默认admin账号
-- ============================================================

-- ============================================================
-- 0. 为knowledge_document表添加content字段（如果不存在）
-- ============================================================
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content TEXT COMMENT '文档内容' AFTER category;

-- ============================================================
-- 1. 修改 sys_user 表，添加密码管理相关字段（使用独立语句，兼容旧版本MySQL）
-- ============================================================
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS password_changed_at DATETIME DEFAULT NULL COMMENT '密码最后修改时间';
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS password_expire_at DATETIME DEFAULT NULL COMMENT '密码过期时间';
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS is_first_login TINYINT(1) DEFAULT 1 COMMENT '是否首次登录，1=是，0=否';

-- ============================================================
-- 2. 创建 RBAC 相关表（如果不存在）
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    role_code VARCHAR(50) NOT NULL COMMENT '角色编码',
    role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
    description VARCHAR(500) DEFAULT NULL COMMENT '角色描述',
    parent_id BIGINT DEFAULT NULL COMMENT '父角色ID',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序号',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0禁用 1启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code),
    KEY idx_parent_id (parent_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色表';

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '权限ID',
    permission_code VARCHAR(100) NOT NULL COMMENT '权限编码',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
    module VARCHAR(50) NOT NULL COMMENT '所属模块',
    action VARCHAR(50) NOT NULL COMMENT '操作类型',
    description VARCHAR(500) DEFAULT NULL COMMENT '权限描述',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT '风险等级',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (permission_code),
    KEY idx_module (module),
    KEY idx_risk_level (risk_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限表';

CREATE TABLE IF NOT EXISTS user_role (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    granted_by BIGINT DEFAULT NULL COMMENT '授权人ID',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    expires_at DATETIME DEFAULT NULL COMMENT '过期时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_user_id (user_id),
    KEY idx_role_id (role_id),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS role_permission (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_role_id (role_id),
    KEY idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联表';

-- ============================================================
-- 3. 初始化超管角色（如果不存在）
-- ============================================================
INSERT INTO sys_role (role_code, role_name, description, parent_id, sort_order, status, created_at, updated_at)
SELECT 'SUPER_ADMIN', '超级管理员', '系统最高权限角色，拥有所有功能的访问和操作权限', 0, 1, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_code = 'SUPER_ADMIN');

-- ============================================================
-- 4. 初始化默认权限（如果不存在）
-- ============================================================
INSERT INTO sys_permission (permission_code, permission_name, module, action, description, risk_level, created_at)
SELECT * FROM (
    SELECT 'user:read' AS permission_code, '查看用户' AS permission_name, 'user' AS module, 'read' AS action, '查看用户列表和详情' AS description, 'low' AS risk_level, NOW() AS created_at
    UNION ALL SELECT 'user:write', '管理用户', 'user', 'write', '创建、编辑用户', 'high', NOW()
    UNION ALL SELECT 'user:delete', '删除用户', 'user', 'delete', '删除用户', 'high', NOW()
    UNION ALL SELECT 'user:role_assign', '分配角色', 'user', 'role_assign', '为用户分配角色', 'high', NOW()
    UNION ALL SELECT 'role:read', '查看角色', 'role', 'read', '查看角色列表和详情', 'low', NOW()
    UNION ALL SELECT 'role:write', '管理角色', 'role', 'write', '创建、编辑角色', 'high', NOW()
    UNION ALL SELECT 'role:delete', '删除角色', 'role', 'delete', '删除角色', 'high', NOW()
    UNION ALL SELECT 'role:permission_assign', '分配权限', 'role', 'permission_assign', '为角色分配权限', 'high', NOW()
    UNION ALL SELECT 'approval:submit', '提交申请', 'approval', 'submit', '提交权限申请', 'medium', NOW()
    UNION ALL SELECT 'approval:approve', '审批申请', 'approval', 'approve', '审批通过/拒绝申请', 'high', NOW()
    UNION ALL SELECT 'approval:read', '查看审批', 'approval', 'read', '查看审批记录', 'low', NOW()
    UNION ALL SELECT 'execution:request', '请求执行', 'execution', 'request', '提交命令执行请求', 'high', NOW()
    UNION ALL SELECT 'execution:approve', '审批执行', 'execution', 'approve', '审批命令执行', 'high', NOW()
    UNION ALL SELECT 'execution:read', '查看执行', 'execution', 'read', '查看执行记录', 'low', NOW()
    UNION ALL SELECT 'audit:read', '查看审计', 'audit', 'read', '查看审计日志', 'low', NOW()
    UNION ALL SELECT 'knowledge:read', '查看知识', 'knowledge', 'read', '查看知识库', 'low', NOW()
    UNION ALL SELECT 'knowledge:write', '管理知识', 'knowledge', 'write', '编辑知识库', 'medium', NOW()
    UNION ALL SELECT 'knowledge:delete', '删除知识', 'knowledge', 'delete', '删除知识', 'medium', NOW()
    UNION ALL SELECT 'alert:read', '查看告警', 'alert', 'read', '查看告警记录', 'low', NOW()
    UNION ALL SELECT 'alert:handle', '处理告警', 'alert', 'handle', '处理告警', 'medium', NOW()
    UNION ALL SELECT 'system:config', '系统配置', 'system', 'config', '系统配置管理', 'high', NOW()
    UNION ALL SELECT 'system:read', '系统查看', 'system', 'read', '查看系统信息', 'low', NOW()
) AS tmp
WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE permission_code = 'user:read');

-- ============================================================
-- 5. 为超管角色分配所有权限
-- ============================================================
INSERT INTO role_permission (role_id, permission_id, granted_at)
SELECT
    r.id AS role_id,
    p.id AS permission_id,
    NOW() AS granted_at
FROM sys_role r
CROSS JOIN sys_permission p
WHERE r.role_code = 'SUPER_ADMIN'
AND NOT EXISTS (
    SELECT 1 FROM role_permission rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
);

-- ============================================================
-- 6. 创建超管账号（如果不存在）
-- 注意：默认密码为 admin123，首次登录必须修改
-- ============================================================
INSERT INTO sys_user (username, password, nickname, email, status, login_fail_count, deleted, is_first_login, created_at, updated_at)
SELECT 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', 'admin@netdata.ops', 1, 0, 0, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

-- ============================================================
-- 7. 为admin账号分配超管角色
-- ============================================================
INSERT INTO user_role (user_id, role_id, granted_by, granted_at)
SELECT
    u.id AS user_id,
    r.id AS role_id,
    u.id AS granted_by,
    NOW() AS granted_at
FROM sys_user u
CROSS JOIN sys_role r
WHERE u.username = 'admin'
AND r.role_code = 'SUPER_ADMIN'
AND NOT EXISTS (
    SELECT 1 FROM user_role ur
    WHERE ur.user_id = u.id AND ur.role_id = r.id
);
