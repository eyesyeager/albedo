package com.eyes.albedo.chat.service;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 幂等服务（api-spec.md §1.4 / EX-013）。
 *
 * <p>机制：Redis {@code SETNX} 抢占 → 执行业务 → 回写结果 ID；命中已完成的键则<b>回放原结果</b>。
 *
 * <p>关键取舍：
 * <ul>
 *   <li>业务失败时<b>必须删除</b>抢占标记，否则用户在 TTL 内无法重试（把偶发失败放大成 10 分钟不可用）</li>
 *   <li>并发重复提交（前一次仍在执行）短暂轮询等待结果，超时后返回 {@code 30020} 让前端重试，
 *       🔴 绝不放行第二次执行（那会重复创建会话/消息）</li>
 *   <li>键含 {@code tenantId + uid}（{@link TenantCacheKeys}），幂等域<b>不跨租户</b>也不跨用户</li>
 * </ul>
 */
@Slf4j
@Service
public class IdempotencyService {

    /** 抢占成功但业务尚未完成时写入的占位值。 */
    private static final String IN_FLIGHT = "__IN_FLIGHT__";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_:-]{8,64}$");
    private static final int WAIT_ROUNDS = 5;
    private static final long WAIT_INTERVAL_MILLIS = 100L;

    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final BusinessConfig businessConfig;

    public IdempotencyService(StringRedisTemplate redis,
                              TenantCacheKeys cacheKeys,
                              BusinessConfig businessConfig) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.businessConfig = businessConfig;
    }

    /**
     * 校验幂等键。
     *
     * @throws BusinessException 10001 缺失或格式非法
     */
    public String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.validation("缺少 Idempotency-Key 请求头");
        }
        String key = idempotencyKey.trim();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw BusinessException.validation("Idempotency-Key 格式非法（允许 8~64 位字母、数字、-、_、:）");
        }
        return key;
    }

    /**
     * 幂等执行。
     *
     * @param uid    当前用户
     * @param key    幂等键
     * @param action 首次执行的业务动作
     * @param replay 命中已完成键时的回放动作（入参为首次执行记录的资源 ID）
     * @param idOf   从业务结果中提取资源 ID
     * @return 首次执行结果或回放结果
     */
    public <T> T executeOnce(long uid, String key, Supplier<T> action,
                             Function<String, T> replay, Function<T, String> idOf) {
        String cacheKey = cacheKey(uid, key);
        Duration ttl = ttl();

        Boolean acquired = safeSetIfAbsent(cacheKey, IN_FLIGHT, ttl);
        if (Boolean.FALSE.equals(acquired)) {
            return replayOrConflict(cacheKey, replay);
        }
        // acquired == null 表示 Redis 不可用：降级为「不保证幂等但不阻断业务」，并记录告警
        if (acquired == null) {
            log.warn("Redis 不可用，本次请求跳过幂等保护：uid={}", uid);
            return action.get();
        }

        try {
            T result = action.get();
            safeSet(cacheKey, idOf.apply(result), ttl);
            return result;
        } catch (RuntimeException e) {
            // 失败必须释放，否则用户在 TTL 内无法重试
            safeDelete(cacheKey);
            throw e;
        }
    }

    /**
     * 查询幂等键已记录的资源 ID（供 SSE 场景自行决定回放方式）。
     */
    public Optional<String> findRecorded(long uid, String key) {
        String value = safeGet(cacheKey(uid, key));
        if (value == null || IN_FLIGHT.equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 抢占幂等键。
     *
     * @return true 表示首次执行；false 表示重复请求（此时应回放）
     */
    public boolean tryAcquire(long uid, String key) {
        Boolean acquired = safeSetIfAbsent(cacheKey(uid, key), IN_FLIGHT, ttl());
        // Redis 不可用时按「允许执行」处理，业务侧仍有数据库唯一键 uk_tenant_idem 兜底
        return !Boolean.FALSE.equals(acquired);
    }

    /**
     * 记录幂等结果。
     */
    public void record(long uid, String key, String resourceId) {
        safeSet(cacheKey(uid, key), resourceId, ttl());
    }

    /**
     * 释放幂等键（业务失败时调用，允许用户重试）。
     */
    public void release(long uid, String key) {
        safeDelete(cacheKey(uid, key));
    }

    // ===================== 内部实现 =====================

    private <T> T replayOrConflict(String cacheKey, Function<String, T> replay) {
        for (int i = 0; i < WAIT_ROUNDS; i++) {
            String value = safeGet(cacheKey);
            if (value != null && !IN_FLIGHT.equals(value)) {
                return replay.apply(value);
            }
            sleep();
        }
        // 前一次请求仍在执行：宁可让前端重试，也不放行第二次执行
        throw new BusinessException(ErrorCode.VERSION_CONFLICT, "相同请求正在处理中，请稍后重试");
    }

    private void sleep() {
        try {
            Thread.sleep(WAIT_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private String cacheKey(long uid, String key) {
        return cacheKeys.chatIdempotency(TenantContext.requireEnabled().tenantId(), uid, key);
    }

    private Duration ttl() {
        return Duration.ofSeconds(
                businessConfig.requireLong(ConfigKeys.GROUP_CHAT, ConfigKeys.IDEMPOTENCY_TTL_SECONDS));
    }

    private Boolean safeSetIfAbsent(String key, String value, Duration ttl) {
        try {
            return redis.opsForValue().setIfAbsent(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("幂等键抢占失败（Redis 不可用）：{}", key, e);
            return null;
        }
    }

    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取幂等键失败：{}", key, e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        if (value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("写入幂等结果失败：{}", key, e);
        }
    }

    private void safeDelete(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("释放幂等键失败：{}", key, e);
        }
    }
}
