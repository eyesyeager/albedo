package com.eyes.albedo.quota.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.quota.dto.QuotaWindow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 额度日窗口计算（🔴 AC-QUOTA-003 / K10 的确定性判据，api-spec §7.15.6）。
 *
 * <p>🔴 <b>为什么这些用例只能靠注入 {@code Clock} 才存在</b>：真实时间下"跨当地零点"只能等到午夜，
 * 而"DST 日长 23h / 25h"与"当地 00:00 不存在"更是<b>物理上无法等到</b>。
 * 时钟注入把日历计算变成纯函数，判据因此可被逐条断言（ADR-020 ⑧）。
 */
class QuotaWindowResolverTest {

    private static final String TENANT = "gift";

    private QuotaWindowResolver at(String instant) {
        return new QuotaWindowResolver(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("🔴 窗口是**租户当地**日历日，不是 UTC 日（Asia/Shanghai 的 UTC 16:30 已是次日）")
    void windowFollowsTenantLocalDate() {
        QuotaWindow window = at("2026-08-18T16:30:00Z").resolve(TENANT, "Asia/Shanghai");

        assertEquals(LocalDate.of(2026, 8, 19), window.localDate(),
                "🔴 UTC 日是 08-18，但租户当地已是 08-19");
        assertEquals("d20260819", window.dateKey());
        assertEquals(Instant.parse("2026-08-18T16:00:00Z"), window.periodStart(),
                "periodStart = 当地 08-19 00:00 = UTC 08-18T16:00Z");
        assertEquals(Instant.parse("2026-08-19T16:00:00Z"), window.resetsAt());
        assertEquals("Asia/Shanghai", window.timezone());
    }

    @Test
    @DisplayName("🔴 跨当地零点：窗口标识与边界切换、resetsAt 恒晚于 periodStart")
    void windowRollsOverAtLocalMidnight() {
        QuotaWindow before = at("2026-08-18T15:59:59Z").resolve(TENANT, "Asia/Shanghai");
        QuotaWindow after = at("2026-08-18T16:00:00Z").resolve(TENANT, "Asia/Shanghai");

        assertEquals("d20260818", before.dateKey());
        assertEquals("d20260819", after.dateKey());
        // 🔴 首尾相接：前一日的 resetsAt 就是后一日的 periodStart（不重叠、不留空）
        assertEquals(before.resetsAt(), after.periodStart());
        assertTrue(after.resetsAt().isAfter(after.periodStart()));
    }

    @Test
    @DisplayName("🔴 K10 / DST：spring-forward 日额度日长 **23h**（严禁按固定 86400 推算）")
    void springForwardDayIs23Hours() {
        // 2026-03-08 America/New_York：当地 02:00 → 03:00，当天只有 23 小时
        QuotaWindow window = at("2026-03-08T12:00:00Z").resolve(TENANT, "America/New_York");

        assertEquals(LocalDate.of(2026, 3, 8), window.localDate());
        assertEquals(Duration.ofHours(23),
                Duration.between(window.periodStart(), window.resetsAt()),
                "🔴 额度日长度必须按日历规则计算（23h），不得写死 86400");
    }

    @Test
    @DisplayName("🔴 K10 / DST：fall-back 日额度日长 **25h**")
    void fallBackDayIs25Hours() {
        // 2026-11-01 America/New_York：当地 02:00 → 01:00，当天有 25 小时
        QuotaWindow window = at("2026-11-01T12:00:00Z").resolve(TENANT, "America/New_York");

        assertEquals(LocalDate.of(2026, 11, 1), window.localDate());
        assertEquals(Duration.ofHours(25),
                Duration.between(window.periodStart(), window.resetsAt()));
    }

    @Test
    @DisplayName("🔴 K10 / DST：\"当地 00:00 不存在\"时取当天第一个真实时刻，且相邻窗口**首尾相接**")
    void handlesMissingLocalMidnight() {
        ZoneId zone = ZoneId.of("America/Havana");
        LocalDate date = LocalDate.of(2026, 3, 8);
        ZoneOffsetTransition gap = zone.getRules().getTransition(date.atStartOfDay());
        assertNotNull(gap, "前置条件：该日当地 00:00 必须落在 DST 空档（依赖 JDK tzdata）");
        assertTrue(gap.isGap(), "前置条件：必须是 spring-forward 空档");

        QuotaWindow missing = at("2026-03-08T12:00:00Z").resolve(TENANT, zone.getId());
        QuotaWindow previous = at("2026-03-07T12:00:00Z").resolve(TENANT, zone.getId());

        assertEquals(gap.getInstant(), missing.periodStart(),
                "🔴 00:00 不存在时必须取当天实际第一个时刻（01:00）");
        assertFalse(LocalTime.MIDNIGHT.equals(missing.periodStart().atZone(zone).toLocalTime()),
                "🔴 该日的 periodStart 当地时间不可能是 00:00");
        assertEquals(date, missing.periodStart().atZone(zone).toLocalDate(),
                "🔴 periodStart 仍必须落在当天");
        // 🔴 相邻两日窗口首尾相接、不重叠不留空（这是 atStartOfDay 同一表达式的必然结果）
        assertEquals(previous.resetsAt(), missing.periodStart());
        assertTrue(missing.resetsAt().isAfter(missing.periodStart()));
    }

    @Test
    @DisplayName("🔴 AC-QUOTA-014：时区非法 → 50003 且**不回落 UTC**、错误信息不回显非法取值")
    void illegalTimezoneFailsClosed() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> at("2026-08-18T12:00:00Z").resolve(TENANT, "Asia/Atlantis"));

        assertEquals(ErrorCode.INTERNAL_ERROR, e.getCode(),
                "🔴 必须是 50003（tenants.timezone 是平台主数据，用 30060 会污染 violations 契约）");
        assertFalse(e.getMessage().contains("Atlantis"),
                "🔴 错误响应不得回显非法配置取值：" + e.getMessage());
    }

    @Test
    @DisplayName("🔴 时区为空 / 空白 → 50003（绝不静默按 UTC 继续判定当日边界）")
    void blankTimezoneFailsClosed() {
        assertEquals(ErrorCode.INTERNAL_ERROR, assertThrows(BusinessException.class,
                () -> at("2026-08-18T12:00:00Z").resolve(TENANT, null)).getCode());
        assertEquals(ErrorCode.INTERNAL_ERROR, assertThrows(BusinessException.class,
                () -> at("2026-08-18T12:00:00Z").resolve(TENANT, "   ")).getCode());
    }

    @Test
    @DisplayName("now() 来自注入的 Clock（生产代码禁止 Instant.now()）")
    void nowComesFromInjectedClock() {
        assertEquals(Instant.parse("2026-08-18T03:21:07Z"),
                at("2026-08-18T03:21:07Z").now());
    }
}
