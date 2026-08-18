package com.eyes.albedo.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.configcheck.dto.ConfigViolationDTO;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillVariableDecl;
import com.eyes.albedo.skill.dto.SkillViolation;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Skill 运行时兜底校验单测（api-spec §7.5.3 / AC-SKL-001 / AC-CFG-004）。
 *
 * <p>🔴 全部失败路径必须是 {@code 30060} 且带字段级 {@code violations}，
 * 绝不允许 NPE / 未分类 500（EX-031）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillValidatorTest {

    private static final int INSTRUCTION_MAX = 50000;
    private static final int MAX_VARIABLES = 50;

    @Mock
    private BusinessConfig businessConfig;

    private SkillValidator validator;

    private final SkillRuntimeContext context =
            new SkillRuntimeContext("gift", "zh-CN", "Asia/Shanghai", java.time.Instant.now());

    @BeforeEach
    void setUp() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_SKILL),
                eq(ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS))).thenReturn(INSTRUCTION_MAX);
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_SKILL),
                eq(ConfigKeys.SKILL_MAX_VARIABLES))).thenReturn(MAX_VARIABLES);
        validator = new SkillValidator(new SkillVariableResolver(), businessConfig,
                new ObjectMapper());
    }

    @Test
    @DisplayName("🔴 rule 字面量必须与 configcheck.ConfigViolationDTO 完全一致（防两套校验双标）")
    void ruleLiteralsStayInSync() {
        assertEquals(ConfigViolationDTO.RULE_REQUIRED, SkillViolation.RULE_REQUIRED);
        assertEquals(ConfigViolationDTO.RULE_LENGTH, SkillViolation.RULE_LENGTH);
        assertEquals(ConfigViolationDTO.RULE_FORMAT, SkillViolation.RULE_FORMAT);
        assertEquals(ConfigViolationDTO.RULE_RANGE, SkillViolation.RULE_RANGE);
        assertEquals(ConfigViolationDTO.RULE_REF_NOT_FOUND, SkillViolation.RULE_REF_NOT_FOUND);
        assertEquals(ConfigViolationDTO.RULE_REF_UNAVAILABLE, SkillViolation.RULE_REF_UNAVAILABLE);
        assertEquals(ConfigViolationDTO.RULE_CROSS_TENANT, SkillViolation.RULE_CROSS_TENANT);
        assertEquals(ConfigViolationDTO.RULE_UNDECLARED_VARIABLE,
                SkillViolation.RULE_UNDECLARED_VARIABLE);
        assertEquals(ConfigViolationDTO.RULE_RESERVED_VARIABLE,
                SkillViolation.RULE_RESERVED_VARIABLE);
        assertEquals(ConfigViolationDTO.RULE_UNUSED_VARIABLE, SkillViolation.RULE_UNUSED_VARIABLE);
    }

    @Test
    @DisplayName("合法：required=false 且无值 → 替换为空串（§7.5.3-5 确定性行为，不失败）")
    void optionalWithoutValueBecomesEmptyString() {
        SkillVersion version = version("你好 {{nickname}}！",
                "[{\"name\":\"nickname\",\"required\":false}]");

        Map<String, String> values = validator.validateForInjection(version,
                validator.parseDeclarations(version), Map.of(), context);

        assertEquals("", values.get("nickname"));
    }

    @Test
    @DisplayName("🔴 §7.5.3-3：未声明即使用 → 30060 rule=undeclaredVariable")
    void undeclaredVariableRejected() {
        SkillVersion version = version("你好 {{userName}}", "[]");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(hasRule(ex, SkillViolation.RULE_UNDECLARED_VARIABLE), payload(ex));
    }

    @Test
    @DisplayName("🔴 §7.5.3-4：required=true 但三级取值均空 → 30060 rule=missingVariableValue")
    void missingRequiredValueRejected() {
        SkillVersion version = version("城市：{{city}}", "[{\"name\":\"city\",\"required\":true}]");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));

        assertTrue(hasRule(ex, SkillViolation.RULE_MISSING_VARIABLE_VALUE), payload(ex));
        // 🔴 只报变量名，不回显取值
        assertTrue(payload(ex).contains("city"));
    }

    @Test
    @DisplayName("🔴 §7.5.3-2③：声明占用平台保留名（tenantId/locale/timezone/nowIso）→ 30060")
    void reservedVariableRejected() {
        SkillVersion version = version("租户 {{tenantId}}",
                "[{\"name\":\"tenantId\",\"required\":false}]");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));

        assertTrue(hasRule(ex, SkillViolation.RULE_RESERVED_VARIABLE), payload(ex));
    }

    @Test
    @DisplayName("内置变量无需声明即可使用（不报 undeclaredVariable）")
    void builtinVariablesNeedNoDeclaration() {
        SkillVersion version = version("租户 {{tenantId}}，时间 {{nowIso}}", "[]");
        Map<String, String> values = validator.validateForInjection(version,
                validator.parseDeclarations(version), Map.of(), context);
        assertEquals("gift", values.get("tenantId"));
    }

    @Test
    @DisplayName("变量数超过 sys_config: skill.max_variables → 30060 rule=outOfRange")
    void tooManyVariables() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_SKILL),
                eq(ConfigKeys.SKILL_MAX_VARIABLES))).thenReturn(2);
        SkillVersion version = version("{{a}}{{b}}{{c}}",
                "[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));
        assertTrue(hasRule(ex, SkillViolation.RULE_RANGE), payload(ex));
    }

    @Test
    @DisplayName("指令正文超过 sys_config: skill.instruction_max_chars → 30060 rule=length")
    void instructionTooLong() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_SKILL),
                eq(ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS))).thenReturn(10);
        SkillVersion version = version("超过十个字符的指令正文内容", "[]");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));
        assertTrue(hasRule(ex, SkillViolation.RULE_LENGTH), payload(ex));
    }

    @Test
    @DisplayName("variables_schema 非 JSON 数组 → 30060 rule=invalidFormat（不回显 JSON 内容）")
    void malformedVariablesSchema() {
        SkillVersion version = version("正文", "{\"not\":\"an array\"}");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.parseDeclarations(version));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(hasRule(ex, SkillViolation.RULE_FORMAT), payload(ex));
    }

    @Test
    @DisplayName("绑定 variable_values 非 JSON 对象 → 30060 rule=invalidFormat")
    void malformedBindingValues() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.parseBindingValues(7L, "[\"a\"]"));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("重复声明 → 30060 rule=invalidFormat")
    void duplicatedDeclaration() {
        SkillVersion version = version("{{a}}",
                "[{\"name\":\"a\",\"required\":false,\"defaultValue\":\"x\"},"
                        + "{\"name\":\"a\",\"required\":false,\"defaultValue\":\"y\"}]");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));
        assertTrue(payload(ex).contains("重复声明"), payload(ex));
    }

    @Test
    @DisplayName("输出约束也参与变量校验（未声明变量藏在 outputConstraint 里同样要被拦下）")
    void outputConstraintParticipatesInValidation() {
        SkillVersion version = version("正文无变量", "[]");
        version.setOutputConstraint("请用 {{tone}} 的语气");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateForInjection(version,
                        validator.parseDeclarations(version), Map.of(), context));
        assertTrue(hasRule(ex, SkillViolation.RULE_UNDECLARED_VARIABLE), payload(ex));
    }

    // ===================== 辅助 =====================

    private SkillVersion version(String instruction, String variablesSchema) {
        SkillVersion version = new SkillVersion();
        version.setSkillId(1L);
        version.setVersion(1);
        version.setInstruction(instruction);
        version.setVariablesSchema(variablesSchema);
        version.setOutputConstraint("");
        version.setStatus(SkillVersion.STATUS_PUBLISHED);
        return version;
    }

    @SuppressWarnings("unchecked")
    private boolean hasRule(BusinessException ex, String rule) {
        Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
        List<SkillViolation> violations = (List<SkillViolation>) payload.get("violations");
        return violations.stream().anyMatch(v -> rule.equals(v.rule()));
    }

    private String payload(BusinessException ex) {
        return String.valueOf(ex.getPayload());
    }
}
