-- 修复 admin 用户角色关联
-- 确保 admin 用户与 SUPER_ADMIN 角色正确关联

-- 1. 找到 SUPER_ADMIN 角色的 ID
SET @super_admin_role_id = (SELECT id FROM sys_role WHERE role_code = 'SUPER_ADMIN' LIMIT 1);

-- 2. 找到 admin 用户的 ID
SET @admin_user_id = (SELECT id FROM sys_user WHERE username = 'admin' LIMIT 1);

-- 3. 如果角色和用户都存在，建立关联
INSERT IGNORE INTO user_role (user_id, role_id, granted_by, granted_at)
VALUES (@admin_user_id, @super_admin_role_id, @admin_user_id, NOW());

-- 4. 验证结果
SELECT '用户角色关联结果：' AS info;
SELECT u.username, r.role_code, r.role_name
FROM user_role ur
JOIN sys_user u ON ur.user_id = u.id
JOIN sys_role r ON ur.role_id = r.id
WHERE u.username = 'admin';
