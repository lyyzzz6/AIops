"""
============================================================
PyOD 离线异常检测器实现
============================================================

用途：
    - 封装 PyOD 库的检测算法
    - 提供统一的检测器接口

算法选择指南：
    - Isolation Forest: 高维数据，速度快
    - LOF: 密度不均数据，局部异常
    - KNN: 低维数据，简单有效

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time

import numpy as np
from loguru import logger
from pyod.models.iforest import IForest
from pyod.models.knn import KNN
from pyod.models.lof import LOF

from app.core.detector_base import DetectorFactory, OfflineDetector


class IsolationForestDetector(OfflineDetector):
    """
    隔离森林检测器

    原理：
        通过随机划分将异常点"隔离"出来
        异常点通常更容易被隔离（需要更少的划分次数）

    优点：
        - 适合高维数据
        - 计算效率高
        - 无需假设数据分布

    缺点：
        - 对局部异常不敏感
        - 需要足够的样本量

    适用场景：
        - CPU/内存使用率异常检测
        - 网络流量异常检测
        - 多维指标联合检测
    """

    def __init__(
        self,
        name: str = "isolation_forest",
        contamination: float = 0.1,
        n_estimators: int = 100,
        max_samples: int | str = "auto",
        max_features: float = 1.0,
        random_state: int | None = None,
    ):
        super().__init__(name, contamination, random_state)

        self.n_estimators = n_estimators
        self.max_samples = max_samples
        self.max_features = max_features

    def fit(self, X: np.ndarray) -> "IsolationForestDetector":
        """
        训练隔离森林模型

        Args:
            X: 训练数据 (n_samples, n_features)

        Returns:
            self: 训练后的检测器
        """
        start_time = time.time()

        # 验证输入
        X = self._validate_input(X)

        logger.info(f"训练 Isolation Forest: {X.shape[0]} 样本, {X.shape[1]} 特征")

        # 创建 PyOD 模型
        self._model = IForest(
            n_estimators=self.n_estimators,
            max_samples=self.max_samples,
            max_features=self.max_features,
            contamination=self.contamination,
            random_state=self.random_state,
            n_jobs=-1,  # 并行计算
        )

        # 训练
        self._model.fit(X)

        self._is_fitted = True
        self._training_time_ms = (time.time() - start_time) * 1000

        logger.info(f"训练完成，耗时: {self._training_time_ms:.2f}ms")

        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """
        预测异常分数

        Args:
            X: 待检测数据

        Returns:
            np.ndarray: 异常分数 [0, 1]
        """
        if not self._is_fitted:
            raise RuntimeError("模型未训练，请先调用 fit() 方法")

        X = self._validate_input(X)

        # PyOD 返回的异常分数
        # decision_function 返回值越大越异常
        scores = self._model.decision_function(X)

        # 归一化到 [0, 1]
        return self._normalize_scores(scores)


class LOFDetector(OfflineDetector):
    """
    局部异常因子检测器 (Local Outlier Factor)

    原理：
        比较数据点与其邻居的局部密度
        密度显著低于邻居的点被认为是异常

    优点：
        - 能检测局部异常
        - 适合密度不均的数据

    缺点：
        - 计算复杂度较高
        - 需要选择合适的邻居数

    适用场景：
        - 网络流量异常（不同时段流量基准不同）
        - 多模态分布数据
    """

    def __init__(
        self,
        name: str = "lof",
        contamination: float = 0.1,
        n_neighbors: int = 20,
        algorithm: str = "auto",
        leaf_size: int = 30,
    ):
        super().__init__(name, contamination)

        self.n_neighbors = n_neighbors
        self.algorithm = algorithm
        self.leaf_size = leaf_size

    def fit(self, X: np.ndarray) -> "LOFDetector":
        """训练 LOF 模型"""
        start_time = time.time()

        X = self._validate_input(X)

        logger.info(f"训练 LOF: {X.shape[0]} 样本, 邻居数: {self.n_neighbors}")

        self._model = LOF(
            n_neighbors=self.n_neighbors,
            algorithm=self.algorithm,
            leaf_size=self.leaf_size,
            contamination=self.contamination,
            n_jobs=-1,
        )

        self._model.fit(X)

        self._is_fitted = True
        self._training_time_ms = (time.time() - start_time) * 1000

        logger.info(f"训练完成，耗时: {self._training_time_ms:.2f}ms")

        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """预测异常分数"""
        if not self._is_fitted:
            raise RuntimeError("模型未训练，请先调用 fit() 方法")

        X = self._validate_input(X)

        # LOF 的异常分数
        scores = self._model.decision_function(X)

        return self._normalize_scores(scores)


class KNNDetector(OfflineDetector):
    """
    K-近邻异常检测器

    原理：
        计算数据点到 K 个最近邻居的距离
        距离大的点被认为是异常

    优点：
        - 概念简单，易于理解
        - 无需假设数据分布

    缺点：
        - 计算复杂度 O(n^2)
        - 对高维数据效果下降

    适用场景：
        - 低维指标检测
        - 数据量较小的场景
    """

    def __init__(
        self,
        name: str = "knn",
        contamination: float = 0.1,
        n_neighbors: int = 5,
        method: str = "largest",
        algorithm: str = "auto",
    ):
        super().__init__(name, contamination)

        self.n_neighbors = n_neighbors
        self.method = method  # 'largest', 'mean', 'median'
        self.algorithm = algorithm

    def fit(self, X: np.ndarray) -> "KNNDetector":
        """训练 KNN 模型"""
        start_time = time.time()

        X = self._validate_input(X)

        logger.info(f"训练 KNN: {X.shape[0]} 样本, K: {self.n_neighbors}")

        self._model = KNN(
            n_neighbors=self.n_neighbors,
            method=self.method,
            algorithm=self.algorithm,
            contamination=self.contamination,
            n_jobs=-1,
        )

        self._model.fit(X)

        self._is_fitted = True
        self._training_time_ms = (time.time() - start_time) * 1000

        logger.info(f"训练完成，耗时: {self._training_time_ms:.2f}ms")

        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """预测异常分数"""
        if not self._is_fitted:
            raise RuntimeError("模型未训练，请先调用 fit() 方法")

        X = self._validate_input(X)

        scores = self._model.decision_function(X)

        return self._normalize_scores(scores)


# ============================================================
# 注册检测器到工厂
# ============================================================
DetectorFactory.register("isolation_forest", IsolationForestDetector)
DetectorFactory.register("lof", LOFDetector)
DetectorFactory.register("knn", KNNDetector)

# 导出
__all__ = [
    "IsolationForestDetector",
    "LOFDetector",
    "KNNDetector",
]
