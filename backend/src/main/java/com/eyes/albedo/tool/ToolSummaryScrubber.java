package com.eyes.albedo.tool;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 摘要脱敏（api-spec §5.4.3，唯一实现）。
 *
 * <p>🔴 <b>顺序不可颠倒</b>（§5.4.3 第 1 条 / ADR-011 第 4 条）：
 * <b>先按键名/值级脱敏 → 再截断 → 再落库/下发</b>。
 * 若先截断再脱敏，敏感值可能刚好落在保留段里而躲过脱敏 —— 那就是数据泄露。
 *
 * <p>🔴 <b>一份摘要，四处复用</b>（§5.4.3 第 6 条）：
 * SSE {@code tool} 事件、{@code tool_calls} 落库、审计 {@code beforeDigest}/{@code afterDigest}、
 * {@code GET /conversations/{id}/tool-calls} 查询接口 ——
 * 不存在"落库明文、下发脱敏"的双轨（双轨等于把明文留在库里，只是没人看见）。
 *
 * <p>脱敏规则：
 * <ol>
 *   <li>敏感键名（大小写不敏感、<b>含子串即命中</b>）整值替换为 {@code ***}</li>
 *   <li>疑似个人信息保留首尾：手机号 → 前 1 + {@code ***} + 后 2；
 *       邮箱 → 首字符 + {@code ***} + {@code @域名}；≥12 位长数字 → 只留后 4</li>
 *   <li>其余字符串超 32 字符 → {@code 前 12 字符…后 8 字符}</li>
 * </ol>
 */
@Slf4j
@Component
public class ToolSummaryScrubber {

    /** 敏感键名（🔴 含子串即命中，大小写不敏感）。 */
    private static final Set<String> SENSITIVE_KEY_PARTS = Set.of(
            "password", "passwd", "secret", "token", "authorization", "credential",
            "apikey", "api_key", "accesskey", "privatekey", "signature", "cookie", "session");

    /** 整值遮蔽标记。 */
    public static final String MASK = "***";

    /** 其余字符串值的保留长度阈值（api-spec §5.4.3 第 4 条）。 */
    private static final int LONG_VALUE_THRESHOLD = 32;
    private static final int LONG_VALUE_HEAD = 12;
    private static final int LONG_VALUE_TAIL = 8;

    /** 中国大陆手机号。 */
    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    /** 邮箱。 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 身份证 / 银行卡类长数字（≥12 位）。 */
    private static final Pattern LONG_DIGITS = Pattern.compile("^\\d{12,}$");

    private final ObjectMapper objectMapper;

    public ToolSummaryScrubber(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 入参摘要（🔴 只输出脱敏后的 {@code k=v} 序列，不输出完整 JSON）。
     *
     * <p>形态示例：{@code orderId=A***23, amount=***}（api-spec §7.9.1 示例）。
     */
    public String argsSummary(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "";
        }
        JsonNode root = parse(argumentsJson);
        if (root == null || !root.isObject()) {
            // 非对象入参：整体按值脱敏（不回显结构）
            return scrubValue("args", argumentsJson);
        }
        StringBuilder out = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(field.getKey()).append('=')
                    .append(scrubNode(field.getKey(), field.getValue()));
        }
        return out.toString();
    }

    /**
     * 结果摘要（结果可能不是 JSON，故按"能解析就逐键脱敏，不能解析就按值脱敏"处理）。
     *
     * <p>⚠️ 注意 {@code "13812345678"} 这类<b>裸标量</b>本身也是合法 JSON（number），
     * 因此必须显式判断"根节点是不是容器"，否则会走到 {@code asText()} 原样输出
     * —— 本轮由单测发现的真实漏洞。
     */
    public String resultSummary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        JsonNode root = parse(content);
        if (root == null || (!root.isObject() && !root.isArray())) {
            return scrubValue("result", content);
        }
        return scrubNode("result", root);
    }

    /**
     * 递归脱敏（🔴 键名命中即整值遮蔽，不再深入 —— 否则子字段仍可能泄露）。
     */
    private String scrubNode(String key, JsonNode node) {
        if (isSensitiveKey(key)) {
            return MASK;
        }
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder out = new StringBuilder("{");
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(field.getKey()).append(':')
                        .append(scrubNode(field.getKey(), field.getValue()));
            }
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (JsonNode item : node) {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(scrubNode(key, item));
            }
            return out.append(']').toString();
        }
        if (node.isTextual()) {
            return scrubValue(key, node.asText());
        }
        // 🔴 数值/布尔也要过值级脱敏：手机号、银行卡号在 JSON 里常被写成 **number**
        //    （如 {"phone":13812345678}）。只对 textual 脱敏会让这类值原样泄露。
        return scrubValue(key, node.asText());
    }

    /**
     * 值级脱敏（手机号 / 邮箱 / 长数字 / 超长字符串）。
     */
    public String scrubValue(String key, String value) {
        if (isSensitiveKey(key)) {
            return MASK;
        }
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (PHONE.matcher(value).matches()) {
            // 保留前 1 位 + *** + 后 2 位
            return value.charAt(0) + MASK + value.substring(value.length() - 2);
        }
        if (EMAIL.matcher(value).matches()) {
            int at = value.indexOf('@');
            return value.charAt(0) + MASK + value.substring(at);
        }
        if (LONG_DIGITS.matcher(value).matches()) {
            return MASK + value.substring(value.length() - 4);
        }
        int count = value.codePointCount(0, value.length());
        if (count > LONG_VALUE_THRESHOLD) {
            int headEnd = value.offsetByCodePoints(0, LONG_VALUE_HEAD);
            int tailStart = value.offsetByCodePoints(0, count - LONG_VALUE_TAIL);
            return value.substring(0, headEnd) + ToolResultTruncator.ELLIPSIS
                    + value.substring(tailStart);
        }
        return value;
    }

    /**
     * 键名是否敏感（🔴 含子串即命中：{@code userToken} / {@code X-Api-Key} 都要中）。
     *
     * <p>🔴 <b>先剥离分隔符再匹配</b>（本轮由单测发现的真实漏洞）：
     * {@code X-Api-Key} 小写后是 {@code x-api-key}，既不含 {@code apikey} 也不含 {@code api_key}，
     * 按字面匹配会<b>放过</b>它 —— 而这正是最常见的凭据头名。
     * 归一化为 {@code xapikey} 后即可命中 {@code apikey}，同时覆盖
     * {@code access-key} / {@code private.key} / {@code Session_ID} 等写法。
     */
    public boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String part : SENSITIVE_KEY_PARTS) {
            if (normalized.contains(part.replace("_", ""))) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
