package com.eyes.albedo.quota.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 一个<b>额度日窗口</b>（api-spec §7.15.6 / ADR-020 ③）。
 *
 * <p>🔴 <b>窗口是"租户当地日历日"，不是 UTC 日</b>：
 * <pre>
 * zone        = ZoneId.of(tenants.timezone)          🔴 非法即 50003（**禁止回落 UTC**）
 * localDate   = 当前时刻在 zone 下的本地日期
 * periodStart = localDate.atStartOfDay(zone)
 * resetsAt    = localDate.plusDays(1).atStartOfDay(zone)
 * </pre>
 *
 * <p>🔴 <b>DST 硬要求（用 {@code atStartOfDay(zone)} 而非"UTC 日 + 固定偏移"的全部原因）</b>：
 * <ul>
 *   <li>一个额度日的长度可以是 <b>23h / 24h / 25h</b>，🔴 严禁以固定 86400 秒推算 {@code resetsAt}</li>
 *   <li>"当地 00:00 不存在"（spring-forward 落在 00:00，如 {@code America/Havana}）时
 *       {@code atStartOfDay(zone)} 返回当天<b>实际第一个时刻</b>（01:00），
 *       且相邻两日窗口仍<b>首尾相接、不重叠不留空</b>
 *       （因为第 N 日的 {@code resetsAt} 与第 N+1 日的 {@code periodStart} 是<b>同一个表达式</b>）</li>
 * </ul>
 *
 * <p>🔴 {@link #dateKey()} 是 Redis 键里的窗口标识（{@code d} + 当地 {@code yyyyMMdd}），
 * 与既有分钟窗 {@code m} + {@code yyyyMMddHHmm} 同体例；
 * ⚠️ 分钟窗<b>仍用 UTC 纪元</b>（ADR-020 ③ 明确追认）—— "一分钟"与时区无关，
 * 时区只影响<b>日历日</b>边界。
 *
 * @param timezone    租户 IANA 时区（原样回显给前端，🔴 前端禁止用浏览器本地时区推算）
 * @param localDate   租户当地日历日
 * @param dateKey     窗口标识（{@code d20260818}）—— Redis 键片段，🔴 不对外下发
 * @param periodStart 该额度日起点（UTC 绝对时刻）
 * @param resetsAt    下一个租户当地零点（UTC 绝对时刻，🔴 必须晚于 {@code periodStart}）
 */
public record QuotaWindow(String timezone,
                          LocalDate localDate,
                          String dateKey,
                          Instant periodStart,
                          Instant resetsAt) {
}
