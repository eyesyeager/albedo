package com.eyes.albedo.metrics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 埋点<b>禁止字段/禁止取值</b>拦截器（🔴 api-spec §7.10.1「禁止字段」块的唯一实现）。
 *
 * <p><b>REQ-OBS-001 · AC-OBS-001 / PRD §15.2 末条</b>
 *
 * <p>🔴 <b>为什么必须有值级拦截（字段白名单不够）</b>：白名单只保证"没有 {@code messageContent}
 * 这个<b>字段</b>"，但客户端完全可以把消息正文塞进 {@code source} / {@code pagePath} /
 * {@code toolKey} 这些<b>合法字段</b>里 —— 一次前端疏忽就会让正文永久落库。
 * 因此判定是<b>双层</b>的：键名子串匹配（不区分大小写）+ 值级正则（手机号 / 邮箱 / 长数字 / JWT）。
 *
 * <p>🔴 <b>处置 = 整条 discarded</b>（不是"抹掉那个字段后入库"）：
 * 命中说明客户端埋点实现有缺陷，我们要让它<b>在计数上可见</b>并被修掉；
 * 而"悄悄抹一下继续存"会让缺陷长期存在，且我们无法判断到底丢了什么。
 *
 * <p>🔴 <b>不回显命中细节</b>（api-spec §7.10.1）：只记安全日志 + 计入 {@code discarded}，
 * 响应里没有任何"你命中了哪个字段"的信息 —— 否则本端点会变成"探测服务端脱敏规则"的工具。
 *
 * <p>与 {@code audit/AuditSanitizer} 的关系：同一套防御思路的<b>两处落地</b>
 * （审计是"命中即 redact"，埋点是"命中即整条丢弃"），
 * 🔴 规则清单有意保持一致（api-spec §7.10.1 明确要求口径与审计一致）。
 * 之所以不复用同一个类：{@code audit} 属 L0 基础层且<b>不得依赖业务包</b>，
 * 而处置策略（redact vs discard）恰恰是业务语义，硬合并会让两边的语义互相污染。
 */
public final class AnalyticsFieldGuard {

    private AnalyticsFieldGuard() {
    }

    /**
     * 禁止<b>键名</b>（不区分大小写的子串匹配，api-spec §7.10.1）。
     *
     * <p>⚠️ 有意"宁多勿少"：丢弃一条埋点毫无代价，落库一条正文是不可逆的安全事故。
     */
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "messagecontent", "content", "text", "prompt", "systemprompt",
            "skillinstruction", "instruction",
            "token", "authorization", "jwt", "credential", "apikey", "api_key",
            "secret", "password", "passwd", "cookie", "session",
            "phone", "mobile", "email", "idcard", "id_card", "bankcard", "bank_card");

    /** 手机号（中国大陆 11 位）。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 长数字串（身份证 / 银行卡）。 */
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{12,}(?!\\d)");
    /** JWT 形态（三段 base64url）。 */
    private static final Pattern JWT_LIKE =
            Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    /**
     * 未列入白名单的字段中是否出现<b>禁止键名</b>。
     *
     * <p>🔴 只要出现<b>任何</b>未知字段就已经说明客户端在传契约外内容；但只有命中禁止键名才丢弃 ——
     * 未知字段可能只是前端的版本差异（如新加了个无害的 {@code abTestGroup}），
     * 一律丢弃会让埋点在前后端版本不同步时大面积失效。
     */
    public static boolean hasForbiddenKey(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            if (forbiddenKey(entry.getKey())) {
                return true;
            }
            if (entry.getValue() != null && sensitiveValue(String.valueOf(entry.getValue()))) {
                return true;
            }
        }
        return false;
    }

    /** 键名是否命中禁止清单。 */
    public static boolean forbiddenKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String forbidden : FORBIDDEN_KEYS) {
            if (lower.contains(forbidden)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取值是否疑似敏感（手机号 / 邮箱 / 长数字 / JWT）。
     */
    public static boolean sensitiveValue(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return PHONE.matcher(value).find()
                || EMAIL.matcher(value).find()
                || LONG_DIGITS.matcher(value).find()
                || JWT_LIKE.matcher(value).find();
    }

    /**
     * 白名单内自由文本字段的联合判定（任一命中 → 整条丢弃）。
     */
    public static boolean anySensitive(String... values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (sensitiveValue(value)) {
                return true;
            }
        }
        return false;
    }
}
