package com.eyes.albedo.mcp;

import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.mcp.entity.McpServer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 凭据解析（🔴 ADR-012 / api-spec §7.4.1，<b>解密结果只存在于调用栈内</b>）。
 *
 * <p><b>REQ-MCP-001 · AC-MCP-001</b>
 *
 * <p>🔴 <b>五条不可协商的纪律</b>：
 * <ol>
 *   <li>只在<b>运行时调用前</b>解密；解密结果<b>不入</b>日志 / 审计 / 异常消息 /
 *       任何缓存（L1 与 L2 均禁入，architecture.md §12.1.1）</li>
 *   <li>对外查询口径只有 {@code configured} + {@code last4}；
 *       🔴 永不返回明文 / 完整密文 / 密文片段 / IV / Tag / 密钥</li>
 *   <li>{@code keyVersion} 一期恒为 {@code 1}；读到非 1 → {@code 30060}
 *       （一期无轮换能力，版本前缀仅为二期预留）</li>
 *   <li>密文格式必须是 {@code v1:{base64url(iv)}:{base64url(ct‖tag)}}，不符 → {@code 30060}</li>
 *   <li>AAD 绑定 {@code mcp:{tenantId}:{mcpKey}} —— 🔴 密文被跨租户搬运或 {@code mcp_key}
 *       被改会<b>解密失败</b>，这是"从加密层杜绝越权"的设计，不是 bug</li>
 * </ol>
 *
 * <p>🔴 返回值刻意是 {@code Map<String,String>}（HTTP 头）而不是明文字符串：
 * 让调用方<b>拿不到"凭据本体"这个概念</b>，只能把它塞进请求头，
 * 从 API 形状上降低"顺手打个日志"的可能性。
 */
@Slf4j
@Component
public class McpCredentialResolver {

    /** {@code authType=header} 时使用的头名（MCP 社区约定，与 bearer 区分开）。 */
    public static final String CUSTOM_AUTH_HEADER = "x-api-key";
    /** {@code authType=bearer} 时使用的头名。 */
    public static final String BEARER_HEADER = "authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final CredentialCipher cipher;

    public McpCredentialResolver(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * 构造上游鉴权请求头（🔴 <b>调用点必须紧邻网络请求</b>，不得提前解密后缓存）。
     *
     * @return 需要附加的请求头；{@code authType=none} 时为空 Map
     * @throws BusinessException 30060 密文格式非法 / 密钥版本不受支持 / AAD 不匹配（跨租户搬运）
     */
    public Map<String, String> authHeaders(McpServer server) {
        String authType = server.getAuthType();
        if (authType == null || McpServer.AUTH_TYPE_NONE.equals(authType)) {
            return Map.of();
        }
        if (server.getCredentialCipher() == null || server.getCredentialCipher().isBlank()) {
            // 🔴 需要凭据却没配 = 配置非法，绝不"降级为匿名调用"（那会让鉴权失败表现为业务异常）
            throw new BusinessException(com.eyes.albedo.common.ErrorCode.RUNTIME_CONFIG_INVALID,
                    "该鉴权方式需要配置凭据");
        }
        // 🔴 解密：仅在此处、仅在内存中；异常消息不含任何密文片段（CredentialCipher 已保证）
        String plaintext = cipher.decrypt(server.getCredentialCipher(),
                server.getTenantId(), server.getMcpKey());
        try {
            return switch (authType) {
                case McpServer.AUTH_TYPE_BEARER -> Map.of(BEARER_HEADER, BEARER_PREFIX + plaintext);
                case McpServer.AUTH_TYPE_HEADER -> Map.of(CUSTOM_AUTH_HEADER, plaintext);
                default -> throw new BusinessException(
                        com.eyes.albedo.common.ErrorCode.RUNTIME_CONFIG_INVALID,
                        "鉴权方式取值必须是 none / bearer / header");
            };
        } finally {
            // 🔴 无法真正擦除 String（不可变），故此处只保证不再引用；
            //    这是选择 String 的已知代价，ADR-012 未要求 char[] 级别的内存擦除。
            log.trace("MCP 凭据已解密用于本次调用：mcpId={}（🔴 不记录任何凭据内容）",
                    server.getId());
        }
    }

    /**
     * 对外可见的凭据状态（api-spec §7.4.1 的 {@code credential} 对象）。
     *
     * <p>🔴 {@code last4} 直接取 {@code credential_last4} 列（离线工具产出）：
     * <b>不得</b>由密文推导，也<b>不得</b>为了拿 last4 而在服务端解密。
     *
     * @param configured 是否已配置凭据
     * @param last4      明文末 4 位；明文 &lt;8 位时为 {@code ****}
     * @param keyVersion 密钥版本（一期恒 1；{@code authType=none} 时为 0）
     * @param updatedAt  凭据更新时间（ISO-8601 UTC；无则 null）
     */
    public record CredentialView(boolean configured, String last4, int keyVersion, String updatedAt) {
    }

    /**
     * 构造对外凭据视图（🔴 唯一允许的凭据回显形态）。
     */
    public CredentialView view(McpServer server) {
        if (server.getAuthType() == null || McpServer.AUTH_TYPE_NONE.equals(server.getAuthType())) {
            // api-spec §7.4.1 第 6 条：authType=none 时固定形态
            return new CredentialView(false, "", 0, null);
        }
        boolean configured = server.getCredentialCipher() != null
                && !server.getCredentialCipher().isBlank();
        String last4 = server.getCredentialLast4() == null || server.getCredentialLast4().isBlank()
                ? CredentialCipher.LAST4_MASKED : server.getCredentialLast4();
        return new CredentialView(configured, last4,
                server.getCredentialKeyVersion() == null ? 0 : server.getCredentialKeyVersion(),
                com.eyes.albedo.common.TimeFormat.iso(server.getCredentialUpdatedAt()));
    }
}
