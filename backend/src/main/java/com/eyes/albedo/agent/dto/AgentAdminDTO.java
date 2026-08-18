package com.eyes.albedo.agent.dto;

/**
 * Agent 管理视图（M2 管理端消费；M1 仅由 Service 层与单测使用）。
 *
 * <p>与 {@link AgentDTO} 的区别：管理视图可以看到状态与版本治理信息，
 * 但🔴 依然<b>不包含</b> {@code systemPrompt} 明文与模型凭据——需要正文时走版本详情接口（M2 契约）。
 *
 * @param agentId        Agent ID（string）
 * @param agentKey       租户内唯一 key
 * @param name           名称
 * @param description    说明
 * @param status         enabled / disabled / deleted
 * @param isDefault      是否默认
 * @param sortOrder      排序值
 * @param currentVersion 当前已发布版本（0 = 未发布）
 * @param version        乐观锁版本（发布/编辑并发令牌）
 * @param updatedAt      更新时间（ISO-8601 UTC）
 * @param updatedBy      更新人 uid（string，可空）
 */
public record AgentAdminDTO(String agentId,
                            String agentKey,
                            String name,
                            String description,
                            String status,
                            boolean isDefault,
                            int sortOrder,
                            long currentVersion,
                            int version,
                            String updatedAt,
                            String updatedBy) {
}
