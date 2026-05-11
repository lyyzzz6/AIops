#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 测试脚本
"""

import json
import requests
import time

# 配置
BACKEND_URL = "http://localhost:8080/api/v1"

def test_health():
    """测试健康检查接口"""
    try:
        response = requests.get(f"{BACKEND_URL}/health", timeout=10)
        if response.status_code == 200:
            print("[✓] 后端服务正常运行")
            return True
        else:
            print(f"[✗] 健康检查失败: {response.status_code}")
            return False
    except Exception as e:
        print(f"[✗] 无法连接后端服务: {e}")
        return False

def test_milvus_connection():
    """测试Milvus向量数据库连接"""
    try:
        # 检查Milvus是否可访问
        response = requests.get("http://localhost:9091/healthz", timeout=10)
        if response.status_code == 200:
            print("[✓] Milvus向量数据库正常运行")
            return True
        else:
            print(f"[✗] Milvus健康检查失败: {response.status_code}")
            return False
    except Exception as e:
        print(f"[✗] 无法连接Milvus: {e}")
        return False

def test_embedding_service():
    """测试向量化服务"""
    try:
        response = requests.post(
            "http://localhost:8002/v1/embeddings",
            json={"input": ["测试文本"], "model": "bge-m3"},
            timeout=30
        )
        if response.status_code == 200:
            result = response.json()
            embedding = result.get("data", [{}])[0].get("embedding", [])
            if len(embedding) == 1024:
                print("[✓] Embedding服务正常，向量维度正确(1024维)")
                return True
            else:
                print(f"[✗] 向量维度不正确: {len(embedding)}")
                return False
        else:
            print(f"[✗] Embedding服务失败: {response.status_code}")
            return False
    except Exception as e:
        print(f"[✗] 无法连接Embedding服务: {e}")
        return False

def test_rag_retrieval():
    """测试RAG检索功能"""
    test_queries = [
        "Linux系统性能监控工具有哪些？",
        "如何排查CPU使用率过高的问题？",
        "Nginx 502错误怎么解决？"
    ]
    
    print("\n=== RAG检索测试 ===")
    
    for query in test_queries:
        print(f"\n查询: {query}")
        try:
            start_time = time.time()
            response = requests.post(
                f"{BACKEND_URL}/chat",
                json={"query": query},
                timeout=60
            )
            elapsed = (time.time() - start_time) * 1000
            
            if response.status_code == 200:
                result = response.json()
                answer = result.get("answer", "")
                sources = result.get("sources", [])
                
                print(f"响应时间: {elapsed:.2f}ms")
                print(f"答案长度: {len(answer)} 字符")
                print(f"引用来源: {len(sources)} 个")
                
                if len(answer) > 0:
                    print(f"答案预览: {answer[:200]}...")
                else:
                    print("[!] 答案为空")
            else:
                print(f"[✗] 检索失败: {response.status_code}")
                
        except Exception as e:
            print(f"[✗] 检索异常: {e}")

def main():
    print("=" * 60)
    print("          RAG 检索增强系统测试")
    print("=" * 60)
    
    # 检查依赖服务
    print("\n=== 服务健康检查 ===")
    services = [
        ("后端服务", test_health),
        ("Milvus向量数据库", test_milvus_connection),
        ("Embedding服务", test_embedding_service)
    ]
    
    all_ready = True
    for name, test_func in services:
        if not test_func():
            all_ready = False
    
    if not all_ready:
        print("\n[!] 部分服务未就绪，请检查服务状态")
        return
    
    # 执行RAG测试
    test_rag_retrieval()
    
    print("\n" + "=" * 60)
    print("          测试完成")
    print("=" * 60)

if __name__ == "__main__":
    main()
