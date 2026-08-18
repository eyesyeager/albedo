package com.eyes.albedo.agent.dto;

import java.math.BigDecimal;

/**
 * Agent 版本发布草稿（管理侧入参，M1 只经 Service 层落地）。
 *
 * <p>校验规则见 PRD §9.3；发布成功后本内容形成<b>不可变快照</b>。
 *
 * @param systemPrompt          系统提示（1~100000）
 * @param providerKey           模型提供方（必须在 {@code sys_config: model.providers} 清单内）
 * @param model                 模型标识（必须在该 provider 的模型清单内）
 * @param temperature           0~2
 * @param maxOutputTokens       ≥1
 * @param contextStrategy       {@code summary_then_window} / {@code window}
 * @param requestTimeoutSeconds 10~300
 * @param toolPolicy            {@code disabled} / {@code auto} / {@code confirm}（M1 只允许 disabled）
 */
public record AgentVersionDraft(String systemPrompt,
                                String providerKey,
                                String model,
                                BigDecimal temperature,
                                Integer maxOutputTokens,
                                String contextStrategy,
                                Integer requestTimeoutSeconds,
                                String toolPolicy) {
}
