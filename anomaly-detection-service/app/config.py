"""
============================================================
配置管理模块
============================================================

用途：
    - 集中管理应用配置
    - 支持环境变量覆盖
    - 类型安全的配置访问

设计原则：
    - 使用 Pydantic Settings 进行类型验证
    - 敏感信息从环境变量读取
    - 默认值适合开发环境

作者：刘一舟
更新时间：2026-04-04
============================================================
"""

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    应用配置类

    配置加载优先级：
    1. 环境变量（最高优先级）
    2. .env 文件
    3. 默认值（最低优先级）

    使用方法：
        settings = Settings()
        print(settings.app_name)
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ============================================================
    # 应用基础配置
    # ============================================================
    app_name: str = "NetData Anomaly Detection Service"
    app_version: str = "1.0.0"
    debug: bool = False
    environment: Literal["development", "staging", "production"] = "development"

    # ============================================================
    # 服务配置
    # ============================================================
    host: str = "0.0.0.0"
    port: int = 8001
    workers: int = 1

    # ============================================================
    # NetData API 配置
    # ============================================================
    # NetData 是实时监控系统，提供丰富的指标数据
    # API 文档：https://learn.netdata.cloud/api
    netdata_host: str = Field(default="localhost", description="NetData 服务器地址")
    netdata_port: int = Field(default=19999, description="NetData API 端口")
    netdata_timeout: float = Field(default=10.0, description="NetData API 超时时间（秒）")

    @property
    def netdata_url(self) -> str:
        """NetData API 基础 URL"""
        return f"http://{self.netdata_host}:{self.netdata_port}"

    # ============================================================
    # 数据库配置（用于存储检测结果）
    # ============================================================
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "ops_user"
    mysql_password: str = "ops123456"
    mysql_database: str = "netdata_ops"

    @property
    def mysql_url(self) -> str:
        """MySQL 连接 URL"""
        return f"mysql+pymysql://{self.mysql_user}:{self.mysql_password}@{self.mysql_host}:{self.mysql_port}/{self.mysql_database}"

    # ============================================================
    # Redis 配置（用于缓存和分布式锁）
    # ============================================================
    redis_host: str = "localhost"
    redis_port: int = 6379
    redis_password: str = "redis123456"
    redis_db: int = 0

    @property
    def redis_url(self) -> str:
        """Redis 连接 URL"""
        return f"redis://:{self.redis_password}@{self.redis_host}:{self.redis_port}/{self.redis_db}"

    # ============================================================
    # 异常检测算法配置
    # ============================================================
    # 默认检测器类型
    default_detector: str = Field(
        default="isolation_forest",
        description="默认检测器类型"
    )

    # Isolation Forest 参数
    # n_estimators: 树的数量，越多越稳定但越慢
    # contamination: 异常比例估计，影响阈值
    iforest_n_estimators: int = Field(default=100, ge=10, le=1000)
    iforest_contamination: float = Field(default=0.1, ge=0.01, le=0.5)

    # LOF 参数
    # n_neighbors: 邻居数量，影响局部密度计算
    lof_n_neighbors: int = Field(default=20, ge=5, le=100)
    lof_contamination: float = Field(default=0.1, ge=0.01, le=0.5)

    # PySAD 在线检测参数
    # window_size: 滑动窗口大小
    online_window_size: int = Field(default=100, ge=10, le=10000)

    # ============================================================
    # 检测阈值配置
    # ============================================================
    # 异常分数阈值（0-1，越大越异常）
    anomaly_threshold: float = Field(default=0.7, ge=0.0, le=1.0)

    # 告警阈值（触发告警的异常分数）
    alert_threshold: float = Field(default=0.85, ge=0.0, le=1.0)

    # ============================================================
    # 性能配置
    # ============================================================
    # 批量检测最大数量
    max_batch_size: int = Field(default=10000, ge=1, le=100000)

    # 结果缓存 TTL（秒）
    cache_ttl: int = Field(default=300, ge=0)

    # ============================================================
    # 日志配置
    # ============================================================
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    log_file: str | None = None
    log_rotation: str = "10 MB"
    log_retention: str = "7 days"

    # ============================================================
    # 验证器
    # ============================================================
    @field_validator("port", "mysql_port", "redis_port", "netdata_port")
    @classmethod
    def validate_port(cls, v: int) -> int:
        """验证端口号范围"""
        if not 1 <= v <= 65535:
            raise ValueError(f"端口号必须在 1-65535 范围内，当前值: {v}")
        return v


@lru_cache()
def get_settings() -> Settings:
    """
    获取配置单例

    使用 lru_cache 确保配置只加载一次
    后续调用直接返回缓存的实例

    Returns:
        Settings: 配置实例
    """
    return Settings()


# 导出配置实例，方便直接使用
settings = get_settings()
