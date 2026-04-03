#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
============================================================
Milvus 向量数据库初始化脚本
============================================================

用途：
    - 创建 Collection（向量集合）
    - 配置索引（IVF_FLAT，平衡性能和准确率）
    - 插入测试数据验证

技术规格：
    - Milvus 版本：2.4
    - Embedding 模型：BGE-M3
    - 向量维度：1024（创建后不可更改！）
    - 相似度度量：COSINE（余弦相似度）

使用方法：
    pip install pymilvus
    python scripts/init_milvus.py --host localhost --port 19530

注意事项：
    1. 向量维度必须与 Embedding 模型匹配
    2. Collection 创建后维度不可更改
    3. 建议先在小数据集测试，验证后再导入生产数据

作者：刘一舟
更新时间：2026-04-03
============================================================
"""

import argparse
import logging
import sys
import time
from dataclasses import dataclass
from typing import List, Optional

try:
    from pymilvus import (
        connections,
        Collection,
        FieldSchema,
        CollectionSchema,
        DataType,
        utility,
        AnnSearchRequest,
        RRFReranker,
    )
except ImportError:
    print("错误：未安装 pymilvus")
    print("请执行: pip install pymilvus>=2.4.0")
    sys.exit(1)

# ============================================================
# 日志配置
# ============================================================
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

# ============================================================
# Collection 配置
# ============================================================
# 为什么维度是 1024？
# BGE-M3 模型输出的向量维度固定为 1024
# Milvus Collection 创建后维度不可更改
# 所以必须确保 Embedding 模型不变更
# ============================================================

@dataclass
class CollectionConfig:
    """Collection 配置数据类"""
    name: str = "ops_knowledge_base"
    description: str = "智能运维知识库向量集合"

    # 向量维度（BGE-M3 固定 1024 维）
    # 创建后不可更改，务必确认！
    vector_dimension: int = 1024

    # 相似度度量类型
    # COSINE: 余弦相似度，适合文本语义检索
    # L2: 欧氏距离，适合图像特征
    # IP: 内积，适合归一化向量
    metric_type: str = "COSINE"

    # 索引参数
    # IVF_FLAT: 平衡性能和准确率
    # nlist: 聚类中心数量，影响检索精度和速度
    index_type: str = "IVF_FLAT"
    nlist: int = 128

    # 搜索参数
    # nprobe: 搜索的聚类数量，越大越准确但越慢
    nprobe: int = 16

    # 分片数量
    # 用于分布式部署，单机模式设为 1
    shard_number: int = 1


def create_connection(host: str, port: int, alias: str = "default") -> bool:
    """
    建立 Milvus 连接

    Args:
        host: Milvus 服务器地址
        port: Milvus gRPC 端口
        alias: 连接别名

    Returns:
        bool: 连接是否成功
    """
    try:
        logger.info(f"正在连接 Milvus: {host}:{port}")
        connections.connect(
            alias=alias,
            host=host,
            port=port,
            timeout=10
        )
        logger.info("Milvus 连接成功")
        return True
    except Exception as e:
        logger.error(f"Milvus 连接失败: {e}")
        return False


def create_collection(config: CollectionConfig, drop_if_exists: bool = False) -> Optional[Collection]:
    """
    创建 Milvus Collection

    Collection 结构设计：
    ┌─────────────────┬──────────────┬─────────────────────────────────┐
    │ 字段名           │ 类型          │ 说明                             │
    ├─────────────────┼──────────────┼─────────────────────────────────┤
    │ id              │ INT64        │ 主键，自增                        │
    │ content         │ VARCHAR      │ 文档内容（最长 8000 字符）          │
    │ embedding       │ FLOAT_VECTOR │ 向量（1024 维）                   │
    │ source          │ VARCHAR      │ 文档来源（URL 或文件名）            │
    │ title           │ VARCHAR      │ 文档标题                          │
    │ chunk_index     │ INT64        │ 片段索引（同一文档的第几段）         │
    │ created_at      │ INT64        │ 创建时间戳                         │
    └─────────────────┴──────────────┴─────────────────────────────────┘

    Args:
        config: Collection 配置
        drop_if_exists: 如果存在是否删除重建

    Returns:
        Collection: 创建的 Collection 对象，失败返回 None
    """
    try:
        # 检查是否已存在
        if utility.has_collection(config.name):
            if drop_if_exists:
                logger.warning(f"Collection '{config.name}' 已存在，正在删除...")
                utility.drop_collection(config.name)
            else:
                logger.info(f"Collection '{config.name}' 已存在")
                return Collection(config.name)

        logger.info(f"正在创建 Collection: {config.name}")

        # 定义字段 Schema
        fields = [
            # 主键字段：INT64，自增
            FieldSchema(
                name="id",
                dtype=DataType.INT64,
                is_primary=True,
                auto_id=True,
                description="主键 ID，自动生成"
            ),
            # 内容字段：VARCHAR，存储文档片段
            FieldSchema(
                name="content",
                dtype=DataType.VARCHAR,
                max_length=8000,
                description="文档内容片段"
            ),
            # 向量字段：FLOAT_VECTOR，1024 维
            # 这是最核心的字段，维度创建后不可更改！
            FieldSchema(
                name="embedding",
                dtype=DataType.FLOAT_VECTOR,
                dim=config.vector_dimension,
                description="文档向量（BGE-M3 1024 维）"
            ),
            # 来源字段：VARCHAR，记录文档来源
            FieldSchema(
                name="source",
                dtype=DataType.VARCHAR,
                max_length=512,
                description="文档来源（URL 或文件名）"
            ),
            # 标题字段：VARCHAR
            FieldSchema(
                name="title",
                dtype=DataType.VARCHAR,
                max_length=256,
                description="文档标题"
            ),
            # 片段索引：INT64，同一文档的第几段
            FieldSchema(
                name="chunk_index",
                dtype=DataType.INT64,
                description="片段索引"
            ),
            # 创建时间戳
            FieldSchema(
                name="created_at",
                dtype=DataType.INT64,
                description="创建时间戳"
            ),
        ]

        # 创建 Collection Schema
        schema = CollectionSchema(
            fields=fields,
            description=config.description,
            enable_dynamic_field=False  # 禁用动态字段，确保数据结构一致性
        )

        # 创建 Collection
        collection = Collection(
            name=config.name,
            schema=schema,
            shard_num=config.shard_number
        )

        logger.info(f"Collection '{config.name}' 创建成功")
        return collection

    except Exception as e:
        logger.error(f"创建 Collection 失败: {e}")
        return None


def create_index(collection: Collection, config: CollectionConfig) -> bool:
    """
    创建向量索引

    索引类型选择：
    ┌───────────────┬─────────────────────────────────────────────────────┐
    │ 索引类型       │ 适用场景                                             │
    ├───────────────┼─────────────────────────────────────────────────────┤
    │ FLAT          │ 精确搜索，数据量小（<10万），无精度损失                 │
    │ IVF_FLAT      │ 平衡方案，中等数据量，可调节精度                       │
    │ IVF_PQ        │ 内存优化，大数据量，有精度损失                         │
    │ HNSW          │ 高性能，内存占用大，适合实时搜索                       │
    │ GPU_IVF_FLAT  │ GPU 加速，适合超大规模                                │
    └───────────────┴─────────────────────────────────────────────────────┘

    我们选择 IVF_FLAT 的原因：
    1. 运维知识库数据量中等（预期 10-50 万条）
    2. 需要可调节的精度（通过 nprobe）
    3. 内存占用可控

    Args:
        collection: Collection 对象
        config: 索引配置

    Returns:
        bool: 是否创建成功
    """
    try:
        logger.info(f"正在创建索引: {config.index_type}")

        # 索引参数
        index_params = {
            "metric_type": config.metric_type,
            "index_type": config.index_type,
            "params": {"nlist": config.nlist}
        }

        # 创建索引
        collection.create_index(
            field_name="embedding",
            index_params=index_params,
            index_name="embedding_index"
        )

        logger.info("索引创建成功")
        return True

    except Exception as e:
        logger.error(f"创建索引失败: {e}")
        return False


def load_collection(collection: Collection) -> bool:
    """
    加载 Collection 到内存

    Milvus 的数据加载机制：
    - 创建索引后需要手动加载到内存
    - 加载后才能进行搜索
    - 内存占用 = 数据大小 + 索引大小

    Args:
        collection: Collection 对象

    Returns:
        bool: 是否加载成功
    """
    try:
        logger.info("正在加载 Collection 到内存...")
        collection.load()
        logger.info("Collection 加载成功")
        return True
    except Exception as e:
        logger.error(f"加载 Collection 失败: {e}")
        return False


def insert_test_data(collection: Collection, config: CollectionConfig, count: int = 10) -> bool:
    """
    插入测试数据验证 Collection 配置

    为什么需要测试数据？
    1. 验证 Collection 配置正确
    2. 测试搜索功能
    3. 评估索引性能

    Args:
        collection: Collection 对象
        config: Collection 配置
        count: 测试数据数量

    Returns:
        bool: 是否插入成功
    """
    try:
        import random
        import time

        logger.info(f"正在插入 {count} 条测试数据...")

        # 生成随机测试数据
        # 实际使用时用 BGE-M3 生成真实 embedding
        test_data = [
            {
                "content": f"这是第 {i} 条运维知识测试内容，用于验证向量数据库配置是否正确。",
                "embedding": [random.random() for _ in range(config.vector_dimension)],
                "source": f"test/source_{i}.md",
                "title": f"测试文档 {i}",
                "chunk_index": 0,
                "created_at": int(time.time())
            }
            for i in range(count)
        ]

        # 插入数据
        # 注意：id 字段是自增的，不需要提供
        collection.insert([
            [d["content"] for d in test_data],
            [d["embedding"] for d in test_data],
            [d["source"] for d in test_data],
            [d["title"] for d in test_data],
            [d["chunk_index"] for d in test_data],
            [d["created_at"] for d in test_data],
        ])

        # 刷新数据（确保数据持久化）
        collection.flush()

        logger.info(f"测试数据插入成功，共 {count} 条")
        return True

    except Exception as e:
        logger.error(f"插入测试数据失败: {e}")
        return False


def test_search(collection: Collection, config: CollectionConfig) -> bool:
    """
    测试向量搜索功能

    搜索流程：
    1. 生成查询向量（实际使用 BGE-M3）
    2. 设置搜索参数
    3. 执行搜索
    4. 返回 Top-K 结果

    Args:
        collection: Collection 对象
        config: Collection 配置

    Returns:
        bool: 搜索是否成功
    """
    try:
        import random

        logger.info("正在测试搜索功能...")

        # 生成随机查询向量
        query_vector = [random.random() for _ in range(config.vector_dimension)]

        # 搜索参数
        search_params = {
            "metric_type": config.metric_type,
            "params": {"nprobe": config.nprobe}
        }

        # 执行搜索
        results = collection.search(
            data=[query_vector],
            anns_field="embedding",
            param=search_params,
            limit=5,
            output_fields=["content", "source", "title"]
        )

        # 打印结果
        logger.info("搜索结果:")
        for hits in results:
            for hit in hits:
                logger.info(f"  - ID: {hit.id}, 距离: {hit.distance:.4f}")
                logger.info(f"    内容: {hit.entity.get('content')[:50]}...")

        logger.info("搜索测试成功")
        return True

    except Exception as e:
        logger.error(f"搜索测试失败: {e}")
        return False


def get_collection_stats(collection: Collection) -> dict:
    """
    获取 Collection 统计信息

    Args:
        collection: Collection 对象

    Returns:
        dict: 统计信息
    """
    try:
        stats = collection.num_entities
        return {
            "name": collection.name,
            "num_entities": stats,
            "schema": str(collection.schema)
        }
    except Exception as e:
        logger.error(f"获取统计信息失败: {e}")
        return {}


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description="Milvus 向量数据库初始化脚本")
    parser.add_argument("--host", default="localhost", help="Milvus 服务器地址")
    parser.add_argument("--port", type=int, default=19530, help="Milvus gRPC 端口")
    parser.add_argument("--drop", action="store_true", help="如果 Collection 存在则删除重建")
    parser.add_argument("--test-data", type=int, default=10, help="插入测试数据数量")
    parser.add_argument("--skip-test", action="store_true", help="跳过搜索测试")

    args = parser.parse_args()

    logger.info("=" * 60)
    logger.info("Milvus 向量数据库初始化")
    logger.info("=" * 60)

    # 创建配置
    config = CollectionConfig()

    # 1. 连接 Milvus
    if not create_connection(args.host, args.port):
        sys.exit(1)

    # 2. 创建 Collection
    collection = create_collection(config, drop_if_exists=args.drop)
    if collection is None:
        sys.exit(1)

    # 3. 创建索引
    if not create_index(collection, config):
        sys.exit(1)

    # 4. 加载到内存
    if not load_collection(collection):
        sys.exit(1)

    # 5. 插入测试数据
    if args.test_data > 0:
        if not insert_test_data(collection, config, args.test_data):
            sys.exit(1)

    # 6. 测试搜索
    if not args.skip_test:
        if not test_search(collection, config):
            sys.exit(1)

    # 7. 打印统计信息
    stats = get_collection_stats(collection)
    logger.info("=" * 60)
    logger.info("初始化完成")
    logger.info(f"Collection: {stats.get('name')}")
    logger.info(f"数据量: {stats.get('num_entities')}")
    logger.info("=" * 60)

    # 断开连接
    connections.disconnect("default")


if __name__ == "__main__":
    main()
