"""
============================================================
pytest 配置文件
============================================================

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import pytest


def pytest_configure(config):
    """pytest 配置"""
    config.addinivalue_line(
        "markers", "slow: 标记慢测试"
    )
    config.addinivalue_line(
        "markers", "integration: 集成测试"
    )
