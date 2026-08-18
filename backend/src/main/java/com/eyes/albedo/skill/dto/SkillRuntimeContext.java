package com.eyes.albedo.skill.dto;

import java.time.Instant;

import com.eyes.albedo.tenant.TenantContext;

/**
 * Skill 变量替换所需的运行时上下文（承载平台内置只读变量的取值来源）。
 *
 * <p>为什么要显式传入而不是内部读 ThreadLocal：注入发生在<b>异步生成线程</b>
 * （architecture.md §9.5.2 第 3 条明令"业务判断禁止依赖隐式上下文"）。
 * 显式参数使同一段逻辑在 Servlet 线程（校验入口）与异步线程（生成）中行为一致，
 * 也让单测无需搭 ThreadLocal 即可断言。
 *
 * <p>🔴 {@code locale} / {@code timezone} 由调用方从租户主数据取；缺省时为空串，
 * 此时若 Skill 正文引用 <code>{{locale}}</code> 且该变量为必填语义，将得到确定的
 * {@code 30060}（而不是静默注入空值）—— 见 {@code SkillVariableResolver}。
 *
 * @param tenantId 租户号（内置变量 {@code tenantId}）
 * @param locale   租户语言（内置变量 {@code locale}）
 * @param timezone 租户时区（内置变量 {@code timezone}）
 * @param now      当前时刻（内置变量 {@code nowIso}，ISO-8601 UTC）
 */
public record SkillRuntimeContext(String tenantId,
                                  String locale,
                                  String timezone,
                                  Instant now) {

    public SkillRuntimeContext {
        tenantId = tenantId == null ? "" : tenantId;
        locale = locale == null ? "" : locale;
        timezone = timezone == null ? "" : timezone;
        now = now == null ? Instant.now() : now;
    }

    /**
     * 仅有租户号的上下文（{@code locale} / {@code timezone} 留空）。
     *
     * <p>用于"Skill 未引用 locale / timezone"的常见情形，避免为内置变量白付一次租户表查询。
     */
    public static SkillRuntimeContext ofTenant(String tenantId) {
        return new SkillRuntimeContext(tenantId, "", "", Instant.now());
    }

    /**
     * 从当前租户上下文构造（🔴 只允许在 Servlet 线程使用）。
     */
    public static SkillRuntimeContext fromTenantContext() {
        return ofTenant(TenantContext.require().tenantId());
    }

    public SkillRuntimeContext withTenantProfile(String newLocale, String newTimezone) {
        return new SkillRuntimeContext(tenantId, newLocale, newTimezone, now);
    }
}
