#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据库导出脚本
导出MySQL数据库的表结构和数据
"""

import os
import sys
import subprocess
import datetime
from pathlib import Path


def get_env_var(name: str, default: str = None) -> str:
    """从环境变量或.env文件获取配置"""
    env_file = Path(__file__).parent.parent / ".env"
    
    # 先检查环境变量
    if name in os.environ:
        return os.environ[name]
    
    # 然后检查.env文件
    if env_file.exists():
        with open(env_file, 'r', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    key, value = line.split('=', 1)
                    key = key.strip()
                    value = value.strip()
                    if key == name:
                        # 移除可能的引号
                        if (value.startswith('"') and value.endswith('"')) or \
                           (value.startswith("'") and value.endswith("'")):
                            value = value[1:-1]
                        return value
    
    return default


def export_database(output_dir: str = "sql/backups"):
    """
    导出数据库
    
    Args:
        output_dir: 输出目录
    """
    # 获取数据库配置
    db_host = get_env_var("MYSQL_HOST", "localhost")
    db_port = get_env_var("MYSQL_PORT", "3307")
    db_user = get_env_var("MYSQL_USER", "ops_user")
    db_password = get_env_var("MYSQL_PASSWORD", "ops123456")
    db_name = get_env_var("MYSQL_DATABASE", "netdata_ops")
    
    # 创建输出目录
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    # 生成时间戳
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    
    # 导出文件名
    filename = f"{db_name}_export_{timestamp}.sql"
    output_file = output_path / filename
    
    print(f"正在导出数据库 '{db_name}'...")
    print(f"连接信息: {db_user}@{db_host}:{db_port}")
    print(f"输出文件: {output_file}")
    print("-" * 60)
    
    try:
        # 使用mysqldump命令
        cmd = [
            "mysqldump",
            f"--host={db_host}",
            f"--port={db_port}",
            f"--user={db_user}",
            f"--password={db_password}",
            "--single-transaction",
            "--routines",
            "--triggers",
            "--events",
            "--add-drop-database",
            "--add-drop-table",
            "--create-options",
            "--quick",
            "--lock-tables=false",
            db_name
        ]
        
        # 执行命令并写入文件
        with open(output_file, 'w', encoding='utf-8') as f:
            result = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, text=True)
        
        if result.returncode == 0:
            print(f"✓ 数据库导出成功!")
            print(f"✓ 文件大小: {output_file.stat().st_size / 1024:.2f} KB")
            
            # 也创建一个不带时间戳的最新版本
            latest_file = output_path / f"{db_name}_latest.sql"
            with open(output_file, 'r', encoding='utf-8') as f_src:
                with open(latest_file, 'w', encoding='utf-8') as f_dst:
                    f_dst.write(f_src.read())
            
            print(f"✓ 最新版本已保存至: {latest_file}")
        else:
            print(f"✗ 数据库导出失败!")
            print(f"错误信息: {result.stderr}")
            return False
            
    except FileNotFoundError:
        print("✗ 错误: 未找到mysqldump命令")
        print("请确保已安装MySQL客户端工具")
        return False
    except Exception as e:
        print(f"✗ 导出过程中发生错误: {e}")
        return False
    
    return True


def export_schema_only(output_dir: str = "sql/backups"):
    """仅导出数据库结构"""
    # 获取数据库配置
    db_host = get_env_var("MYSQL_HOST", "localhost")
    db_port = get_env_var("MYSQL_PORT", "3307")
    db_user = get_env_var("MYSQL_USER", "ops_user")
    db_password = get_env_var("MYSQL_PASSWORD", "ops123456")
    db_name = get_env_var("MYSQL_DATABASE", "netdata_ops")
    
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"{db_name}_schema_{timestamp}.sql"
    output_file = output_path / filename
    
    print(f"\n正在导出数据库结构...")
    
    try:
        cmd = [
            "mysqldump",
            f"--host={db_host}",
            f"--port={db_port}",
            f"--user={db_user}",
            f"--password={db_password}",
            "--no-data",
            "--routines",
            "--triggers",
            "--events",
            db_name
        ]
        
        with open(output_file, 'w', encoding='utf-8') as f:
            result = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE, text=True)
        
        if result.returncode == 0:
            print(f"✓ 数据库结构导出成功!")
            print(f"✓ 文件: {output_file}")
        else:
            print(f"✗ 结构导出失败: {result.stderr}")
            
    except Exception as e:
        print(f"✗ 结构导出错误: {e}")


def print_help():
    """打印帮助信息"""
    print("""
数据库导出工具
==============

用法:
    python export_database.py [选项]

选项:
    --all       导出结构和数据 (默认)
    --schema    仅导出结构
    --help      显示此帮助信息

示例:
    python export_database.py
    python export_database.py --schema
""")


if __name__ == "__main__":
    # 解析命令行参数
    args = sys.argv[1:]
    
    if "--help" in args or "-h" in args:
        print_help()
        sys.exit(0)
    
    schema_only = "--schema" in args
    
    # 导出数据库
    success = export_database()
    
    if success and not schema_only:
        export_schema_only()
    
    if success:
        print("\n✓ 所有导出任务完成!")
        sys.exit(0)
    else:
        print("\n✗ 导出失败!")
        sys.exit(1)
