"""
============================================================
NetData API 客户端
============================================================

用途：
    - 从 NetData 获取实时监控数据
    - 支持多种指标类型
    - 异步 HTTP 请求

NetData API 文档：
    https://learn.netdata.cloud/api

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import asyncio
from datetime import datetime
from typing import Any

import httpx
from loguru import logger

from app.config import settings
from app.models import MetricDataPoint


class NetDataClient:
    """
    NetData API 客户端

    NetData 是高性能实时监控系统，提供丰富的指标：
    - system.cpu: CPU 使用率
    - system.ram: 内存使用
    - system.load: 系统负载
    - system.network: 网络流量
    - disk.io: 磁盘 I/O
    - apps.cpu: 进程 CPU
    - apps.mem: 进程内存
    """

    def __init__(
        self,
        host: str | None = None,
        port: int | None = None,
        timeout: float | None = None,
    ):
        """
        初始化 NetData 客户端

        Args:
            host: NetData 服务器地址
            port: NetData API 端口
            timeout: 请求超时时间
        """
        self.host = host or settings.netdata_host
        self.port = port or settings.netdata_port
        self.timeout = timeout or settings.netdata_timeout
        self.base_url = f"http://{self.host}:{self.port}/api/v1"

        # 异步 HTTP 客户端
        self._client: httpx.AsyncClient | None = None

    async def __aenter__(self) -> "NetDataClient":
        """异步上下文管理器入口"""
        self._client = httpx.AsyncClient(timeout=self.timeout)
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        """异步上下文管理器出口"""
        if self._client:
            await self._client.aclose()
            self._client = None

    @property
    def client(self) -> httpx.AsyncClient:
        """获取 HTTP 客户端"""
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=self.timeout)
        return self._client

    async def get_chart_data(
        self,
        chart: str,
        after: int = -60,
        before: int = 0,
        points: int = 60,
        group: str = "average",
        format: str = "json",
        host: str | None = None,
    ) -> dict[str, Any]:
        """
        获取图表数据

        Args:
            chart: 图表名称，如 system.cpu, system.ram
            after: 起始时间（相对当前，负数表示之前）
            before: 结束时间（相对当前）
            points: 返回数据点数量
            group: 聚合方式：average, sum, min, max
            format: 返回格式：json, csv
            host: 目标主机（覆盖默认）

        Returns:
            dict: API 响应数据
        """
        url = f"{self.base_url}/data"

        # 如果指定了 host，需要处理多主机场景
        params = {
            "chart": chart,
            "after": after,
            "before": before,
            "points": points,
            "group": group,
            "format": format,
        }

        # 移除 None 值
        params = {k: v for k, v in params.items() if v is not None}

        logger.debug(f"请求 NetData: {url} {params}")

        try:
            response = await self.client.get(url, params=params)
            response.raise_for_status()
            return response.json()

        except httpx.HTTPStatusError as e:
            logger.error(f"NetData API 错误: {e.response.status_code}")
            raise
        except httpx.RequestError as e:
            logger.error(f"NetData 连接错误: {e}")
            raise

    async def fetch_chart_data(
        self,
        chart: str,
        after: int = -60,
        before: int = 0,
        points: int = 60,
        host: str | None = None,
    ) -> list[MetricDataPoint]:
        """
        获取并解析图表数据为数据点列表

        这是更高级的接口，返回结构化的数据点

        Args:
            chart: 图表名称
            after: 起始时间
            before: 结束时间
            points: 数据点数量
            host: 目标主机

        Returns:
            list[MetricDataPoint]: 数据点列表
        """
        raw_data = await self.get_chart_data(
            chart=chart,
            after=after,
            before=before,
            points=points,
            host=host,
        )

        # 解析 NetData 响应格式
        # {
        #   "labels": ["time", "user", "nice", "system", ...],
        #   "data": [[1234567890, 10.5, 0.0, 5.2, ...], ...]
        # }
        labels = raw_data.get("labels", [])
        data = raw_data.get("data", [])

        if not data:
            return []

        # 转换为 MetricDataPoint
        data_points = []

        for row in data:
            timestamp = datetime.fromtimestamp(row[0])

            # NetData 返回多列数据，每列是一个维度
            for i, label in enumerate(labels[1:], start=1):  # 跳过 time 列
                if i < len(row):
                    data_points.append(
                        MetricDataPoint(
                            timestamp=timestamp,
                            metric_name=f"{chart}.{label}",
                            value=float(row[i]),
                            host=host or self.host,
                        )
                    )

        return data_points

    async def get_charts(self, host: str | None = None) -> list[dict[str, Any]]:
        """
        获取所有可用图表列表

        Returns:
            list: 图表信息列表
        """
        url = f"{self.base_url}/charts"

        try:
            response = await self.client.get(url)
            response.raise_for_status()
            data = response.json()

            # 返回图表简要信息
            charts = []
            for chart_id, chart_info in data.get("charts", {}).items():
                charts.append({
                    "id": chart_id,
                    "name": chart_info.get("name"),
                    "family": chart_info.get("family"),
                    "context": chart_info.get("context"),
                    "title": chart_info.get("title"),
                    "units": chart_info.get("units"),
                })

            return charts

        except (httpx.HTTPStatusError, httpx.RequestError) as e:
            logger.error(f"获取图表列表失败: {e}")
            raise

    async def get_alarms(self, host: str | None = None) -> dict[str, Any]:
        """
        获取当前告警状态

        Returns:
            dict: 告警信息
        """
        url = f"{self.base_url}/alarms"

        try:
            response = await self.client.get(url)
            response.raise_for_status()
            return response.json()

        except (httpx.HTTPStatusError, httpx.RequestError) as e:
            logger.error(f"获取告警失败: {e}")
            raise

    async def health_check(self) -> bool:
        """
        检查 NetData 服务是否可用

        Returns:
            bool: 是否可用
        """
        try:
            response = await self.client.get(
                f"http://{self.host}:{self.port}/api/v1/info",
                timeout=5.0,
            )
            return response.status_code == 200
        except Exception as e:
            logger.warning(f"NetData 健康检查失败: {e}")
            return False

    async def close(self) -> None:
        """关闭客户端连接"""
        if self._client:
            await self._client.aclose()
            self._client = None


# 常用图表常量
class NetDataCharts:
    """常用 NetData 图表名称"""

    # 系统级指标
    CPU = "system.cpu"  # CPU 使用率
    RAM = "system.ram"  # 内存使用
    LOAD = "system.load"  # 系统负载
    UPTIME = "system.uptime"  # 运行时间

    # 网络
    NETWORK = "system.network"  # 网络流量
    NET_ERRORS = "system.net_errors"  # 网络错误

    # 磁盘
    DISK_IO = "disk.io"  # 磁盘 I/O
    DISK_SPACE = "disk.space"  # 磁盘空间
    DISK_OPS = "disk.ops"  # 磁盘操作

    # 进程
    PROC_CPU = "apps.cpu"  # 进程 CPU
    PROC_MEM = "apps.mem"  # 进程内存
    PROC_FILES = "apps.files"  # 进程文件描述符

    # 容器（如果安装了相应插件）
    CONTAINER_CPU = "cgroup_cpu.cpu"  # 容器 CPU
    CONTAINER_MEM = "cgroup_memory.mem"  # 容器内存
