package com.eyes.albedo.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 缓存失效请求（api-spec §7.2.1）。
 *
 * <p>🔴 {@code reason} 是<b>审计必需</b>字段（缺失 → {@code 10001}）：一期没有管理后台，
 * 缓存失效是唯一的"让改库生效"的开关，若不要求写原因，事后无法回答"谁为什么清了缓存"。
 * {@code scope=all} 属高危操作，{@code reason} 必须说明工单号。
 *
 * @param scope       失效作用域：tenant | host | sysconfig | agentVersion | all
 * @param tenantId    scope=tenant / agentVersion 时必填
 * @param host        scope=host 时必填（服务端再规范化：去端口、转小写、去末尾点）
 * @param configGroup scope=sysconfig 时可选；缺省失效全部配置项
 * @param agentId     scope=agentVersion 时可选；缺省失效该租户全部 Agent 版本快照
 * @param reason      1~200 字符，🔴 审计必需
 */
public record CacheEvictRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 32, message = "长度非法")
        String scope,

        @Pattern(regexp = "^$|^[a-z][a-z0-9-]{1,31}$", message = "租户号格式非法")
        String tenantId,

        @Size(max = 253, message = "长度不能超过 253")
        String host,

        @Size(max = 100, message = "长度不能超过 100")
        String configGroup,

        String agentId,

        @NotBlank(message = "不能为空（审计必需）")
        @Size(min = 1, max = 200, message = "长度必须在 1~200 之间")
        String reason) {
}
