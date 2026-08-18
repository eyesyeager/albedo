package com.eyes.albedo.skill.dto;

/**
 * 运行时配置违规项（Skill 侧的 {@code 30060} 载荷元素）。
 *
 * <p>🔴 <b>为什么不复用 {@code configcheck.dto.ConfigViolationDTO}</b>：依赖方向要求
 * {@code configcheck → skill}（校验入口读取 Skill 数据），反向依赖会成环。
 * 因此本记录与 {@code ConfigViolationDTO} <b>形态完全一致</b>（5 个字段、同名 {@code rule} 字面量），
 * 由 {@code SkillValidatorTest} 断言两侧字面量逐一相等，防止漂移。
 *
 * <p>🔴 {@code message} 禁含：Skill 正文 / {@code systemPrompt} / 密钥 / 内部地址 / 堆栈
 * （api-spec §7.3.1、§7.5.2 第 4 条）。变量名可以出现（它本身是配置标识，不是内容）。
 *
 * @param objectType 对象类型（{@code skill} / {@code skillVersion} / {@code agentVersion}）
 * @param objectId   对象 ID（string，ADR-004）
 * @param field      字段名（对外 camelCase）
 * @param rule       规则分类
 * @param message    可展示说明
 */
public record SkillViolation(String objectType,
                             String objectId,
                             String field,
                             String rule,
                             String message) {

    // ===== objectType =====
    public static final String OBJECT_SKILL = "skill";
    public static final String OBJECT_SKILL_VERSION = "skillVersion";
    public static final String OBJECT_AGENT_VERSION = "agentVersion";

    // ===== rule 字面量（🔴 必须与 configcheck.dto.ConfigViolationDTO 完全一致） =====
    public static final String RULE_REQUIRED = "required";
    public static final String RULE_LENGTH = "length";
    public static final String RULE_FORMAT = "invalidFormat";
    public static final String RULE_RANGE = "outOfRange";
    public static final String RULE_REF_NOT_FOUND = "refNotFound";
    public static final String RULE_REF_UNAVAILABLE = "refUnavailable";
    public static final String RULE_CROSS_TENANT = "crossTenantReference";
    public static final String RULE_UNDECLARED_VARIABLE = "undeclaredVariable";
    public static final String RULE_RESERVED_VARIABLE = "reservedVariable";
    public static final String RULE_UNUSED_VARIABLE = "unusedVariable";
    /** 🔴 本轮新增（api-spec §7.5.3 第 4 条已定义该 rule 名，登记表无需新增错误码）。 */
    public static final String RULE_MISSING_VARIABLE_VALUE = "missingVariableValue";

    public static SkillViolation of(String objectType, Long objectId, String field,
                                    String rule, String message) {
        return new SkillViolation(objectType,
                objectId == null ? null : String.valueOf(objectId), field, rule, message);
    }
}
