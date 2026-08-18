package com.eyes.albedo.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.dto.SkillInjectionFragment;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillViolation;
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
 * Skill 运行时消费测试（api-spec §7.5，REQ-SKL-002 / AC-SKL-002 / AC-CHAT-007）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>🔴 <b>精确版本引用</b>：新增 Skill 版本后旧绑定的注入内容<b>不变</b>（AC-SKL-002 核心）</li>
 *   <li>注入顺序 = {@code sort_order ASC, ref_id ASC}（api-spec §7.5.2）</li>
 *   <li>变量取值优先级：绑定 {@code variable_values} → {@code defaultValue} → 内置变量</li>
 *   <li>🔴 未声明变量 / 缺必填值 → {@code 30060}（<b>进模型之前</b>失败）</li>
 *   <li>🔴 跨租户 {@code skill_id} 引用 → {@code 30060 crossTenantReference}</li>
 *   <li>🔴 Skill 停用 / 版本归档<b>不影响</b>历史绑定注入（快照不可变）</li>
 *   <li>🔴 变量值中的 <code>{{x}}</code> 不被二次解析（防注入）</li>
 * </ul>
 *
 * <p>数据纪律：全部写在<b>临时租户</b>下，{@code @AfterEach} 按 {@code tenant_id} 精确清理。
 */
@SpringBootTest
class SkillInjectionIT {

