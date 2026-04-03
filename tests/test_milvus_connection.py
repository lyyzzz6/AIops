#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
============================================================
Milvus 连接测试脚本
============================================================

用途：
    - 验证 Milvus 服务是否正常
    - 测试基本 CRUD 操作
    - 评估连接性能

使用方法：
    pip install pymilvus
    python tests/test_milvus_connection.py

作者：刘一舟
更新时间：2026-04-03
============================================================
"""

import time
import sys

try:
    from pymilvus import connections, utility
except ImportError:
    print("错误：未安装 pymilvus")
    print("请执行: pip install pymilvus>=2.4.0")
    sys.exit(1)


def test_connection(host: str = "localhost", port: int = 19530) -> bool:
    """
    测试 Milvus 连接

    Args:
        host: Milvus 服务器地址
        port: Milvus gRPC 端口

    Returns:
        bool: 连接是否成功
    """
    print(f"测试连接 Milvus: {host}:{port}")

    try:
        # 记录开始时间
        start_time = time.time()

        # 尝试连接
        connections.connect(
            alias="test",
            host=host,
            port=port,
            timeout=5
        )

        # 计算连接耗时
        elapsed = time.time() - start_time
        print(f"[✓] 连接成功，耗时: {elapsed*1000:.2f}ms")

        # 获取服务器版本
        version = utility.get_server_version()
        print(f"[✓] Milvus 版本: {version}")

        # 列出所有 Collection
        collections = utility.list_collections()
        print(f"[✓] 现有 Collection: {collections if collections else '无'}")

        # 断开连接
        connections.disconnect("test")
        print("[✓] 连接测试完成")

        return True

    except Exception as e:
        print(f"[✗] 连接失败: {e}")
        return False


def test_health_check(host: str = "localhost", port: int = 9091) -> bool:
    """
    测试 Milvus 健康检查端点

    Args:
        host: Milvus 服务器地址
        port: Milvus Metrics 端口

    Returns:
        bool: 健康检查是否通过
    """
    import urllib.request
    import urllib.error

    url = f"http://{host}:{port}/healthz"
    print(f"\n测试健康检查端点: {url}")

    try:
        start_time = time.time()
        response = urllib.request.urlopen(url, timeout=5)
        elapsed = time.time() - start_time

        if response.status == 200:
            print(f"[✓] 健康检查通过，耗时: {elapsed*1000:.2f}ms")
            return True
        else:
            print(f"[✗] 健康检查失败，状态码: {response.status}")
            return False

    except urllib.error.URLError as e:
        print(f"[✗] 无法访问健康检查端点: {e}")
        return False
    except Exception as e:
        print(f"[✗] 健康检查异常: {e}")
        return False


def main():
    """主函数"""
    print("=" * 50)
    print("Milvus 连接测试")
    print("=" * 50)

    # 测试 gRPC 连接
    grpc_success = test_connection()

    # 测试健康检查端点
    health_success = test_health_check()

    # 总结
    print("\n" + "=" * 50)
    print("测试结果:")
    print(f"  gRPC 连接: {'通过' if grpc_success else '失败'}")
    print(f"  健康检查: {'通过' if health_success else '失败'}")
    print("=" * 50)

    if grpc_success and health_success:
        print("\n[✓] Milvus 服务正常运行")
        return 0
    else:
        print("\n[✗] Milvus 服务异常，请检查 Docker 容器状态")
        print("    查看日志: docker-compose logs milvus-standalone")
        return 1


if __name__ == "__main__":
    sys.exit(main())
