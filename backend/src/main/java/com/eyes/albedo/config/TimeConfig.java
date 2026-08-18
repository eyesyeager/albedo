package com.eyes.albedo.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源装配（🔴 ADR-020 ⑧ 的落点，注入范围<b>严格受限</b>）。
 *
 * <p><b>为什么要有一个 {@link Clock} Bean</b>：限流与额度的<b>窗口计算</b>本质是
 * "给定时刻 + 时区 → 该时刻属于哪个窗口、窗口边界在哪"这一<b>纯函数</b>，
 * 而它的正确性依据是<b>本地时区的日历规则</b>（含夏令时 23h / 25h、"当地 00:00 不存在"）。
 * 真实时间下这些分支<b>物理上无法测试</b>（跨当地零点只能等到午夜，测 DST 更不可能），
 * 因此把时刻作为<b>输入</b>注入是设计改进而非测试钩子。
 *
 * <p>🔴 <b>与 ADR-017 落点 #12ⓒ 的口径差异是有意的</b>（{@code GenerationDeadline} 坚持用
 * 不可变的 {@code System.nanoTime()} 并拒绝植入时钟钩子）：
 * <pre>
 * 维度        GenerationDeadline（ADR-017）     限流/额度窗口（ADR-020）
 * 被测对象    **时长 / 预算**（"还剩几秒"）      **日历边界身份**（"这是哪个窗口"）
 * 正确性依据  nanoTime 的**单调性**             本地时区的**日历规则**（含 DST）
 * 是否业务判定 否（内部预算控制）                🔴 是（窗口标识就是 Redis 键名）
 * 注入的性质  测试钩子（预算判定权交给替身）      纯函数化（输入=时刻+时区）
 * </pre>
 * 🔴 统一判据：<b>注入是否把"业务判定"变成可被替身操纵的东西</b> —— 前者是，后者不是。
 *
 * <p>🔴 <b>注入范围仅限两处</b>（{@code chat/service/MessageRateLimiter} 与
 * {@code quota/service/QuotaWindowResolver}），🔴 不做全局改造；
 * 这两处生产代码中<b>禁止</b>再出现 {@code Instant.now()} / {@code ZonedDateTime.now()}。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟。
     *
     * <p>🔴 固定 {@code systemUTC()} 而不是 {@code systemDefaultZone()}：所有"绝对时刻"一律 UTC，
     * 时区只在<b>窗口边界计算</b>时按租户 {@code tenants.timezone} 参与（api-spec §7.15.6）；
     * 让时钟自带默认时区会给"服务器时区影响业务判定"留后门。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