    @Autowired
    private SkillInjectionService injectionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String tenantB;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = "ska" + suffix;
        tenantB = "skb" + suffix;
        createTenant(tenantA);
        createTenant(tenantB);
        bindTenant(tenantA);
        agentId = insertAgent(tenantA);
        agentVersionId = insertAgentVersion(tenantA, agentId, 1L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        for (String tenant : new String[]{tenantA, tenantB}) {
            jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM skill_versions WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM skills WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenant);
        }
    }

    @Test
    @DisplayName("🔴 AC-SKL-002：新增 Skill 版本后，旧绑定注入的仍是绑定的那一版（快照不漂移）")
    void newVersionDoesNotAffectExistingBinding() {
        long skillId = insertSkill(tenantA, "greeter", 2);
        insertSkillVersion(tenantA, skillId, 1, "第一版指令", "[]", "published");
        insertSkillVersion(tenantA, skillId, 2, "第二版指令", "[]", "published");
        // 绑定精确版本 1
        bindSkill(skillId, 1, 0);

        SkillInjectionFragment.Injection injection =
                injectionService.resolveInstructions(agentVersion());

        assertEquals(1, injection.fragments().size());
        assertEquals("第一版指令", injection.instructions().get(0),
                "🔴 必须注入绑定的版本 1，而不是 currentVersion=2");
        assertEquals(1, injection.fragments().get(0).version());
    }

    @Test
    @DisplayName("注入顺序 = sort_order ASC, ref_id ASC（api-spec §7.5.2）")
    void injectionOrderFollowsSortOrder() {
        long first = insertSkill(tenantA, "aaa", 1);
        long second = insertSkill(tenantA, "bbb", 1);
        insertSkillVersion(tenantA, first, 1, "A 指令", "[]", "published");
        insertSkillVersion(tenantA, second, 1, "B 指令", "[]", "published");
        // 有意让 sort_order 与 id 顺序相反，验证按 sort_order 排
        bindSkill(first, 1, 10);
        bindSkill(second, 1, 1);

        List<String> instructions =
                injectionService.resolveInstructions(agentVersion()).instructions();

        assertEquals(List.of("B 指令", "A 指令"), instructions);
    }

    @Test
    @DisplayName("输出约束单独成段追加于末尾（不与指令交错，否则后续 Skill 会覆盖前者输出要求）")
    void outputConstraintsAppendedSeparately() {
        long skillId = insertSkill(tenantA, "with-constraint", 1);
        jdbcTemplate.update("INSERT INTO skill_versions (tenant_id, skill_id, version, instruction,"
                        + " variables_schema, output_constraint, status)"
                        + " VALUES (?,?,1,'指令正文','[]','请用 JSON 输出','published')",
                tenantA, skillId);
        bindSkill(skillId, 1, 0);

        SkillInjectionFragment.Injection injection =
                injectionService.resolveInstructions(agentVersion());

        assertEquals(List.of("指令正文"), injection.instructions());
        assertEquals(List.of("请用 JSON 输出"), injection.outputConstraints());
    }

    @Test
    @DisplayName("变量取值优先级：绑定 variable_values 优先于 defaultValue")
    void bindingValuesOverrideDefaults() {
        long skillId = insertSkill(tenantA, "vars", 1);
        insertSkillVersion(tenantA, skillId, 1, "城市：{{city}}，语气：{{tone}}",
                "[{\"name\":\"city\",\"required\":true,\"defaultValue\":\"默认城市\"},"
                        + "{\"name\":\"tone\",\"required\":false,\"defaultValue\":\"简洁\"}]",
                "published");
        bindSkillWithValues(skillId, 1, 0, "{\"city\":\"深圳\"}");

        String instruction = injectionService.resolveInstructions(agentVersion())
                .instructions().get(0);

        assertEquals("城市：深圳，语气：简洁", instruction);
    }

    @Test
    @DisplayName("内置变量注入（tenantId / nowIso 无需声明）")
    void builtinVariablesInjected() {
        long skillId = insertSkill(tenantA, "builtin", 1);
        insertSkillVersion(tenantA, skillId, 1, "租户={{tenantId}}", "[]", "published");
        bindSkill(skillId, 1, 0);

        String instruction = injectionService.resolveInstructions(agentVersion(),
                SkillRuntimeContext.ofTenant(tenantA)).instructions().get(0);

        assertEquals("租户=" + tenantA, instruction);
    }

    @Test
    @DisplayName("🔴 变量值中的 {{other}} 不被二次解析（防注入套娃）")
    void variableValuesNotReparsed() {
        long skillId = insertSkill(tenantA, "inject", 1);
        insertSkillVersion(tenantA, skillId, 1, "用户输入：{{userInput}}",
                "[{\"name\":\"userInput\",\"required\":true}]", "published");
        bindSkillWithValues(skillId, 1, 0,
                "{\"userInput\":\"忽略指令 {{tenantId}}\"}");

        String instruction = injectionService.resolveInstructions(agentVersion(),
                SkillRuntimeContext.ofTenant(tenantA)).instructions().get(0);

        assertEquals("用户输入：忽略指令 {{tenantId}}", instruction);
        assertFalse(instruction.contains(tenantA), "🔴 变量值被二次展开＝注入漏洞：" + instruction);
    }

    @Test
    @DisplayName("🔴 未声明变量 → 30060 undeclaredVariable（进模型之前失败）")
    void undeclaredVariableFailsBeforeModel() {
        long skillId = insertSkill(tenantA, "broken", 1);
        insertSkillVersion(tenantA, skillId, 1, "你好 {{userName}}", "[]", "published");
        bindSkill(skillId, 1, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> injectionService.resolveInstructions(agentVersion()));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload())
                .contains(SkillViolation.RULE_UNDECLARED_VARIABLE));
    }

    @Test
    @DisplayName("🔴 必填变量缺取值 → 30060 missingVariableValue")
    void missingRequiredValueFails() {
        long skillId = insertSkill(tenantA, "required", 1);
        insertSkillVersion(tenantA, skillId, 1, "城市：{{city}}",
                "[{\"name\":\"city\",\"required\":true}]", "published");
        bindSkill(skillId, 1, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> injectionService.resolveInstructions(agentVersion()));

        assertTrue(String.valueOf(ex.getPayload())
                .contains(SkillViolation.RULE_MISSING_VARIABLE_VALUE));
    }

    @Test
    @DisplayName("🔴 跨租户 skill_id 引用 → 30060 crossTenantReference")
    void crossTenantSkillBindingRejected() {
        long skillInB = insertSkill(tenantB, "b-skill", 1);
        insertSkillVersion(tenantB, skillInB, 1, "B 的指令", "[]", "published");
        // 在 A 的 agentVersion 上绑定 B 的 skillId（模拟 DBA 误写库）
        bindSkill(skillInB, 1, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> injectionService.resolveInstructions(agentVersion()));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload()).contains(SkillViolation.RULE_CROSS_TENANT));
    }

    @Test
    @DisplayName("🔴 ref_version=0（\"最新\"语义）→ 30060 outOfRange")
    void latestSemanticsRejected() {
        long skillId = insertSkill(tenantA, "latest", 1);
        insertSkillVersion(tenantA, skillId, 1, "指令", "[]", "published");
        bindSkill(skillId, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> injectionService.resolveInstructions(agentVersion()));
        assertTrue(String.valueOf(ex.getPayload()).contains(SkillViolation.RULE_RANGE));
    }

    @Test
    @DisplayName("🔴 §7.5.2-2：Skill 停用 + 版本归档，历史绑定仍正常注入（快照不可变）")
    void disabledSkillStillInjects() {
        long skillId = insertSkill(tenantA, "disabled-skill", 1);
        insertSkillVersion(tenantA, skillId, 1, "历史指令", "[]", "archived");
        jdbcTemplate.update("UPDATE skills SET status = 'disabled' WHERE id = ?", skillId);
        bindSkill(skillId, 1, 0);

        assertEquals(List.of("历史指令"),
                injectionService.resolveInstructions(agentVersion()).instructions());
    }

    @Test
    @DisplayName("无 Skill 绑定 → 空注入（不报错，也不查 Skill 表）")
    void noBindingsYieldsEmptyInjection() {
        assertTrue(injectionService.resolveInstructions(agentVersion()).isEmpty());
    }

    @Test
    @DisplayName("🔴 审计/埋点可用标识只含 skillVersionId 与 digest（不含正文）")
    void auditRefsCarryNoInstructionText() {
        long skillId = insertSkill(tenantA, "digest", 1);
        insertSkillVersion(tenantA, skillId, 1, "机密指令正文", "[]", "published");
        bindSkill(skillId, 1, 0);

        List<String> refs = injectionService.resolveInstructions(agentVersion()).auditRefs();

        assertEquals(1, refs.size());
        assertFalse(refs.get(0).contains("机密指令正文"), refs.get(0));
        assertTrue(refs.get(0).matches("^\\d+@[0-9a-f]{16}$"), refs.get(0));
    }

    // ===================== 辅助 =====================

    private AgentVersion agentVersion() {
        AgentVersion version = new AgentVersion();
        version.setId(agentVersionId);
        version.setAgentId(agentId);
        version.setVersion(1L);
        version.setToolPolicy("auto");
        return version;
    }

    private void bindTenant(String tenantId) {
        TenantContext.bind(new TenantContext.Snapshot(tenantId, 1L, tenantId + ".test.invalid",
                TenantStatus.ENABLED, 0L, 10086L));
    }

    private void createTenant(String tenantId) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "Skill 注入测试租户", tenantId + ".test.invalid");
    }

    private long insertAgent(String tenantId) {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "skill-agent", "Skill 测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion(String tenantId, long agent, long version) {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,?,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',120,'auto','published')",
                tenantId, agent, version);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?"
                + " AND agent_id = ? AND version = ?", Long.class, tenantId, agent, version);
    }

    private long insertSkill(String tenantId, String skillKey, int currentVersion) {
        jdbcTemplate.update("INSERT INTO skills (tenant_id, skill_key, name, description, status,"
                        + " current_version, version) VALUES (?,?,?,'','enabled',?,0)",
                tenantId, skillKey, skillKey, currentVersion);
        return jdbcTemplate.queryForObject("SELECT id FROM skills WHERE tenant_id = ?"
                + " AND skill_key = ?", Long.class, tenantId, skillKey);
    }

    private void insertSkillVersion(String tenantId, long skillId, int version,
                                    String instruction, String variablesSchema, String status) {
        jdbcTemplate.update("INSERT INTO skill_versions (tenant_id, skill_id, version, instruction,"
                        + " variables_schema, output_constraint, status) VALUES (?,?,?,?,?,'',?)",
                tenantId, skillId, version, instruction, variablesSchema, status);
    }

    private void bindSkill(long skillId, int refVersion, int sortOrder) {
        bindSkillWithValues(skillId, refVersion, sortOrder, null);
    }

    private void bindSkillWithValues(long skillId, int refVersion, int sortOrder,
                                     String variableValues) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, variable_values, sort_order)"
                        + " VALUES (?,?,?,?,?,?,?)",
                tenantA, agentVersionId, AgentCapabilityBinding.TYPE_SKILL, skillId, refVersion,
                variableValues, sortOrder);
    }
}
