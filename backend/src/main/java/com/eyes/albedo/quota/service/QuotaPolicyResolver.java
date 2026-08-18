package com.eyes.albedo.quota.service;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.quota.dto.EffectiveQuotaPolicy;
import com.eyes.albedo.quota.entity.TenantQuotaPolicy;
import com.eyes.albedo.quota.repository.TenantQuotaPolicyRepository;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 有效额度策略解析器（🔴 <b>租户覆盖的唯一入口</b>，api-spec §7.15.5 / ADR-020 ①）。
 *
 * <p>🔴 <b>为什么租户覆盖不走 {@code BusinessConfig}</b>（明确否决 {@code requireIntForTenant}）：
 * {@code BusinessConfig} 是 {@code sys_config}（<b>平台作用域</b>）的读取器，把租户维度塞进它会让
 * §7 的配置域边界失守，并诱导后续把任意租户配置塞进平台表。平台默认仍走
 * {@code BusinessConfig.requireInt/requireBoolean}（缺配即 {@code 50003}，语义不变）。
 *
 * <p>🔴 <b>逐字段解析规则</b>（AC-QUOTA-014 / 015）：
 * <pre>
 * ① 覆盖列 IS NULL            → 继承平台默认（"未配置"的唯一表达）
 * ② 覆盖列 NOT NULL 且合法     → 使用覆盖值
 * ③ 覆盖列 NOT NULL 但非法(<1) → 🔴 50003 + ERROR 日志，**禁止静默继承平台默认**
 *                               （静默继承会永久掩盖误配置）
 * ④ 平台默认缺失/不可解析/越界 → 启动期即拒绝启动（StartupChecker）；
 *                               运行期兜底仍为 BusinessConfig.requireXxx → 50003
 * ⑤ 生效时间：读取时取 effective_at <= now 的**最新一行**（🔴 无定时任务、无调度器）
 * </pre>
 * 🔴 代码中<b>不得</b>出现平台默认值字面量作为兜底。
 *
 * <p>🔴 <b>零缓存</b>（§12.1.1）：每次准入 1 次索引点查（{@code uk_tenant_effective} 覆盖）。
 * 这样 AC-QUOTA-015「管理员上调额度并生效后重新查询即恢复发送」<b>免实现</b>，
 * 也不需要任何失效逻辑；🔴 <b>禁止</b>自造 {@code quota.policy_cache_ttl_seconds} 之类的键。
 */
@Slf4j
@Service
public class QuotaPolicyResolver {

    /** 阈值下界（🔴 契约常量：阈值必须至少允许一次请求，与 {@code StartupChecker} 同源）。 */
    private static final int MIN_LIMIT = 1;

    private final BusinessConfig businessConfig;
    private final TenantQuotaPolicyRepository policyRepository;

    public QuotaPolicyResolver(BusinessConfig businessConfig,
                               TenantQuotaPolicyRepository policyRepository) {
        this.businessConfig = businessConfig;
        this.policyRepository = policyRepository;
    }

    /**
     * 解析当前租户的有效策略（🔴 <b>一次准入只调用一次</b>，结果快照传给后续各步）。
     *
     * @param tenantId 仅用于日志（🔴 数据隔离由 discriminator 保证，不作为查询条件）
     * @param now      判定时刻（UTC，由 {@code QuotaWindowResolver.now()} 提供）
     * @throws BusinessException 50003 平台默认缺失/非法，或租户覆盖非 {@code NULL} 但非法
     */
    @Transactional(readOnly = true)
    public EffectiveQuotaPolicy resolve(String tenantId, Instant now) {
        // 平台默认（🔴 缺失 / 不可解析一律 50003，无代码默认值兜底）
        boolean qpmEnabled = businessConfig.requireBoolean(ConfigKeys.GROUP_RATELIMIT,
                ConfigKeys.QPM_ENABLED);
        int qpmLimit = requirePlatformLimit(ConfigKeys.MESSAGE_PER_MINUTE,
                businessConfig.requireInt(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE));
        boolean dailyEnabled = businessConfig.requireBoolean(ConfigKeys.GROUP_RATELIMIT,
                ConfigKeys.DAILY_QUOTA_ENABLED);
        int dailyLimit = requirePlatformLimit(ConfigKeys.DAILY_QUOTA_LIMIT,
                businessConfig.requireInt(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT));

        TenantQuotaPolicy override = currentOverride(now);
        if (override == null) {
            return new EffectiveQuotaPolicy(qpmEnabled, qpmLimit, dailyEnabled, dailyLimit);
        }
        // 🔴 逐字段：NULL = 继承；非 NULL = 覆盖（非法即 50003，绝不静默继承）
        if (override.getQpmEnabled() != null) {
            qpmEnabled = override.getQpmEnabled();
        }
        if (override.getQpmLimit() != null) {
            qpmLimit = requireOverrideLimit(tenantId, override, "qpm_limit", override.getQpmLimit());
        }
        if (override.getDailyQuotaEnabled() != null) {
            dailyEnabled = override.getDailyQuotaEnabled();
        }
        if (override.getDailyQuotaLimit() != null) {
            dailyLimit = requireOverrideLimit(tenantId, override, "daily_quota_limit",
                    override.getDailyQuotaLimit());
        }
        return new EffectiveQuotaPolicy(qpmEnabled, qpmLimit, dailyEnabled, dailyLimit);
    }

    /**
     * 当前生效的租户覆盖行（{@code null} = 该租户从未配置覆盖）。
     *
     * <p>🔴 {@code effective_at} 在<b>未来</b>的行对当前尝试完全无效（AC-QUOTA-015）。
     */
    private TenantQuotaPolicy currentOverride(Instant now) {
        List<TenantQuotaPolicy> rows = policyRepository.findEffective(now, PageRequest.of(0, 1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 平台默认阈值的运行期兜底校验（🔴 正常情况下由 {@code StartupChecker} 在启动期拦死）。
     */
    private int requirePlatformLimit(String key, int value) {
        if (value < MIN_LIMIT) {
            log.error("[QUOTA] 🔴 平台默认阈值非法（运行期兜底，正常应在启动期被拦下）："
                    + "sys_config[{}.{}] 取值小于 {}", ConfigKeys.GROUP_RATELIMIT, key, MIN_LIMIT);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return value;
    }

    /**
     * 租户覆盖阈值校验：🔴 非法即 {@code 50003}，<b>绝不静默继承平台默认</b>（AC-QUOTA-014）。
     *
     * <p>🔴 日志只记 tenantId / 列名 / 策略行 id，<b>不回显取值</b>，
     * 错误响应更不含表名、键名与内部取值。
     */
    private int requireOverrideLimit(String tenantId, TenantQuotaPolicy row, String column,
                                     int value) {
        if (value < MIN_LIMIT) {
            log.error("[QUOTA] 🔴 租户额度覆盖非法，本次请求 fail-closed（禁止静默继承平台默认）："
                            + "tenantId={} policyId={} column={}",
                    tenantId, row.getId(), column);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        return value;
    }
}
