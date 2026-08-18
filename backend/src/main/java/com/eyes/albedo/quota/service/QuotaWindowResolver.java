package com.eyes.albedo.quota.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.quota.dto.QuotaWindow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 额度日窗口计算器（api-spec §7.15.6 / ADR-020 ③ / ⑧）。
 *
 * <p>🔴 <b>纯函数化</b>：输入 = 时刻（{@link Clock}）+ 租户 IANA 时区，输出 = 窗口标识与边界。
 * 时钟由构造注入正是为此（见 {@code config/TimeConfig} 对 ADR-017 口径差异的说明），
 * 🔴 本类生产代码中<b>禁止</b>出现 {@code Instant.now()} / {@code ZonedDateTime.now()}。
 *
 * <p>🔴 <b>时区非法 / 空一律 {@code 50003}，禁止回落 UTC</b>（AR-027 ①）：
 * 回落会把中国租户的"今日"边界静默挪到 08:00 —— 用户看到的"今日额度"与产品定义不符，
 * 而且<b>没有任何人会发现</b>（静默错误比失败更糟）。
 * 🔴 选 {@code 50003} 而非 {@code 30060}：{@code tenants.timezone} 是<b>平台主数据</b>
 * （DBA 维护、租户与终端用户均不可编辑），不属于 {@code 30060} 的"Agent/Skill/MCP/Tool
 * 配置或引用链"，用 {@code 30060} 会污染其 {@code violations} 契约。
 */
@Slf4j
@Component
public class QuotaWindowResolver {

    /**
     * 窗口标识前缀（与既有分钟窗 {@code m} 同体例；🔴 键格式见 §12.2）。
     */
    private static final String DAY_WINDOW_PREFIX = "d";

    private static final DateTimeFormatter DAY_PATTERN = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Clock clock;

    public QuotaWindowResolver(Clock clock) {
        this.clock = clock;
    }

    /** 当前时刻（🔴 唯一时间来源，供调用方与本类保持同一"现在"）。 */
    public Instant now() {
        return clock.instant();
    }

    /**
     * 解析当前额度日窗口。
     *
     * @param tenantId 仅用于 ERROR 日志定位（🔴 错误响应不含租户信息与非法取值）
     * @param timezone {@code tenants.timezone}（经 {@code TenantService} 取得）
     * @throws BusinessException 50003 时区为空或不是合法 IANA 时区（🔴 不回落 UTC）
     */
    public QuotaWindow resolve(String tenantId, String timezone) {
        return resolveAt(tenantId, timezone, now());
    }

    /**
     * 解析指定时刻所属的额度日窗口（🔴 供"跨日 / DST"确定性用例与快照复用）。
     */
    public QuotaWindow resolveAt(String tenantId, String timezone, Instant at) {
        ZoneId zone = requireZone(tenantId, timezone);
        LocalDate localDate = at.atZone(zone).toLocalDate();
        // 🔴 必须用 atStartOfDay(zone)：它按**日历规则**求"当天第一个时刻"，
        //    自动覆盖 23h/25h 与"当地 00:00 不存在"（返回 01:00）两种 DST 形态。
        Instant periodStart = localDate.atStartOfDay(zone).toInstant();
        Instant resetsAt = localDate.plusDays(1).atStartOfDay(zone).toInstant();
        return new QuotaWindow(zone.getId(), localDate,
                DAY_WINDOW_PREFIX + DAY_PATTERN.format(localDate), periodStart, resetsAt);
    }

    private ZoneId requireZone(String tenantId, String timezone) {
        if (timezone == null || timezone.isBlank()) {
            log.error("[QUOTA] 🔴 租户时区为空，额度日窗口无法判定（fail-closed，禁止回落 UTC）："
                    + "tenantId={}", tenantId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            // 🔴 只记 tenantId，**不回显非法值给用户**（错误响应不含配置内容，AC-QUOTA-014）
            log.error("[QUOTA] 🔴 租户时区不是合法 IANA 时区，额度日窗口无法判定"
                    + "（fail-closed，禁止回落 UTC）：tenantId={}", tenantId);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
