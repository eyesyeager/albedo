package com.eyes.albedo.quota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.quota.dto.EffectiveQuotaPolicy;
import com.eyes.albedo.quota.entity.TenantQuotaPolicy;
import com.eyes.albedo.quota.repository.TenantQuotaPolicyRepository;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

/**
 * 配置分层解析（🔴 AC-QUOTA-014 / AC-QUOTA-015 / K11 / K14，api-spec §7.15.5）。
 *
 * <p>逐条钉住四种取值形态：
 * <pre>
 * ① 覆盖列 IS NULL            → 继承平台默认
 * ② 覆盖列 NOT NULL 且合法     → 使用覆盖值（支持**部分覆盖**）
 * ③ 覆盖列 NOT NULL 但非法     → 🔴 50003，**绝不静默继承平台默认**
 * ④ 平台默认非法               → 50003（运行期兜底；启动期由 StartupChecker 拦死）
 * ⑤ effective_at 未到          → 不生效（由 Repository 的 where 过滤，本处断言"无行=纯平台默认"）
 * </pre>
 *
 * <p>🔴 <b>平台默认全部来自 mock 的 {@code BusinessConfig}</b>：这本身就是"代码里没有 3 / 50
 * 兜底"的守护 —— 若实现里写了默认值，把 mock 配成别的值时断言就会失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotaPolicyResolverTest {

    private static final String TENANT = "gift";
    private static final Instant NOW = Instant.parse("2026-08-18T03:21:07Z");

    @Mock
    private BusinessConfig businessConfig;
    @Mock
    private TenantQuotaPolicyRepository repository;

    private QuotaPolicyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new QuotaPolicyResolver(businessConfig, repository);
        // 平台默认（🔴 刻意用与库内默认不同的值，证明实现没有任何代码内兜底）
        platformDefaults(true, 7, true, 90);
        when(repository.findEffective(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
    }

    private void platformDefaults(boolean qpmEnabled, int qpm, boolean dailyEnabled, int daily) {
        when(businessConfig.requireBoolean(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.QPM_ENABLED))
                .thenReturn(qpmEnabled);
        when(businessConfig.requireInt(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE))
                .thenReturn(qpm);
        when(businessConfig.requireBoolean(ConfigKeys.GROUP_RATELIMIT,
                ConfigKeys.DAILY_QUOTA_ENABLED)).thenReturn(dailyEnabled);
        when(businessConfig.requireInt(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT))
                .thenReturn(daily);
    }

    private void override(TenantQuotaPolicy row) {
        when(repository.findEffective(eq(NOW), any(Pageable.class))).thenReturn(List.of(row));
    }

    private TenantQuotaPolicy row(Boolean qpmEnabled, Integer qpmLimit,
                                  Boolean dailyEnabled, Integer dailyLimit) {
        TenantQuotaPolicy policy = new TenantQuotaPolicy();
        policy.setId(1001L);
        policy.setEffectiveAt(NOW.minusSeconds(1));
        policy.setQpmEnabled(qpmEnabled);
        policy.setQpmLimit(qpmLimit);
        policy.setDailyQuotaEnabled(dailyEnabled);
        policy.setDailyQuotaLimit(dailyLimit);
        return policy;
    }

    @Test
    @DisplayName("🔴 无租户覆盖行 → 全部继承平台默认（sys_config: ratelimit.*）")
    void inheritsPlatformDefaultsWhenNoOverrideRow() {
        EffectiveQuotaPolicy policy = resolver.resolve(TENANT, NOW);

        assertEquals(new EffectiveQuotaPolicy(true, 7, true, 90), policy);
    }

    @Test
    @DisplayName("🔴 AC-QUOTA-015：逐字段覆盖 —— 只覆盖 QPM 时日限额仍继承平台默认（部分覆盖）")
    void partialOverrideInheritsRemainingFields() {
        override(row(null, 5, null, null));

        EffectiveQuotaPolicy policy = resolver.resolve(TENANT, NOW);

        assertEquals(5, policy.qpmLimit(), "🔴 覆盖列非 NULL → 用覆盖值");
        assertEquals(90, policy.dailyQuotaLimit(), "🔴 NULL 列 → 继承平台默认（这是未配置的唯一表达）");
        assertTrue(policy.qpmEnabled());
        assertTrue(policy.dailyQuotaEnabled());
    }

    @Test
    @DisplayName("🔴 开关覆盖：daily_quota_enabled=false → 不受每日限额约束（不影响 QPM）")
    void enabledFlagsCanBeOverriddenIndependently() {
        override(row(null, null, false, null));

        EffectiveQuotaPolicy policy = resolver.resolve(TENANT, NOW);

        assertFalse(policy.dailyQuotaEnabled());
        assertTrue(policy.qpmEnabled(), "🔴 关闭日额度不得连带关闭 QPM");
        assertEquals(7, policy.qpmLimit());
    }

    @Test
    @DisplayName("🔴 AC-QUOTA-014：租户覆盖非法（qpm_limit=0）→ 50003，**禁止静默继承平台默认**")
    void illegalQpmOverrideFailsClosed() {
        override(row(null, 0, null, null));

        BusinessException e = assertThrows(BusinessException.class,
                () -> resolver.resolve(TENANT, NOW));
        assertEquals(ErrorCode.INTERNAL_ERROR, e.getCode());
        assertFalse(e.getMessage().contains("qpm_limit"),
                "🔴 错误响应不得泄露表名 / 列名：" + e.getMessage());
    }

    @Test
    @DisplayName("🔴 AC-QUOTA-014：租户覆盖非法（daily_quota_limit=-1）→ 50003，不静默继承")
    void illegalDailyOverrideFailsClosed() {
        override(row(null, null, null, -1));

        assertEquals(ErrorCode.INTERNAL_ERROR,
                assertThrows(BusinessException.class, () -> resolver.resolve(TENANT, NOW)).getCode());
    }

    @Test
    @DisplayName("🔴 平台默认非法（daily_quota_limit=0）→ 运行期兜底 50003（启动期由 StartupChecker 拦死）")
    void illegalPlatformDefaultFailsClosed() {
        platformDefaults(true, 7, true, 0);

        assertEquals(ErrorCode.INTERNAL_ERROR,
                assertThrows(BusinessException.class, () -> resolver.resolve(TENANT, NOW)).getCode());
    }

    @Test
    @DisplayName("🔴 K14：effective_at 未到的行不参与解析（读取时过滤，🔴 无定时任务）")
    void futureRowIsNotEffective() {
        // 未来行不会被 findEffective(now) 返回 —— 这里以"仓库返回空"表达该过滤语义，
        // where effective_at <= :now 的正确性由 QuotaPolicyIT 对真实库断言
        when(repository.findEffective(eq(NOW), any(Pageable.class))).thenReturn(List.of());

        assertEquals(7, resolver.resolve(TENANT, NOW).qpmLimit());
    }
}
