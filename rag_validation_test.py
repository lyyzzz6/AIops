#!/usr/bin/env python3
"""
RAG 召回率验证测试脚本

这个脚本专门用于验证 RAG 系统的召回效果：
1. 使用专有知识数据（LLM 不可能知道的）
2. 验证检索的准确性
3. 计算召回率、准确率等指标
"""

import json
import time
import requests
from typing import Dict, List, Any, Tuple


# ============================================================
# 配置
# ============================================================

BASE_URL = "http://localhost:8080/api/v1"
TEST_DATA_FILE = "rag_validation_test_data.json"

# 测试用例：每个测试用例包含查询和预期应该检索到的文档
TEST_CASES = [
    {
        "query": "蓝晶星云的默认管理员账号是什么？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["关于蓝晶星云系统配置指南"],
        "keywords_in_response": ["nebula_admin", "BlueCrystal2024!@#"],
        "description": "测试专有名词1：管理员账号"
    },
    {
        "query": "紫电清霜的配置文件在哪里？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["紫电清霜缓存策略详解"],
        "keywords_in_response": ["/etc/purple-lightning/purple.conf", "/var/log/purple-lightning/"],
        "description": "测试专有名词2：配置文件路径"
    },
    {
        "query": "赤焰金乌的告警级别有哪些？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["赤焰金乌日志分析工具使用说明"],
        "keywords_in_response": ["INFO", "WARNING", "ERROR", "CRITICAL"],
        "description": "测试专有名词3：告警级别"
    },
    {
        "query": "碧海潮生的回滚命令是什么？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["碧海潮生数据库迁移手册"],
        "keywords_in_response": ["bst rollback", "--snapshot"],
        "description": "测试专有名词4：回滚命令"
    },
    {
        "query": "金风玉露的仪表盘类型有哪些？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["金风玉露监控面板配置方法"],
        "keywords_in_response": ["system_overview", "database_health", "network_traffic"],
        "description": "测试专有名词5：仪表盘类型"
    },
    {
        "query": "银河倒悬的默认端口是多少？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["银河倒悬服务注册中心说明"],
        "keywords_in_response": ["8500", "8502", "8600"],
        "description": "测试专有名词6：端口号"
    },
    {
        "query": "冰壶秋月支持哪些配置格式？",
        "expected_sources": ["内部文档"],
        "expected_titles": ["冰壶秋月配置中心使用指南"],
        "keywords_in_response": ["YAML", "JSON", "Properties", "TOML"],
        "description": "测试专有名词7：配置格式"
    },
]


# ============================================================
# 辅助函数
# ============================================================

def login() -> str:
    """登录获取 token"""
    print("=" * 70)
    print("1. 登录系统")
    print("=" * 70)
    
    login_data = {
        "username": "admin",
        "password": "admin123"
    }
    try:
        response = requests.post(f"{BASE_URL}/auth/login", json=login_data, timeout=10)
        if response.status_code == 200:
            result = response.json()
            token = result.get("data", {}).get("token", "")
            print("✓ 登录成功")
            return token
        else:
            print(f"⚠ 登录失败: {response.status_code}，将尝试无需认证访问")
            return ""
    except Exception as e:
        print(f"⚠ 登录异常: {e}，将尝试无需认证访问")
        return ""


