"""
============================================================
异常检测服务层
============================================================

用途：
    - 协调检测器的使用
    - 管理检测器实例生命周期
    - 提供统一的检测接口

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time
import uuid
from pathlib import Path
from typing import Any

import numpy as np
from loguru import logger

from app.config import settings
from app.core import (
    BaseDetector,
    DetectorFactory,
    IsolationForestDetector,
    LOFDetector,
    KNNDetector,
    HalfSpaceTreesDetector,
)
from app.models import DetectorType


class DetectionService:
    """
    异常检测服务

    职责：
    1. 管理检测器实例池
    2. 提供批量/流式检测接口
    3. 处理检测结果
    4. 模型持久化

    使用方法：
        service = DetectionService()

        # 批量检测
        scores, is_anomaly = await service.detect_batch(data, "isolation_forest")

        # 流式检测
        score = await service.detect_stream(value, "half_space_trees")
    """

    def __init__(self, model_dir: str | Path | None = None):
        """
        初始化检测服务

        Args:
            model_dir: 模型存储目录
        """
        self.model_dir = Path(model_dir or "models")
        self.model_dir.mkdir(parents=True, exist_ok=True)

        # 检测器实例池
        # key: detector_type, value: detector instance
        self._detectors: dict[str, BaseDetector] = {}

        # 在线检测器实例（每种类型一个）
        self._online_detectors: dict[str, BaseDetector] = {}

        logger.info(f"检测服务初始化，模型目录: {self.model_dir}")

    async def detect_batch(
        self,
        data: np.ndarray,
        detector_type: DetectorType | str,
        threshold: float | None = None,
    ) -> tuple[np.ndarray, np.ndarray]:
        """
        批量异常检测

        Args:
            data: 待检测数据 (n_samples, n_features)
            detector_type: 检测器类型
            threshold: 异常阈值

        Returns:
            tuple: (异常分数数组, 是否异常数组)
        """
        start_time = time.time()

        # 规范化参数
        if isinstance(detector_type, DetectorType):
            detector_type = detector_type.value

        threshold = threshold or settings.anomaly_threshold

        # 获取或创建检测器
        detector = await self._get_detector(detector_type)

        # 如果是未训练的离线检测器，先用当前数据训练
        if not detector.is_fitted:
            logger.info(f"检测器 {detector_type} 未训练，使用当前数据进行训练")
            detector.fit(data)

        # 执行检测
        scores, is_anomaly = detector.detect(data, threshold)

        elapsed = (time.time() - start_time) * 1000
        logger.debug(
            f"批量检测完成: {len(data)} 条数据, "
            f"{np.sum(is_anomaly)} 个异常, 耗时: {elapsed:.2f}ms"
        )

        return scores, is_anomaly

    async def detect_stream(
        self,
        value: float,
        detector_type: DetectorType | str = DetectorType.HALF_SPACE_TREES,
    ) -> float:
        """
        流式异常检测

        对单个值进行实时检测

        Args:
            value: 待检测值
            detector_type: 在线检测器类型

        Returns:
            float: 异常分数 [0, 1]
        """
        if isinstance(detector_type, DetectorType):
            detector_type = detector_type.value

        # 获取或创建在线检测器
        detector = await self._get_online_detector(detector_type)

        # 执行流式检测
        if hasattr(detector, "score_single"):
            score = detector.score_single(value)
        else:
            # 回退到批量方式
            data = np.array([[value]])
            scores = detector.predict(data)
            score = scores[0]

        return score

    async def train(
        self,
        data: np.ndarray,
        detector_type: DetectorType | str,
        contamination: float = 0.1,
        model_name: str | None = None,
    ) -> str:
        """
        训练检测器

        Args:
            data: 训练数据
            detector_type: 检测器类型
            contamination: 异常比例
            model_name: 模型名称（用于保存）

        Returns:
            str: 模型名称
        """
        if isinstance(detector_type, DetectorType):
            detector_type = detector_type.value

        # 生成模型名称
        model_name = model_name or f"{detector_type}_{uuid.uuid4().hex[:8]}"

        # 创建检测器
        detector = self._create_detector(detector_type, contamination=contamination)

        # 训练
        detector.fit(data)

        # 保存到实例池
        self._detectors[model_name] = detector

        # 持久化
        model_path = self.model_dir / f"{model_name}.joblib"
        detector.save(model_path)

        return model_name

    async def load_model(self, model_name: str) -> BaseDetector:
        """
        加载已保存的模型

        Args:
            model_name: 模型名称

        Returns:
            BaseDetector: 加载的检测器
        """
        model_path = self.model_dir / f"{model_name}.joblib"

        if not model_path.exists():
            raise FileNotFoundError(f"模型文件不存在: {model_path}")

        detector = BaseDetector.load(model_path)
        self._detectors[model_name] = detector

        return detector

    async def _get_detector(self, detector_type: str) -> BaseDetector:
        """
        获取检测器实例

        如果实例池中存在则返回，否则创建新实例

        Args:
            detector_type: 检测器类型

        Returns:
            BaseDetector: 检测器实例
        """
        # 检查实例池
        if detector_type in self._detectors:
            return self._detectors[detector_type]

        # 创建新实例
        detector = self._create_detector(detector_type)
        self._detectors[detector_type] = detector

        return detector

    async def _get_online_detector(self, detector_type: str) -> BaseDetector:
        """
        获取在线检测器实例

        在线检测器需要保持状态，每种类型只创建一个实例

        Args:
            detector_type: 检测器类型

        Returns:
            BaseDetector: 在线检测器实例
        """
        if detector_type in self._online_detectors:
            return self._online_detectors[detector_type]

        # 创建新的在线检测器
        detector = self._create_detector(detector_type)

        # 在线检测器需要预热
        # 使用随机数据进行初始化
        warmup_data = np.random.randn(100, 1) * 0.1
        detector.fit(warmup_data)

        self._online_detectors[detector_type] = detector

        return detector

    def _create_detector(
        self,
        detector_type: str,
        **kwargs: Any,
    ) -> BaseDetector:
        """
        创建检测器实例

        根据类型创建对应的检测器

        Args:
            detector_type: 检测器类型
            **kwargs: 检测器参数

        Returns:
            BaseDetector: 检测器实例
        """
        # 默认参数
        default_kwargs = {
            "contamination": kwargs.get("contamination", settings.iforest_contamination),
        }

        # 根据类型设置特定参数
        if detector_type == DetectorType.ISOLATION_FOREST.value:
            default_kwargs.update({
                "n_estimators": settings.iforest_n_estimators,
            })
            return IsolationForestDetector(**default_kwargs)

        elif detector_type == DetectorType.LOF.value:
            default_kwargs.update({
                "n_neighbors": settings.lof_n_neighbors,
            })
            return LOFDetector(**default_kwargs)

        elif detector_type == DetectorType.KNN.value:
            return KNNDetector(**default_kwargs)

        elif detector_type == DetectorType.HALF_SPACE_TREES.value:
            return HalfSpaceTreesDetector(
                window_size=settings.online_window_size,
            )

        else:
            # 使用工厂创建
            return DetectorFactory.create(detector_type, **default_kwargs)

    def get_detector_stats(self) -> dict[str, Any]:
        """
        获取所有检测器统计信息

        Returns:
            dict: 统计信息
        """
        stats = {
            "offline_detectors": {},
            "online_detectors": {},
        }

        for name, detector in self._detectors.items():
            stats["offline_detectors"][name] = detector.get_stats()

        for name, detector in self._online_detectors.items():
            stats["online_detectors"][name] = detector.get_stats()

        return stats
