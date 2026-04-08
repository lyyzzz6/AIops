"""
============================================================
FastAPI 应用入口
============================================================

用途：
    - 创建 FastAPI 应用实例
    - 配置中间件
    - 注册路由
    - 配置异常处理

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

import time
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from loguru import logger

from app.config import settings
from app.api.routes import detection, health

# ============================================================
# 生命周期管理
# ============================================================
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """
    应用生命周期管理

    startup: 应用启动时执行
    shutdown: 应用关闭时执行
    """
    # === 启动阶段 ===
    startup_time = time.time()
    logger.info(f"正在启动 {settings.app_name} v{settings.app_version}")
    logger.info(f"环境: {settings.environment}")
    logger.info(f"调试模式: {settings.debug}")

    # 配置日志
    logger.add(
        sink=settings.log_file or "logs/app.log",
        rotation=settings.log_rotation,
        retention=settings.log_retention,
        level=settings.log_level,
        encoding="utf-8",
    )

    # 预加载默认检测器
    logger.info("预加载默认检测器...")

    app.state.startup_time = startup_time

    logger.info("服务启动完成")

    yield  # 应用运行中

    # === 关闭阶段 ===
    logger.info("正在关闭服务...")

    # 保存模型状态
    # TODO: 实现模型持久化

    logger.info("服务已关闭")


# ============================================================
# 创建 FastAPI 应用
# ============================================================
app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
    description="""
## 智能运维异常检测服务

基于 PyOD 和 PySAD 的实时异常检测微服务。

### 功能特性
- **批量检测**: 使用离线算法（Isolation Forest, LOF, KNN）
- **流式检测**: 使用在线算法（Half-Space Trees, xStream）
- **NetData 集成**: 直接从 NetData API 获取指标数据

### 检测器类型
| 类型 | 算法 | 适用场景 |
|------|------|----------|
| `isolation_forest` | 隔离森林 | 高维数据，快速检测 |
| `lof` | 局部异常因子 | 密度不均数据 |
| `knn` | K-近邻 | 低维数据 |
| `half_space_trees` | 半空间树 | 实时流式检测 |
| `xstream` | xStream | 高维流式数据 |
    """,
    openapi_url="/api/openapi.json",
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    lifespan=lifespan,
)

# ============================================================
# 中间件配置
# ============================================================
# CORS 中间件
# 允许前端跨域访问
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 生产环境应限制来源
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# 请求日志中间件
@app.middleware("http")
async def log_requests(request: Request, call_next):
    """记录请求日志和耗时"""
    start_time = time.time()

    # 记录请求
    logger.debug(f"请求: {request.method} {request.url.path}")

    # 执行请求
    response = await call_next(request)

    # 计算耗时
    process_time = (time.time() - start_time) * 1000

    # 添加响应头
    response.headers["X-Process-Time-Ms"] = f"{process_time:.2f}"

    # 记录响应
    logger.debug(f"响应: {request.method} {request.url.path} - {response.status_code} - {process_time:.2f}ms")

    return response


# ============================================================
# 异常处理
# ============================================================
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """全局异常处理"""
    logger.exception(f"未处理的异常: {exc}")

    return JSONResponse(
        status_code=500,
        content={
            "error": "InternalServerError",
            "message": "服务器内部错误",
            "detail": str(exc) if settings.debug else None,
        },
    )


@app.exception_handler(ValueError)
async def value_error_handler(request: Request, exc: ValueError):
    """参数错误处理"""
    logger.warning(f"参数错误: {exc}")

    return JSONResponse(
        status_code=400,
        content={
            "error": "ValidationError",
            "message": str(exc),
        },
    )


# ============================================================
# 注册路由
# ============================================================
app.include_router(
    health.router,
    prefix="/api",
    tags=["健康检查"],
)

app.include_router(
    detection.router,
    prefix="/api/v1/detection",
    tags=["异常检测"],
)


# ============================================================
# 根路径
# ============================================================
@app.get("/", tags=["根路径"])
async def root():
    """根路径，返回服务信息"""
    return {
        "name": settings.app_name,
        "version": settings.app_version,
        "docs": "/api/docs",
        "health": "/api/health",
    }


# ============================================================
# 应用入口
# ============================================================
if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
        log_level=settings.log_level.lower(),
    )