def get_headers(token: str) -> Dict[str, str]:
    """获取请求头"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def import_test_documents(token: str) -> Tuple[int, int]:
    """导入测试文档"""
    print("\n" + "=" * 70)
    print("2. 导入测试文档")
    print("=" * 70)
    
    try:
        with open(TEST_DATA_FILE, "r", encoding="utf-8") as f:
            documents = json.load(f)
        print(f"读取到 {len(documents)} 个测试文档")
    except Exception as e:
        print(f"✗ 读取测试数据失败: {e}")
        return 0, 0
    
    success_count = 0
    for doc in documents:
        try:
            print(f"\n正在导入: {doc['title']}")
            response = requests.post(
                f"{BASE_URL}/knowledge/documents",
                json=doc,
                headers=get_headers(token),
                timeout=30
            )
            if response.status_code == 200:
                print(f"✓ 成功导入: {doc['title']}")
                success_count += 1
            else:
                print(f"✗ 导入失败: {response.status_code} - {response.text}")
        except Exception as e:
            print(f"✗ 导入异常: {e}")
        
        time.sleep(0.5)
    
    print(f"\n文档导入完成: {success_count}/{len(documents)}")
    return len(documents), success_count


def wait_for_indexing(seconds: int = 5):
    """等待索引构建完成"""
    print("\n" + "=" * 70)
    print(f"3. 等待文档索引完成 ({seconds}秒)")
    print("=" * 70)
    for i in range(seconds, 0, -1):
        print(f"\r等待中... {i}秒", end="", flush=True)
        time.sleep(1)
    print("\n✓ 等待完成")


def test_retrieval_only(token: str) -> Dict[str, Any]:
    """测试纯检索功能"""
    print("\n" + "=" * 70)
    print("4. 测试纯检索功能")
    print("=" * 70)
    
    results = []
    total_cases = len(TEST_CASES)
    
    for i, case in enumerate(TEST_CASES, 1):
        print(f"\n--- 测试用例 {i}/{total_cases}: {case['description']} ---")
        print(f"查询: {case['query']}")
        
        try:
            response = requests.post(
                f"{BASE_URL}/knowledge/retrieve",
                json={"query": case["query"], "topK": 5},
                headers=get_headers(token),
                timeout=10
            )
            
            if response.status_code == 200:
                data = response.json().get("data", [])
                print(f"检索到 {len(data)} 条结果:")
                
                retrieved_titles = [r.get("title", "") for r in data]
                retrieved_sources = [r.get("source", "") for r in data]
                
                # 检查是否检索到预期文档
                expected_title_found = any(
                    expected in retrieved_titles 
                    for expected in case["expected_titles"]
                )
                
                expected_source_found = any(
                    expected in retrieved_sources 
                    for expected in case["expected_sources"]
                )
                
                # 打印检索结果
                for j, r in enumerate(data, 1):
                    score = r.get("rrfScore", r.get("score", 0))
                    title = r.get("title", "")
                    source = r.get("source", "")
                    print(f"  [{j}] 分数: {score:.4f} | {title} ({source})")
                
                # 记录结果
                results.append({
                    "query": case["query"],
                    "expected_titles": case["expected_titles"],
                    "retrieved_titles": retrieved_titles,
                    "expected_title_found": expected_title_found,
                    "expected_source_found": expected_source_found,
                    "success": expected_title_found or expected_source_found,
                })
                
                if expected_title_found:
                    print("✓ 命中预期文档！")
                else:
                    print("✗ 未命中预期文档")
                
            else:
                print(f"✗ 检索失败: {response.status_code} - {response.text}")
                results.append({
                    "query": case["query"],
                    "success": False,
                    "error": f"HTTP {response.status_code}"
                })
                
        except Exception as e:
            print(f"✗ 检索异常: {e}")
            results.append({
                "query": case["query"],
                "success": False,
                "error": str(e)
            })
    
    return results


def test_chat_with_rag(token: str) -> Dict[str, Any]:
    """测试完整的 RAG 问答"""
    print("\n" + "=" * 70)
    print("5. 测试完整 RAG 问答（验证来源引用）")
    print("=" * 70)
    
    results = []
    
    for i, case in enumerate(TEST_CASES, 1):
        print(f"\n--- 测试用例 {i}/{len(TEST_CASES)}: {case['description']} ---")
        print(f"查询: {case['query']}")
        
        try:
            response = requests.post(
                f"{BASE_URL}/chat",
                json={
                    "sessionId": f"test-session-{int(time.time())}",
                    "query": case["query"]
                },
                headers=get_headers(token),
                timeout=30
            )
            
            if response.status_code == 200:
                data = response.json()
                success = data.get("success", False)
                answer = data.get("response", "")
                sources = data.get("sources", [])
                
                print(f"✓ 收到回答，包含 {len(sources)} 个来源引用")
                
                # 检查来源引用
                source_titles = [s.get("title", "") for s in sources]
                has_expected_source = any(
                    title in case["expected_titles"] 
                    for title in source_titles
                )
                
                # 检查回答中是否包含关键信息
                has_keywords = any(
                    keyword in answer 
                    for keyword in case["keywords_in_response"]
                )
                
                print(f"来源标题: {source_titles}")
                print(f"包含预期来源: {'是' if has_expected_source else '否'}")
                print(f"回答包含关键词: {'是' if has_keywords else '否'}")
                print(f"回答预览: {answer[:150]}..." if len(answer) > 150 else f"回答: {answer}")
                
                results.append({
                    "query": case["query"],
                    "success": success,
                    "has_expected_source": has_expected_source,
                    "has_keywords": has_keywords,
                    "source_titles": source_titles,
                    "answer": answer,
                    "sources": sources,
                    "case_success": has_expected_source and has_keywords
                })
                
            else:
                print(f"✗ 问答失败: {response.status_code} - {response.text}")
                results.append({
                    "query": case["query"],
                    "success": False,
                    "error": f"HTTP {response.status_code}"
                })
                
        except Exception as e:
            print(f"✗ 问答异常: {e}")
            results.append({
                "query": case["query"],
                "success": False,
                "error": str(e)
            })
    
    return results


def calculate_metrics(
    retrieval_results: List[Dict[str, Any]], 
    chat_results: List[Dict[str, Any]]
) -> Dict[str, Any]:
    """计算指标"""
    print("\n" + "=" * 70)
    print("6. 计算测试指标")
    print("=" * 70)
    
    # 检索指标
    retrieval_total = len(retrieval_results)
    retrieval_success = sum(1 for r in retrieval_results if r.get("success", False))
    retrieval_recall = retrieval_success / retrieval_total if retrieval_total > 0 else 0
    
    # 问答指标
    chat_total = len(chat_results)
    chat_case_success = sum(1 for r in chat_results if r.get("case_success", False))
    chat_source_rate = sum(1 for r in chat_results if r.get("has_expected_source", False)) / chat_total if chat_total > 0 else 0
    chat_keyword_rate = sum(1 for r in chat_results if r.get("has_keywords", False)) / chat_total if chat_total > 0 else 0
    chat_accuracy = chat_case_success / chat_total if chat_total > 0 else 0
    
    metrics = {
        "retrieval": {
            "total": retrieval_total,
            "success": retrieval_success,
            "recall": retrieval_recall
        },
        "chat": {
            "total": chat_total,
            "case_success": chat_case_success,
            "source_hit_rate": chat_source_rate,
            "keyword_hit_rate": chat_keyword_rate,
            "accuracy": chat_accuracy
        }
    }
    
    print("\n=== 检索指标 ===")
    print(f"总测试数: {metrics['retrieval']['total']}")
    print(f"成功命中: {metrics['retrieval']['success']}")
    print(f"召回率: {metrics['retrieval']['recall']:.2%}")
    
    print("\n=== 问答指标 ===")
    print(f"总测试数: {metrics['chat']['total']}")
    print(f"完整成功(来源+关键词): {metrics['chat']['case_success']}")
    print(f"来源命中率: {metrics['chat']['source_hit_rate']:.2%}")
    print(f"关键词命中率: {metrics['chat']['keyword_hit_rate']:.2%}")
    print(f"整体准确率: {metrics['chat']['accuracy']:.2%}")
    
    return metrics


def save_results(
    retrieval_results: List[Dict[str, Any]], 
    chat_results: List[Dict[str, Any]], 
    metrics: Dict[str, Any]
):
    """保存测试结果"""
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    output_file = f"rag_validation_results_{timestamp}.json"
    
    output = {
        "timestamp": timestamp,
        "metrics": metrics,
        "retrieval_results": retrieval_results,
        "chat_results": chat_results
    }
    
    try:
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(output, f, ensure_ascii=False, indent=2)
        print(f"\n测试结果已保存到: {output_file}")
    except Exception as e:
        print(f"\n保存结果失败: {e}")


# ============================================================
# 主函数
# ============================================================

def main():
    print("╔" + "=" * 68 + "╗")
    print("║" + " " * 15 + "RAG 召回率验证测试工具" + " " * 30 + "║")
    print("╚" + "=" * 68 + "╝")
    print("\n测试说明：")
    print("- 使用7个专有知识文档（虚构的公司内部系统）")
    print("- 这些知识LLM不可能在训练数据中见过")
    print("- 通过检查来源引用和关键词验证RAG效果")
    print("- 计算召回率、准确率等指标")
    
    # 执行测试
    token = login()
    total_docs, imported_docs = import_test_documents(token)
    
    if imported_docs == 0:
        print("\n⚠ 没有成功导入任何文档，测试终止")
        return
    
    wait_for_indexing(10)
    
    retrieval_results = test_retrieval_only(token)
    chat_results = test_chat_with_rag(token)
    
    metrics = calculate_metrics(retrieval_results, chat_results)
    save_results(retrieval_results, chat_results, metrics)
    
    print("\n" + "=" * 70)
    print("测试完成！")
    print("=" * 70)
    
    if metrics["retrieval"]["recall"] >= 0.7:
        print("\n🎉 RAG 检索效果良好！")
    elif metrics["retrieval"]["recall"] >= 0.5:
        print("\n⚡ RAG 检索效果一般，可能需要优化")
    else:
        print("\n⚠ RAG 检索效果较差，需要检查")


if __name__ == "__main__":
    main()
