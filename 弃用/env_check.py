#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Python 环境诊断工具
运行这个脚本检查环境配置
"""

import sys
import os
import subprocess
import json

def check_environment():
    """检查当前 Python 环境配置"""
    print("=" * 50)
    print("Python 环境诊断报告")
    print("=" * 50)
    print()

    # 1. 当前解释器信息
    print("[1] 当前 Python 解释器:")
    print(f"    路径: {sys.executable}")
    print(f"    版本: {sys.version}")
    print()

    # 2. 环境变量
    print("[2] 关键环境变量:")
    print(f"    PYTHON_HOME: {os.environ.get('PYTHON_HOME', '未设置')}")
    print(f"    PATH 前3项: {';'.join(os.environ.get('PATH', '').split(';')[:3])}")
    print()

    # 3. 检查 pip
    print("[3] Pip 状态:")
    try:
        import pip
        print(f"    pip 版本: {pip.__version__}")
        print(f"    pip 路径: {pip.__file__}")
    except ImportError:
        print("    警告: pip 未安装!")
    print()

    # 4. 已安装包
    print("[4] 关键包检查:")
    packages = ['numpy', 'pandas', 'requests', 'flask', 'django']
    for pkg in packages:
        try:
            mod = __import__(pkg)
            version = getattr(mod, '__version__', 'unknown')
            print(f"    {pkg}: {version}")
        except ImportError:
            print(f"    {pkg}: 未安装")
    print()

    # 5. 建议
    print("[5] 建议:")
    if 'WindowsApps' in sys.executable:
        print("    ⚠️  警告: 你正在使用 Windows Store 版本的 Python")
        print("       这可能导致环境不稳定。建议使用独立安装的 Python。")
    else:
        print("    ✓ Python 路径看起来正常")

    print()
    print("=" * 50)

if __name__ == "__main__":
    check_environment()
