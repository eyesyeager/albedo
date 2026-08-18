package com.eyes.albedo.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 凭据加解密（AES-256-GCM，ADR-012 / api-spec §7.4.1 / AC-MCP-001 / AC-MCP-007）。
 *
 * <p><b>密文格式（🔴 单一字符串列 {@code mcp_servers.credential_cipher}）</b>：
 * <pre>
 *   v{keyVersion}:{base64url(iv)}:{base64url(ciphertext||tag)}
 * </pre>
 * 三段冒号分隔；不符该格式 → {@code 30060}（{@code rule=invalidCipher}）。
 * 🔴 一期 {@code keyVersion} 恒为 {@code 1}（{@code application.yml} 白名单只允许单密钥
 * {@code app.crypto.secret}，一期<b>没有密钥轮换能力</b>），读到非 1 一律 {@code 30060}。
 * 版本前缀是为二期"多密钥解密 + 单密钥加密"预留。
 *
 * <p><b>🔴 AAD 绑定 {@code "mcp:{tenantId}:{mcpKey}"}</b>（本类最关键的安全设计）：
 * GCM 的附加认证数据参与完整性校验但不加密。把密文与"哪个租户的哪个 MCP"绑定后，
 * 把密文从 A 租户复制到 B 租户（或改了 {@code mcp_key}）会<b>解密失败</b>，
 * 从加密层杜绝「密文跨租户搬运」式越权（DBA 误操作或恶意复制）。
 * ⚠️ 副作用：{@code tenant_id} 或 {@code mcp_key} 变更后<b>必须重新加密写入</b>，
 * 否则表现为 {@code 30060}（校验入口）/ {@code 30052}（运行时调用）—— 已写入 README 运维手册。
 *
 * <p><b>🔴 只写不回显</b>：本类<b>不提供</b>任何"解密后回显"的对外通道——
 * {@link #decrypt} 只供运行时调用前在内存中使用，结果不入日志、不入审计、不入异常消息、
 * 不进任何缓存（L1/L2 均禁入，architecture.md §12.1.1）。
 * 对外查询口径只有 {@code configured} + {@code last4}（api-spec §7.4.1）。
 * 🔴 <b>禁止</b>提供"提交明文换密文"的 HTTP 端点（ADR-012 方案 C 已否决）：
 * 密文只能由 {@code src/test} 下的离线工具产出，由 DBA 写库。
 *
 * <p>相同明文每次加密结果必然不同（12 字节随机 IV + 128 位 Tag，AC-MCP-007）。
 */
@Component
public class CredentialCipher {

    /** 一期唯一支持的密钥版本。 */
    public static final int CURRENT_KEY_VERSION = 1;
    /** GCM 推荐 IV 长度（字节）。 */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度（位）。 */
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    /** {@code last4} 的最小明文长度：更短的明文一律返回掩码，防止被 last4 反推。 */
    private static final int LAST4_MIN_PLAINTEXT_LENGTH = 8;
    /** 明文过短时的 last4 占位值（api-spec §7.4.1 第 4 条）。 */
    public static final String LAST4_MASKED = "****";

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${app.crypto.secret}") String secret) {
        this.key = deriveKey(secret);
    }

    /**
     * 加密（🔴 只供<b>离线工具</b>与单测使用；生产运行时只解密）。
     *
     * @param plaintext 凭据明文（不落任何日志）
     * @param tenantId  租户号（参与 AAD）
     * @param mcpKey    MCP 键（参与 AAD）
     * @return {@code v1:{base64url(iv)}:{base64url(ct||tag)}}
     */
    public String encrypt(String plaintext, String tenantId, String mcpKey) {
        if (plaintext == null || plaintext.isEmpty()) {
            // 🔴 异常消息中不得出现任何输入片段
            throw new IllegalArgumentException("凭据明文为空");
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(tenantId, mcpKey));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v" + CURRENT_KEY_VERSION + ':'
                    + encoder().encodeToString(iv) + ':'
                    + encoder().encodeToString(sealed);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // 不带 cause 的输入信息，仅暴露"加密失败"
            throw new IllegalStateException("凭据加密失败");
        }
    }

    /**
     * 解密（🔴 只在<b>运行时调用前</b>的内存中使用）。
     *
     * <p>失败一律 {@code 30060}，且消息中<b>不含</b>密文片段 / IV / Tag / 密钥 / 明文，
     * 也不区分"格式错 / 密钥错 / AAD 不匹配"以外的细节（避免成为解密预言机）。
     *
     * @throws BusinessException 30060 密文格式非法 / 密钥版本不受支持 / 认证失败（含跨租户搬运）
     */
    public String decrypt(String cipherText, String tenantId, String mcpKey) {
        Parsed parsed = parse(cipherText);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, parsed.iv()));
            cipher.updateAAD(aad(tenantId, mcpKey));
            return new String(cipher.doFinal(parsed.sealed()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 🔴 AAD 不匹配（密文被跨租户搬运 / mcp_key 被改）也走这里
            throw invalidCipher();
        }
    }

    /**
     * 校验密文格式与密钥版本（🔴 <b>不解密</b>，供配置校验入口使用）。
     *
     * <p>为什么校验入口不解密：ADR-012 第 5 条限定"解密只发生在运行时调用前"。
     * 因此 AAD 不匹配（跨租户搬运）只能在<b>运行时</b>暴露为 {@code 30052}，
     * 校验入口只能覆盖格式与版本 —— 该限制已回报 @架构师。
     *
     * @throws BusinessException 30060 格式非法或密钥版本不受支持
     */
    public void requireValidFormat(String cipherText) {
        parse(cipherText);
    }

    /**
     * 密文格式是否合法（不抛异常版本，供批量校验聚合 violations 使用）。
     */
    public boolean isValidFormat(String cipherText) {
        try {
            parse(cipherText);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * 明文末 4 位（🔴 明文长度 &lt; 8 一律返回 {@link #LAST4_MASKED}，防止短凭据被反推）。
     *
     * <p>🔴 {@code last4} 仅供人工核对，<b>禁止</b>用于任何比较、校验或鉴权逻辑；
     * 也<b>不得</b>由密文推导（只能在离线加密时由明文一并产出）。
     */
    public static String last4(String plaintext) {
        if (plaintext == null || plaintext.length() < LAST4_MIN_PLAINTEXT_LENGTH) {
            return LAST4_MASKED;
        }
        return plaintext.substring(plaintext.length() - 4);
    }

    /**
     * AAD = {@code mcp:{tenantId}:{mcpKey}}。
     */
    private static byte[] aad(String tenantId, String mcpKey) {
        if (tenantId == null || tenantId.isBlank() || mcpKey == null || mcpKey.isBlank()) {
            // 缺少绑定维度就等于没有绑定，必须拒绝而不是退化成无 AAD 加密
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "凭据加解密缺少租户或 MCP 绑定维度");
        }
        return ("mcp:" + tenantId + ':' + mcpKey).getBytes(StandardCharsets.UTF_8);
    }

    private Parsed parse(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw invalidCipher();
        }
        String[] parts = cipherText.trim().split(":");
        if (parts.length != 3) {
            throw invalidCipher();
        }
        if (parts[0].length() < 2 || parts[0].charAt(0) != 'v') {
            throw invalidCipher();
        }
        int version;
        try {
            version = Integer.parseInt(parts[0].substring(1));
        } catch (NumberFormatException e) {
            throw invalidCipher();
        }
        if (version != CURRENT_KEY_VERSION) {
            // 一期无轮换能力：非 1 即非法（ADR-012）
            throw invalidCipher();
        }
        byte[] iv;
        byte[] sealed;
        try {
            iv = decoder().decode(parts[1]);
            sealed = decoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw invalidCipher();
        }
        if (iv.length != IV_LENGTH || sealed.length <= TAG_LENGTH_BITS / 8) {
            throw invalidCipher();
        }
        return new Parsed(version, iv, sealed);
    }

    private static BusinessException invalidCipher() {
        // 🔴 统一措辞，不回显任何输入细节（api-spec §7.3.1 violations 禁含密钥片段）
        return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                "凭据密文格式非法或密钥版本不受支持");
    }

    /**
     * 由 {@code app.crypto.secret} 派生 32 字节 AES-256 密钥。
     *
     * <p>为什么做 SHA-256 派生而不是直接取字节：配置里的 secret 是<b>人可读字符串</b>，
     * 长度不固定；直接截断会在 secret 短于 32 字节时静默降低密钥强度。
     * SHA-256 保证恒为 32 字节且全字节参与。
     */
    private static byte[] deriveKey(String secret) {
        if (secret == null || secret.trim().length() < 16) {
            throw new IllegalStateException("app.crypto.secret 未配置或长度不足（≥16 字符）");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(secret.trim().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Base64.Encoder encoder() {
        return Base64.getUrlEncoder().withoutPadding();
    }

    private static Base64.Decoder decoder() {
        return Base64.getUrlDecoder();
    }

    private record Parsed(int keyVersion, byte[] iv, byte[] sealed) {
    }
}
