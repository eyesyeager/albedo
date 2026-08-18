package com.eyes.albedo.agent.dto;

import java.math.BigDecimal;

/**
 * Agent 运行时快照（内部使用，🔴 绝不出现在任何面向用户的响应中）。
 *
 * <p>由 {@code agent_versions} 的不可变行映射而来。会话固定绑定
 * {@code (agentId, version)}，因此同一会话在 Agent 升级后仍复现原行为（RISK-005）。
 *
 * @param agentId               Agent ID
 * @param version               版本号
 * @param systemPrompt          系统提示（内部资产）
 * @param providerKey           模型提供方
 * @param model                 模型标识
 * @param temperature           采样温度
 * @param maxOutputTokens       最大输出 token
 * @param contextStrategy       上下文策略
 * @param requestTimeoutSeconds 单次请求超时秒数
 * @param toolPolicy            工具策略（M1 恒为 disabled）
 */
public record AgentRuntime(long agentId,
                           long version,
                           String systemPrompt,
                           String providerKey,
                           String model,
                           BigDecimal temperature,
                           int maxOutputTokens,
                           String contextStrategy,
                           int requestTimeoutSeconds,
                           String toolPolicy) {
}
