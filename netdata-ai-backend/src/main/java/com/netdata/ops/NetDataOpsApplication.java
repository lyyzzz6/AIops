package com.netdata.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ============================================================
 * 智能运维问答与执行系统 - 主应用入口
 * ============================================================
 * 
 * 面向 NetData 监控数据的智能运维问答与执行系统
 * 
 * 核心功能：
 * 1. 自然语言问答（RAG 增强的运维知识问答）
 * 2. 智能故障诊断（异常检测 + 根因分析）
 * 3. 命令执行（风险评估 + 人工审批）
 *
 * 技术栈：
 * - Spring Boot 3.3.x
 * - Spring AI 1.0.x
 * - Milvus 2.4
 * - Python FastAPI (异常检测)
 *
 * @author 刘一舟
 * @since 2026-04-30
 */
@SpringBootApplication
@EnableAsync
public class NetDataOpsApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(NetDataOpsApplication.class, args);
    }
}
