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
    private final com.eyes.albedo.tool.ToolConfirmRegistry confirmRegistry;

    public ChatCancelService(StringRedisTemplate redis,
                            TenantCacheKeys cacheKeys,
                            CancelRegistry cancelRegistry,
                            BusinessConfig businessConfig,
                            com.eyes.albedo.tool.ToolConfirmRegistry confirmRegistry) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.cancelRegistry = cancelRegistry;
        this.businessConfig = businessConfig;
        this.confirmRegistry = confirmRegistry;
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
     * <p>🔴 <b>三路收敛的第一棒</b>（architecture.md §9.5.4 / ADR-008 第 9 条）：
     * <ol>
     *   <li>写 Redis 取消标记（跨实例兜底）</li>
     *   <li>关闭本机上游流（生成线程若在读流，立刻结束）</li>
     *   <li>🔴 <b>唤醒确认等待</b>：生成线程若正挂在 {@code ToolConfirmRegistry.await(...)}，
     *       关流<b>不会</b>唤醒它（它没在读流）。不做这一步，用户点了停止仍会白等满
     *       {@code tool.confirm_wait_seconds}（默认 120s）—— 64 个线程被这样占住就是可用性事故（AR-008）</li>
     * </ol>
     */
    public void cancel(String tenantId, long messageId) {
        mark(tenantId, messageId);
        boolean localHit = cancelRegistry.close(messageId);
        int confirmWaiters = confirmRegistry.cancelByMessage(messageId);
        log.info("已请求停止生成：messageId={} localHit={} confirmWaitersWoken={}",
                messageId, localHit, confirmWaiters);
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
