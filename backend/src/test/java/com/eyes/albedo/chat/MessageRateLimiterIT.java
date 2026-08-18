package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.eyes.albedo.chat.service.MessageRateLimiter;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tenant.TenantCacheKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 消息限流复核（PRD §8.8 / api-spec §7.12 / AC-LMT-001 / AC-LMT-003~005）——<b>对真实 Redis</b>。
 *
 * <p>逐条对照契约：
 * <ul>
 *   <li>阈值<b>由入参传入</b>（🔴 V1.4.5 起限流器不再自行读 {@code sys_config}，
 *       阈值来自 {@code QuotaPolicyResolver} 的有效策略）</li>
 *   <li>{@code 10005} + 🔴 {@code data.retryAfterSeconds} 必填且 ≥1</li>
 *   <li>🔴 计数键含 {@code tenantId + uid}：跨租户、跨用户<b>互不占用额度</b></li>
 *   <li>🔴 <b>小时窗已废除</b>（AC-LMT-005）：QPM 放宽后连续 130 次 &gt; 历史 120 仍无任何拒绝</li>
 * </ul>
 *
 * <p>🔴 <b>既有 flaky 的根因修复</b>（ADR-020 ⑧）：原用例直接用容器里的 Bean（真实时钟），
 * 若两次 {@code check} 之间跨过分钟边界，计数器会被重置从而"该拒绝的没拒绝"。
 * 现在每个用例用 <b>{@code Clock.fixed}</b> 自建限流器 —— 窗口标识固定，🔴 竞态在结构上消失
 * （这是<b>修根因</b>而不是复跑掩盖），同时仍然跑在<b>真实 Redis</b> 与真实 Lua 上。
 *
 * <p>🔴 <b>不为 SSE 流内 {@code 10005} 编写用例</b>（api-spec §7.12 V1.1.3 判定）：
 * 限流在<b>建流之前</b>执行且一期<b>无任何流内限流点</b>，该路径属<b>契约预留、不可达</b>。
 */
@SpringBootTest
class MessageRateLimiterIT {

    private static final long UID = 900000111L;
    private static final long OTHER_UID = 900000112L;
    /** 固定窗口时刻（🔴 消除跨分钟边界竞态）。 */
    private static final Instant FIXED = Instant.parse("2026-08-18T03:21:07Z");

    @Autowired
    private MessageRateLimiter wiredLimiter;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TenantCacheKeys cacheKeys;

    private MessageRateLimiter limiter;
    private String tenantA;
    private String tenantB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = "rla" + suffix;
        tenantB = "rlb" + suffix;
        limiter = new MessageRateLimiter(redis, cacheKeys, Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        // 🔴 限流键属"运行时状态键"，测试自行精确清理（缓存失效接口禁止删它们）
        for (String tenant : new String[]{tenantA, tenantB}) {
            for (long uid : new long[]{UID, OTHER_UID}) {
                redis.keys(cacheKeys.messageRateLimit(tenant, uid, "*"))
                        .forEach(redis::delete);
            }
        }
    }

    @Test
    @DisplayName("🔴 限流器已装配且构造签名为 (redis, cacheKeys, clock)（阈值不再来自 sys_config）")
    void limiterIsWiredWithInjectedClock() {
        assertTrue(wiredLimiter != null, "MessageRateLimiter 必须仍是容器内的 Bean");
    }

    @Test
    @DisplayName("🔴 AC-LMT-003 边界：默认 QPM=3 时前 3 次通过、第 4 次 → 10005 + retryAfterSeconds ≥1")
    void fourthRequestInMinuteIsRejected() {
        limiter.check(tenantA, UID, 3);
        limiter.check(tenantA, UID, 3);
        limiter.check(tenantA, UID, 3);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> limiter.check(tenantA, UID, 3));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getCode());
        assertTrue(ex.getPayload() instanceof Map, "🔴 必须携带 data 载荷");
        Object retryAfter = ((Map<?, ?>) ex.getPayload()).get("retryAfterSeconds");
        assertTrue(retryAfter instanceof Number, "🔴 retryAfterSeconds 必填且为 number");
        assertTrue(((Number) retryAfter).longValue() >= 1,
                "🔴 必须 ≥1：回 0 会让前端『倒计时 0 秒立即重试』从而立刻再撞一次限流");
    }

    @Test
    @DisplayName("🔴 AC-LMT-004：同一 uid 的 QPM 阈值按租户策略分别生效（覆盖 5 vs 继承 3）")
    void thresholdIsPerTenantPolicy() {
        // gift 侧覆盖为 5：前 4 次全部通过
        for (int i = 0; i < 4; i++) {
            limiter.check(tenantA, UID, 5);
        }
        // redbook 侧继承 3：第 4 次被拒（🔴 且不受 tenantA 已消耗 4 次的影响）
        limiter.check(tenantB, UID, 3);
        limiter.check(tenantB, UID, 3);
        limiter.check(tenantB, UID, 3);
        assertEquals(ErrorCode.RATE_LIMITED,
                assertThrows(BusinessException.class, () -> limiter.check(tenantB, UID, 3)).getCode());
    }

    @Test
    @DisplayName("🔴 AC-LMT-005 反向断言：QPM 放宽为 200 后连续 130 次（>历史 120）**无任何**限流拒绝")
    void noHourlyWindowRejectionExists() {
        // 🔴 唯一可执行判据："不存在任何小时窗拒绝"——历史 message_per_hour=120 已废弃删行
        for (int i = 0; i < 130; i++) {
            limiter.check(tenantA, UID, 200);
        }
    }

    @Test
    @DisplayName("🔴 AC-LMT-001：计数键含 tenantId —— 一个租户打满**不占用**另一个租户额度")
    void countersAreNotSharedAcrossTenants() {
        limiter.check(tenantA, UID, 1);
        assertThrows(BusinessException.class, () -> limiter.check(tenantA, UID, 1));

        // 🔴 同一 uid 在另一个租户下必须仍有完整额度
        limiter.check(tenantB, UID, 1);
    }

    @Test
    @DisplayName("🔴 AC-LMT-001：计数键含 uid —— 一个用户打满不占用同租户其他用户额度")
    void countersAreNotSharedAcrossUsers() {
        limiter.check(tenantA, UID, 1);
        assertThrows(BusinessException.class, () -> limiter.check(tenantA, UID, 1));

        limiter.check(tenantA, OTHER_UID, 1);
    }

    @Test
    @DisplayName("🔴 限流键必须经 TenantCacheKeys 生成且含租户与 uid（AC-TEN-005 缓存维度完整）")
    void keyContainsTenantAndUid() {
        String key = cacheKeys.messageRateLimit(tenantA, UID, "m202608131900");

        assertTrue(key.contains(tenantA), "🔴 键必须含 tenantId：" + key);
        assertTrue(key.contains(String.valueOf(UID)), "🔴 键必须含 uid：" + key);
        assertTrue(key.contains("limit:msg"), key);
    }
}
