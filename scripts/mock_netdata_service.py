#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
============================================================
Mock Netdata Service - 模拟Netdata监控数据服务
============================================================

用途：
    - 提供模拟的Netdata API接口
    - 生成逼真的监控数据用于测试
    - 支持各种常用的指标图表

作者：刘一舟
更新时间：2026-05-11
============================================================
"""

import random
import time
from datetime import datetime, timedelta
from typing import List, Dict, Any

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="Mock Netdata Service", description="模拟Netdata监控数据服务")

# 模拟数据生成器
class MockDataGenerator:
    """模拟监控数据生成器"""
    
    def __init__(self):
        self.base_values = {
            "system.cpu": {"user": 30.0, "system": 15.0, "idle": 50.0, "nice": 5.0},
            "system.ram": {"used": 60.0, "cached": 20.0, "free": 15.0, "buffers": 5.0},
            "system.load": {"load1": 1.5, "load5": 1.2, "load15": 1.0},
            "system.net": {"in": 1000.0, "out": 800.0},
            "disk.io": {"read": 500.0, "write": 300.0}
        }
        
    def generate_data_point(self, base: float, variance: float = 10.0) -> float:
        """生成带波动的数据点"""
        noise = random.uniform(-variance, variance)
        value = base + noise
        return max(0.0, min(100.0, value))
    
    def generate_time_series(self, chart: str, points: int = 60) -> Dict[str, Any]:
        """生成时间序列数据"""
        now = time.time()
        labels = []
        data = []
        
        if chart == "system.cpu":
            labels = ["time", "user", "system", "idle", "nice", "iowait", "irq", "softirq"]
            base = self.base_values["system.cpu"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(base["user"], 15.0),
                    self.generate_data_point(base["system"], 10.0),
                    self.generate_data_point(base["idle"], 10.0),
                    self.generate_data_point(base["nice"], 3.0),
                    self.generate_data_point(5.0, 3.0),
                    self.generate_data_point(1.0, 1.0),
                    self.generate_data_point(1.0, 1.0)
                ]
                data.append(row)
        
        elif chart == "system.ram":
            labels = ["time", "used", "cached", "free", "buffers"]
            base = self.base_values["system.ram"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(base["used"], 5.0),
                    self.generate_data_point(base["cached"], 3.0),
                    self.generate_data_point(base["free"], 5.0),
                    self.generate_data_point(base["buffers"], 2.0)
                ]
                data.append(row)
        
        elif chart == "system.load":
            labels = ["time", "load1", "load5", "load15"]
            base = self.base_values["system.load"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(base["load1"], 0.5),
                    self.generate_data_point(base["load5"], 0.3),
                    self.generate_data_point(base["load15"], 0.2)
                ]
                data.append(row)
        
        elif chart == "system.net":
            labels = ["time", "in", "out"]
            base = self.base_values["system.net"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(base["in"], 200.0),
                    self.generate_data_point(base["out"], 150.0)
                ]
                data.append(row)
        
        elif chart == "disk.io":
            labels = ["time", "read", "write"]
            base = self.base_values["disk.io"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(base["read"], 100.0),
                    self.generate_data_point(base["write"], 80.0)
                ]
                data.append(row)
        
        else:
            # 默认生成简单的随机数据
            labels = ["time", "value"]
            for i in range(points):
                timestamp = int(now - (points - i - 1))
                row = [
                    timestamp,
                    self.generate_data_point(50.0, 20.0)
                ]
                data.append(row)
        
        return {
            "labels": labels,
            "data": data
        }

generator = MockDataGenerator()

# API 端点
@app.get("/api/v1/info")
async def get_info():
    """Netdata信息端点"""
    return {
        "version": "1.44.0",
        "uid": "mock-netdata-123456",
        "mirrored_hosts": [],
        "host_labels": {
            "_os_name": "Linux",
            "_os_version": "6.5.0",
            "_kernel_version": "6.5.0-28-generic",
            "_architecture": "x86_64",
            "_is_k8s_node": "false",
            "_is_docker": "true"
        },
        "cloud_enabled": False
    }

@app.get("/api/v1/charts")
async def get_charts():
    """获取可用图表列表"""
    return {
        "charts": {
            "system.cpu": {
                "id": "system.cpu",
                "name": "system.cpu",
                "family": "system",
                "context": "system.cpu",
                "title": "CPU Usage",
                "units": "percentage",
                "type": "area"
            },
            "system.ram": {
                "id": "system.ram",
                "name": "system.ram",
                "family": "system",
                "context": "system.ram",
                "title": "Memory Usage",
                "units": "percentage",
                "type": "stacked"
            },
            "system.load": {
                "id": "system.load",
                "name": "system.load",
                "family": "system",
                "context": "system.load",
                "title": "System Load Average",
                "units": "load",
                "type": "line"
            },
            "system.net": {
                "id": "system.net",
                "name": "system.net",
                "family": "system",
                "context": "system.net",
                "title": "Network Traffic",
                "units": "kilobits/s",
                "type": "area"
            },
            "disk.io": {
                "id": "disk.io",
                "name": "disk.io",
                "family": "disk",
                "context": "disk.io",
                "title": "Disk I/O",
                "units": "operations/s",
                "type": "area"
            }
        }
    }

@app.get("/api/v1/data")
async def get_data(
    chart: str,
    after: int = -60,
    before: int = 0,
    points: int = 60,
    group: str = "average",
    format: str = "json",
    options: str = ""
):
    """
    获取图表数据
    
    参数说明：
    - chart: 图表名称
    - after: 起始时间（相对当前，负数表示过去）
    - before: 结束时间（相对当前）
    - points: 返回数据点数量
    - group: 聚合方式
    - format: 返回格式
    """
    try:
        data = generator.generate_time_series(chart, min(points, 1000))
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/api/v1/alarms")
async def get_alarms():
    """获取告警信息"""
    return {
        "alarms": {
            "last_repeat": 0,
            "status": {
                "warning": 1,
                "critical": 0
            },
            "alarms": {
                "1": {
                    "id": 1,
                    "name": "disk_space_usage",
                    "chart": "disk.space._",
                    "family": "disk space",
                    "status": "WARNING",
                    "severity": "warning",
                    "value": 85.0,
                    "units": "%",
                    "when": int(time.time()),
                    "description": "Disk space usage is above 80%"
                }
            }
        }
    }

@app.get("/")
async def root():
    """根路径"""
    return {
        "service": "Mock Netdata Service",
        "version": "1.0.0",
        "status": "running",
        "endpoints": {
            "info": "/api/v1/info",
            "charts": "/api/v1/charts",
            "data": "/api/v1/data",
            "alarms": "/api/v1/alarms"
        }
    }

if __name__ == "__main__":
    print("Starting Mock Netdata Service on port 19999...")
    uvicorn.run(
        "mock_netdata_service:app",
        host="0.0.0.0",
        port=19999,
        log_level="info"
    )
