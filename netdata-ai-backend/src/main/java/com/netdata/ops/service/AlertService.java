package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.AlertRecord;
import com.netdata.ops.exception.BusinessException;
import com.netdata.ops.exception.ErrorCode;
import com.netdata.ops.mapper.AlertRecordMapper;
import com.netdata.ops.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRecordMapper alertRecordMapper;
    private final EmailService emailService;

    /**
     * 分页查询告警记录
     */
    public PageResult<AlertRecord> getAlertPage(int current, int size,
                                                 String severity, String status,
                                                 String host, String keyword) {
        Page<AlertRecord> page = new Page<>(current, size);
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<>();

        if (severity != null && !severity.isBlank()) {
            wrapper.eq(AlertRecord::getSeverity, severity);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(AlertRecord::getStatus, status);
        }
        if (host != null && !host.isBlank()) {
            wrapper.eq(AlertRecord::getHost, host);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(AlertRecord::getAlertName, keyword)
                    .or().like(AlertRecord::getMessage, keyword));
        }
        wrapper.orderByDesc(AlertRecord::getCreatedAt);

        Page<AlertRecord> result = alertRecordMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 获取告警详情
     */
    public AlertRecord getAlertById(Long id) {
        AlertRecord alert = alertRecordMapper.selectById(id);
        if (alert == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "告警记录不存在");
        }
        return alert;
    }

    /**
     * 确认/解决告警
     */
    @Transactional
    public AlertRecord resolveAlert(Long id, String diagnosisResult) {
        AlertRecord alert = alertRecordMapper.selectById(id);
        if (alert == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "告警记录不存在");
        }
        if ("resolved".equals(alert.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "告警已被解决");
        }

        alert.setStatus("resolved");
        alert.setDiagnosisResult(diagnosisResult);
        alert.setResolvedBy(SecurityUtils.getCurrentUserId());
        alert.setResolvedAt(LocalDateTime.now());
        alert.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.updateById(alert);

        log.info("告警已解决: alertId={}, resolvedBy={}", alert.getAlertId(), SecurityUtils.getCurrentUsername());
        return alert;
    }

    /**
     * 接收外部告警（由NetData webhook或定时拉取触发）
     */
    @Transactional
    public AlertRecord createAlert(String alertId, String source, String severity,
                                    String alertName, String message, String host,
                                    String metricName, String metricValue, String threshold) {
        // 先检查该 alertId 是否已存在（无论状态）
        LambdaQueryWrapper<AlertRecord> existsWrapper = new LambdaQueryWrapper<>();
        existsWrapper.eq(AlertRecord::getAlertId, alertId);
        AlertRecord existingAlert = alertRecordMapper.selectOne(existsWrapper);
        
        if (existingAlert != null) {
            if ("firing".equals(existingAlert.getStatus())) {
                // 已有正在 firing 的告警，更新数据但保留状态
                log.debug("告警已存在且正在firing，更新数据: alertId={}", alertId);
                existingAlert.setSeverity(severity != null ? severity : "warning");
                existingAlert.setMetricValue(metricValue);
                existingAlert.setThreshold(threshold);
                existingAlert.setMessage(message);
                existingAlert.setUpdatedAt(LocalDateTime.now());
                alertRecordMapper.updateById(existingAlert);
                return existingAlert;
            } else {
                // 之前已恢复的告警，先删除旧记录，再重新创建
                log.debug("旧告警已恢复，删除旧记录并重新创建: alertId={}", alertId);
                alertRecordMapper.deleteById(existingAlert.getId());
            }
        }

        AlertRecord alert = new AlertRecord();
        alert.setAlertId(alertId);
        alert.setSource(source != null ? source : "netdata");
        alert.setSeverity(severity != null ? severity : "warning");
        alert.setAlertName(alertName);
        alert.setMessage(message);
        alert.setHost(host);
        alert.setMetricName(metricName);
        alert.setMetricValue(metricValue);
        alert.setThreshold(threshold);
        alert.setStatus("firing");
        alert.setCreatedAt(LocalDateTime.now());
        alert.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.insert(alert);

        log.info("新告警入库: alertId={}, severity={}, host={}", alertId, severity, host);

        // 发送邮件通知（仅 critical 和 warning 级别）
        if ("critical".equals(alert.getSeverity()) || "warning".equals(alert.getSeverity())) {
            emailService.sendAlertNotification(alert);
        }

        return alert;
    }

    /**
     * 批量解决告警
     */
    @Transactional
    public int batchResolve(List<Long> ids, String diagnosisResult) {
        int count = 0;
        for (Long id : ids) {
            AlertRecord alert = alertRecordMapper.selectById(id);
            if (alert != null && "firing".equals(alert.getStatus())) {
                alert.setStatus("resolved");
                alert.setDiagnosisResult(diagnosisResult);
                alert.setResolvedBy(SecurityUtils.getCurrentUserId());
                alert.setResolvedAt(LocalDateTime.now());
                alert.setUpdatedAt(LocalDateTime.now());
                alertRecordMapper.updateById(alert);
                count++;
            }
        }
        log.info("批量解决告警: count={}, by={}", count, SecurityUtils.getCurrentUsername());
        return count;
    }

    /**
     * 告警统计概览
     */
    public Map<String, Object> getAlertStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // 总告警数
        LambdaQueryWrapper<AlertRecord> totalWrapper = new LambdaQueryWrapper<>();
        long totalCount = alertRecordMapper.selectCount(totalWrapper);
        stats.put("total", totalCount);
        
        // 正在告警数
        long firingCount = alertRecordMapper.countFiring();
        stats.put("firing", firingCount);
        
        // 已恢复数
        LambdaQueryWrapper<AlertRecord> resolvedWrapper = new LambdaQueryWrapper<>();
        resolvedWrapper.eq(AlertRecord::getStatus, "resolved");
        long resolvedCount = alertRecordMapper.selectCount(resolvedWrapper);
        stats.put("resolved", resolvedCount);
        
        // 严重告警数
        LambdaQueryWrapper<AlertRecord> criticalWrapper = new LambdaQueryWrapper<>();
        criticalWrapper.eq(AlertRecord::getStatus, "firing")
                       .eq(AlertRecord::getSeverity, "critical");
        long criticalCount = alertRecordMapper.selectCount(criticalWrapper);
        stats.put("critical", criticalCount);
        
        // 补充额外统计信息
        stats.put("firingCount", firingCount);
        stats.put("resolvedToday", alertRecordMapper.countResolvedToday());
        stats.put("severityDistribution", alertRecordMapper.selectFiringStatsBySeverity());

        // 统计各主机的告警数量
        LambdaQueryWrapper<AlertRecord> hostWrapper = new LambdaQueryWrapper<>();
        hostWrapper.eq(AlertRecord::getStatus, "firing");
        hostWrapper.select(AlertRecord::getHost);
        hostWrapper.groupBy(AlertRecord::getHost);
        List<AlertRecord> hostAlerts = alertRecordMapper.selectList(hostWrapper);
        stats.put("affectedHosts", hostAlerts.size());

        return stats;
    }

    /**
     * 获取告警趋势（最近7天，按天汇总）
     */
    public List<Map<String, Object>> getAlertTrend() {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(AlertRecord::getCreatedAt, LocalDateTime.now().minusDays(7));
        wrapper.orderByAsc(AlertRecord::getCreatedAt);

        List<AlertRecord> records = alertRecordMapper.selectList(wrapper);

        // 按日期分组统计
        Map<String, Map<String, Long>> dailyStats = new HashMap<>();
        for (AlertRecord record : records) {
            String date = record.getCreatedAt().toLocalDate().toString();
            dailyStats.computeIfAbsent(date, k -> new HashMap<>(Map.of(
                    "critical", 0L, "warning", 0L, "info", 0L
            )));
            String sev = record.getSeverity() != null ? record.getSeverity() : "info";
            dailyStats.get(date).merge(sev, 1L, Long::sum);
        }

        return dailyStats.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("date", entry.getKey());
                    item.putAll(entry.getValue());
                    return item;
                })
                .toList();
    }

    /**
     * 触发AI诊断（对接智能诊断Agent）
     */
    @Transactional
    public Map<String, Object> triggerDiagnosis(Long alertId) {
        AlertRecord alert = alertRecordMapper.selectById(alertId);
        if (alert == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "告警记录不存在");
        }

        // TODO: 调用DiagnosisAgent进行智能诊断
        // 此处模拟诊断结果
        String diagnosisContent = String.format(
                "AI诊断结果：\n告警: %s\n主机: %s\n指标: %s = %s (阈值: %s)\n" +
                "可能原因: 资源使用率超过预期，建议检查相关进程或扩容。\n" +
                "建议操作: 1. 查看top/htop确认进程 2. 检查是否有异常任务 3. 考虑扩容",
                alert.getAlertName(), alert.getHost(),
                alert.getMetricName(), alert.getMetricValue(), alert.getThreshold()
        );

        alert.setDiagnosisResult(diagnosisContent);
        alert.setUpdatedAt(LocalDateTime.now());
        alertRecordMapper.updateById(alert);

        log.info("AI诊断完成: alertId={}", alert.getAlertId());

        Map<String, Object> result = new HashMap<>();
        result.put("alertId", alert.getId());
        result.put("diagnosis", diagnosisContent);
        result.put("timestamp", LocalDateTime.now());
        return result;
    }
}
