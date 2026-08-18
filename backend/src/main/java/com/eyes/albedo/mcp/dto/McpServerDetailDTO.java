package com.eyes.albedo.mcp.dto;

import java.util.List;

/**
 * MCP 配置只读视图（api-spec §7.4.1）。
 *
 * <p>🔴 <b>凭据口径（AC-MCP-001，不可协商）</b>：只回
 * {@code credential.configured} + {@code last4} + {@code keyVersion} + {@code updatedAt}；
 * 永不回明文 / 完整密文 / 密文片段 / IV / Nonce / Tag / 密钥 / 解密结果。
 *
 * <p>⚠️ {@code endpoint} 按契约示例<b>需要</b>返回（DBA 排障要看地址），
 * 与"日志/审计/SSE 禁记 endpoint"并不矛盾：本接口是
 * {@code @TenantRole({TENANT_ADMIN, TENANT_OPERATOR})} 的<b>管理面只读</b>接口，
 * 且 endpoint 是该租户自己配置的数据。🔴 但它<b>不得</b>出现在任何面向终端用户的响应中。
 *
 * @param mcpId           MCP ID（string，ADR-004）
 * @param mcpKey          租户内唯一键
 * @param name            展示名
 * @param transport       streamable_http / sse
 * @param endpoint        服务地址
 * @param authType        none / bearer / header
 * @param credential      凭据状态（🔴 见上）
 * @param timeoutSeconds  单次调用超时
 * @param status          enabled / disabled
 * @param lastCheckStatus healthy / unhealthy
 * @param lastCheckResult 8 个分类字面量之一
 * @param lastCheckedAt   最近检测时间（ISO-8601 UTC）
 * @param allowedToolKeys 🔴 已授权且启用的工具键（{@code granted=1 AND status='enabled'}）
 * @param version         乐观锁列
 */
public record McpServerDetailDTO(String mcpId,
                                 String mcpKey,
                                 String name,
                                 String transport,
                                 String endpoint,
                                 String authType,
                                 CredentialDTO credential,
                                 int timeoutSeconds,
                                 String status,
                                 String lastCheckStatus,
                                 String lastCheckResult,
                                 String lastCheckedAt,
                                 List<String> allowedToolKeys,
                                 int version) {

    /**
     * 凭据状态（🔴 唯一允许的凭据回显形态）。
     *
     * @param configured 是否已配置
     * @param last4      明文末 4 位；明文 &lt;8 位为 {@code ****}；{@code authType=none} 时为空串
     * @param keyVersion 一期恒 1（{@code none} 时 0）
     * @param updatedAt  凭据更新时间（ISO-8601 UTC；无则 null）
     */
    public record CredentialDTO(boolean configured, String last4, int keyVersion,
                                String updatedAt) {
    }
}
