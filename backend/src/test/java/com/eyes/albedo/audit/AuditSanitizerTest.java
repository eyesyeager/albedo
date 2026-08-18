package com.eyes.albedo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计脱敏与摘要测试（AC-AUD-002 / architecture.md §11.1.2 六类禁记清单）。
 *
 * <p>为什么必须有这组用例：审计只追加不修改，一次误写明文会永久留在库里。
 * 这些断言就是"六类禁记项"的可执行版本。
 */
class AuditSanitizerTest {

    @Test
    @DisplayName("🔴 凭据类键名命中 → 整个 reason 被 redact（fail-closed）")
    void forbiddenKeysAreRedacted() {
        for (String raw : new String[]{
                "credential=abc123456789",
                "Authorization: Bearer xyz",
                "apiKey rotated",
                "更新了 password 字段",
                "systemPrompt 调整",
                "skillInstruction 变更",
                "token=abcdefg",
                "cookie 失效",
                "jdbc:mysql://10.0.0.1:3306/albedo",
                "select * from users"}) {
            assertEquals(AuditSanitizer.REDACTED, AuditSanitizer.reason(raw),
                    "应被整体 redact：" + raw);
        }
    }

    @Test
    @DisplayName("🔴 个人信息值级掩码：手机号 / 邮箱 / 长数字串")
    void personalDataIsMasked() {
        assertFalse(AuditSanitizer.reason("联系人 13800138000 已变更").contains("13800138000"));
        assertFalse(AuditSanitizer.reason("通知 ops@example.com").contains("ops@example.com"));
        assertFalse(AuditSanitizer.reason("卡号 6222021234567890123").contains("6222021234567890123"));
    }

    @Test
    @DisplayName("🔴 基础设施细节掩码：URL（可能是 MCP endpoint / 内网地址）")
    void urlIsMasked() {
        String sanitized = AuditSanitizer.reason("工单 OPS-1 指向 https://mcp.internal.example.com/v1");
        assertFalse(sanitized.contains("mcp.internal.example.com"), "endpoint 禁止入审计");
        assertTrue(sanitized.contains("OPS-1"), "正常工单号应保留：" + sanitized);
    }

    @Test
    @DisplayName("正常 reason 原样保留并截断到 200 字符（不切半个字符）")
    void normalReasonKeptAndTruncated() {
        assertEquals("工单 OPS-2026 发布 gift 站点新配置",
                AuditSanitizer.reason("  工单 OPS-2026 发布 gift 站点新配置  "));

        String longReason = "中".repeat(300);
        String truncated = AuditSanitizer.reason(longReason);
        assertEquals(AuditSanitizer.REASON_MAX_CHARS,
                truncated.codePointCount(0, truncated.length()));
    }

    @Test
    @DisplayName("userAgent：去 JWT 形态片段并截断")
    void userAgentSanitized() {
        String ua = "Mozilla/5.0 eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K";
        String sanitized = AuditSanitizer.userAgent(ua);
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiJ9"), "疑似 Token 片段必须去除");
        assertTrue(AuditSanitizer.userAgent("x".repeat(500)).length()
                <= AuditSanitizer.USER_AGENT_MAX_CHARS);
    }

    @Test
    @DisplayName("IP 按 /24（IPv4）或 /48（IPv6）截断")
    void ipTruncated() {
        assertEquals("203.0.113.0/24", AuditSanitizer.ip("203.0.113.45"));
        assertEquals("203.0.113.0/24", AuditSanitizer.ip("203.0.113.45, 10.0.0.1"));
        assertTrue(AuditSanitizer.ip("2001:db8:85a3::8a2e:370:7334").endsWith("/48"));
        assertEquals("", AuditSanitizer.ip(null));
    }

    @Test
    @DisplayName("digest 列：合法形态原样保留，疑似明文强制摘要化")
    void digestColumnDefends() {
        assertEquals("", AuditSanitizer.digestColumn(null));
        assertEquals(AuditDigest.CHANGED, AuditSanitizer.digestColumn(AuditDigest.CHANGED));
        assertEquals(AuditDigest.UNCHANGED, AuditSanitizer.digestColumn(AuditDigest.UNCHANGED));

        String digest = AuditDigest.digest("sk-live-abcdef");
        assertEquals(AuditDigest.DIGEST_HEX_LENGTH, digest.length());
        assertEquals(digest, AuditSanitizer.digestColumn(digest), "合法摘要必须原样保留");

        String plaintext = "sk-live-abcdef";
        String defended = AuditSanitizer.digestColumn(plaintext);
        assertFalse(defended.contains("sk-live"), "🔴 疑似明文必须被摘要化，不得原样落库");
        assertEquals(digest, defended);
    }

    @Test
    @DisplayName("digest 形态校验：16 位小写 hex / changed / unchanged / 空串")
    void digestFormValidation() {
        assertTrue(AuditDigest.isValidForm(""));
        assertTrue(AuditDigest.isValidForm(null));
        assertTrue(AuditDigest.isValidForm("changed"));
        assertTrue(AuditDigest.isValidForm(AuditDigest.digest("x")));
        assertFalse(AuditDigest.isValidForm("ABCDEF0123456789"), "大写 hex 不合法");
        assertFalse(AuditDigest.isValidForm("zzzzzzzzzzzzzzzz"));
        assertFalse(AuditDigest.isValidForm("0123"));
    }

    @Test
    @DisplayName("摘要稳定且不可逆：同值同摘要，不同值不同摘要")
    void digestIsStable() {
        assertEquals(AuditDigest.digest("value-a"), AuditDigest.digest("value-a"));
        assertFalse(AuditDigest.digest("value-a").equals(AuditDigest.digest("value-b")));
        assertEquals("", AuditDigest.digest(""));
        assertEquals(AuditDigest.CHANGED, AuditDigest.changeMarker(true));
        assertEquals(AuditDigest.UNCHANGED, AuditDigest.changeMarker(false));
    }
}
