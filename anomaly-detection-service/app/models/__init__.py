"""
数据模型模块
"""

from app.models.schemas import (
    AnomalyLevel,
    AnomalyResult,
    BatchDetectionRequest,
    BatchDetectionResponse,
    DetectionContext,
    DetectionStatus,
    DetectorType,
    ErrorResponse,
    HealthResponse,
    MetricDataPoint,
    NetDataFetchRequest,
    StreamDetectionRequest,
    StreamDetectionResponse,
    TrainDetectorRequest,
    TrainDetectorResponse,
)

__all__ = [
    # 枚举
    "AnomalyLevel",
    "DetectionStatus",
    "DetectorType",
    # 请求模型
    "BatchDetectionRequest",
    "StreamDetectionRequest",
    "TrainDetectorRequest",
    "NetDataFetchRequest",
    "MetricDataPoint",
    # 响应模型
    "AnomalyResult",
    "BatchDetectionResponse",
    "StreamDetectionResponse",
    "TrainDetectorResponse",
    "HealthResponse",
    "ErrorResponse",
    # 内部模型
    "DetectionContext",
]
