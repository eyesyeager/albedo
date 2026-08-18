package com.eyes.albedo.platform.dto;

/**
 * 单个内部作用域的失效结果（api-spec §7.2.1 的 {@code data.results[]}）。
 *
 * <p>作用域字面量（@测试 据此断言）：{@code tenantHost} / {@code tenantCode} / {@code siteConfig} /
 * {@code agentVersion} / {@code memberRole} / {@code sysconfig}。
 *
 * @param scope      内部作用域名
 * @param target     目标（host / 租户号 / 配置分组 / {@code *}）
 * @param l1Evicted  L1 实际失效条目数
 * @param l2Evicted  L2 实际失效条目数
 * @param status     {@code succeeded} / {@code failed}
 */
public record CacheEvictScopeResultDTO(String scope,
                                       String target,
                                       long l1Evicted,
                                       long l2Evicted,
                                       String status) {

    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";

    public boolean failed() {
        return STATUS_FAILED.equals(status);
    }
}
