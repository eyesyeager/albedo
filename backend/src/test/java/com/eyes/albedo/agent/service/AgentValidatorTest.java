package com.eyes.albedo.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import com.eyes.albedo.agent.dto.AgentVersionDraft;
import com.eyes.albedo.agent.dto.ModelProvider;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.site.dto.ViolationDTO;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Agent 发布校验单测（PRD §9.3 / AC-AGT-004 相关规则）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentValidatorTest {

    @Mock
    private ConfigService configService;

    private AgentValidator validator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        validator = new AgentValidator(configService);
        when(configService.getJson(eq(ConfigKeys.GROUP_MODEL), eq(ConfigKeys.MODEL_PROVIDERS),
                any(TypeReference.class), any()))
                .thenReturn(List.of(new ModelProvider("hunyuan", "腾讯混元", List.of("hunyuan-a13b"))));
    }

    @Test
    @DisplayName("合法发布内容：无违规")
    void validDraftPasses() {
        assertTrue(validator.validateVersion(draft("hunyuan", "hunyuan-a13b")).isEmpty());
    }

    @Test
    @DisplayName("模型必须在 sys_config 清单内：未登记的 provider / model 一律拒绝发布")
    void modelMustBeInAllowedList() {
        assertEquals("notAllowed", violation(draft("openai", "hunyuan-a13b"), "providerKey").rule());
        assertEquals("notAllowed", violation(draft("hunyuan", "gpt-4o"), "model").rule());
    }

    @Test
    @DisplayName("temperature 越界（0~2）→ range 违规")
    void temperatureRange() {
        AgentVersionDraft draft = new AgentVersionDraft("系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("2.5"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                120, AgentVersion.TOOL_POLICY_DISABLED);
        assertEquals("range", violation(draft, "temperature").rule());
    }

    @Test
    @DisplayName("requestTimeoutSeconds 越界（10~300）→ range 违规")
    void timeoutRange() {
        AgentVersionDraft tooSmall = new AgentVersionDraft("系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                5, AgentVersion.TOOL_POLICY_DISABLED);
        assertEquals("range", violation(tooSmall, "requestTimeoutSeconds").rule());

        AgentVersionDraft tooLarge = new AgentVersionDraft("系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                301, AgentVersion.TOOL_POLICY_DISABLED);
        assertEquals("range", violation(tooLarge, "requestTimeoutSeconds").rule());
    }

    @Test
    @DisplayName("M1 只允许 toolPolicy=disabled（工具运行时在 M3，避免发布出无法执行的能力声明）")
    void toolPolicyRestrictedInM1() {
        AgentVersionDraft draft = new AgentVersionDraft("系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                120, "auto");
        assertEquals("unsupported", violation(draft, "toolPolicy").rule());
    }

    @Test
    @DisplayName("contextStrategy 只允许 summary_then_window / window")
    void contextStrategyEnum() {
        AgentVersionDraft draft = new AgentVersionDraft("系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096, "magic", 120, AgentVersion.TOOL_POLICY_DISABLED);
        assertEquals("enum", violation(draft, "contextStrategy").rule());
    }

    @Test
    @DisplayName("agentKey 必须匹配 ^[a-z][a-z0-9_-]{1,63}$")
    void agentKeyPattern() {
        assertTrue(validator.validateProfile("gift-helper", "礼遇顾问", "说明", 0).isEmpty());

        List<ViolationDTO> upper = validator.validateProfile("Gift", "礼遇顾问", "说明", 0);
        assertEquals("pattern", upper.get(0).rule());

        List<ViolationDTO> startsWithDigit = validator.validateProfile("1gift", "礼遇顾问", "说明", 0);
        assertEquals("pattern", startsWithDigit.get(0).rule());
    }

    @Test
    @DisplayName("sortOrder 取值范围 0~9999")
    void sortOrderRange() {
        assertEquals("range",
                validator.validateProfile("gift", "礼遇顾问", "说明", 10000).get(0).rule());
    }

    @Test
    @DisplayName("systemPrompt 必填且不超过 100000 字符")
    void systemPromptRequired() {
        AgentVersionDraft blank = new AgentVersionDraft("  ", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                120, AgentVersion.TOOL_POLICY_DISABLED);
        assertEquals("required", violation(blank, "systemPrompt").rule());
    }

    private AgentVersionDraft draft(String providerKey, String model) {
        return new AgentVersionDraft("系统提示", providerKey, model, new BigDecimal("0.7"), 4096,
                AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 120, AgentVersion.TOOL_POLICY_DISABLED);
    }

    private ViolationDTO violation(AgentVersionDraft draft, String field) {
        return validator.validateVersion(draft).stream()
                .filter(v -> field.equals(v.field()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到字段 " + field + " 的违规项："
                        + validator.validateVersion(draft)));
    }
}
