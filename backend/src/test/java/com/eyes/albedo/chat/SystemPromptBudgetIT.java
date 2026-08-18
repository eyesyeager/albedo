package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.service.ContextAssembler;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.ViolationRules;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 🔴 <b>system 提示总长预算</b>（api-spec V1.1.3 §7.5.2 ① 裁决 / §7.1.2
 * {@code chat.system_prompt_max_chars}）。
 *
 * <p><b>本用例最关键的断言是"没有发生截断"</b>：
 * 契约<b>明确否决</b>了"只截断最低优先级片段"的做法 —— 注入顺序
 * （{@code systemPrompt} → {@code instruction} → {@code output_constraint}）本身是<b>语义依赖链</b>，
 * 截掉尾部 {@code output_constraint} 会让模型以"没有输出约束"的形态运行，
 * 表现为"AI 偶尔不守格式"却查不出原因（静默降级）。
 * 因此这里既断言"超限必抛 {@code 30060}"，也断言"未超限时片段<b>完整保留</b>"。
 */
@SpringBootTest
class SystemPromptBudgetIT {

    @Autowired
    private ContextAssembler contextAssembler;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;

    private SysConfigOverride override;
    private String tenantId;
    private long agentId;
    private long agentVersionId;
    private long conversationId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "spb" + suffix;
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "system 预算测试租户", tenantId + ".test.invalid");
        TenantContext.bind(new TenantContext.Snapshot(tenantId, 1L, tenantId + ".test.invalid",
                TenantStatus.ENABLED, 0L, 900000101L));
        agentId = insertAgent();
        agentVersionId = insertAgentVersion();
        conversationId = insertConversation();
        override = new SysConfigOverride(jdbcTemplate, configService);
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM skill_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM skills WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM conversations WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("🔴 §7.5.2 ①：多 Skill 叠加超 chat.system_prompt_max_chars → 30060 "
            + "rule=systemPromptBudgetExceeded（进模型之前失败）")
    void exceedingBudgetFailsBeforeModel() {
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "300");
        // 🔴 用不会出现在任何提示文案里的填充字，确保"message 未回显正文"的断言是真断言
        bindSkill(insertSkill("s1", "甲".repeat(200), ""), 1, 0);
        bindSkill(insertSkill("s2", "乙".repeat(200), ""), 1, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contextAssembler.assemble(tenantId, conversationId, runtime(), version()));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        String payload = String.valueOf(ex.getPayload());
        assertTrue(payload.contains(ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED),
                "🔴 violations[].rule 必须是 systemPromptBudgetExceeded：" + payload);
        assertTrue(payload.contains("systemPrompt"), payload);
        // 🔴 禁回正文片段（Skill 正文与 systemPrompt 都是内部资产）
        assertFalse(ex.getMessage().contains("甲"), "🔴 message 不得回显正文：" + ex.getMessage());
        assertFalse(payload.contains("乙"), "🔴 violations 不得回显正文：" + payload);
    }

    @Test
    @DisplayName("🔴 未超限时片段**完整保留**：output_constraint 一个字都没少（禁止任何截断）")
    void noTruncationWhenWithinBudget() {
        String instruction = "指".repeat(100);
        String constraint = "请用 JSON 输出，字段固定为 answer。";
        bindSkill(insertSkill("s1", instruction, constraint), 1, 0);

        List<AiMessage> context = contextAssembler.assemble(tenantId, conversationId, runtime(),
                version());

        String system = context.get(0).content();
        assertTrue(system.contains(instruction), "🔴 指令必须完整注入");
        assertTrue(system.endsWith(constraint),
                "🔴 output_constraint 必须完整保留在末尾（截断它 = 静默降级）：" + system);
    }

    @Test
    @DisplayName("🔴 边界：恰好等于上限 → 通过；再加 1 个码点 → 30060（且**不是**截断后通过）")
    void boundaryIsExactAndFailsClosed() {
        String instruction = "指".repeat(50);
        // system_prompt("系统提示"=4) + 分隔符(2) + instruction(50) = 56
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "56");
        bindSkill(insertSkill("s1", instruction, ""), 1, 0);

        List<AiMessage> context = contextAssembler.assemble(tenantId, conversationId, runtime(),
                version());
        assertEquals(56, context.get(0).content().codePointCount(0,
                context.get(0).content().length()), "前提：拼装结果恰好 56 个码点");

        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "55");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> contextAssembler.assemble(tenantId, conversationId, runtime(), version()));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("🔴 emoji 按码点计（不按 UTF-16 length）：同一份文本不因用了 emoji 而莫名超限")
    void emojiCountsAsSingleCodePoint() {
        // 20 个 emoji：UTF-16 length=40，码点=20
        String instruction = "😀".repeat(20);
        // system_prompt(4) + 分隔符(2) + 20 = 26
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "26");
        bindSkill(insertSkill("s1", instruction, ""), 1, 0);

        List<AiMessage> context = contextAssembler.assemble(tenantId, conversationId, runtime(),
                version());

        assertTrue(context.get(0).content().contains(instruction),
                "🔴 若按 UTF-16 计会误判超限（40 > 26）");
    }

    @Test
    @DisplayName("🔴 变量替换**后**才计长度（否则可用长变量值绕过预算）")
    void budgetCountsSubstitutedLength() {
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "80");
        // 模板本身很短（{{city}} 占 8 个码点），但取值有 200 个码点
        long skillId = insertSkill("s1", "城市：{{city}}",
                "[{\"name\":\"city\",\"required\":true}]", "");
        bindSkillWithValues(skillId, 1, 0,
                "{\"city\":\"" + "城".repeat(200) + "\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> contextAssembler.assemble(tenantId, conversationId, runtime(), version()));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload())
                        .contains(ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED),
                "🔴 必须按替换后长度判定，否则长变量值即为绕过通道");
    }

    @Test
    @DisplayName("无 Skill 绑定时只有 systemPrompt，默认预算下正常通过")
    void systemPromptOnlyPasses() {
        List<AiMessage> context = contextAssembler.assemble(tenantId, conversationId, runtime(),
                version());

        assertEquals("系统提示", context.get(0).content());
    }

    // ===================== 辅助 =====================

    private AgentRuntime runtime() {
        return new AgentRuntime(agentId, 1L, "系统提示", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.70"), 4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW,
                120, "auto");
    }

    private AgentVersion version() {
        AgentVersion version = new AgentVersion();
        version.setId(agentVersionId);
        version.setAgentId(agentId);
        version.setVersion(1L);
        version.setToolPolicy("auto");
        return version;
    }

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                + " status, is_default, current_version, version, sort_order)"
                + " VALUES (?,'spb-agent','预算测试助手','','enabled',1,1,0,0)", tenantId);
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion() {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',120,'auto','published')",
                tenantId, agentId);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertConversation() {
        jdbcTemplate.update("INSERT INTO conversations (tenant_id, uid, agent_id, agent_version,"
                        + " title, title_source, status, message_count, last_message_at, version)"
                        + " VALUES (?,900000101,?,1,'预算测试','auto','active',0,UTC_TIMESTAMP(3),0)",
                tenantId, agentId);
        return jdbcTemplate.queryForObject("SELECT id FROM conversations WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertSkill(String skillKey, String instruction, String outputConstraint) {
        return insertSkill(skillKey, instruction, "[]", outputConstraint);
    }

    private long insertSkill(String skillKey, String instruction, String variablesSchema,
                             String outputConstraint) {
        jdbcTemplate.update("INSERT INTO skills (tenant_id, skill_key, name, description, status,"
                        + " current_version, version) VALUES (?,?,?,'','enabled',1,0)",
                tenantId, skillKey, skillKey);
        long skillId = jdbcTemplate.queryForObject("SELECT id FROM skills WHERE tenant_id = ?"
                + " AND skill_key = ?", Long.class, tenantId, skillKey);
        jdbcTemplate.update("INSERT INTO skill_versions (tenant_id, skill_id, version, instruction,"
                        + " variables_schema, output_constraint, status)"
                        + " VALUES (?,?,1,?,?,?,'published')",
                tenantId, skillId, instruction, variablesSchema, outputConstraint);
        return skillId;
    }

    private void bindSkill(long skillId, int refVersion, int sortOrder) {
        bindSkillWithValues(skillId, refVersion, sortOrder, null);
    }

    private void bindSkillWithValues(long skillId, int refVersion, int sortOrder,
                                     String variableValues) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, variable_values, sort_order)"
                        + " VALUES (?,?,?,?,?,?,?)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_SKILL, skillId, refVersion,
                variableValues, sortOrder);
    }
}
