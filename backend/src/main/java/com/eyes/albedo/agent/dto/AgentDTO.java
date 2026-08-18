package com.eyes.albedo.agent.dto;

/**
 * Agent 公开展示信息（api-spec.md §4.4.1）。
 *
 * <p>🔴 安全红线：<b>禁止</b>出现 {@code systemPrompt} / {@code providerKey} / {@code model} /
 * {@code temperature} / {@code maxOutputTokens} / 能力绑定等内部字段——本 DTO 是面向匿名访客的。
 *
 * @param agentId      Agent ID（string，ADR-004）
 * @param agentKey     租户内唯一 key
 * @param name         展示名称
 * @param description  选择器说明
 * @param avatarUrl    头像
 * @param agentVersion 当前已发布版本号（number）
 * @param isDefault    是否默认 Agent
 * @param sortOrder    排序值
 */
public record AgentDTO(String agentId,
                       String agentKey,
                       String name,
                       String description,
                       String avatarUrl,
                       long agentVersion,
                       boolean isDefault,
                       int sortOrder) {
}
