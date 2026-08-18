package com.eyes.albedo.skill.dto;

/**
 * Skill 变量声明（{@code skill_versions.variables_schema} 的数组元素，api-spec §7.5.1）。
 *
 * <p>形态：{@code {"name","required","description","defaultValue"}}；数量 ≤
 * {@code sys_config: skill.max_variables}。
 *
 * <p>🔴 {@code required} 用包装类型 {@code Boolean}：JSON 里缺省该字段时必须能与
 * "显式写 false" 区分开（缺省按 {@code false} 处理，但不能因为拆箱 NPE 而炸成 500）。
 *
 * @param name         变量名，须匹配 {@code ^[a-zA-Z][a-zA-Z0-9_]{0,63}$}
 * @param required     是否必填；{@code true} 且三级取值均为空 → {@code 30060}
 * @param description  说明（仅供 DBA 阅读，不参与运行时）
 * @param defaultValue 默认值（取值优先级第 ②）
 */
public record SkillVariableDecl(String name,
                                Boolean required,
                                String description,
                                String defaultValue) {

    public boolean requiredOrFalse() {
        return Boolean.TRUE.equals(required);
    }
}
