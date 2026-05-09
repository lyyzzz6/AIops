package com.netdata.ops.core.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;

/**
 * ============================================================
 * 分布式锁服务
 * ============================================================
 *
 * 设计目的：
 * 基于 Redis 实现分布式锁，防止同一命令被重复执行。
 * 使用 SET NX EX 原子操作获取锁，Lua 脚本安全释放锁。
 *
 * 适用场景：
 * - 高风险命令防重复执行
 * - Agent 并发控制
 * - 审批后命令的唯一执行保证
 *
 * 线程安全：依赖 Redis 原子操作，本身无可变状态。
 *
 * @author 刘一舟
 * @since 2026-05-07
 */
@Component
@Slf4j
public class DistributedLockService {

    private final StringRedisTemplate redisTemplate;

    /**
     * 锁 Key 前缀
     */
    private static final String LOCK_PREFIX = "agent:lock:";

    /**
     * 命令执行锁前缀
     */
    private static final String CMD_LOCK_PREFIX = "agent:lock:cmd:";

    /**
     * 默认锁 TTL（5 分钟自动释放，防止死锁）
     */
    private static final Duration DEFAULT_LOCK_TTL = Duration.ofMinutes(5);

    /**
     * Lua 脚本：安全释放锁（仅当 owner 匹配时才删除）
     * 避免误删其他持有者的锁
     */
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else " +
                    "return 0 " +
                    "end";

    public DistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取分布式锁
     *
     * 使用 Redis SET NX EX 原子操作，确保互斥性和防死锁。
     *
     * @param key   锁的唯一标识
     * @param owner 锁的持有者标识（通常用 traceId 或 UUID）
     * @param ttl   锁的过期时间
     * @return true 表示获取成功，false 表示已被其他持有者占用
     */
    public boolean tryLock(String key, String owner, Duration ttl) {
        String lockKey = LOCK_PREFIX + key;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner, ttl);
        boolean success = Boolean.TRUE.equals(acquired);

        if (success) {
            log.debug("获取分布式锁成功: key={}, owner={}, ttl={}s", key, owner, ttl.getSeconds());
        } else {
            log.debug("获取分布式锁失败（已被占用）: key={}, owner={}", key, owner);
        }

        return success;
    }

    /**
     * 释放分布式锁
     *
     * 使用 Lua 脚本确保只有锁的持有者才能释放，防止误删。
     *
     * @param key   锁的唯一标识
     * @param owner 锁的持有者标识
     * @return true 表示释放成功，false 表示锁不存在或非持有者
     */
    public boolean releaseLock(String key, String owner) {
        String lockKey = LOCK_PREFIX + key;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(lockKey), owner);
        boolean success = result != null && result == 1L;

        if (success) {
            log.debug("释放分布式锁成功: key={}, owner={}", key, owner);
        } else {
            log.debug("释放分布式锁失败（锁不存在或非持有者）: key={}, owner={}", key, owner);
        }

        return success;
    }

    /**
     * 锁定命令执行（防重复）
     *
     * 以命令内容的哈希值作为锁 Key，traceId 作为 owner，
     * 确保同一命令在 TTL 内不会被重复执行。
     *
     * @param command 待执行的命令
     * @param traceId 链路追踪 ID（作为锁持有者）
     * @return true 表示锁定成功，可以执行；false 表示命令正在执行中
     */
    public boolean lockCommandExecution(String command, String traceId) {
        String commandKey = generateCommandKey(command);
        String lockKey = CMD_LOCK_PREFIX + commandKey;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, traceId, DEFAULT_LOCK_TTL);
        boolean success = Boolean.TRUE.equals(acquired);

        if (success) {
            log.info("命令执行锁定成功: command={}, traceId={}", command, traceId);
        } else {
            log.warn("命令重复执行被拦截: command={}, traceId={}", command, traceId);
        }

        return success;
    }

    /**
     * 解锁命令执行
     *
     * @param command 已执行完的命令
     * @param traceId 链路追踪 ID
     */
    public void unlockCommandExecution(String command, String traceId) {
        String commandKey = generateCommandKey(command);
        String lockKey = CMD_LOCK_PREFIX + commandKey;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(RELEASE_LOCK_SCRIPT, Long.class);
        Long result = redisTemplate.execute(script,
                Collections.singletonList(lockKey), traceId);

        if (result != null && result == 1L) {
            log.info("命令执行解锁成功: command={}, traceId={}", command, traceId);
        } else {
            log.debug("命令执行解锁跳过（已过期或非持有者）: command={}, traceId={}", command, traceId);
        }
    }

    /**
     * 生成命令的唯一 Key（基于哈希）
     */
    private String generateCommandKey(String command) {
        // 使用简单的哈希避免 Key 过长
        return Integer.toHexString(command.hashCode());
    }
}
