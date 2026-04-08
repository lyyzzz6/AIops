"""
============================================================
PySAD 在线异常检测器实现
============================================================

用途：
    - 封装 PySAD 库的流式检测算法
    - 支持实时单条数据处理

算法选择指南：
    - Half-Space Trees: 实时监控，低延迟
    - xStream: 高维流式数据

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time

import numpy as np
from loguru import logger

from app.config import settings
from app.core.detector_base import DetectorFactory, OnlineDetector

# PySAD 导入
try:
    from pysad.models import HalfSpaceTrees, xStream
    from pysad.utils import ArrayStreamer
    PYSAD_AVAILABLE = True
except ImportError:
    PYSAD_AVAILABLE = False
    logger.warning("PySAD 未安装，在线检测功能不可用")


class HalfSpaceTreesDetector(OnlineDetector):
    """
    半空间树检测器 (Half-Space Trees)

    原理：
        使用随机投影构建半空间树
        在树中深度较浅的点被认为是异常

    优点：
        - 真正的流式检测，无需批量数据
        - 内存占用固定
        - 检测延迟低

    缺点：
        - 需要一定的"预热"数据
        - 对概念漂移敏感

    适用场景：
        - 实时监控告警
        - CPU/内存/网络流量实时异常检测
        - 单指标流式监控

    使用方法：
        detector = HalfSpaceTreesDetector()
        for value in stream:
            score = detector.score(value)
            if score > threshold:
                alert()
    """

    def __init__(
        self,
        name: str = "half_space_trees",
        n_estimators: int = 25,
        window_size: int = 100,
        max_depth: int = 15,
        n_features: int = 1,
    ):
        """
        初始化半空间树检测器

        Args:
            name: 检测器名称
            n_estimators: 树的数量
            window_size: 滑动窗口大小
            max_depth: 树的最大深度
            n_features: 特征数量
        """
        super().__init__(name, contamination=0.1)

        if not PYSAD_AVAILABLE:
            raise ImportError("PySAD 未安装，无法使用 HalfSpaceTrees")

        self.n_estimators = n_estimators
        self.window_size = window_size
        self.max_depth = max_depth
        self.n_features = n_features

        # 初始化模型
        self._model = HalfSpaceTrees(
            n_estimators=self.n_estimators,
            window_size=self.window_size,
            max_depth=self.max_depth,
        )

        # 数据流迭代器
        self._streamer = ArrayStreamer()

        # 统计信息
        self._sample_count = 0
        self._last_score = 0.0

    def fit(self, X: np.ndarray) -> "HalfSpaceTreesDetector":
        """
        使用初始数据"预热"检测器

        注意：在线检测器不需要传统意义上的训练
        这个方法用于用历史数据初始化内部状态

        Args:
            X: 初始数据 (n_samples, n_features)

        Returns:
            self
        """
        X = self._validate_input(X)

        logger.info(f"预热 HalfSpaceTrees: {X.shape[0]} 样本")

        # 逐条处理数据进行"预热"
        for x in X:
            self._process_single(x.reshape(1, -1))

        self._is_fitted = True
        self._sample_count = len(X)

        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """
        批量预测异常分数

        Args:
            X: 数据 (n_samples, n_features)

        Returns:
            np.ndarray: 异常分数
        """
        X = self._validate_input(X)

        scores = []
        for x in X:
            score = self._process_single(x.reshape(1, -1))
            scores.append(score)

        return np.array(scores)

    def partial_fit(self, X: np.ndarray) -> "HalfSpaceTreesDetector":
        """
        在线学习

        更新检测器的内部状态

        Args:
            X: 新数据

        Returns:
            self
        """
        X = self._validate_input(X)

        for x in X:
            self._process_single(x.reshape(1, -1))

        self._sample_count += len(X)

        return self

    def score_single(self, value: float) -> float:
        """
        对单个值进行评分

        这是流式检测的主要接口

        Args:
            value: 单个指标值

        Returns:
            float: 异常分数 [0, 1]
        """
        x = np.array([[value]])
        score = self._process_single(x)
        self._sample_count += 1
        return score

    def _process_single(self, x: np.ndarray) -> float:
        """
        处理单个数据点

        Args:
            x: 数据点 (1, n_features)

        Returns:
            float: 异常分数
        """
        # PySAD 的 score 方法返回原始分数
        score = self._model.score(x.reshape(1, -1))[0]

        # 保存最后一个分数
        self._last_score = float(score)

        return self._normalize_stream_score(score)

    def _normalize_stream_score(self, score: float) -> float:
        """
        归一化流式检测分数

        PySAD 返回的分数范围不固定
        使用 Sigmoid 函数映射到 [0, 1]

        Args:
            score: 原始分数

        Returns:
            float: 归一化分数 [0, 1]
        """
        # 使用 Sigmoid 归一化
        # 将任意范围的分数映射到 (0, 1)
        import math
        normalized = 1 / (1 + math.exp(-score))

        return normalized

    def get_stats(self) -> dict:
        """获取检测器统计信息"""
        stats = super().get_stats()
        stats.update({
            "sample_count": self._sample_count,
            "last_score": self._last_score,
            "window_size": self.window_size,
        })
        return stats


class xStreamDetector(OnlineDetector):
    """
    xStream 流式异常检测器

    原理：
        使用参考角度和链式投影
        比半空间树更适合高维数据

    优点：
        - 适合高维流式数据
        - 检测精度高

    缺点：
        - 计算复杂度较高
        - 内存占用稍大

    适用场景：
        - 多维指标联合检测
        - 复杂特征的异常检测
    """

    def __init__(
        self,
        name: str = "xstream",
        n_components: int = 100,
        n_chains: int = 100,
        window_size: int = 100,
    ):
        """
        初始化 xStream 检测器

        Args:
            name: 检测器名称
            n_components: 组件数量
            n_chains: 链数量
            window_size: 窗口大小
        """
        super().__init__(name, contamination=0.1)

        if not PYSAD_AVAILABLE:
            raise ImportError("PySAD 未安装，无法使用 xStream")

        self.n_components = n_components
        self.n_chains = n_chains
        self.window_size = window_size

        # 初始化模型
        self._model = xStream(
            n_components=self.n_components,
            n_chains=self.n_chains,
            window_size=self.window_size,
        )

        self._sample_count = 0

    def fit(self, X: np.ndarray) -> "xStreamDetector":
        """预热检测器"""
        X = self._validate_input(X)

        logger.info(f"预热 xStream: {X.shape[0]} 样本")

        for x in X:
            self._model.score(x.reshape(1, -1))

        self._is_fitted = True
        self._sample_count = len(X)

        return self

    def predict(self, X: np.ndarray) -> np.ndarray:
        """批量预测"""
        X = self._validate_input(X)

        scores = []
        for x in X:
            score = self._model.score(x.reshape(1, -1))[0]
            scores.append(self._normalize_stream_score(score))

        self._sample_count += len(X)
        return np.array(scores)

    def partial_fit(self, X: np.ndarray) -> "xStreamDetector":
        """在线学习"""
        X = self._validate_input(X)

        for x in X:
            self._model.score(x.reshape(1, -1))

        self._sample_count += len(X)
        return self

    def score_single(self, value: float) -> float:
        """对单个值评分"""
        x = np.array([[value]])
        score = self._model.score(x)[0]
        self._sample_count += 1
        return self._normalize_stream_score(score)

    def _normalize_stream_score(self, score: float) -> float:
        """归一化流式分数"""
        import math
        return 1 / (1 + math.exp(-score))


# ============================================================
# 注册检测器到工厂
# ============================================================
if PYSAD_AVAILABLE:
    DetectorFactory.register("half_space_trees", HalfSpaceTreesDetector)
    DetectorFactory.register("xstream", xStreamDetector)


__all__ = [
    "HalfSpaceTreesDetector",
    "xStreamDetector",
    "PYSAD_AVAILABLE",
]
