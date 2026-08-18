package com.eyes.albedo.configcheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.configcheck.dto.ConfigValidationReportDTO;
import com.eyes.albedo.configcheck.dto.ConfigViolationDTO;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;
import com.eyes.albedo.tool.ToolRiskPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 🔴 <b>G10 收尾</b>：{@code objectType=agentVersion} 的能力绑定引用链递归校验
 * （api-spec §7.3.1 G10 表，<b>M3 签署条件之一</b>）。
 *
 * <p>逐条对应契约判据：
 * <ul>
 *   <li>递归深度<b>固定 2 层</b>：绑定 → (skill+版本 / mcpTool+服务 / localTool 授权+平台注册)</li>
 *   <li>第 2 层非法（版本 {@code archived} / endpoint 内网 / {@code granted=0}）→ {@code 30060}
 *       且 {@code violations[]} <b>精确指向第 2 层对象</b></li>
 *   <li>🔴 查询次数 <b>≤4</b>（禁 N+1）—— 用 {@code validateWithQueryCount} 直接断言</li>
 *   <li>🔴 {@code includeReferences=true} 且完成递归 → {@code warnings} 中
 *       <b>不再出现</b> {@code referencesNotFullyChecked}；
 *       {@code includeReferences=false} 时<b>必须继续出现</b></li>
 *   <li>🔴 只在聚合层可见的两项：system 提示预算、模型函数名碰撞</li>
 *   <li>🔴 环检测：{@code circularReference}（fail-closed，防御性）</li>
 * </ul>
 *
 * <p>🔴 本用例直接调用 {@code RuntimeConfigValidator}（而不是走 HTTP）：
 * 需要断言"查询次数"这类<b>无法从响应体观察</b>的纪律；HTTP 层的权限/错误码已由
 * {@code ConfigValidateIT} 覆盖，两者不重复。租户上下文用 {@code TenantContext.bind} 显式建立。
 */
@SpringBootTest
class AgentVersionRecursiveValidateIT {

    @Autowired
    private RuntimeConfigValidator validator;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;

