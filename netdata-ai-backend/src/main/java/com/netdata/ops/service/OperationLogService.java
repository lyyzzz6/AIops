package com.netdata.ops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.netdata.ops.dto.response.PageResult;
import com.netdata.ops.entity.OperationLog;
import com.netdata.ops.mapper.OperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 操作日志查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogMapper operationLogMapper;

    /**
     * 分页查询操作日志
     */
    public PageResult<OperationLog> getLogPage(int current, int size,
                                                String module, String action,
                                                String username, LocalDateTime startTime,
                                                LocalDateTime endTime) {
        Page<OperationLog> page = new Page<>(current, size);
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();

        if (module != null && !module.isBlank()) {
            wrapper.eq(OperationLog::getModule, module);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(OperationLog::getAction, action);
        }
        if (username != null && !username.isBlank()) {
            wrapper.like(OperationLog::getUsername, username);
        }
        if (startTime != null) {
            wrapper.ge(OperationLog::getCreatedAt, startTime);
        }
        if (endTime != null) {
            wrapper.le(OperationLog::getCreatedAt, endTime);
        }
        wrapper.orderByDesc(OperationLog::getCreatedAt);

        Page<OperationLog> result = operationLogMapper.selectPage(page, wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 操作日志统计
     */
    public Map<String, Object> getLogStats() {
        Map<String, Object> stats = new HashMap<>();

        // 总操作数
        stats.put("total", operationLogMapper.selectCount(null));

        // 成功数
        LambdaQueryWrapper<OperationLog> successWrapper = new LambdaQueryWrapper<>();
        successWrapper.eq(OperationLog::getStatus, 1);
        stats.put("success", operationLogMapper.selectCount(successWrapper));

        // 失败数
        LambdaQueryWrapper<OperationLog> failWrapper = new LambdaQueryWrapper<>();
        failWrapper.eq(OperationLog::getStatus, 0);
        stats.put("failed", operationLogMapper.selectCount(failWrapper));

        // 今日操作数
        LambdaQueryWrapper<OperationLog> todayWrapper = new LambdaQueryWrapper<>();
        todayWrapper.ge(OperationLog::getCreatedAt, LocalDateTime.now().toLocalDate().atStartOfDay());
        stats.put("today", operationLogMapper.selectCount(todayWrapper));

        return stats;
    }
}
