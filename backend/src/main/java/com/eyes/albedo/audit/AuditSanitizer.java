package com.eyes.albedo.audit;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 审计入库前的脱敏与防御性校验（architecture.md §11.1.2 的 🔴 六类禁记清单）。
 *
 * <p>为什么必须有这一层（而不是"靠调用方自觉"）：审计是安全证据，一旦写入就<b>不可修改</b>
 * （仅追加），任何一次误写明文都会永久留在库里，且 AC-AUD-002 是硬性验收项。
 * 调用方遍布 mcp / tool / platform 多个模块，靠约定必然会漏；因此在<b>唯一写入通道</b>上做
 * 键名 + 值级双重拦截，属于 fail-closed 设计。
 *
 * <p>六类禁记项（任一出现即为安全缺陷）：
 * <pre>
 * 1. 凭据类：MCP 凭据明文/密文/密文片段/IV/Tag/密钥、api_key、authorization、jwt、cookie、session
 * 2. 消息与内部资产：user/assistant/tool 消息正文、systemPrompt、Skill 指令正文、历史摘要正文
 * 3. 工具明文：完整入参、完整结果（只允许脱敏摘要）
 * 4. 个人信息：完整手机号、完整邮箱、身份证/银行卡号
 * 5. 基础设施细节：MCP endpoint、内部 IP/端口、堆栈、SQL、JDBC URL
 * 6. 其他租户的标识与资源存在性
 * </pre>
 *
 * <p>处置策略：
 * <ul>
 *   <li>命中<b>禁记键名</b>（第 1/2/3/5 类的典型标识）→ 🔴 整个字段替换为 {@link #REDACTED}
 *       （fail-closed：宁可丢失可读性，也不赌"这次可能是安全的"）</li>
 *   <li>命中<b>值级模式</b>（手机号 / 邮箱 / 长数字串 / URL / JWT 形态）→ 就地掩码</li>
 *   <li>超长 → 按字段上限截断（不切出半个字符）</li>
 * </ul>
 */
public final class AuditSanitizer {

    private AuditSanitizer() {
    }

    /** 命中禁记键名后的替换值（🔴 不回显命中细节，避免把"命中了什么"也变成信息泄露）。 */
    public static final String REDACTED = "[redacted]";
    /** {@code reason} 列上限（architecture.md §11.1.2：≤200 字符）。 */
    public static final int REASON_MAX_CHARS = 200;
    /** {@code user_agent} 列上限。 */
    public static final int USER_AGENT_MAX_CHARS = 200;
    /** {@code object_id} 列上限。 */
    public static final int OBJECT_ID_MAX_CHARS = 64;

    /**
     * 禁记键名（不区分大小写的子串匹配，口径与 api-spec §7.10.1 的埋点禁止字段一致）。
     *
     * <p>⚠️ 有意保持"宁多勿少"：误伤一条 reason 的可读性远好于漏记一次明文泄露。
     */
    private static final List<String> FORBIDDEN_KEYS = List.of(
            "credential", "apikey", "api_key", "api-key", "authorization", "jwt", "bearer ",
            "secret", "password", "passwd", "token", "cookie", "session",
            "systemprompt", "system_prompt", "skillinstruction", "skill_instruction", "instruction",
            "messagecontent", "message_content",
            "idcard", "id_card", "bankcard", "bank_card",
            "jdbc:", "endpoint=", "\tat ", "select ", "insert ", "update ", "delete from"
    );

    /** 手机号（中国大陆 11 位）。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    /** 邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 长数字串（身份证 / 银行卡 / 订单号等）。 */
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{12,}(?!\\d)");
    /** JWT 形态（三段 base64url）。 */
    private static final Pattern JWT_LIKE =
            Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");
    /** URL（可能含 MCP endpoint / 内部地址）。 */
    private static final Pattern URL_LIKE = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");

    /**
     * 清洗 {@code reason}：禁记键名 → 整体 redact；值级模式 → 掩码；末尾截断到 200 字符。
     */
    public static String reason(String raw) {
        return sanitize(raw, REASON_MAX_CHARS);
    }

    /**
     * 清洗 {@code user_agent}：截断 200 字符并去除疑似 Token 片段。
     */
    public static String userAgent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String masked = JWT_LIKE.matcher(raw).replaceAll("***");
        return truncate(masked.trim(), USER_AGENT_MAX_CHARS);
    }

    /**
     * 清洗 {@code object_id}：只允许可安全外泄的对象标识（ID / 租户号 / Host / 作用域名）。
     *
     * <p>仍走同一套脱敏，避免有人把凭据当作 objectId 传进来。
     */
    public static String objectId(String raw) {
        return sanitize(raw, OBJECT_ID_MAX_CHARS);
    }

    /**
     * IP 按 /24（IPv4）或 /48（IPv6）截断入库（architecture.md §11.1.2）。
     *
     * <p>为什么截断：审计需要"大致来源"以支撑安全分析，但完整 IP 属个人可识别信息，
     * 且对排障价值有限。
     */
    public static String ip(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        // 代理链取第一个
        int comma = value.indexOf(',');
        if (comma > 0) {
            value = value.substring(0, comma).trim();
        }
        if (value.indexOf(':') >= 0) {
            String[] groups = value.split(":");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, groups.length); i++) {
                sb.append(groups[i]).append(':');
            }
            return truncate(sb.append(":/48").toString(), 64);
        }
        String[] octets = value.split("\\.");
        if (octets.length == 4) {
            return octets[0] + '.' + octets[1] + '.' + octets[2] + ".0/24";
        }
        return truncate(value, 64);
    }

    /**
     * digest 列的防御性处理：形态不合法（疑似明文）→ 🔴 强制摘要化。
     */
    public static String digestColumn(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (AuditDigest.isValidForm(raw)) {
            return raw;
        }
        // 调用方传了非摘要形态：不信任、不写入，转成摘要
        return AuditDigest.digest(raw);
    }

    private static String sanitize(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : FORBIDDEN_KEYS) {
            if (lower.contains(forbidden)) {
                return REDACTED;
            }
        }
        value = JWT_LIKE.matcher(value).replaceAll("***");
        value = URL_LIKE.matcher(value).replaceAll("***");
        value = EMAIL.matcher(value).replaceAll("***");
        value = PHONE.matcher(value).replaceAll("***");
        value = LONG_DIGITS.matcher(value).replaceAll("***");
        return truncate(value, maxChars);
    }

    /**
     * 按<b>码点</b>截断，🔴 不切出半个字符（否则落库出现乱码，且会破坏后续 digest 对照）。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxChars);
        return value.substring(0, end);
    }
}
