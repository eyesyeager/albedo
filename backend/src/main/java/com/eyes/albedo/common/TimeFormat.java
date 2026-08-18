package com.eyes.albedo.common;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 对外时间序列化（api-spec.md §1.1）。
 *
 * <p>契约：ISO-8601 UTC，<b>固定 3 位毫秒</b>，形如 {@code 2026-08-12T10:00:00.000Z}。
 *
 * <p>为什么不依赖 Jackson 默认 {@code Instant} 序列化：Jackson 会在毫秒为 0 时省略小数部分
 * （输出 {@code 2026-08-12T10:00:00Z}），与契约样例不一致。因此 DTO 中时间字段统一声明为
 * {@code String}，由本工具转换，保证前端解析与断言稳定。
 */
public final class TimeFormat {

    private static final DateTimeFormatter ISO_UTC_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private TimeFormat() {
    }

    /**
     * 转为对外 ISO-8601 UTC 字符串；{@code null} 原样返回 {@code null}（Jackson 会按 non_null 省略）。
     */
    public static String iso(Instant instant) {
        return instant == null ? null : ISO_UTC_MILLIS.format(instant);
    }
}
