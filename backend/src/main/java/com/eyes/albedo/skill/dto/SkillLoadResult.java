package com.eyes.albedo.skill.dto;

/**
 * 按需加载单个 Skill 的完整正文（🔴 由 {@code tool/SkillLoadHandler} 消费并原样序列化为
 * 工具结果，随 {@code AiMessage.tool} 回灌模型）。
 *
 * <p>🔴 <b>正文内部资产纪律的调整</b>：本记录携带的 {@code instruction} / {@code outputConstraint} /
 * {@code resourcesText} 就是 Skill 正文本身，与 {@link SkillInjectionFragment}（清单场景，
 * 恒不含正文）语义不同 —— 见 {@code SkillInjectionService} 类注释「边界纪律」第三条的权衡说明。
 *
 * @param skillKey        技能键（回显给模型，便于多技能场景下自我核对）
 * @param name            技能名称
 * @param instruction     已完成变量替换的指令正文
 * @param outputConstraint 已完成变量替换的输出约束（无则空串）
 * @param resourcesText   该版本关联的 {@code skill_resources} 渲染文本（无资源则空串）
 */
public record SkillLoadResult(String skillKey,
                              String name,
                              String instruction,
                              String outputConstraint,
                              String resourcesText) {

    public boolean hasOutputConstraint() {
        return outputConstraint != null && !outputConstraint.isBlank();
    }

    public boolean hasResources() {
        return resourcesText != null && !resourcesText.isBlank();
    }
}
