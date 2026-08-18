package com.eyes.albedo.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillVariableDecl;
import com.eyes.albedo.skill.dto.SkillViolation;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Skill 版本的<b>运行时兜底校验</b>（api-spec §7.5.3 / AC-SKL-001 / AC-CFG-004）。
 *
 * <p><b>REQ-SKL-001 / REQ-SKL-002</b>
 *
 * <p>🔴 <b>为什么运行时还要再校验一遍</b>：一期租户配置由 DBA 直接写库（DEC-010），
 * 没有"保存时校验"这个应用层入口。AC-CFG-004 要求"运行时兜底以<b>当前库内</b>配置为准"，
 * 且非法配置必须在<b>进入模型之前</b>以 {@code 30060} 失败 ——
 * 🔴 禁止 NPE / 未分类 500 / 静默截断系统提示。
 *
 * <p>与 {@code configcheck.RuntimeConfigValidator} 的分工：
 * <ul>
 *   <li>{@code RuntimeConfigValidator} —— <b>批量、聚合式</b>校验（DBA 自查入口，收集全部
 *       {@code violations} 后一次返回，不抛异常中断）</li>
 *   <li>本类 —— <b>运行时、快速失败式</b>校验（生成链路上一旦发现非法立即抛
 *       {@code 30060}，带 {@code violations} 载荷）</li>
 * </ul>
 * 两者共用 {@link SkillVariableResolver} 的<b>同一套</b>变量规则（正则 / 保留名），
 * 避免"校验入口说合法、运行时说非法"的双标。
 */
@Slf4j
@Component
public class SkillValidator {

    private final SkillVariableResolver variableResolver;
    private final BusinessConfig businessConfig;
    private final ObjectMapper objectMapper;

