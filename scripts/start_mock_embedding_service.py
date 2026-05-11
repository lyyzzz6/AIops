#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
============================================================
Mock Embedding Service (用于测试)
============================================================

用途：
    - 提供mock向量化服务（不依赖真实模型）
    - 兼容OpenAI Embeddings API格式
    - 返回随机向量用于测试RAG流程

技术规格：
    - 向量维度：1024（与BGE-M3一致）
    - 框架：FastAPI
    - 端口：8002

使用方法：
    pip install fastapi uvicorn
    python scripts/start_mock_embedding_service.py

作者：刘一舟
更新时间：2026-05-10
============================================================
"""

import random
import time

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# ============================================================
# 全局配置
# ============================================================
EMBEDDING_DIMENSION = 1024
PORT = 8002
HOST = "0.0.0.0"

# ============================================================
# FastAPI应用
# ============================================================
app = FastAPI(
    title="Mock Embedding Service",
    description="提供mock向量化服务，用于测试RAG流程",
    version="1.0.0"
)

# 请求模型
class EmbeddingRequest(BaseModel):
    input: list
    model: str = "bge-m3"
    encoding_format: str = "float"

# 响应模型
class EmbeddingData(BaseModel):
    object: str = "embedding"
    embedding: list
    index: int

class EmbeddingResponse(BaseModel):
    object: str = "list"
    data: list
    model: str = "bge-m3"
    usage: dict

# 缓存：相同文本返回相同向量（基于hash）
vector_cache = {}

def get_vector(text: str) -> list:
    """生成文本的向量（基于hash确保相同文本返回相同向量）"""
    if text in vector_cache:
        return vector_cache[text]
    
    # 使用hash作为随机种子，确保相同文本生成相同向量
    seed = hash(text) % (2**31 - 1)
    random.seed(seed)
    vector = [random.uniform(-1, 1) for _ in range(EMBEDDING_DIMENSION)]
    vector_cache[text] = vector
    return vector

# ============================================================
# API端点
# ============================================================

@app.get("/health")
async def health_check():
    """健康检查"""
    return {"status": "healthy", "dimension": EMBEDDING_DIMENSION, "mode": "mock"}

@app.post("/v1/embeddings")
async def create_embeddings(request: EmbeddingRequest):
    """
    创建文本向量（mock版本）
    
    兼容OpenAI Embeddings API格式：
    POST /v1/embeddings
    {
        "input": ["文本1", "文本2"],
        "model": "bge-m3",
        "encoding_format": "float"
    }
    
    返回：
    {
        "object": "list",
        "data": [
            {"object": "embedding", "embedding": [0.1, 0.2, ...], "index": 0},
            ...
        ],
        "model": "bge-m3",
        "usage": {"prompt_tokens": N, "total_tokens": N}
    }
    """
    try:
        texts = request.input
        if not texts or len(texts) == 0:
            raise HTTPException(status_code=400, detail="input不能为空")
        
        # 计算token数量（简单估算）
        prompt_tokens = sum(len(text) for text in texts)
        
        # 生成mock向量
        start_time = time.time()
        embeddings = [get_vector(str(text)) for text in texts]
        inference_time = time.time() - start_time
        
        # 构建响应
        data = []
        for i, embedding in enumerate(embeddings):
            data.append(EmbeddingData(
                object="embedding",
                embedding=embedding,
                index=i
            ))
        
        response = EmbeddingResponse(
            data=data,
            model="bge-m3",
            usage={
                "prompt_tokens": prompt_tokens,
                "total_tokens": prompt_tokens
            }
        )
        
        print(f"[INFO] Mock向量化完成: {len(texts)} 条, 耗时 {inference_time:.2f}s")
        return response
        
    except Exception as e:
        print(f"[ERROR] 向量化失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ============================================================
# 主函数
# ============================================================
if __name__ == "__main__":
    print(f"[INFO] 启动Mock向量化服务，端口: {PORT}")
    print(f"[INFO] 向量维度: {EMBEDDING_DIMENSION}")
    print(f"[INFO] 模式: Mock (随机向量，相同文本返回相同向量)")
    print(f"[INFO] API: http://{HOST}:{PORT}/v1/embeddings")
    print("=" * 60)
    
    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        log_level="info"
    )
