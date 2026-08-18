package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code inputSchema} 摘要单测（api-spec §7.6.2 / §7.4.3 的 {@code schema_changed} 判定基础）。
 *
 * <p>🔴 为什么这组断言重要：摘要决定<b>已授权工具是否被自动降级</b>。
 * 若"仅字段顺序变化"就产生新摘要，DBA 会被反复要求重新授权，
 * 最终必然有人把这条安全校验关掉 —— 那才是真正的提权风险。
 */
class McpSchemaDigestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("🔴 字段顺序 / 缩进不同但语义相同 → 摘要必须相同（否则降级会误触发）")
    void orderInsensitive() throws Exception {
        String a = "{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"},"
                + "\"name\":{\"type\":\"string\"}},\"required\":[\"uid\"]}";
        String b = "{\n  \"required\": [\"uid\"],\n  \"properties\": {\n"
                + "    \"name\": {\"type\": \"string\"},\n    \"uid\": {\"type\": \"string\"}\n"
                + "  },\n  \"type\": \"object\"\n}";

        assertEquals(McpSchemaDigest.of(objectMapper.readTree(a)),
                McpSchemaDigest.of(objectMapper.readTree(b)));
    }

    @Test
    @DisplayName("🔴 新增必填参数 → 摘要必须变化（这正是「偷换 Schema」的形态）")
    void semanticChangeDetected() throws Exception {
        String before = "{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"}},"
                + "\"required\":[\"uid\"]}";
        String after = "{\"type\":\"object\",\"properties\":{\"uid\":{\"type\":\"string\"},"
                + "\"scope\":{\"type\":\"string\"}},\"required\":[\"uid\",\"scope\"]}";

        assertNotEquals(McpSchemaDigest.of(objectMapper.readTree(before)),
                McpSchemaDigest.of(objectMapper.readTree(after)));
    }

    @Test
    @DisplayName("🔴 数组顺序变化必须被视为变化（prefixItems / required 顺序在 Schema 中有语义）")
    void arrayOrderSensitive() throws Exception {
        String a = "{\"required\":[\"a\",\"b\"]}";
        String b = "{\"required\":[\"b\",\"a\"]}";
        assertNotEquals(McpSchemaDigest.of(objectMapper.readTree(a)),
                McpSchemaDigest.of(objectMapper.readTree(b)));
    }

    @Test
    @DisplayName("无 Schema → 空串（\"没有 Schema\" 与 \"空对象 Schema\" 必须可区分）")
    void nullSchema() throws Exception {
        assertEquals("", McpSchemaDigest.of(null));
        assertNotEquals("", McpSchemaDigest.of(objectMapper.readTree("{}")));
    }

    @Test
    @DisplayName("摘要长度固定 16 hex（与 audit digest 口径一致）")
    void digestLength() throws Exception {
        String digest = McpSchemaDigest.of(objectMapper.readTree("{\"type\":\"object\"}"));
        assertEquals(McpSchemaDigest.DIGEST_HEX_LENGTH, digest.length());
        assertTrue(digest.matches("^[0-9a-f]{16}$"), digest);
    }
}
