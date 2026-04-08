"""
============================================================
API 接口测试
============================================================

测试内容：
    - API 端点响应
    - 请求验证
    - 错误处理

运行方法：
    pytest tests/test_api.py -v

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import DetectorType


@pytest.fixture
def client():
    """创建测试客户端"""
    return TestClient(app)


class TestHealthEndpoints:
    """测试健康检查端点"""

    def test_health(self, client):
        """测试健康检查"""
        response = client.get("/api/health")

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "healthy"
        assert "version" in data
        assert "uptime_seconds" in data

    def test_ready(self, client):
        """测试就绪检查"""
        response = client.get("/api/ready")

        assert response.status_code == 200
        assert response.json()["status"] == "ready"

    def test_live(self, client):
        """测试存活检查"""
        response = client.get("/api/live")

        assert response.status_code == 200
        assert response.json()["status"] == "alive"


class TestRootEndpoint:
    """测试根路径"""

    def test_root(self, client):
        """测试根路径"""
        response = client.get("/")

        assert response.status_code == 200
        data = response.json()
        assert "name" in data
        assert "version" in data
        assert "docs" in data


class TestDetectionEndpoints:
    """测试检测端点"""

    def test_batch_detect(self, client):
        """测试批量检测"""
        request_data = {
            "data": [
                {"metric_name": "cpu.usage", "value": 10.0},
                {"metric_name": "cpu.usage", "value": 15.0},
                {"metric_name": "cpu.usage", "value": 12.0},
                {"metric_name": "cpu.usage", "value": 11.0},
                {"metric_name": "cpu.usage", "value": 100.0},  # 可能是异常
            ],
            "detector_type": "isolation_forest",
            "return_scores": True,
        }

        response = client.post("/api/v1/detection/batch", json=request_data)

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "success"
        assert data["total_count"] == 5
        assert "anomaly_count" in data
        assert "results" in data

    def test_batch_detect_invalid_data(self, client):
        """测试无效数据"""
        # 数据点太少
        request_data = {
            "data": [
                {"metric_name": "cpu.usage", "value": 10.0},
            ],
            "detector_type": "isolation_forest",
        }

        response = client.post("/api/v1/detection/batch", json=request_data)

        assert response.status_code == 422  # Validation Error

    def test_stream_detect(self, client):
        """测试流式检测"""
        request_data = {
            "data_point": {
                "metric_name": "cpu.usage",
                "value": 50.0,
            },
            "detector_type": "half_space_trees",
        }

        response = client.post("/api/v1/detection/stream", json=request_data)

        assert response.status_code == 200
        data = response.json()
        assert "is_anomaly" in data
        assert "anomaly_score" in data
        assert "level" in data

    def test_train_detector(self, client):
        """测试训练检测器"""
        # 生成训练数据
        import numpy as np
        np.random.seed(42)
        training_values = np.random.randn(50).tolist()

        request_data = {
            "training_data": [
                {"metric_name": "cpu.usage", "value": v}
                for v in training_values
            ],
            "detector_type": "isolation_forest",
            "contamination": 0.1,
        }

        response = client.post("/api/v1/detection/train", json=request_data)

        assert response.status_code == 201
        data = response.json()
        assert data["status"] == "success"
        assert "model_name" in data
        assert data["training_samples"] == 50


class TestOpenAPI:
    """测试 OpenAPI 文档"""

    def test_docs(self, client):
        """测试 API 文档"""
        response = client.get("/api/docs")
        assert response.status_code == 200

    def test_openapi_json(self, client):
        """测试 OpenAPI JSON"""
        response = client.get("/api/openapi.json")
        assert response.status_code == 200
        data = response.json()
        assert "openapi" in data
        assert "paths" in data
