package com.eyes.albedo.agent.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.eyes.albedo.agent.dto.AgentVersionDraft;
import com.eyes.albedo.agent.dto.ModelProvider;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.site.dto.ViolationDTO;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent 发布校验器（PRD §9.3 + 架构 §10 发布校验）。
 *
 * <p>校验分类对应 {@code 30021} 的 {@code violations[]}：必填、长度、区间、枚举、模型清单归属。
 *
 * <p>🔴 模型清单来自 {@code sys_config: model.providers}，禁止在代码里写死可选模型
 * （否则新增模型要改代码发版，违反反硬编码红线）。
 */
@Slf4j
@Component
public class AgentValidator {

    private static final Pattern AGENT_KEY = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    private static final int SYSTEM_PROMPT_MAX = 100_000;
    private static final int NAME_MAX = 60;
    private static final int DESCRIPTION_MAX = 300;
    private static final BigDecimal TEMPERATURE_MIN = BigDecimal.ZERO;
    private static final BigDecimal TEMPERATURE_MAX = new BigDecimal("2");
    private static final int TIMEOUT_MIN = 10;
    private static final int TIMEOUT_MAX = 300;
    private static final int SORT_ORDER_MAX = 9999;

    private final ConfigService configService;

    public AgentValidator(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 校验 Agent 主体字段。
     */
    public List<ViolationDTO> validateProfile(String agentKey, String name, String description,
                                              Integer sortOrder) {
        List<ViolationDTO> violations = new ArrayList<>();
        if (agentKey == null || agentKey.isBlank()) {
            violations.add(ViolationDTO.required("agentKey"));
        } else if (!AGENT_KEY.matcher(agentKey).matches()) {
            violations.add(new ViolationDTO("agentKey", "pattern",
                    "只允许小写字母开头，2~64 位小写字母、数字、下划线或连字符"));
        }
        if (name == null || name.isBlank()) {
            violations.add(ViolationDTO.required("name"));
        } else if (name.codePointCount(0, name.length()) > NAME_MAX) {
            violations.add(ViolationDTO.length("name", 1, NAME_MAX));
        }
        if (description != null && description.codePointCount(0, description.length()) > DESCRIPTION_MAX) {
            violations.add(ViolationDTO.length("description", 0, DESCRIPTION_MAX));
        }
        if (sortOrder != null && (sortOrder < 0 || sortOrder > SORT_ORDER_MAX)) {
            violations.add(new ViolationDTO("sortOrder", "range", "取值范围 0~" + SORT_ORDER_MAX));
        }
        return violations;
    }

    /**
     * 校验发布内容（模型、参数、策略）。
     */
    public List<ViolationDTO> validateVersion(AgentVersionDraft draft) {
        List<ViolationDTO> violations = new ArrayList<>();
        if (draft == null) {
            violations.add(ViolationDTO.required("version"));
            return violations;
        }
        String prompt = draft.systemPrompt();
        if (prompt == null || prompt.isBlank()) {
            violations.add(ViolationDTO.required("systemPrompt"));
        } else if (prompt.codePointCount(0, prompt.length()) > SYSTEM_PROMPT_MAX) {
            violations.add(ViolationDTO.length("systemPrompt", 1, SYSTEM_PROMPT_MAX));
        }

        validateModel(violations, draft.providerKey(), draft.model());

        BigDecimal temperature = draft.temperature();
        if (temperature == null) {
            violations.add(ViolationDTO.required("temperature"));
        } else if (temperature.compareTo(TEMPERATURE_MIN) < 0 || temperature.compareTo(TEMPERATURE_MAX) > 0) {
            violations.add(new ViolationDTO("temperature", "range", "取值范围 0~2"));
        }

        Integer maxOutputTokens = draft.maxOutputTokens();
        if (maxOutputTokens == null || maxOutputTokens < 1) {
            violations.add(new ViolationDTO("maxOutputTokens", "range", "必须为不小于 1 的整数"));
        }

        String strategy = draft.contextStrategy();
        if (strategy == null || strategy.isBlank()) {
            violations.add(ViolationDTO.required("contextStrategy"));
        } else if (!AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW.equals(strategy)
                && !AgentVersion.CONTEXT_STRATEGY_WINDOW.equals(strategy)) {
            violations.add(new ViolationDTO("contextStrategy", "enum",
                    "只允许 summary_then_window 或 window"));
        }

        Integer timeout = draft.requestTimeoutSeconds();
        if (timeout == null || timeout < TIMEOUT_MIN || timeout > TIMEOUT_MAX) {
            violations.add(new ViolationDTO("requestTimeoutSeconds", "range",
                    "取值范围 " + TIMEOUT_MIN + "~" + TIMEOUT_MAX + " 秒"));
        }

        String toolPolicy = draft.toolPolicy();
        if (toolPolicy == null || toolPolicy.isBlank()) {
            violations.add(ViolationDTO.required("toolPolicy"));
        } else if (!AgentVersion.TOOL_POLICY_DISABLED.equals(toolPolicy)) {
            // M1 不含工具运行时（Skill/MCP/Tool 编排在 M3），此处 fail-closed：
            // 允许发布 auto/confirm 会让线上出现"声明了能力但无法执行"的不可解释状态
            violations.add(new ViolationDTO("toolPolicy", "unsupported",
                    "当前里程碑仅支持 disabled，工具编排能力在 M3 提供"));
        }
        return violations;
    }

    private void validateModel(List<ViolationDTO> violations, String providerKey, String model) {
        if (providerKey == null || providerKey.isBlank()) {
            violations.add(ViolationDTO.required("providerKey"));
        }
        if (model == null || model.isBlank()) {
            violations.add(ViolationDTO.required("model"));
        }
        if (providerKey == null || model == null || providerKey.isBlank() || model.isBlank()) {
            return;
        }
        List<ModelProvider> providers = providers();
        Optional<ModelProvider> provider = providers.stream()
                .filter(p -> providerKey.equals(p.providerKey()))
                .findFirst();
        if (provider.isEmpty()) {
            violations.add(new ViolationDTO("providerKey", "notAllowed", "不在平台允许的模型提供方清单内"));
            return;
        }
        List<String> models = provider.get().models();
        if (models == null || !models.contains(model)) {
            violations.add(new ViolationDTO("model", "notAllowed", "不在该提供方允许的模型清单内"));
        }
    }

    /**
     * 平台允许的模型提供方清单。
     */
    public List<ModelProvider> providers() {
        return configService.getJson(ConfigKeys.GROUP_MODEL, ConfigKeys.MODEL_PROVIDERS,
                new TypeReference<List<ModelProvider>>() {
                }, List.of());
    }
}