    private SysConfigOverride override;
    private String tenantId;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "g10" + suffix;
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "G10 递归校验租户", "g10-" + suffix + ".test.invalid");
        // 🔴 直接建立租户上下文：本用例不经 HTTP，Hibernate discriminator 需要它
        TenantContext.bind(new TenantContext.Snapshot(tenantId, 1L,
                "g10-" + suffix + ".test.invalid", TenantStatus.ENABLED, 0L, 900000091L));
        agentId = insertAgent();
        agentVersionId = insertAgentVersion(ToolRiskPolicy.POLICY_AUTO, "系统提示");
        override = new SysConfigOverride(jdbcTemplate, configService);
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM skill_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM skills WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM mcp_tools WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM mcp_servers WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE 'it_g10_%'");
        TenantContext.clear();
    }

    // ===================== warnings 消失规则 =====================

    @Test
    @DisplayName("🔴 G10：includeReferences=true 且完成递归 → warnings 不再含 referencesNotFullyChecked")
    void warningDisappearsWhenReferencesChecked() {
        bindSkill(insertPublishedSkill("greeter", "请礼貌作答。"), 1);

        ConfigValidationReportDTO report = validate(true);

        assertTrue(report.valid(), "合法绑定链必须 valid=true：" + report.violations());
        assertFalse(hasRule(report, ConfigViolationDTO.RULE_REFERENCES_NOT_FULLY_CHECKED),
                "🔴 已完成递归后不得再输出该 warning（否则调用方无法判断是否真查了）");
        assertTrue(report.checkedObjects() >= 4,
                "checkedObjects 必须含 agentVersion + 绑定 + skill + skillVersion，实际="
                        + report.checkedObjects());
    }

    @Test
    @DisplayName("🔴 G10：includeReferences=false 时 warning **必须继续出现**（确实没查引用）")
    void warningRemainsWhenReferencesSkipped() {
        bindSkill(insertPublishedSkill("greeter", "请礼貌作答。"), 1);

        ConfigValidationReportDTO report = validate(false);

        assertTrue(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_REFERENCES_NOT_FULLY_CHECKED),
                "🔴 不得因'功能已补齐'就不再提示：调用方必须能区分「没查」与「查了没问题」");
    }

    // ===================== 查询收敛 =====================

    @Test
    @DisplayName("🔴 G10：三类绑定全在场时递归批量查询 ≤4 次（绑定表 1 + 三类各 1，禁 N+1）")
    void recursionUsesAtMostFourQueries() {
        // 三类绑定各两条 —— 若实现是 N+1，查询数会随绑定条数增长
        bindSkill(insertPublishedSkill("s1", "指令一"), 1);
        bindSkill(insertPublishedSkill("s2", "指令二"), 1);
        long mcpId = insertMcp("crm", "https://mcp.example.com/v1");
        bindMcpTool(insertMcpTool(mcpId, "crm:lookup_user"));
        bindMcpTool(insertMcpTool(mcpId, "crm:create_ticket"));
        bindLocalTool(insertGrant(insertLocalTool("it_g10_a")));
        bindLocalTool(insertGrant(insertLocalTool("it_g10_b")));

        RuntimeConfigValidator.ValidationOutcome outcome = validator.validateWithQueryCount(
                RuntimeConfigValidator.TYPE_AGENT_VERSION, String.valueOf(agentVersionId), true);

        assertTrue(outcome.referenceQueries() <= 4,
                "🔴 递归查询次数必须 ≤4，实际=" + outcome.referenceQueries());
        assertEquals(4, outcome.referenceQueries(),
                "三类绑定都在场时应恰好 4 次（绑定表 + skill/mcpTool/localTool 各一次批量查）");
    }

    @Test
    @DisplayName("绑定为空时不发起第 2 层查询（只查绑定表 1 次）")
    void noBindingsMeansSingleQuery() {
        RuntimeConfigValidator.ValidationOutcome outcome = validator.validateWithQueryCount(
                RuntimeConfigValidator.TYPE_AGENT_VERSION, String.valueOf(agentVersionId), true);

        assertEquals(1, outcome.referenceQueries());
    }

    // ===================== 第 2 层非法（契约验收判据）=====================

    @Test
    @DisplayName("🔴 G10 判据：skill_versions.status=archived → 30060 且 violations 指向第 2 层")
    void archivedSkillVersionRejected() {
        long skillId = insertPublishedSkill("greeter", "请礼貌作答。");
        jdbcTemplate.update("UPDATE skill_versions SET status = 'archived' WHERE tenant_id = ?"
                + " AND skill_id = ?", tenantId, skillId);
        bindSkill(skillId, 1);

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_REF_UNAVAILABLE));
        assertTrue(report.violations().stream()
                        .anyMatch(v -> "skillVersion".equals(v.objectType())),
                "🔴 violations 必须精确指向第 2 层对象（skillVersion）：" + report.violations());
    }

    @Test
    @DisplayName("🔴 G10 判据：mcp_servers.endpoint 改为内网地址 → 30060 rule=ssrfRejected（第 2 层）")
    void internalEndpointRejected() {
        long mcpId = insertMcp("crm", "https://10.0.0.9/v1");
        bindMcpTool(insertMcpTool(mcpId, "crm:lookup_user"));

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_SSRF_REJECTED));
        assertTrue(report.violations().stream().anyMatch(v -> "mcp".equals(v.objectType())));
        // 🔴 不得回显解析出的 IP / 内部地址
        assertTrue(report.violations().stream()
                        .noneMatch(v -> v.message() != null && v.message().contains("10.0.0.9")),
                "🔴 violations[].message 禁止回显内部地址");
    }

    @Test
    @DisplayName("🔴 G10 判据：tenant_tool_grants.granted=0 → 30060 rule=refUnavailable（第 2 层）")
    void revokedLocalGrantRejected() {
        long grantId = insertGrant(insertLocalTool("it_g10_a"));
        jdbcTemplate.update("UPDATE tenant_tool_grants SET granted = 0 WHERE id = ?", grantId);
        bindLocalTool(grantId);

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_REF_UNAVAILABLE));
        assertTrue(report.violations().stream()
                .anyMatch(v -> "toolGrant".equals(v.objectType())));
    }

    @Test
    @DisplayName("🔴 授权行被删除重建导致绑定悬挂 → refNotFound（fail-closed，而非静默少一个工具）")
    void danglingGrantBindingReported() {
        long grantId = insertGrant(insertLocalTool("it_g10_a"));
        bindLocalTool(grantId);
        // 模拟运维违规 DELETE + INSERT（architecture.md §13.5.10 明令禁止）
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE id = ?", grantId);

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_REF_NOT_FOUND));
    }

    // ===================== 只在聚合层可见的两项 =====================

    @Test
    @DisplayName("🔴 §7.5.2 ①：多 Skill 叠加超 chat.system_prompt_max_chars → "
            + "30060 rule=systemPromptBudgetExceeded（聚合层专属）")
    void systemPromptBudgetExceededAtAggregateLayer() {
        // 单个 Skill 合法（远小于 skill.instruction_max_chars），但叠加后超总预算
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, "300");
        bindSkill(insertPublishedSkill("s1", "指" .repeat(200)), 1);
        bindSkill(insertPublishedSkill("s2", "令".repeat(200)), 1);

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_SYSTEM_PROMPT_BUDGET_EXCEEDED),
                "🔴 必须给出 systemPromptBudgetExceeded：" + report.violations());
        assertTrue(report.violations().stream()
                        .filter(v -> ConfigViolationDTO.RULE_SYSTEM_PROMPT_BUDGET_EXCEEDED
                                .equals(v.rule()))
                        .allMatch(v -> !v.message().contains("指") && !v.message().contains("令")),
                "🔴 message 禁止回显正文片段（Skill 正文是内部资产）");
    }

    @Test
    @DisplayName("单个满长 Skill 在默认预算下合法（不变量 chat ≥ skill 成立时不得误杀）")
    void singleLongSkillWithinDefaultBudget() {
        bindSkill(insertPublishedSkill("s1", "指".repeat(5000)), 1);

        ConfigValidationReportDTO report = validate(true);

        assertTrue(report.valid(), "默认预算 100000 ≥ 单 Skill 长度，不得误判：" + report.violations());
    }

    @Test
    @DisplayName("🔴 §7.6.5 ⑥：两个 toolKey 归一化后同名 → 30060 rule=functionNameCollision（聚合层专属）")
    void functionNameCollisionAtAggregateLayer() {
        // 🔴 两个**格式完全合法**的 toolKey（都是 {mcpKey}:{toolName}）归一化后撞名：
        //    crm:lookup_user  → crm_lookup_user
        //    crm_lookup:user  → crm_lookup_user
        //    这正是契约举的碰撞场景 —— 单看任一条都合法，只有全清单聚合才看得出来。
        long crmId = insertMcp("crm", "https://mcp.example.com/v1");
        long crmLookupId = insertMcp("crm_lookup", "https://mcp2.example.com/v1");
        bindMcpTool(insertMcpTool(crmId, "crm:lookup_user"));
        bindMcpTool(insertMcpTool(crmLookupId, "crm_lookup:user"));

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_FUNCTION_NAME_COLLISION),
                "🔴 必须给出 functionNameCollision：" + report.violations());
    }

    @Test
    @DisplayName("🔴 §7.6.5：归一化后 >64 字符 → 30060 rule=functionNameTooLong")
    void functionNameTooLongAtAggregateLayer() {
        long mcpId = insertMcp("crm", "https://mcp.example.com/v1");
        bindMcpTool(insertMcpTool(mcpId, "crm:" + "t".repeat(70)));

        ConfigValidationReportDTO report = validate(true);

        assertFalse(report.valid());
        assertTrue(hasRule(report, ConfigViolationDTO.RULE_FUNCTION_NAME_TOO_LONG),
                "🔴 必须给出 functionNameTooLong（拒绝不截断）：" + report.violations());
    }

    @Test
    @DisplayName("tool_policy=disabled 时跳过函数名聚合判定（运行时清单为空，报错即假警报）")
    void functionNameChecksSkippedWhenToolsDisabled() {
        long disabledVersionId = insertAgentVersion(AgentVersionPolicies.DISABLED, "系统提示");
        long crmId = insertMcp("crm", "https://mcp.example.com/v1");
        long crmLookupId = insertMcp("crm_lookup", "https://mcp2.example.com/v1");
        bindMcpToolTo(disabledVersionId, insertMcpTool(crmId, "crm:lookup_user"));
        bindMcpToolTo(disabledVersionId, insertMcpTool(crmLookupId, "crm_lookup:user"));

        RuntimeConfigValidator.ValidationOutcome outcome = validator.validateWithQueryCount(
                RuntimeConfigValidator.TYPE_AGENT_VERSION, String.valueOf(disabledVersionId), true);

        assertFalse(hasRule(outcome.report(), ConfigViolationDTO.RULE_FUNCTION_NAME_COLLISION),
                "🔴 工具被整体禁用时不得报函数名冲突（假警报会训练 DBA 忽略校验结果）");
    }

    /** {@code agent_versions.tool_policy} 字面量（避免在测试里散落魔法串）。 */
    private static final class AgentVersionPolicies {
        private static final String DISABLED = "disabled";
    }

    // ===================== 环检测（防御性）=====================

    @Test
    @DisplayName("🔴 G10：visited/path 分离 —— 同一 MCP 服务被两个工具绑定引用（菱形）不得误判成环")
    void diamondReferenceIsNotCycle() {
        // 🔴 真实可达的菱形：两条 mcpTool 绑定指向**同一个** mcp_servers 行
        //    → 第 2 层会两次进入同一个 mcp 对象（visited 命中）。
        //    若实现用 path 兼作 visited，就会把它误判成环并把合法配置判非法。
        long mcpId = insertMcp("crm", "https://mcp.example.com/v1");
        bindMcpTool(insertMcpTool(mcpId, "crm:lookup_user"));
        bindMcpTool(insertMcpTool(mcpId, "crm:create_ticket"));

        ConfigValidationReportDTO report = validate(true);

        assertFalse(hasRule(report, ConfigViolationDTO.RULE_CIRCULAR_REFERENCE),
                "🔴 菱形引用不是环，绝不能误判（会把正常配置判非法）");
        assertTrue(report.valid(), "合法菱形绑定必须通过：" + report.violations());
    }

    // ===================== 辅助 =====================

    private ConfigValidationReportDTO validate(boolean includeReferences) {
        return validator.validate(RuntimeConfigValidator.TYPE_AGENT_VERSION,
                String.valueOf(agentVersionId), includeReferences);
    }

    private boolean hasRule(ConfigValidationReportDTO report, String rule) {
        return report.violations().stream().anyMatch(v -> rule.equals(v.rule()))
                || report.warnings().stream().anyMatch(v -> rule.equals(v.rule()));
    }

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                + " status, is_default, current_version, version, sort_order)"
                + " VALUES (?,'g10-agent','G10 助手','','enabled',1,1,0,0)", tenantId);
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion(String toolPolicy, String systemPrompt) {
        int version = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM agent_versions WHERE tenant_id = ?",
                Integer.class, tenantId);
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,?,?,'hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',120,?,'published')",
                tenantId, agentId, version, systemPrompt, toolPolicy);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?"
                + " AND version = ?", Long.class, tenantId, version);
    }

    private long insertPublishedSkill(String skillKey, String instruction) {
        jdbcTemplate.update("INSERT INTO skills (tenant_id, skill_key, name, description, status,"
                        + " current_version, version) VALUES (?,?,?,'','enabled',1,0)",
                tenantId, skillKey, skillKey);
        long skillId = jdbcTemplate.queryForObject(
                "SELECT id FROM skills WHERE tenant_id = ? AND skill_key = ?",
                Long.class, tenantId, skillKey);
        insertSkillVersion(skillId, 1, instruction);
        return skillId;
    }

    private void insertSkillVersion(long skillId, int version, String instruction) {
        jdbcTemplate.update("INSERT INTO skill_versions (tenant_id, skill_id, version, instruction,"
                        + " variables_schema, output_constraint, status)"
                        + " VALUES (?,?,?,?,'[]','','published')",
                tenantId, skillId, version, instruction);
    }

    private void bindSkill(long skillId, int refVersion) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,?,0)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_SKILL, skillId, refVersion);
    }

    private long insertMcp(String mcpKey, String endpoint) {
        jdbcTemplate.update("INSERT INTO mcp_servers (tenant_id, mcp_key, name, transport, endpoint,"
                        + " auth_type, credential_cipher, credential_last4, credential_key_version,"
                        + " timeout_seconds, status, version)"
                        + " VALUES (?,?,?,'streamable_http',?,'none','','',1,30,'enabled',0)",
                tenantId, mcpKey, mcpKey, endpoint);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_servers WHERE tenant_id = ? AND mcp_key = ?",
                Long.class, tenantId, mcpKey);
    }

    private long insertMcpTool(long mcpId, String toolKey) {
        jdbcTemplate.update("INSERT INTO mcp_tools (tenant_id, mcp_id, tool_key, tool_name,"
                        + " description, input_schema, input_schema_digest, risk_level, granted,"
                        + " status, change_type) VALUES (?,?,?,?,'','{\"type\":\"object\"}',"
                        + "'d','low',1,'enabled','unchanged')",
                tenantId, mcpId, toolKey, toolKey.substring(toolKey.indexOf(':') + 1));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_tools WHERE tenant_id = ? AND tool_key = ?",
                Long.class, tenantId, toolKey);
    }

    private void bindMcpTool(long mcpToolId) {
        bindMcpToolTo(agentVersionId, mcpToolId);
    }

    private void bindMcpToolTo(long versionId, long mcpToolId) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,1,0)",
                tenantId, versionId, AgentCapabilityBinding.TYPE_MCP_TOOL, mcpToolId);
    }

    private String insertLocalTool(String toolKey) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_tools WHERE tool_key = ?", Integer.class, toolKey);
        if (exists == null || exists == 0) {
            jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                            + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                            + " VALUES (?,?,1,'','{\"type\":\"object\"}','low',1,5,'enabled')",
                    toolKey, toolKey);
        }
        return toolKey;
    }

    private long insertGrant(String toolKey) {
        jdbcTemplate.update("INSERT INTO tenant_tool_grants (tenant_id, tool_key, granted, config,"
                + " status) VALUES (?,?,1,NULL,'enabled')", tenantId, toolKey);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_tool_grants WHERE tenant_id = ? AND tool_key = ?",
                Long.class, tenantId, toolKey);
    }

    private void bindLocalTool(long grantId) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,1,0)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_LOCAL_TOOL, grantId);
    }
}
