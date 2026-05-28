"""
异常检测路由
============================================================

用途：
    - 批量异常检测 API
    - 流式异常检测 API
    - 检测器训练 API

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time
from datetime import datetime

import numpy as np
from fastapi import APIRouter, Depends, HTTPException, status
from loguru import logger

from app.config import settings
from app.models import (
    AnomalyLevel,
    AnomalyResult,
    BatchDetectionRequest,
    BatchDetectionResponse,
    DetectionStatus,
    MetricDataPoint,
    NetDataFetchRequest,
    StreamDetectionRequest,
    StreamDetectionResponse,
    TrainDetectorRequest,
    TrainDetectorResponse,
)
from app.netdata.client import NetDataClient
from app.services.detection_service import DetectionService

router = APIRouter()

# 依赖注入：检测服务实例
def get_detection_service() -> DetectionService:
    """获取检测服务实例"""
    return DetectionService()


def get_netdata_client() -> NetDataClient:
    """获取 NetData 客户端实例"""
    return NetDataClient()


# ============================================================
# 批量检测接口
# ============================================================
@router.post(
    "/batch",
    response_model=BatchDetectionResponse,
    status_code=status.HTTP_200_OK,
    summary="批量异常检测",
    description="对一批时序数据进行异常检测，适用于离线分析场景",
)
async def batch_detect(
    request: BatchDetectionRequest,
    service: DetectionService = Depends(get_detection_service),
) -> BatchDetectionResponse:
    """
    批量异常检测接口

    适用场景：
    - 历史数据分析
    - 离线报表生成
    - 模型验证

    使用离线检测器：
    - isolation_forest: 隔离森林
    - lof: 局部异常因子
    - knn: K-近邻
    """
    start_time = time.time()

    try:
        logger.info(
            f"批量检测请求: {len(request.data)} 条数据, "
            f"检测器: {request.detector_type}"
        )

        # 转换数据为 numpy 数组
        values = np.array([dp.value for dp in request.data]).reshape(-1, 1)

        # 获取阈值
        threshold = request.threshold or settings.anomaly_threshold

        # 执行检测
        scores, is_anomaly = await service.detect_batch(
            data=values,
            detector_type=request.detector_type,
            threshold=threshold,
        )

        # 构建结果
        results = []
        anomaly_count = 0

        for i, (score, anomaly, data_point) in enumerate(
            zip(scores, is_anomaly, request.data)
        ):
            if anomaly:
                anomaly_count += 1

            # 判断异常等级
            if score >= settings.alert_threshold:
                level = AnomalyLevel.CRITICAL
            elif score >= threshold:
                level = AnomalyLevel.WARNING
            else:
                level = AnomalyLevel.NORMAL

            results.append(
                AnomalyResult(
                    index=i,
                    is_anomaly=anomaly,
                    anomaly_score=float(score),
                    level=level,
                    metric_name=data_point.metric_name,
                    value=data_point.value,
                    timestamp=data_point.timestamp or datetime.now(),
                )
            )

        processing_time = (time.time() - start_time) * 1000

        logger.info(
            f"批量检测完成: {anomaly_count}/{len(request.data)} 异常, "
            f"耗时: {processing_time:.2f}ms"
        )

        return BatchDetectionResponse(
            status=DetectionStatus.SUCCESS,
            detector_type=request.detector_type,
            threshold=threshold,
            total_count=len(request.data),
            anomaly_count=anomaly_count,
            processing_time_ms=processing_time,
            results=results if request.return_scores else [],
        )

    except Exception as e:
        logger.exception(f"批量检测失败: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"检测失败: {str(e)}",
        )


# ============================================================
# 流式检测接口
# ============================================================
@router.post(
    "/stream",
    response_model=StreamDetectionResponse,
    status_code=status.HTTP_200_OK,
    summary="流式异常检测",
    description="对单条数据进行实时异常检测，适用于实时监控场景",
)
async def stream_detect(
    request: StreamDetectionRequest,
    service: DetectionService = Depends(get_detection_service),
) -> StreamDetectionResponse:
    """
    流式异常检测接口

    适用场景：
    - 实时监控告警
    - 数据流处理
    - 在线学习

    使用在线检测器：
    - half_space_trees: 半空间树
    - xstream: xStream
    """
    start_time = time.time()

    try:
        # 获取阈值
        threshold = request.threshold or settings.anomaly_threshold

        # 执行流式检测
        score = await service.detect_stream(
            value=request.data_point.value,
            detector_type=request.detector_type,
        )

        is_anomaly = score >= threshold

        # 判断异常等级
        if score >= settings.alert_threshold:
            level = AnomalyLevel.CRITICAL
        elif score >= threshold:
            level = AnomalyLevel.WARNING
        else:
            level = AnomalyLevel.NORMAL

        processing_time = (time.time() - start_time) * 1000

        return StreamDetectionResponse(
            is_anomaly=is_anomaly,
            anomaly_score=float(score),
            level=level,
            detector_type=request.detector_type,
            processing_time_ms=processing_time,
        )

    except Exception as e:
        logger.exception(f"流式检测失败: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"检测失败: {str(e)}",
        )


# ============================================================
# 训练检测器接口
# ============================================================
@router.post(
    "/train",
    response_model=TrainDetectorResponse,
    status_code=status.HTTP_201_CREATED,
    summary="训练检测器",
    description="使用历史数据训练离线检测器",
)
async def train_detector(
    request: TrainDetectorRequest,
    service: DetectionService = Depends(get_detection_service),
) -> TrainDetectorResponse:
    """
    训练检测器接口

    用于：
    - 初始化离线检测器
    - 更新模型参数
    - 保存模型供后续使用
    """
    start_time = time.time()

    try:
        logger.info(
            f"训练检测器: {request.detector_type}, "
            f"样本数: {len(request.training_data)}"
        )

        # 转换数据
        values = np.array([dp.value for dp in request.training_data]).reshape(-1, 1)

        # 训练模型
        model_name = await service.train(
            data=values,
            detector_type=request.detector_type,
            contamination=request.contamination,
            model_name=request.model_name,
        )

        training_time = (time.time() - start_time) * 1000

        logger.info(f"训练完成: {model_name}, 耗时: {training_time:.2f}ms")

        return TrainDetectorResponse(
            status="success",
            detector_type=request.detector_type,
            model_name=model_name,
            training_samples=len(request.training_data),
            training_time_ms=training_time,
        )

    except Exception as e:
        logger.exception(f"训练失败: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"训练失败: {str(e)}",
        )


# ============================================================
# NetData 数据获取接口
# ============================================================
@router.post(
    "/netdata/fetch",
    response_model=BatchDetectionResponse,
    summary="从 NetData 获取数据并检测",
    description="直接从 NetData API 获取指标数据并进行异常检测",
)
async def fetch_and_detect(
    request: NetDataFetchRequest,
    service: DetectionService = Depends(get_detection_service),
    netdata: NetDataClient = Depends(get_netdata_client),
) -> BatchDetectionResponse:
    """
    从 NetData 获取数据并检测

    集成 NetData 监控系统，自动获取指标数据进行检测
    """
    start_time = time.time()

    try:
        # 从 NetData 获取数据
        data_points = await netdata.fetch_chart_data(
            chart=request.chart,
            after=request.after,
            before=request.before,
            points=request.points,
            host=request.host,
        )

        if not data_points:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="未获取到数据",
            )

        # 转换并检测
        values = np.array([dp.value for dp in data_points]).reshape(-1, 1)

        scores, is_anomaly = await service.detect_batch(
            data=values,
            detector_type=settings.default_detector,
            threshold=settings.anomaly_threshold,
        )

        # 构建结果
        results = []
        anomaly_count = 0

        for i, (score, anomaly, data_point) in enumerate(
            zip(scores, is_anomaly, data_points)
        ):
            if anomaly:
                anomaly_count += 1

            level = (
                AnomalyLevel.CRITICAL
                if score >= settings.alert_threshold
                else AnomalyLevel.WARNING
                if score >= settings.anomaly_threshold
                else AnomalyLevel.NORMAL
            )

            results.append(
                AnomalyResult(
                    index=i,
                    is_anomaly=anomaly,
                    anomaly_score=float(score),
                    level=level,
                    metric_name=data_point.metric_name,
                    value=data_point.value,
                    timestamp=data_point.timestamp or datetime.now(),
                )
            )

        processing_time = (time.time() - start_time) * 1000

        return BatchDetectionResponse(
            status=DetectionStatus.SUCCESS,
            detector_type=settings.default_detector,
            threshold=settings.anomaly_threshold,
            total_count=len(data_points),
            anomaly_count=anomaly_count,
            processing_time_ms=processing_time,
            results=results,
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.exception(f"NetData 数据获取或检测失败: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"操作失败: {str(e)}",
        )
