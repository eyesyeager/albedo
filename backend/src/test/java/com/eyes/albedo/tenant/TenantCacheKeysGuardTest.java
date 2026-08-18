package com.eyes.albedo.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 缓存键与「运行时状态键」保护测试（architecture.md §12.2 / api-spec §7.2.1）。
 *
 * <p>🔴 为什么单独测这个：缓存失效接口一旦误删运行时状态键，会造成
 * 幂等失效（重复建消息）、停止生成失灵、用户已提交的工具确认丢失、限流额度被免费重置 ——
 * 这些故障都很难在功能测试里被发现，但都是真实的业务破坏。
 */
class TenantCacheKeysGuardTest {

    private final TenantCacheKeys cacheKeys = new TenantCacheKeys("test");

    @Test
    @DisplayName("🔴 运行时状态键一律受保护：chat:idem / chat:cancel / tool:confirm / limit:msg")
    void runtimeStateKeysAreProtected() {
        assertTrue(cacheKeys.isProtectedRuntimeStateKey(
                cacheKeys.chatIdempotency("gift", 1L, "key-1")));
        assertTrue(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.chatCancel("gift", 5002L)));
        assertTrue(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.toolConfirm("gift", 9001L)));
        assertTrue(cacheKeys.isProtectedRuntimeStateKey(
                cacheKeys.messageRateLimit("gift", 1L, "202608131200")));
    }

    @Test
    @DisplayName("真正的缓存键不受保护（否则失效接口会变成空转）")
    void cacheKeysAreEvictable() {
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.tenantByHost("a.example.com")));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.tenantByCode("gift")));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.siteConfig("gift", 3L)));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.agentVersion("gift", 12L, 5L)));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.memberRole("gift", 10086L)));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.sysConfigItem("tool", "max_rounds")));
        // 会话摘要属缓存（更新即覆写），可失效
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(cacheKeys.chatSummary("gift", 1001L)));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(null));
        assertFalse(cacheKeys.isProtectedRuntimeStateKey(""));
    }

    @Test
    @DisplayName("键模式含租户维度且与固定键前缀一致（避免误删其他作用域）")
    void patternsCarryTenantDimension() {
        assertEquals("albedo:test:gift:site:config:*", cacheKeys.siteConfigPattern("gift"));
        assertEquals("albedo:test:gift:agent:version:*", cacheKeys.agentVersionPattern("gift", null));
        assertEquals("albedo:test:gift:agent:version:12:*", cacheKeys.agentVersionPattern("gift", 12L));
        assertEquals("albedo:test:gift:member:role:*", cacheKeys.memberRolePattern("gift"));
        assertEquals("albedo:test:platform:sysconfig:*", cacheKeys.sysConfigItemPattern(null));
        assertEquals("albedo:test:platform:sysconfig:tool:*", cacheKeys.sysConfigItemPattern("tool"));

        // 模式必须能匹配到对应固定键（前缀一致性）
        assertTrue(cacheKeys.siteConfig("gift", 3L)
                .startsWith(cacheKeys.siteConfigPattern("gift").replace("*", "")));
        assertTrue(cacheKeys.agentVersion("gift", 12L, 5L)
                .startsWith(cacheKeys.agentVersionPattern("gift", 12L).replace("*", "")));
    }

    @Test
    @DisplayName("🔴 K15 / V1.4.5：两个日额度运行时状态键同样受保护（删除等于免费重置额度）")
    void dailyQuotaKeysAreProtected() {
        String count = cacheKeys.dailyQuotaCount("gift", 10086L, "d20260818");
        String hold = cacheKeys.dailyQuotaHold("gift", 10086L, "d20260818");

        assertTrue(cacheKeys.isProtectedRuntimeStateKey(count),
                "🔴 quota:day 删除 = 当日已结算数清零（免费重置额度）：" + count);
        assertTrue(cacheKeys.isProtectedRuntimeStateKey(hold),
                "🔴 quota:hold 删除 = 在途预占凭空消失（同一用户并发突破日上限）：" + hold);
    }

    @Test
    @DisplayName("🔴 日额度键格式（architecture.md §12.2 登记）：含 tenantId + uid + 当地日期")
    void dailyQuotaKeyFormat() {
        assertEquals("albedo:test:gift:quota:day:10086:d20260818",
                cacheKeys.dailyQuotaCount("gift", 10086L, "d20260818"));
        assertEquals("albedo:test:gift:quota:hold:10086:d20260818",
                cacheKeys.dailyQuotaHold("gift", 10086L, "d20260818"));
        // 🔴 跨租户 / 跨用户必然是不同键（AC-QUOTA-002 的键层面保证）
        assertTrue(!cacheKeys.dailyQuotaCount("gift", 10086L, "d20260818")
                .equals(cacheKeys.dailyQuotaCount("redbook", 10086L, "d20260818")));
        assertTrue(!cacheKeys.dailyQuotaCount("gift", 10086L, "d20260818")
                .equals(cacheKeys.dailyQuotaCount("gift", 999L, "d20260818")));
    }

    @Test
    @DisplayName("工具确认信号键格式（architecture.md §12.2 登记）")
    void toolConfirmKeyFormat() {
        assertEquals("albedo:test:gift:tool:confirm:9001", cacheKeys.toolConfirm("gift", 9001L));
    }
}
