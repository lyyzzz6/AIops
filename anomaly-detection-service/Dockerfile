# ============================================================
# 智能运维异常检测服务 - Dockerfile
# ============================================================
#
# 构建方法：
#   docker build -t netdata-ops-anomaly:latest .
#
# 运行方法：
#   docker run -p 8001:8001 netdata-ops-anomaly:latest
#
# 作者：刘一舟
# 更新时间：2026-04-04
# ============================================================

# 使用 Python 3.11 slim 镜像
FROM python:3.11-slim as base

# 设置环境变量
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONFAULTHANDLER=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# 安装系统依赖
# build-essential: 编译 Python 包
# libgomp1: OpenMP 支持（numpy/scipy）
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libgomp1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 设置工作目录
WORKDIR /app

# ============================================================
# 构建阶段
# ============================================================
FROM base as builder

# 复制依赖文件
COPY requirements.txt .

# 创建虚拟环境
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 安装 Python 依赖
RUN pip install --upgrade pip && \
    pip install -r requirements.txt

# ============================================================
# 运行阶段
# ============================================================
FROM base as runner

# 复制虚拟环境
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# 创建非 root 用户
RUN useradd --create-home --shell /bin/bash appuser

# 设置工作目录
WORKDIR /app

# 复制应用代码
COPY --chown=appuser:appuser . .

# 创建必要目录
RUN mkdir -p /app/logs /app/models && \
    chown -R appuser:appuser /app

# 切换到非 root 用户
USER appuser

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8001/api/health || exit 1

# 暴露端口
EXPOSE 8001

# 启动命令
# 使用 gunicorn + uvicorn worker 生产环境部署
CMD ["gunicorn", "app.main:app", \
     "--workers", "2", \
     "--worker-class", "uvicorn.workers.UvicornWorker", \
     "--bind", "0.0.0.0:8001", \
     "--timeout", "120", \
     "--keep-alive", "5", \
     "--access-logfile", "-", \
     "--error-logfile", "-"]
