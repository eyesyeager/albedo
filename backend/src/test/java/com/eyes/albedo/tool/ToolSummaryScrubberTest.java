package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摘要脱敏单测（api-spec §5.4.3 逐条）。
 *
 * <p>🔴 这组断言的意义：摘要会同时进入 SSE 事件、{@code tool_calls} 落库、审计与查询接口
 * （§5.4.3 第 6 条"一份摘要四处复用"）。任何漏脱敏都是<b>四处同时泄露</b>。
 */
class ToolSummaryScrubberTest {

    private final ToolSummaryScrubber scrubber = new ToolSummaryScrubber(new ObjectMapper());

    @Test
    @DisplayName("🔴 §5.4.3-2：敏感键名（含子串、大小写不敏感）整值替换为 ***")
    void sensitiveKeysMasked() {
        String summary = scrubber.argsSummary("{\"password\":\"p@ss\",\"userToken\":\"abc123\","
                + "\"X-Api-Key\":\"k1\",\"AccessKeyId\":\"ak\",\"sessionId\":\"s1\","
                + "\"orderId\":\"A12345\"}");

        assertTrue(summary.contains("password=" + ToolSummaryScrubber.MASK), summary);
        assertTrue(summary.contains("userToken=" + ToolSummaryScrubber.MASK), summary);
        assertTrue(summary.contains("X-Api-Key=" + ToolSummaryScrubber.MASK), summary);
        assertTrue(summary.contains("AccessKeyId=" + ToolSummaryScrubber.MASK), summary);
        assertTrue(summary.contains("sessionId=" + ToolSummaryScrubber.MASK), summary);
        // 非敏感键保留
        assertTrue(summary.contains("orderId=A12345"), summary);
        // 🔴 明文一律不出现
        assertFalse(summary.contains("p@ss"));
        assertFalse(summary.contains("abc123"));
    }

    @Test
    @DisplayName("🔴 §5.4.3-3：手机号保留前 1 + *** + 后 2；邮箱保留首字符 + *** + @域名")
    void personalInfoPartiallyMasked() {
        String summary = scrubber.argsSummary(
                "{\"phone\":\"13812345678\",\"email\":\"zhangsan@example.com\"}");

        assertTrue(summary.contains("phone=1" + ToolSummaryScrubber.MASK + "78"), summary);
        assertTrue(summary.contains("email=z" + ToolSummaryScrubber.MASK + "@example.com"), summary);
        assertFalse(summary.contains("13812345678"));
        assertFalse(summary.contains("zhangsan@"));
    }

    @Test
    @DisplayName("🔴 §5.4.3-3：≥12 位长数字（身份证/银行卡）只保留后 4 位")
    void longDigitsKeepTail() {
        String summary = scrubber.argsSummary("{\"bankCard\":\"6222021234567890123\"}");
        assertTrue(summary.contains("bankCard=" + ToolSummaryScrubber.MASK + "0123"), summary);
        assertFalse(summary.contains("6222021234567890123"));
    }

    @Test
    @DisplayName("§5.4.3-4：其余字符串超 32 字符 → 前 12 …后 8")
    void longStringsElided() {
        String value = "A".repeat(40);
        String summary = scrubber.argsSummary("{\"note\":\"" + value + "\"}");
        assertTrue(summary.contains("A".repeat(12) + ToolResultTruncator.ELLIPSIS + "A".repeat(8)),
                summary);
        assertFalse(summary.contains(value));
    }

    @Test
    @DisplayName("🔴 嵌套对象/数组中的敏感键同样被遮蔽（键名命中即整值遮蔽，不再深入）")
    void nestedSensitiveKeysMasked() {
        String summary = scrubber.resultSummary(
                "{\"data\":{\"credential\":{\"secret\":\"s\",\"id\":\"1\"},\"items\":"
                        + "[{\"token\":\"t1\"},{\"name\":\"ok\"}]}}");

        assertFalse(summary.contains("\"s\""));
        assertFalse(summary.contains("t1"));
        assertTrue(summary.contains(ToolSummaryScrubber.MASK), summary);
        assertTrue(summary.contains("name:ok"), summary);
    }

    @Test
    @DisplayName("非 JSON 结果按值级脱敏（不因解析失败而原样透出）")
    void nonJsonResultScrubbed() {
        String summary = scrubber.resultSummary("13812345678");
        assertEquals("1" + ToolSummaryScrubber.MASK + "78", summary);
    }

    @Test
    @DisplayName("空值安全：null / 空串 → 空串")
    void nullSafe() {
        assertEquals("", scrubber.argsSummary(null));
        assertEquals("", scrubber.argsSummary(""));
        assertEquals("", scrubber.resultSummary(null));
    }

    @Test
    @DisplayName("键名敏感判定：含子串即命中（userToken / api_key / privateKeyPem）")
    void sensitiveKeyDetection() {
        assertTrue(scrubber.isSensitiveKey("userToken"));
        assertTrue(scrubber.isSensitiveKey("api_key"));
        assertTrue(scrubber.isSensitiveKey("privateKeyPem"));
        assertTrue(scrubber.isSensitiveKey("AUTHORIZATION"));
        assertFalse(scrubber.isSensitiveKey("orderId"));
        assertFalse(scrubber.isSensitiveKey(null));
    }
}
