-- ============================================================
-- 更新管理员密码为admin123
-- ============================================================
-- 使用方法：在需要重置密码时执行此脚本
-- ============================================================

USE netdata_ops;

-- 更新admin用户的密码为admin123
-- 这个BCrypt哈希值对应密码：admin123
UPDATE `sys_user` 
SET `password` = '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
    `password_changed_at` = NOW(),
    `is_first_login` = 1,
    `updated_at` = NOW()
WHERE `username` = 'admin';

-- 验证更新结果
SELECT 
    id, 
    username, 
    nickname, 
    is_first_login, 
    password_changed_at, 
    updated_at 
FROM `sys_user` 
WHERE `username` = 'admin';
