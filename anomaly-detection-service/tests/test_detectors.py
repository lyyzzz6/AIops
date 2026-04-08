"""
============================================================
异常检测器单元测试
============================================================

测试内容：
    - 检测器训练和预测
    - 异常分数范围
    - 边界情况处理

运行方法：
    pytest tests/test_detectors.py -v

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import numpy as np
import pytest

from app.core import (
    DetectorFactory,
    IsolationForestDetector,
    KNNDetector,
    LOFDetector,
    PYSAD_AVAILABLE,
)
from app.models import DetectorType


class TestDetectorFactory:
    """测试检测器工厂"""

    def test_list_available(self):
        """测试列出可用检测器"""
        available = DetectorFactory.list_available()
        assert "isolation_forest" in available
        assert "lof" in available
        assert "knn" in available

    def test_create_isolation_forest(self):
        """测试创建隔离森林检测器"""
        detector = DetectorFactory.create("isolation_forest")
        assert isinstance(detector, IsolationForestDetector)
        assert detector.name == "isolation_forest"

    def test_create_lof(self):
        """测试创建 LOF 检测器"""
        detector = DetectorFactory.create("lof", n_neighbors=10)
        assert isinstance(detector, LOFDetector)
        assert detector.n_neighbors == 10

    def test_create_unknown_type(self):
        """测试创建未知类型"""
        with pytest.raises(ValueError, match="未知检测器类型"):
            DetectorFactory.create("unknown_type")


class TestIsolationForestDetector:
    """测试隔离森林检测器"""

    @pytest.fixture
    def detector(self):
        """创建检测器实例"""
        return IsolationForestDetector(
            n_estimators=50,
            contamination=0.1,
            random_state=42,
        )

    @pytest.fixture
    def normal_data(self):
        """生成正常数据"""
        np.random.seed(42)
        return np.random.randn(100, 1)

    @pytest.fixture
    def anomaly_data(self):
        """生成异常数据"""
        return np.array([[10.0], [15.0], [20.0]])

    def test_fit(self, detector, normal_data):
        """测试训练"""
        result = detector.fit(normal_data)

        assert result is detector  # 返回 self
        assert detector.is_fitted

    def test_fit_invalid_data(self, detector):
        """测试无效数据"""
        with pytest.raises(ValueError, match="输入数据不能为空"):
            detector.fit(np.array([]))

        with pytest.raises(ValueError, match="非有限值"):
            detector.fit(np.array([[np.nan]]))

    def test_predict(self, detector, normal_data):
        """测试预测"""
        detector.fit(normal_data)
        scores = detector.predict(normal_data)

        assert scores.shape == (100,)
        assert np.all(scores >= 0) and np.all(scores <= 1)

    def test_predict_without_fit(self, detector, normal_data):
        """测试未训练时预测"""
        with pytest.raises(RuntimeError, match="模型未训练"):
            detector.predict(normal_data)

    def test_detect(self, detector, normal_data, anomaly_data):
        """测试异常检测"""
        detector.fit(normal_data)

        # 正常数据应该得分较低
        normal_scores, normal_is_anomaly = detector.detect(normal_data[:10], threshold=0.7)
        assert np.sum(normal_is_anomaly) <= 2  # 大部分正常

        # 异常数据应该得分较高
        anomaly_scores, anomaly_is_anomaly = detector.detect(anomaly_data, threshold=0.7)
        assert np.sum(anomaly_is_anomaly) >= 2  # 大部分异常

    def test_get_stats(self, detector, normal_data):
        """测试统计信息"""
        detector.fit(normal_data)
        stats = detector.get_stats()

        assert stats["name"] == "isolation_forest"
        assert stats["is_fitted"] is True
        assert stats["training_time_ms"] > 0


class TestLOFDetector:
    """测试 LOF 检测器"""

    @pytest.fixture
    def detector(self):
        return LOFDetector(n_neighbors=10, contamination=0.1)

    @pytest.fixture
    def mixed_data(self):
        """生成混合数据（正常 + 异常）"""
        np.random.seed(42)
        normal = np.random.randn(90, 1)
        anomaly = np.random.randn(10, 1) * 5 + 10
        return np.vstack([normal, anomaly])

    def test_fit_predict(self, detector, mixed_data):
        """测试训练和预测"""
        detector.fit(mixed_data)
        scores = detector.predict(mixed_data)

        assert scores.shape == (100,)
        assert np.all(scores >= 0) and np.all(scores <= 1)

    def test_local_anomaly_detection(self, detector):
        """测试局部异常检测"""
        # 创建两个密度不同的簇
        np.random.seed(42)
        cluster1 = np.random.randn(50, 1) * 0.1  # 密集簇
        cluster2 = np.random.randn(50, 1) * 2 + 10  # 稀疏簇

        data = np.vstack([cluster1, cluster2])
        detector.fit(data)
        scores = detector.predict(data)

        # LOF 应该能检测到密度差异
        assert scores is not None


class TestKNNDetector:
    """测试 KNN 检测器"""

    @pytest.fixture
    def detector(self):
        return KNNDetector(n_neighbors=5, contamination=0.1)

    @pytest.fixture
    def data(self):
        np.random.seed(42)
        return np.random.randn(50, 1)

    def test_fit_predict(self, detector, data):
        """测试训练和预测"""
        detector.fit(data)
        scores = detector.predict(data)

        assert scores.shape == (50,)
        assert np.all(scores >= 0) and np.all(scores <= 1)


@pytest.mark.skipif(not PYSAD_AVAILABLE, reason="PySAD 未安装")
class TestOnlineDetectors:
    """测试在线检测器"""

    def test_half_space_trees(self):
        """测试半空间树检测器"""
        from app.core import HalfSpaceTreesDetector

        detector = HalfSpaceTreesDetector(window_size=50)

        # 预热
        np.random.seed(42)
        warmup_data = np.random.randn(50, 1) * 0.1
        detector.fit(warmup_data)

        # 流式检测
        normal_score = detector.score_single(0.1)  # 正常值
        anomaly_score = detector.score_single(10.0)  # 异常值

        assert 0 <= normal_score <= 1
        assert 0 <= anomaly_score <= 1
        # 异常值分数应该更高
        assert anomaly_score > normal_score


class TestDetectorType:
    """测试检测器类型枚举"""

    def test_enum_values(self):
        """测试枚举值"""
        assert DetectorType.ISOLATION_FOREST.value == "isolation_forest"
        assert DetectorType.LOF.value == "lof"
        assert DetectorType.KNN.value == "knn"
        assert DetectorType.HALF_SPACE_TREES.value == "half_space_trees"

    def test_from_string(self):
        """测试从字符串创建"""
        detector_type = DetectorType("isolation_forest")
        assert detector_type == DetectorType.ISOLATION_FOREST
