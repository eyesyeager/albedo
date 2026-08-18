package com.eyes.albedo.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code inputSchema} 摘要（api-spec §7.6.2：{@code sha256(规范化 inputSchema)} 前 16 hex）。
 *
 * <p><b>为什么必须"规范化"再摘要</b>：{@code schema_changed} 的判定直接决定
 * 🔴 <b>已授权工具是否被自动降级</b>（api-spec §7.4.3 规则 3）。
 * 若不规范化，上游只是把 JSON 字段换了个顺序或加了缩进，摘要就会变化 →
 * 每次发现都把所有已授权工具降级 → DBA 被迫反复重新授权 → 最终会有人"干脆关掉这个校验"，
 * 那才是真正的提权风险。
 *
 * <p>规范化规则：递归<b>按键名排序</b>对象成员，数组保持原顺序（数组顺序在 JSON Schema 中有语义，
 * 如 {@code prefixItems}），不保留空白。
 */
public final class McpSchemaDigest {

    /** 摘要长度（hex 字符数，与 {@code audit} 的 digest 口径一致）。 */
    public static final int DIGEST_HEX_LENGTH = 16;

    private McpSchemaDigest() {
    }

    /**
     * 计算摘要；{@code schema} 为空时返回空串（"无 Schema" 与 "空对象 Schema" 必须可区分）。
     */
    public static String of(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return "";
        }
        return sha256Prefix(canonicalize(schema));
    }

    /**
     * 规范化为确定性字符串（不依赖 Jackson 的序列化顺序）。
     */
    public static String canonicalize(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node instanceof ObjectNode) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            Collections.sort(names);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append('"').append(escape(names.get(i))).append("\":");
                write(node.get(names.get(i)), out);
            }
            out.append('}');
            return;
        }
        if (node instanceof ArrayNode) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
            return;
        }
        if (node.isTextual()) {
            out.append('"').append(escape(node.asText())).append('"');
            return;
        }
        out.append(node.asText());
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256Prefix(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DIGEST_HEX_LENGTH);
            for (int i = 0; i < DIGEST_HEX_LENGTH / 2; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
