package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tenant.TenantCacheKeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 限流单测（AC-LMT-001 / AC-LMT-003~005 / EX-021）。
 *
 * <p>🔴 <b>V1.4.5（ADR-020）后的三处判据变化</b>：
 * <ul>
 *   <li>阈值<b>由入参传入</b>（不再读 {@code sys_config}）—— 因此本类不再 mock
 *       {@code BusinessConfig}，"代码里没有 30/120 字面量"的守护上移到
 *       {@code QuotaHardcodeScanTest} 与 {@code QuotaPolicyResolverTest}</li>
 *   <li>🔴 <b>小时窗已废除</b>：不存在任何"第二个窗口"的断言</li>
 *   <li>🔴 时钟构造注入 → 可确定性验证"跨分钟边界"，
 *       并<b>从根因上</b>消除既有 flaky（原 {@code thresholdComesFromSysConfig} 的窗口竞态）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageRateLimiterTest {

    /** 固定时刻：2026-08-18T03:21:07Z → 分钟窗 m202608180321。 */
    private static final Instant FIXED = Instant.parse("2026-08-18T03:21:07Z");

    @Mock
    private StringRedisTemplate redis;

    private MessageRateLimiter limiter(Clock clock) {
        return new MessageRateLimiter(redis, new TenantCacheKeys("test"), clock);
    }

    @Test
    @DisplayName("未超限：正常放行")
    void allowsWithinLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(List.of(1L, 60L));

        limiter(Clock.fixed(FIXED, ZoneOffset.UTC)).check("gift", 10086L, 3);
    }

    @Test
    @DisplayName("🔴 AC-LMT-003 边界：阈值 N 时第 N+1 次 → 10005 且 data.retryAfterSeconds ≥1")
    void rejectsOverMinuteLimit() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(List.of(4L, 42L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> limiter(Clock.fixed(FIXED, ZoneOffset.UTC)).check("gift", 10086L, 3));
        assertEquals(ErrorCode.RATE_LIMITED, e.getCode());
        assertTrue(e.getPayload() instanceof Map<?, ?>, "必须带附加数据");
        assertEquals(42L, ((Map<?, ?>) e.getPayload()).get("retryAfterSeconds"));
    }

    @Test
    @DisplayName("🔴 阈值来自入参（策略解析结果）：同一计数下 limit=3 拒绝、limit=5 放行")
    void thresholdComesFromArgument() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(List.of(4L, 30L));
        MessageRateLimiter limiter = limiter(Clock.fixed(FIXED, ZoneOffset.UTC));

        assertThrows(BusinessException.class, () -> limiter.check("gift", 10086L, 3));
        // 🔴 AC-LMT-004：gift 覆盖为 5 时第 4 次仍可发（同一计数值、仅阈值不同）
        limiter.check("gift", 10086L, 5);
    }

    @Test
    @DisplayName("🔴 跨分钟边界（fixed clock 确定性）：窗口键随分钟推进而切换")
    void windowKeyRollsOverAcrossMinuteBoundary() {
        List<String> keys = new ArrayList<>();
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    keys.add(((List<?>) invocation.getArgument(1)).get(0).toString());
                    return List.of(1L, 60L);
                });

        limiter(Clock.fixed(Instant.parse("2026-08-18T03:21:59Z"), ZoneOffset.UTC))
                .check("gift", 10086L, 3);
        limiter(Clock.fixed(Instant.parse("2026-08-18T03:22:00Z"), ZoneOffset.UTC))
                .check("gift", 10086L, 3);

        assertTrue(keys.get(0).endsWith(":m202608180321"), keys.get(0));
        assertTrue(keys.get(1).endsWith(":m202608180322"), keys.get(1));
    }

    @Test
    @DisplayName("🔴 小时窗已废除：一次 check 只消费**一个**窗口键（不再有第二次 Redis 往返）")
    void hourWindowIsRemoved() {
        List<String> keys = new ArrayList<>();
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    keys.add(((List<?>) invocation.getArgument(1)).get(0).toString());
                    return List.of(1L, 60L);
                });

        limiter(Clock.fixed(FIXED, ZoneOffset.UTC)).check("gift", 10086L, 3);

        assertEquals(1, keys.size(), "🔴 小时窗分支必须已删除，实际消费的窗口键：" + keys);
        assertTrue(keys.get(0).contains(":m"), keys.get(0));
    }

    @Test
    @DisplayName("Redis 不可用 → 放行（限流是保护措施，不应制造不可用；🔴 与日额度有意不同）")
    void failsOpenWhenRedisDown() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        limiter(Clock.fixed(FIXED, ZoneOffset.UTC)).check("gift", 10086L, 3);
    }

    @Test
    @DisplayName("计数键含 tenantId 与 uid：不同租户互不占用额度")
    void keysAreTenantScoped() {
        TenantCacheKeys keys = new TenantCacheKeys("test");
        String gift = keys.messageRateLimit("gift", 10086L, "m202608121200");
        String redbook = keys.messageRateLimit("redbook", 10086L, "m202608121200");
        String otherUser = keys.messageRateLimit("gift", 999L, "m202608121200");

        assertTrue(gift.contains(":gift:"), gift);
        assertTrue(redbook.contains(":redbook:"), redbook);
        assertTrue(!gift.equals(redbook), "不同租户必须是不同的计数键");
        assertTrue(!gift.equals(otherUser), "不同用户必须是不同的计数键");
    }
}