    public SkillValidator(SkillVariableResolver variableResolver,
                          BusinessConfig businessConfig,
                          ObjectMapper objectMapper) {
        this.variableResolver = variableResolver;
        this.businessConfig = businessConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 {@code variables_schema}（JSON 数组）。
     *
     * @throws BusinessException 30060 不是合法 JSON 数组
     */
    public List<SkillVariableDecl> parseDeclarations(SkillVersion version) {
        String raw = version.getVariablesSchema();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<SkillVariableDecl> declarations =
                    objectMapper.readValue(raw, new TypeReference<List<SkillVariableDecl>>() {
                    });
            return declarations == null ? List.of() : declarations;
        } catch (Exception e) {
            // 🔴 只报字段名与规则，不回显 JSON 内容（可能含业务敏感默认值）
            throw reject(List.of(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION,
                    version.getId(), "variablesSchema", SkillViolation.RULE_FORMAT,
                    "变量声明必须是 JSON 数组")));
        }
    }

    /**
     * 解析绑定的 {@code variable_values}（JSON 对象 KV）。
     *
     * @throws BusinessException 30060 不是合法 JSON 对象
     */
    public Map<String, String> parseBindingValues(Long agentVersionId, String rawValues) {
        if (rawValues == null || rawValues.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> values =
                    objectMapper.readValue(rawValues, new TypeReference<Map<String, String>>() {
                    });
            return values == null ? Map.of() : values;
        } catch (Exception e) {
            throw reject(List.of(SkillViolation.of(SkillViolation.OBJECT_AGENT_VERSION,
                    agentVersionId, "variableValues", SkillViolation.RULE_FORMAT,
                    "绑定的变量取值必须是 JSON 对象（键值均为字符串）")));
        }
    }

    /**
     * 校验单个 Skill 版本的可注入性，并返回<b>已解析的变量取值</b>。
     *
     * <p>校验顺序（先结构、后内容，保证 violations 可读）：
     * <ol>
     *   <li>{@code instruction} 非空且 ≤ {@code sys_config: skill.instruction_max_chars}</li>
     *   <li>{@code outputConstraint} ≤ {@link SkillVersion#OUTPUT_CONSTRAINT_MAX_CHARS}</li>
     *   <li>声明数量 ≤ {@code sys_config: skill.max_variables}；变量名合法；无重复；不占保留名</li>
     *   <li>正文中使用的变量必须已声明（或是内置保留名）</li>
     *   <li>{@code required=true} 的变量三级取值不得全空</li>
     * </ol>
     *
     * @throws BusinessException 30060 任一项不通过（🔴 带全部 {@code violations}，一次给全便于 DBA 修）
     */
    public Map<String, String> validateForInjection(SkillVersion version,
                                                    List<SkillVariableDecl> declarations,
                                                    Map<String, String> bindingValues,
                                                    SkillRuntimeContext context) {
        List<SkillViolation> violations = new ArrayList<>();
        Long id = version.getId();

        // ① 指令正文长度
        String instruction = version.getInstruction();
        int instructionMax = businessConfig.requireInt(ConfigKeys.GROUP_SKILL,
                ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS);
        if (instruction == null || instruction.isBlank()) {
            violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                    "instruction", SkillViolation.RULE_REQUIRED, "指令正文不能为空"));
            instruction = "";
        } else if (instruction.codePointCount(0, instruction.length()) > instructionMax) {
            violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                    "instruction", SkillViolation.RULE_LENGTH,
                    "指令正文长度超过上限 " + instructionMax));
        }

        // ② 输出约束长度
        String outputConstraint = version.getOutputConstraint() == null
                ? "" : version.getOutputConstraint();
        if (outputConstraint.codePointCount(0, outputConstraint.length())
                > SkillVersion.OUTPUT_CONSTRAINT_MAX_CHARS) {
            violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                    "outputConstraint", SkillViolation.RULE_LENGTH,
                    "输出约束长度超过上限 " + SkillVersion.OUTPUT_CONSTRAINT_MAX_CHARS));
        }

        // ③ 声明本身
        int maxVariables = businessConfig.requireInt(ConfigKeys.GROUP_SKILL,
                ConfigKeys.SKILL_MAX_VARIABLES);
        if (declarations.size() > maxVariables) {
            violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                    "variablesSchema", SkillViolation.RULE_RANGE,
                    "声明变量数超过上限 " + maxVariables));
        }
        Set<String> declaredNames = new LinkedHashSet<>();
        for (SkillVariableDecl decl : declarations) {
            if (decl == null || decl.name() == null || decl.name().isBlank()) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                        "variablesSchema", SkillViolation.RULE_REQUIRED, "变量声明缺少 name"));
                continue;
            }
            String name = decl.name();
            if (!SkillVariableResolver.VARIABLE_NAME.matcher(name).matches()) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                        "variablesSchema", SkillViolation.RULE_FORMAT,
                        "变量名不符合 ^" + SkillVariableResolver.VARIABLE_NAME_REGEX + "$：" + name));
                continue;
            }
            if (SkillVariableResolver.RESERVED_VARIABLES.contains(name)) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                        "variablesSchema", SkillViolation.RULE_RESERVED_VARIABLE,
                        "变量名占用平台保留名：" + name));
                continue;
            }
            declaredNames.add(name);
        }
        for (String duplicated : variableResolver.duplicatedNames(declarations)) {
            violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                    "variablesSchema", SkillViolation.RULE_FORMAT,
                    "变量重复声明：" + duplicated));
        }

        // ④ 使用但未声明（内置保留名无需声明）
        Set<String> used = new LinkedHashSet<>(variableResolver.usedVariables(instruction));
        used.addAll(variableResolver.usedVariables(outputConstraint));
        for (String name : used) {
            if (!declaredNames.contains(name)
                    && !SkillVariableResolver.RESERVED_VARIABLES.contains(name)) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                        "instruction", SkillViolation.RULE_UNDECLARED_VARIABLE,
                        "引用了未声明变量：{{" + name + "}}"));
            }
        }

        // ⑤ 必填变量三级取值均为空
        Map<String, String> resolved =
                variableResolver.resolveValues(declarations, bindingValues, context);
        for (SkillVariableDecl decl : declarations) {
            if (decl == null || decl.name() == null || !decl.requiredOrFalse()) {
                continue;
            }
            String value = resolved.get(decl.name());
            if (value == null || value.isBlank()) {
                // 🔴 只报变量名，不回显任何取值
                violations.add(SkillViolation.of(SkillViolation.OBJECT_SKILL_VERSION, id,
                        "variablesSchema", SkillViolation.RULE_MISSING_VARIABLE_VALUE,
                        "必填变量缺少取值：" + decl.name()));
            }
        }

        if (!violations.isEmpty()) {
            throw reject(violations);
        }

        // required=false 且无值 → 空串（第 5 条：确定性行为，不失败）
        Map<String, String> finalValues = new java.util.LinkedHashMap<>(resolved);
        finalValues.replaceAll((name, value) -> value == null ? "" : value);
        return finalValues;
    }

    /**
     * 统一的 {@code 30060} 抛出口（载荷形态与 api-spec §7.3.1 的 {@code violations[]} 一致）。
     */
    public BusinessException reject(List<SkillViolation> violations) {
        log.warn("Skill 运行时配置非法，拒绝注入：violations={}", violations.size());
        return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置校验失败",
                Map.of("violations", List.copyOf(violations)));
    }
}
