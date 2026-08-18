package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MCP 凭据加解密测试（ADR-012 / AC-MCP-001 / AC-MCP-007）。
 *
 * <p>覆盖：加解密往返、AAD 不匹配必须失败（防密文跨租户搬运）、错误 keyVersion → 30060、
 * 密文格式非法 → 30060、相同明文密文不同、last4 规则。
 */
class CredentialCipherTest {

    private static final String SECRET = "albedo-unit-test-crypto-secret-32bytes!!";
    private static final String TENANT_A = "gift";
    private static final String TENANT_B = "redbook";
    private static final String MCP_KEY = "crm";

    private final CredentialCipher cipher = new CredentialCipher(SECRET);

    @Test
    @DisplayName("加解密往返：密文格式为 v1:{iv}:{ct||tag} 且可还原明文")
    void roundTrip() {
        String plaintext = "sk-live-abcdef123456";
        String encrypted = cipher.encrypt(plaintext, TENANT_A, MCP_KEY);

        assertTrue(encrypted.startsWith("v1:"), "一期 keyVersion 恒为 1，密文必须以 v1: 开头：" + encrypted);
        assertEquals(3, encrypted.split(":").length, "密文必须是三段冒号分隔");
        assertEquals(plaintext, cipher.decrypt(encrypted, TENANT_A, MCP_KEY));
    }

    @Test
    @DisplayName("AC-MCP-007：相同明文每次加密结果都不同（随机 IV）")
    void sameePlaintextProducesDifferentCipher() {
        String plaintext = "sk-live-abcdef123456";
        assertNotEquals(cipher.encrypt(plaintext, TENANT_A, MCP_KEY),
                cipher.encrypt(plaintext, TENANT_A, MCP_KEY));
    }

    @Test
    @DisplayName("🔴 AAD 绑定：把密文搬到另一个租户必须解密失败 → 30060")
    void crossTenantCipherIsRejected() {
        String encrypted = cipher.encrypt("sk-live-abcdef123456", TENANT_A, MCP_KEY);

        BusinessException e = assertThrows(BusinessException.class,
                () -> cipher.decrypt(encrypted, TENANT_B, MCP_KEY));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, e.getCode());
        // 🔴 消息中不得出现密文 / 明文片段
        assertFalse(e.getMessage().contains("sk-live"), "错误消息禁含明文片段");
    }

    @Test
    @DisplayName("🔴 AAD 绑定：mcp_key 被改后必须解密失败 → 30060")
    void changedMcpKeyIsRejected() {
        String encrypted = cipher.encrypt("sk-live-abcdef123456", TENANT_A, MCP_KEY);
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> cipher.decrypt(encrypted, TENANT_A, "crm2")).getCode());
    }

    @Test
    @DisplayName("错误 keyVersion（非 1）→ 30060：一期无密钥轮换能力")
    void unsupportedKeyVersionIsRejected() {
        String encrypted = cipher.encrypt("sk-live-abcdef123456", TENANT_A, MCP_KEY);
        String tampered = "v2:" + encrypted.substring(3);

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> cipher.decrypt(tampered, TENANT_A, MCP_KEY)).getCode());
        assertFalse(cipher.isValidFormat(tampered));
    }

    @Test
    @DisplayName("密文格式非法（段数不对 / 非 base64 / IV 长度错）→ 30060")
    void malformedCipherIsRejected() {
        for (String bad : new String[]{"", "   ", "v1:onlytwo", "v1:a:b:c", "x1:aaaa:bbbb",
                "v1:not*base64:bbbb", "v1:" + "AAAA" + ":BBBB"}) {
            assertFalse(cipher.isValidFormat(bad), "应判为非法：" + bad);
            assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                    assertThrows(BusinessException.class,
                            () -> cipher.decrypt(bad, TENANT_A, MCP_KEY)).getCode(),
                    "应返回 30060：" + bad);
        }
    }

    @Test
    @DisplayName("篡改密文正文（GCM Tag 校验失败）→ 30060")
    void tamperedPayloadIsRejected() {
        String encrypted = cipher.encrypt("sk-live-abcdef123456", TENANT_A, MCP_KEY);
        String[] parts = encrypted.split(":");
        char[] payload = parts[2].toCharArray();
        payload[0] = payload[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + ':' + parts[1] + ':' + new String(payload);

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> cipher.decrypt(tampered, TENANT_A, MCP_KEY)).getCode());
    }

    @Test
    @DisplayName("缺少租户或 mcpKey 绑定维度 → 30060（不退化为无 AAD 加密）")
    void missingAadDimensionIsRejected() {
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> cipher.encrypt("sk-live-abcdef123456", "", MCP_KEY)).getCode());
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> cipher.encrypt("sk-live-abcdef123456", TENANT_A, null)).getCode());
    }

    @Test
    @DisplayName("last4：明文 ≥8 位取末 4 位；<8 位一律 ****（防短凭据被反推）")
    void last4Rules() {
        assertEquals("3456", CredentialCipher.last4("sk-live-123456"));
        assertEquals(CredentialCipher.LAST4_MASKED, CredentialCipher.last4("short12"));
        assertEquals(CredentialCipher.LAST4_MASKED, CredentialCipher.last4(null));
        assertEquals(8, "12345678".length());
        assertEquals("5678", CredentialCipher.last4("12345678"));
    }

    @Test
    @DisplayName("空明文拒绝加密，且异常消息不含输入片段")
    void emptyPlaintextRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> cipher.encrypt("", TENANT_A, MCP_KEY));
        assertTrue(e.getMessage().contains("明文为空"));
    }

    @Test
    @DisplayName("密钥过短直接拒绝装配（避免静默降低密钥强度）")
    void weakSecretRejected() {
        assertThrows(IllegalStateException.class, () -> new CredentialCipher("short"));
        assertThrows(IllegalStateException.class, () -> new CredentialCipher(null));
    }

    @Test
    @DisplayName("不同 secret 无法互相解密（密钥隔离）")
    void differentSecretCannotDecrypt() {
        String encrypted = cipher.encrypt("sk-live-abcdef123456", TENANT_A, MCP_KEY);
        CredentialCipher other = new CredentialCipher("another-secret-with-enough-length!!");
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> other.decrypt(encrypted, TENANT_A, MCP_KEY)).getCode());
    }
}
