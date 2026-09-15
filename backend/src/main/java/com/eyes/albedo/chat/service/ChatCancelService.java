package com.eyes.albedo.chat.service;

import java.time.Duration;

import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 取消生成服务：Redis 取消标记（跨实例）+ 本机关流（即时）。
 *
 * <p>双通道设计见 {@link CancelRegistry}。标记键走 {@link TenantCacheKeys}，
 * 因此🔴 取消动作也不可能跨租户误伤（键内含 tenantId）。
 */
@Slf4j
@Service
public class ChatCancelService {

    private static final String CANCEL_MARKER = "1";

    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final CancelRegistry cancelRegistry;
    private final BusinessConfig businessConfig;

    public ChatCancelService(StringRedisTemplate redis,
                            TenantCacheKeys cacheKeys,
                            CancelRegistry cancelRegistry,
                            BusinessConfig businessConfig) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.cancelRegistry = cancelRegistry;
        this.businessConfig = businessConfig;
    }

    /**
     * 请求取消：写标记 + 立即关闭本机上游流。
     */
    public void cancel(long messageId) {
        cancel(TenantContext.requireEnabled().tenantId(), messageId);
    }

    /**
     * 请求取消（显式租户，供异步任务使用）。
     *
     * <p>🔴 <b>双路收敛</b>（architecture.md §9.5.4）：
     * <ol>
     *   <li>写 Redis 取消标记（跨实例兜底）</li>
     *   <li>关闭本机上游流（生成线程若在读流，立刻结束）</li>
     * </ol>
     */
    public void cancel(String tenantId, long messageId) {
        mark(tenantId, messageId);
        boolean localHit = cancelRegistry.close(messageId);
        log.info("已请求停止生成：messageId={} localHit={}", messageId, localHit);
    }

    /**
     * 是否已被取消（生成循环按分片间隔检查）。
     */
    public boolean isCancelled(String tenantId, long messageId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(cacheKeys.chatCancel(tenantId, messageId)));
        } catch (RuntimeException e) {
            // Redis 不可用时不误判为取消：本机关流仍能终止生成
            log.warn("读取取消标记失败，按未取消处理：messageId={}", messageId);
            return false;
        }
    }

    /**
     * 清理取消标记（生成结束后调用）。
     */
    public void clear(String tenantId, long messageId) {
        try {
            redis.delete(cacheKeys.chatCancel(tenantId, messageId));
        } catch (RuntimeException e) {
            log.warn("清理取消标记失败：messageId={}", messageId);
        }
    }

    private void mark(String tenantId, long messageId) {
        try {
            redis.opsForValue().set(cacheKeys.chatCancel(tenantId, messageId), CANCEL_MARKER, markerTtl());
        } catch (RuntimeException e) {
            log.warn("写入取消标记失败（本机关流仍会生效）：messageId={}", messageId);
        }
    }

    private Duration markerTtl() {
        return Duration.ofSeconds(businessConfig.requireLong(
                ConfigKeys.GROUP_CHAT, ConfigKeys.CANCEL_MARKER_TTL_SECONDS));
    }
}
