"""
============================================================
Pydantic 数据模型定义
============================================================

用途：
    - API 请求/响应模型
    - 数据验证
    - 自动生成 OpenAPI 文档

设计原则：
    - 所有模型继承 BaseModel
    - 使用 Field 添加描述和约束
    - 使用 ConfigDict 配置行为

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

from datetime import datetime
from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


# ============================================================
# 枚举类型
# ============================================================
class DetectorType(str, Enum):
    """检测器类型枚举"""

    # 离线检测器（需要批量数据训练）
    ISOLATION_FOREST = "isolation_forest"  # 隔离森林
    LOF = "lof"  # 局部异常因子
    KNN = "knn"  # K-近邻

    # 在线检测器（流式处理）
    HALF_SPACE_TREES = "half_space_trees"  # 半空间树


class AnomalyLevel(str, Enum):
    """异常等级枚举"""

    NORMAL = "normal"  # 正常
    WARNING = "warning"  # 警告
    CRITICAL = "critical"  # 严重


class DetectionStatus(str, Enum):
    """检测状态枚举"""

    SUCCESS = "success"
    FAILED = "failed"
    PARTIAL = "partial"  # 部分成功


# ============================================================
# 请求模型
# ============================================================
class MetricDataPoint(BaseModel):
    """
    单个指标数据点

    用于接收单条时序数据
    """

    timestamp: datetime | None = Field(
        default=None,
        description="时间戳，不提供则使用当前时间"
    )
    metric_name: str = Field(
        ...,
        min_length=1,
        max_length=100,
        description="指标名称，如 cpu.usage, memory.used"
    )
    value: float = Field(
        ...,
        description="指标值"
    )
    host: str | None = Field(
        default=None,
        max_length=255,
        description="主机名或 IP"
    )
    labels: dict[str, str] | None = Field(
        default=None,
        description="附加标签（如 instance, job）"
    )


class BatchDetectionRequest(BaseModel):
    """
    批量检测请求

    用于提交多条时序数据进行批量异常检测
    """

    data: list[MetricDataPoint] = Field(
        ...,
        min_length=1,
        max_length=10000,
        description="待检测的数据点列表"
    )
    detector_type: DetectorType = Field(
        default=DetectorType.ISOLATION_FOREST,
        description="检测器类型"
    )
    threshold: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
        description="异常阈值（覆盖默认值）"
    )
    return_scores: bool = Field(
        default=True,
        description="是否返回异常分数"
    )

    @field_validator("data")
    @classmethod
    def validate_data_length(cls, v: list) -> list:
        """验证数据点数量"""
        if len(v) < 3:
            raise ValueError("数据点数量必须 >= 3，异常检测需要足够样本")
        return v


class StreamDetectionRequest(BaseModel):
    """
    流式检测请求

    用于实时单条数据的异常检测
    """

    data_point: MetricDataPoint = Field(
        ...,
        description="待检测的数据点"
    )
    detector_type: DetectorType = Field(
        default=DetectorType.HALF_SPACE_TREES,
        description="在线检测器类型"
    )
    threshold: float | None = Field(
        default=None,
        ge=0.0,
        le=1.0,
        description="异常阈值"
    )


class TrainDetectorRequest(BaseModel):
    """
    训练检测器请求

    用于用历史数据训练离线检测器
    """

    training_data: list[MetricDataPoint] = Field(
        ...,
        min_length=10,
        max_length=100000,
        description="训练数据"
    )
    detector_type: DetectorType = Field(
        default=DetectorType.ISOLATION_FOREST,
        description="检测器类型"
    )
    contamination: float = Field(
        default=0.1,
        ge=0.01,
        le=0.5,
        description="预期异常比例"
    )
    model_name: str | None = Field(
        default=None,
        max_length=100,
        description="模型名称（用于保存和加载）"
    )


class NetDataFetchRequest(BaseModel):
    """
    NetData 数据获取请求

    用于从 NetData API 获取指标数据
    """

    chart: str = Field(
        ...,
        description="NetData 图表名称，如 system.cpu, system.ram"
    )
    after: int = Field(
        default=-60,
        description="起始时间（相对当前，负数表示之前多少秒）"
    )
    before: int = Field(
        default=0,
        description="结束时间（相对当前）"
    )
    points: int = Field(
        default=60,
        ge=1,
        le=1000,
        description="返回数据点数量"
    )
    host: str | None = Field(
        default=None,
        description="目标主机（覆盖默认）"
    )


# ============================================================
# 响应模型
# ============================================================
class AnomalyResult(BaseModel):
    """
    单个异常检测结果
    """

    index: int = Field(..., description="数据点索引")
    is_anomaly: bool = Field(..., description="是否异常")
    anomaly_score: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="异常分数（0-1，越大越异常）"
    )
    level: AnomalyLevel = Field(..., description="异常等级")
    metric_name: str = Field(..., description="指标名称")
    value: float = Field(..., description="原始值")
    timestamp: datetime = Field(..., description="时间戳")


class BatchDetectionResponse(BaseModel):
    """
    批量检测响应
    """

    status: DetectionStatus = Field(..., description="检测状态")
    detector_type: DetectorType = Field(..., description="使用的检测器")
    threshold: float = Field(..., description="使用的阈值")
    total_count: int = Field(..., ge=0, description="总数据点数")
    anomaly_count: int = Field(..., ge=0, description="异常数据点数")
    processing_time_ms: float = Field(..., ge=0, description="处理耗时（毫秒）")
    results: list[AnomalyResult] = Field(
        default_factory=list,
        description="检测结果列表"
    )
    message: str | None = Field(default=None, description="附加信息")


class StreamDetectionResponse(BaseModel):
    """
    流式检测响应
    """

    is_anomaly: bool = Field(..., description="是否异常")
    anomaly_score: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="异常分数"
    )
    level: AnomalyLevel = Field(..., description="异常等级")
    detector_type: DetectorType = Field(..., description="检测器类型")
    processing_time_ms: float = Field(..., ge=0, description="处理耗时（毫秒）")


class TrainDetectorResponse(BaseModel):
    """
    训练检测器响应
    """

    status: str = Field(..., description="训练状态")
    detector_type: DetectorType = Field(..., description="检测器类型")
    model_name: str = Field(..., description="模型名称")
    training_samples: int = Field(..., description="训练样本数")
    training_time_ms: float = Field(..., description="训练耗时（毫秒）")
    message: str | None = Field(default=None, description="附加信息")


class HealthResponse(BaseModel):
    """
    健康检查响应
    """

    status: str = Field(..., description="服务状态")
    version: str = Field(..., description="服务版本")
    detectors_loaded: list[str] = Field(
        default_factory=list,
        description="已加载的检测器"
    )
    uptime_seconds: float = Field(..., description="运行时间（秒）")


class ErrorResponse(BaseModel):
    """
    错误响应
    """

    error: str = Field(..., description="错误类型")
    message: str = Field(..., description="错误详情")
    detail: dict[str, Any] | None = Field(
        default=None,
        description="额外错误信息"
    )


# ============================================================
# 内部数据模型
# ============================================================
class DetectionContext(BaseModel):
    """
    检测上下文

    用于在检测过程中传递信息
    """

    model_config = ConfigDict(arbitrary_types_allowed=True)

    detector_type: DetectorType
    threshold: float
    timestamp: datetime = Field(default_factory=datetime.now)
    metadata: dict[str, Any] = Field(default_factory=dict)
