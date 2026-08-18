package com.eyes.albedo.agent.dto;

import java.util.List;

/**
 * 模型提供方清单项（取自 {@code sys_config: model.providers}，反硬编码）。
 *
 * <p>🔴 Agent 发布时必须校验 {@code providerKey} 与 {@code model} 都在清单内，
 * 否则会把不可用模型发布到线上（发布后再失败等于线上故障）。
 *
 * @param providerKey 提供方标识，如 {@code hunyuan}
 * @param name        展示名
 * @param models      该提供方允许的模型标识清单
 */
public record ModelProvider(String providerKey, String name, List<String> models) {
}
