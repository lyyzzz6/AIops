#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
============================================================
BGE-M3 向量化服务
============================================================

用途：
    - 提供文本向量化服务
    - 兼容 OpenAI Embeddings API 格式
    - 支持批量处理

技术规格：
    - 模型：BGE-M3 (bge-m3)
    - 向量维度：1024
    - 框架：FastAPI
    - 端口：8002

使用方法：
    pip install fastapi uvicorn sentence-transformers
    python scripts/start_embedding_service.py

作者：刘一舟
更新时间：2026-05-10
============================================================
"""

import json
import time
from typing import List, Dict, Any

import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

# ============================================================
# 全局配置
# ============================================================
MODEL_NAME = "BAAI/bge-m3"
EMBEDDING_DIMENSION = 1024
PORT = 8002
HOST = "0.0.0.0"

# ============================================================
# 初始化模型
# ============================================================
print(f"[INFO] 正在加载 BGE-M3 模型...")
start_time = time.time()
try:
    model = SentenceTransformer(MODEL_NAME)
    load_time = time.time() - start_time
    print(f"[INFO] BGE-M3 模型加载完成，耗时 {load_time:.2f}s")
except Exception as e:
    print(f"[ERROR] 加载模型失败: {e}")
    exit(1)

# ============================================================
# FastAPI 应用
# ============================================================
app = FastAPI(
    title="BGE-M3 Embedding Service",
    description="提供文本向量化服务，兼容 OpenAI Embeddings API 格式",
    version="1.0.0"
)

# 请求模型
class EmbeddingRequest(BaseModel):
    input: List[str]
    model: str = "bge-m3"
    encoding_format: str = "float"

# 响应模型
class EmbeddingData(BaseModel):
    object: str = "embedding"
    embedding: List[float]
    index: int

class EmbeddingResponse(BaseModel):
    object: str = "list"
    data: List[EmbeddingData]
    model: str = "bge-m3"
    usage: Dict[str, int]

# ============================================================
# API 端点
# ============================================================

@app.get("/health")
async def health_check():
    """健康检查"""
    return {"status": "healthy", "model": MODEL_NAME, "dimension": EMBEDDING_DIMENSION}

@app.post("/v1/embeddings")
async def create_embeddings(request: EmbeddingRequest):
    """
    创建文本向量
    
    兼容 OpenAI Embeddings API 格式：
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
            raise HTTPException(status_code=400, detail="input 不能为空")
        
        # 计算 token 数量（简单估算）
        prompt_tokens = sum(len(text) for text in texts)
        
        # 生成向量
        start_time = time.time()
        embeddings = model.encode(texts, convert_to_numpy=True).tolist()
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
        
        print(f"[INFO] 向量化完成: {len(texts)} 条, 耗时 {inference_time:.2f}s")
        return response
        
    except Exception as e:
        print(f"[ERROR] 向量化失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ============================================================
# 主函数
# ============================================================
if __name__ == "__main__":
    print(f"[INFO] 启动向量化服务，端口: {PORT}")
    print(f"[INFO] 模型: {MODEL_NAME}")
    print(f"[INFO] 向量维度: {EMBEDDING_DIMENSION}")
    print(f"[INFO] API: http://{HOST}:{PORT}/v1/embeddings")
    print("=" * 60)
    
    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        log_level="info"
    )
