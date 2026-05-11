#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成BCrypt密码哈希的脚本
用于生成数据库初始化脚本中的密码
"""

try:
    import bcrypt
except ImportError:
    print("需要安装bcrypt: pip install bcrypt")
    exit(1)


def generate_bcrypt_hash(password: str, rounds: int = 10) -> str:
    """
    生成BCrypt哈希
    
    Args:
        password: 原始密码
        rounds: 迭代次数
    
    Returns:
        BCrypt哈希字符串
    """
    salt = bcrypt.gensalt(rounds=rounds)
    password_bytes = password.encode('utf-8')
    hash_bytes = bcrypt.hashpw(password_bytes, salt)
    return hash_bytes.decode('utf-8')


def verify_password(password: str, hashed: str) -> bool:
    """
    验证密码
    
    Args:
        password: 原始密码
        hashed: BCrypt哈希
    
    Returns:
        是否匹配
    """
    password_bytes = password.encode('utf-8')
    hash_bytes = hashed.encode('utf-8')
    return bcrypt.checkpw(password_bytes, hash_bytes)


if __name__ == "__main__":
    password = "admin123"
    
    print(f"正在生成密码 '{password}' 的BCrypt哈希...")
    hashed = generate_bcrypt_hash(password)
    
    print(f"\n原始密码: {password}")
    print(f"BCrypt哈希: {hashed}")
    print(f"\n验证结果: {verify_password(password, hashed)}")
    
    print(f"\n可在SQL中使用:")
    print(f"INSERT INTO sys_user (username, password, ...) VALUES ('admin', '{hashed}', ...);")
