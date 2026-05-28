#!/usr/bin/env python3
"""
RAG 测试数据导入脚本
使用方法：python import_rag_test_data.py
"""

import json
import requests

# 后端 API 地址
BASE_URL = "http://localhost:8080/api/v1"

# 测试数据文件路径
TEST_DATA_FILE = "rag_test_documents.json"


def login():
    """模拟登录获取 token"""
    print("正在尝试登录...")
    # 默认管理员账号（根据实际配置调整）
    login_data = {
        "username": "admin",
        "password": "admin123"
    }
    try:
        response = requests.post(f"{BASE_URL}/auth/login", json=login_data)
        if response.status_code == 200:
            result = response.json()
            token = result.get("data", {}).get("token", "")
            print("登录成功！")
            return token
        else:
            print(f"登录失败: {response.status_code} - {response.text}")
            print("尝试无需认证直接导入...")
            return None
    except Exception as e:
        print(f"登录异常: {e}")
        return None


def import_document(token, doc):
    """导入单个文档"""
    headers = {
        "Content-Type": "application/json"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    
    try:
        response = requests.post(
            f"{BASE_URL}/knowledge/documents",
            json=doc,
            headers=headers
        )
        if response.status_code == 200:
            print(f"✓ 成功导入: {doc['title']}")
            return True
        else:
            print(f"✗ 导入失败 [{doc['title']}]: {response.status_code} - {response.text}")
            return False
    except Exception as e:
        print(f"✗ 导入异常 [{doc['title']}]: {e}")
        return False


def test_retrieval(token, query, top_k=3):
    """测试检索功能"""
    headers = {
        "Content-Type": "application/json"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    
    print(f"\n--- 测试检索: '{query}' ---")
    try:
        response = requests.post(
            f"{BASE_URL}/knowledge/retrieve",
            json={"query": query, "topK": top_k},
            headers=headers
        )
        if response.status_code == 200:
            result = response.json()
            docs = result.get("data", [])
            print(f"找到 {len(docs)} 条相关文档:")
            for i, doc in enumerate(docs, 1):
                print(f"  {i}. 得分: {doc.get('score', 0):.4f} - {doc.get('title', 'N/A')}")
            return True
        else:
            print(f"检索失败: {response.status_code} - {response.text}")
            return False
    except Exception as e:
        print(f"检索异常: {e}")
        return False


def main():
    print("=" * 60)
    print("RAG 测试数据导入工具")
    print("=" * 60)
    
    # 加载测试数据
    try:
        with open(TEST_DATA_FILE, "r", encoding="utf-8") as f:
            documents = json.load(f)
        print(f"已加载 {len(documents)} 个测试文档")
    except FileNotFoundError:
        print(f"错误: 找不到测试数据文件 {TEST_DATA_FILE}")
        return
    except json.JSONDecodeError:
        print(f"错误: 测试数据文件格式不正确")
        return
    
    # 尝试登录
    token = login()
    
    # 导入文档
    print(f"\n开始导入 {len(documents)} 个文档...")
    success_count = 0
    for doc in documents:
        if import_document(token, doc):
            success_count += 1
    
    print(f"\n导入完成: {success_count}/{len(documents)} 个文档成功")
    
    # 测试检索
    if success_count > 0:
        print("\n" + "=" * 60)
        print("测试 RAG 检索功能")
        print("=" * 60)
        
        test_queries = [
            "Linux 系统性能监控工具有哪些？",
            "如何排查 CPU 使用率过高？",
            "Nginx 502 错误怎么解决？",
            "MySQL 慢查询优化",
            "Docker 容器资源限制",
        ]
        
        for query in test_queries:
            test_retrieval(token, query)
    
    print("\n" + "=" * 60)
    print("测试数据导入完成！")
    print("=" * 60)
    print("\n你现在可以:")
    print("1. 访问前端 http://localhost:3000/ 使用知识库")
    print("2. 通过对话功能测试 RAG 问答")
    print("3. 访问 http://localhost:8080/swagger-ui.html 测试 API")


if __name__ == "__main__":
    main()
