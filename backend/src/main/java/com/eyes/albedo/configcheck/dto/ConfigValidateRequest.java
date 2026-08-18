package com.eyes.albedo.configcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 配置校验请求（api-spec §7.3.1）。
 *
 * @param objectType        {@code agent} | {@code agentVersion} | {@code skill} | {@code skillVersion}
 *                          | {@code mcp} | {@code localTool} | {@code toolGrant} | {@code siteConfig}
 * @param objectId          对象 ID；🔴 非当前租户对象 → {@code 10004}（不泄露存在性）
 * @param includeReferences 是否沿引用链递归校验，默认 {@code true}
 */
public record ConfigValidateRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 32, message = "长度非法")
        String objectType,

        @NotBlank(message = "不能为空")
        @Size(max = 64, message = "长度非法")
        String objectId,

        Boolean includeReferences) {

    /** 缺省沿引用链递归（api-spec §7.3.1 默认 true）。 */
    public boolean includeReferencesOrDefault() {
        return includeReferences == null || includeReferences;
    }
}
