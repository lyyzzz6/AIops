"""
============================================================
异常检测器抽象基类
============================================================

用途：
    - 定义检测器统一接口
    - 实现模板方法模式
    - 提供公共工具方法

设计模式：
    - 抽象工厂模式：创建不同类型的检测器
    - 模板方法模式：统一检测流程
    - 策略模式：不同检测算法可互换

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

import joblib
import numpy as np
from loguru import logger


class BaseDetector(ABC):
    """
    异常检测器抽象基类

    所有检测器都应继承此类并实现以下方法：
    - fit(): 训练模型
    - predict(): 预测异常分数
    - partial_fit(): 在线学习（可选）
    """

    def __init__(
        self,
        name: str,
        contamination: float = 0.1,
        random_state: int | None = None,
    ):
        """
        初始化检测器

        Args:
            name: 检测器名称
            contamination: 预期异常比例（0.01-0.5）
            random_state: 随机种子（可复现性）
        """
        self.name = name
        self.contamination = contamination
        self.random_state = random_state
        self._model: Any = None
        self._is_fitted = False
        self._training_time_ms: float = 0
        self._prediction_count: int = 0

    @property
    def is_fitted(self) -> bool:
        """模型是否已训练"""
        return self._is_fitted

    @property
    def model(self) -> Any:
        """底层模型对象"""
        return self._model

    @abstractmethod
    def fit(self, X: np.ndarray) -> "BaseDetector":
        """
        训练模型

        Args:
            X: 训练数据，形状为 (n_samples, n_features)

        Returns:
            self: 训练后的检测器实例
        """
        pass

    @abstractmethod
    def predict(self, X: np.ndarray) -> np.ndarray:
        """
        预测异常分数

        Args:
            X: 待检测数据，形状为 (n_samples, n_features)

        Returns:
            np.ndarray: 异常分数数组，形状为 (n_samples,)，值在 [0, 1] 范围
        """
        pass

    def fit_predict(self, X: np.ndarray) -> np.ndarray:
        """
        训练并预测

        Args:
            X: 数据

        Returns:
            np.ndarray: 异常分数
        """
        self.fit(X)
        return self.predict(X)

    def detect(self, X: np.ndarray, threshold: float = 0.5) -> tuple[np.ndarray, np.ndarray]:
        """
        检测异常

        Args:
            X: 待检测数据
            threshold: 异常阈值

        Returns:
            tuple: (异常分数, 是否异常)
        """
        scores = self.predict(X)
        is_anomaly = scores >= threshold
        self._prediction_count += len(X)
        return scores, is_anomaly

    def partial_fit(self, X: np.ndarray) -> "BaseDetector":
        """
        在线学习（可选实现）

        用于流式检测场景，逐批更新模型

        Args:
            X: 新数据

        Returns:
            self: 更新后的检测器实例

        Raises:
            NotImplementedError: 如果检测器不支持在线学习
        """
        raise NotImplementedError(f"{self.name} 不支持在线学习")

    def save(self, path: str | Path) -> None:
        """
        保存模型到文件

        Args:
            path: 保存路径
        """
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)

        model_data = {
            "name": self.name,
            "model": self._model,
            "contamination": self.contamination,
            "is_fitted": self._is_fitted,
        }

        joblib.dump(model_data, path)
        logger.info(f"模型已保存: {path}")

    @classmethod
    def load(cls, path: str | Path) -> "BaseDetector":
        """
        从文件加载模型

        Args:
            path: 模型文件路径

        Returns:
            BaseDetector: 加载的检测器实例
        """
        model_data = joblib.load(path)

        detector = cls(
            name=model_data["name"],
            contamination=model_data["contamination"],
        )
        detector._model = model_data["model"]
        detector._is_fitted = model_data["is_fitted"]

        logger.info(f"模型已加载: {path}")
        return detector

    def get_stats(self) -> dict[str, Any]:
        """
        获取检测器统计信息

        Returns:
            dict: 统计信息
        """
        return {
            "name": self.name,
            "is_fitted": self._is_fitted,
            "contamination": self.contamination,
            "training_time_ms": self._training_time_ms,
            "prediction_count": self._prediction_count,
        }

    def _validate_input(self, X: np.ndarray) -> np.ndarray:
        """
        验证输入数据

        Args:
            X: 输入数据

        Returns:
            np.ndarray: 验证后的数据

        Raises:
            ValueError: 如果数据无效
        """
        if not isinstance(X, np.ndarray):
            X = np.array(X)

        if X.ndim == 1:
            X = X.reshape(-1, 1)

        if X.size == 0:
            raise ValueError("输入数据不能为空")

        if not np.all(np.isfinite(X)):
            raise ValueError("输入数据包含非有限值（NaN 或 Inf）")

        return X

    def _normalize_scores(self, scores: np.ndarray) -> np.ndarray:
        """
        归一化异常分数到 [0, 1] 范围

        PyOD 返回的分数范围不固定，需要归一化

        Args:
            scores: 原始分数

        Returns:
            np.ndarray: 归一化分数
        """
        # 使用 Min-Max 归一化
        min_val = np.min(scores)
        max_val = np.max(scores)

        if max_val == min_val:
            # 所有值相同，返回 0
            return np.zeros_like(scores)

        normalized = (scores - min_val) / (max_val - min_val)
        return normalized

    def __repr__(self) -> str:
        return f"{self.__class__.__name__}(name={self.name!r}, is_fitted={self._is_fitted})"


class OfflineDetector(BaseDetector):
    """
    离线检测器基类

    需要先训练再使用，适合批量数据分析
    """

    @property
    def supports_online_learning(self) -> bool:
        """是否支持在线学习"""
        return False


class OnlineDetector(BaseDetector):
    """
    在线检测器基类

    支持流式处理，适合实时监控
    """

    @property
    def supports_online_learning(self) -> bool:
        """是否支持在线学习"""
        return True

    @abstractmethod
    def partial_fit(self, X: np.ndarray) -> "OnlineDetector":
        """在线学习实现"""
        pass


class DetectorFactory:
    """
    检测器工厂类

    使用工厂模式创建不同类型的检测器
    """

    _registry: dict[str, type[BaseDetector]] = {}

    @classmethod
    def register(cls, name: str, detector_class: type[BaseDetector]) -> None:
        """
        注册检测器类

        Args:
            name: 检测器名称
            detector_class: 检测器类
        """
        cls._registry[name] = detector_class
        logger.debug(f"注册检测器: {name}")

    @classmethod
    def create(
        cls,
        name: str,
        **kwargs: Any,
    ) -> BaseDetector:
        """
        创建检测器实例

        Args:
            name: 检测器名称
            **kwargs: 传递给检测器的参数

        Returns:
            BaseDetector: 检测器实例

        Raises:
            ValueError: 如果检测器类型未注册
        """
        if name not in cls._registry:
            available = list(cls._registry.keys())
            raise ValueError(f"未知检测器类型: {name}，可用类型: {available}")

        detector_class = cls._registry[name]
        return detector_class(name=name, **kwargs)

    @classmethod
    def list_available(cls) -> list[str]:
        """列出所有可用的检测器"""
        return list(cls._registry.keys())
