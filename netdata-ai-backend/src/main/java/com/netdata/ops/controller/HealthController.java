package com.netdata.ops.controller;

import com.netdata.ops.dto.response.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查控制器
 */
@Tag(name = "系统健康", description = "系统状态检查、版本信息")
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Value("${spring.application.name:netdata-ops}")
    private String appName;

    private final LocalDateTime startTime = LocalDateTime.now();

    @Operation(summary = "健康检查")
    @GetMapping
    public R<Map<String, Object>> health() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "UP");
        info.put("application", appName);
        info.put("version", "2.0.0");
        info.put("timestamp", LocalDateTime.now());

        // JVM信息
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        Duration uptime = Duration.ofMillis(runtimeBean.getUptime());
        info.put("uptime", formatDuration(uptime));

        // 内存信息
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMB", runtime.maxMemory() / 1024 / 1024);
        memory.put("totalMB", runtime.totalMemory() / 1024 / 1024);
        memory.put("freeMB", runtime.freeMemory() / 1024 / 1024);
        memory.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        info.put("memory", memory);

        // 系统信息
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("javaVersion", System.getProperty("java.version"));
        system.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        system.put("processors", runtime.availableProcessors());
        info.put("system", system);

        return R.ok(info);
    }

    private String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;
        return String.format("%dd %dh %dm", days, hours, minutes);
    }
}
