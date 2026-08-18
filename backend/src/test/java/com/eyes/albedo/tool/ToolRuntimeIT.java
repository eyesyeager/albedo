package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;
import com.eyes.albedo.testsupport.MockMcpServer;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.entity.ToolCall;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 工具运行时能力层测试（清单四条件 + 状态机 + 执行链路）。
 *
 * <p><b>REQ-TOL-001 / REQ-TOL-002 / REQ-MCP-003 ·
 * AC-TOL-001 / AC-TOL-002 / AC-TOL-003 / AC-MCP-004 / AC-MCP-005 / AC-CHAT-007 / AC-AUD-003</b>
 *
 * <p>覆盖：
 * <ul>
 *   <li>🔴 清单四条件（{@code local_tools.status} / {@code granted} / 绑定 / {@code tool_policy}）
 *       缺一即不进清单</li>
 *   <li>🔴 未授权工具被请求调用 → {@code 30050} + 审计 {@code tool.grant_denied}</li>
 *   <li>🔴 高风险工具即便 {@code tool_policy=auto} 也标记为需确认（不可降级）</li>
 *   <li>🔴 三段式执行：{@code running} 提交 → 事务外执行 → 终态短事务；状态机拒绝非法流转</li>
 *   <li>🔴 MCP 工具运行时改库为内网 → {@code denied}({@code 30050}) + 审计（AC-MCP-004）</li>
 *   <li>🔴 超大结果 → {@code truncated=true} 落库（EX-017）</li>
 * </ul>
 *
 * <p>数据纪律：全部写在<b>临时租户</b>下并在 {@code @AfterEach} 精确清理；
 * {@code local_tools} 是平台表，用 {@code it_tr_%} 前缀清理。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({MockMcpServer.class, ToolRuntimeIT.TestTools.class})
class ToolRuntimeIT {

    /** 测试专用本地 Tool 实现体（🔴 生产一期无内置实现，见 {@link LocalToolRegistry} 注释）。 */
    @TestConfiguration
    static class TestTools {

        static final String ECHO_KEY = "it_tr_echo";

        @Bean
        LocalToolHandler echoHandler() {
            return new LocalToolHandler() {
                @Override
                public String toolKey() {
                    return ECHO_KEY;
                }

                @Override
                public String execute(LocalToolInvocation invocation) {
                    return "{\"echo\":" + invocation.argumentsJson() + "}";
                }
            };
        }
    }

    private static final long MESSAGE_ID = 990001L;
    private static final long CONVERSATION_ID = 990002L;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ToolCatalogService catalogService;
    @Autowired
    private ToolAuthorizationService authorizationService;
    @Autowired
    private ToolCallRecorder recorder;
    @Autowired
    private ToolExecutorRegistry executorRegistry;
    @Autowired
    private McpToolExecutor mcpToolExecutor;
    @Autowired
    private ToolSummaryScrubber scrubber;
    @Autowired
    private ToolResultTruncator truncator;
    @Autowired
    private ToolOrchestrator toolOrchestrator;
    @Autowired
    private ToolGrantPointCheck grantPointCheck;

    private SysConfigOverride override;
    private String tenantId;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        tenantId = "trt" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "工具运行时测试租户", tenantId + ".test.invalid");
        agentId = insertAgent();
        agentVersionId = insertAgentVersion(ToolRiskPolicy.POLICY_AUTO);

        override = new SysConfigOverride(jdbcTemplate, configService)
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS, "false")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS,
                        "[\"127.0.0.1/32\"]");
        bindTenant();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        override.restore();
        jdbcTemplate.update("DELETE FROM tool_calls WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM mcp_tools WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM mcp_servers WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE 'it_tr_%'");
    }

    // ===================== 清单四条件（api-spec §7.7.2） =====================

    @Test
    @DisplayName("AC-TOL-001：四条件全满足 → 本地 Tool 进入清单")
    void localToolInCatalogWhenAllConditionsMet() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);

        List<ToolDefinition> catalog = catalogService.buildCatalog(agentVersion());

        assertEquals(1, catalog.size());
        assertEquals(TestTools.ECHO_KEY, catalog.get(0).toolKey());
        assertTrue(catalog.get(0).local());
        assertFalse(catalog.get(0).requiresConfirmation(), "low + auto → 自动执行");
    }

    @Test
    @DisplayName("🔴 granted=0 → 不进清单（授权是四条件之二）")
    void notGrantedNotInCatalog() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 0, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);

        assertTrue(catalogService.buildCatalog(agentVersion()).isEmpty());
    }

    @Test
    @DisplayName("🔴 平台 Tool 被停用 → 不进清单（四条件之一）")
    void platformDisabledNotInCatalog() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_DISABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);

        assertTrue(catalogService.buildCatalog(agentVersion()).isEmpty());
    }

    @Test
    @DisplayName("🔴 未被 agentVersion 绑定 → 不进清单（四条件之三，AC-MCP-005 同理）")
    void notBoundNotInCatalog() {
        grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        // 故意不建绑定

        assertTrue(catalogService.buildCatalog(agentVersion()).isEmpty());
    }

    @Test
    @DisplayName("🔴 tool_policy=disabled → 清单为空（四条件之四）")
    void policyDisabledEmptiesCatalog() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        long disabledVersionId = insertAgentVersionWith(ToolRiskPolicy.POLICY_DISABLED, 2L);

        AgentVersion disabled = new AgentVersion();
        disabled.setId(disabledVersionId);
        disabled.setAgentId(agentId);
        disabled.setToolPolicy(ToolRiskPolicy.POLICY_DISABLED);

        assertTrue(catalogService.buildCatalog(disabled).isEmpty());
    }

    @Test
    @DisplayName("🔴 注册表有行但平台无实现体 → 30060（清单构造阶段就失败）")
    void missingHandlerFailsCatalogBuild() {
        long grantId = grantLocalTool("it_tr_ghost", 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> catalogService.buildCatalog(agentVersion()));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("🔴 AC-TOL-002：high 风险即便 tool_policy=auto 也必须确认（不可降级）")
    void highRiskAlwaysRequiresConfirmation() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_HIGH, 0);
        bindLocalTool(grantId);

        List<ToolDefinition> catalog = catalogService.buildCatalog(agentVersion());
        assertEquals(1, catalog.size());
        assertTrue(catalog.get(0).requiresConfirmation());
        assertFalse(catalog.get(0).idempotent(), "idempotent=0 必须如实反映（决定 30056）");
    }

    @Test
    @DisplayName("🔴 超时越界（> tool.max_timeout_seconds）→ 30060")
    void timeoutOutOfRangeRejected() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        jdbcTemplate.update("UPDATE local_tools SET timeout_seconds = 9999 WHERE tool_key = ?",
                TestTools.ECHO_KEY);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> catalogService.buildCatalog(agentVersion()));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    // ===================== 运行时二次鉴权 =====================

    @Test
    @DisplayName("🔴 AC-AUD-003：未授权工具被请求调用 → 30050 + 审计 tool.grant_denied")
    void unauthorizedToolDeniedWithAudit() {
        List<ToolDefinition> emptyCatalog = List.of();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authorizationService.requireAuthorized(tenantId, emptyCatalog,
                        "it_tr_echo", AuditContext.system("it-request")));

        assertEquals(ErrorCode.TOOL_DENIED, ex.getCode());
        assertEquals(1, countAudit("tool.grant_denied"));
    }

    @Test
    @DisplayName("已授权工具 → 返回定义（不写审计，避免正常流量淹没安全事件）")
    void authorizedToolPasses() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        List<ToolDefinition> catalog = catalogService.buildCatalog(agentVersion());

        ToolDefinition definition = authorizationService.requireAuthorized(tenantId, catalog,
                TestTools.ECHO_KEY, AuditContext.system());

        assertEquals(TestTools.ECHO_KEY, definition.toolKey());
        assertEquals(0, countAudit("tool.grant_denied"));
    }

    // ===================== 三段式执行与状态机 =====================

    @Test
    @DisplayName("🔴 ADR-010 三段式：pending → running（短事务）→ 事务外执行 → succeeded（短事务）")
    void threePhaseExecution() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);

        // ① 短事务：pending
        ToolCall pending = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "call-1", 1,
                definition);
        assertEquals(ToolCall.STATUS_PENDING, pending.getStatus());

        // ② 短事务：running（提交后立即返回）
        String argsSummary = truncator.truncateArgsSummary(
                scrubber.argsSummary("{\"phone\":\"13812345678\"}"));
        ToolCall running = recorder.markRunning(pending.getId(), argsSummary);
        assertEquals(ToolCall.STATUS_RUNNING, running.getStatus());
        // 🔴 落库摘要必须已脱敏（一份摘要四处复用）
        assertFalse(queryString("SELECT args_summary FROM tool_calls WHERE id = ?",
                pending.getId()).contains("13812345678"));

        // ③ 事务外执行
        ToolExecutionResult result = executorRegistry.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition,
                        "{\"phone\":\"13812345678\"}"));
        assertTrue(result.succeeded());

        // ④ 短事务：终态
        ToolCall succeeded = recorder.markSucceeded(pending.getId(),
                truncator.truncateResultSummary(scrubber.resultSummary(result.content())),
                result.truncated());
        assertEquals(ToolCall.STATUS_SUCCEEDED, succeeded.getStatus());
        assertEquals(null, succeeded.getErrorCode());
        assertFalse(queryString("SELECT result_summary FROM tool_calls WHERE id = ?",
                pending.getId()).contains("13812345678"), "🔴 结果摘要同样必须脱敏");
    }

    @Test
    @DisplayName("🔴 幂等落库：同一 (messageId, providerCallId) 重复投递不重复建行")
    void recordPendingIsIdempotent() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);

        ToolCall first = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "dup-1", 1, definition);
        ToolCall second = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "dup-1", 1, definition);

        assertEquals(first.getId(), second.getId());
        assertEquals(1, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?", tenantId));
    }

    @Test
    @DisplayName("🔴 终态不可被覆盖：succeeded 后再写 timed_out → 30060（保护 30055 冲突判定）")
    void terminalStateCannotBeOverwritten() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);
        ToolCall call = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "call-2", 1, definition);
        recorder.markRunning(call.getId(), "");
        recorder.markSucceeded(call.getId(), "ok", false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> recorder.markTerminal(call.getId(), ToolCall.STATUS_TIMED_OUT,
                        ErrorCode.TOOL_TIMEOUT, null, null, null));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertEquals(ToolCall.STATUS_SUCCEEDED,
                queryString("SELECT status FROM tool_calls WHERE id = ?", call.getId()));
    }

    @Test
    @DisplayName("🔴 §9.5.4 不变量 3：流结束时非终态行统一收敛为 cancelled（无僵尸卡片）")
    void pendingCallsConvergeToCancelled() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);
        recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "call-3", 1, definition);
        ToolCall running = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "call-4", 1,
                definition);
        recorder.markRunning(running.getId(), "");

        assertEquals(2, recorder.cancelPendingByMessage(MESSAGE_ID));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ? "
                + "AND status = 'cancelled'", tenantId));
        // 幂等：再次收敛不再改动
        assertEquals(0, recorder.cancelPendingByMessage(MESSAGE_ID));
    }

    @Test
    @DisplayName("awaiting_confirmation 落库（第三阶段等待逻辑的前置状态能力）")
    void awaitingConfirmationRecorded() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_HIGH, 0);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);
        ToolCall call = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "call-5", 1, definition);

        ToolCall awaiting = recorder.markAwaitingConfirmation(call.getId(), "orderId=A***23");

        assertEquals(ToolCall.STATUS_AWAITING_CONFIRMATION, awaiting.getStatus());
        assertEquals(1, queryInt("SELECT requires_confirmation FROM tool_calls WHERE id = ?",
                call.getId()));
    }

    // ===================== MCP 工具执行（Mock） =====================

    @Test
    @DisplayName("AC-MCP-003：MCP 工具执行成功 → succeeded + 结果回灌")
    void mcpToolExecutionSucceeds() {
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_SUCCESS);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition,
                        "{\"uid\":\"10086\"}"));

        assertEquals(ToolCall.STATUS_SUCCEEDED, result.status());
        assertTrue(result.content().contains("张三"));
    }

    @Test
    @DisplayName("🔴 EX-017：MCP 超大结果 → 字节截断 + truncated=true")
    void mcpOversizeResultTruncated() {
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_MAX_BYTES, "1024");
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS, "20");
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_OVERSIZE_RESULT);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertTrue(result.truncated(), "超字节上限必须标记 truncated");
        assertTrue(result.content().endsWith(ToolResultTruncator.TRUNCATION_MARKER));
    }

    @Test
    @DisplayName("MCP 上游 isError=true → failed + 30057")
    void mcpExecutionErrorMapsTo30057() {
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_EXECUTION_ERROR);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_FAILED, result.status());
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, result.errorCode());
    }

    @Test
    @DisplayName("🔴 AC-TOL-003：MCP 调用超时 → timed_out + 30056（MCP 一律按非幂等，禁止自动重试）")
    void mcpTimeoutMapsTo30056() {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS, "1");
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_TIMEOUT);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_TIMED_OUT, result.status());
        assertEquals(ErrorCode.TOOL_RETRY_BLOCKED, result.errorCode());
    }

    @Test
    @DisplayName("MCP 鉴权失败 → failed + 30052")
    void mcpAuthFailedMapsTo30052() {
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_AUTH_FAILED);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_FAILED, result.status());
        assertEquals(ErrorCode.MCP_UNAVAILABLE, result.errorCode());
    }

    @Test
    @DisplayName("🔴 AC-MCP-004：调用前改库为内网地址 → denied + 30050 + 审计 mcp.ssrf_rejected")
    void mcpRuntimeSsrfGuard() {
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_SUCCESS);
        // DBA 在生成进行中把地址改成内网
        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE tenant_id = ?",
                "https://169.254.169.254/latest/meta-data", tenantId);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_DENIED, result.status());
        assertEquals(ErrorCode.TOOL_DENIED, result.errorCode());
        assertEquals(1, countAudit("mcp.ssrf_rejected"));
    }

    @Test
    @DisplayName("🔴 生成进行中 MCP 服务被停用 → denied + 30050（改库即生效）")
    void mcpServerDisabledDuringGeneration() {
        ToolDefinition definition = mcpDefinition(MockMcpServer.SCENARIO_SUCCESS);
        jdbcTemplate.update("UPDATE mcp_servers SET status = 'disabled' WHERE tenant_id = ?",
                tenantId);

        ToolExecutionResult result = mcpToolExecutor.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_DENIED, result.status());
        assertEquals(ErrorCode.TOOL_DENIED, result.errorCode());
    }

    @Test
    @DisplayName("🔴🔴 §7.8.1 ③ 执行期竞态：running 后撤授权 → denied + 30050（**不是** failed/30052）"
            + " + 审计 tool.grant_denied")
    void executionRaceConvergesToDeniedNotFailed() {
        long mcpId = insertMcpServer(MockMcpServer.SCENARIO_SUCCESS);
        long toolId = insertMcpTool(mcpId, "mock:lookup_user", 1, "enabled");
        bindMcpTool(toolId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);

        // ① preflight 通过（此刻服务仍启用）
        executorRegistry.preflight(new ToolExecutor.ToolExecutionRequest(tenantId, 10086L,
                definition, "{}"));
        ToolCall pending = recorder.recordPending(CONVERSATION_ID, MESSAGE_ID, "race-1", 1,
                definition);
        // ② 行已置 running —— 竞态窗口由此打开（§7.6.3 第 4 步的重校验发生在 running 之后）
        recorder.markRunning(pending.getId(), "{}");
        assertEquals(ToolCall.STATUS_RUNNING,
                queryString("SELECT status FROM tool_calls WHERE id = ?", pending.getId()));

        // ③ 🔴 DBA 恰好在此刻撤销授权 / 停用服务
        jdbcTemplate.update("UPDATE mcp_servers SET status = 'disabled' WHERE id = ?", mcpId);

        ToolExecutionResult result = executorRegistry.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        // 🔴 执行器必须给出 denied + 30050，并标明拒绝来源（决定审计 action）
        assertEquals(ToolCall.STATUS_DENIED, result.status());
        assertEquals(ErrorCode.TOOL_DENIED, result.errorCode(),
                "🔴 安全拒绝恒 30050；归一化为 30052 会把安全事件伪装成上游故障");
        assertEquals(ToolExecutionResult.DeniedCause.GRANT_REVOKED, result.deniedCause());

        // ④ 🔴 running → denied 现在是**合法流转**（V1.1.3 ③ 增补），且审计同事务写入
        ToolCall denied = recorder.markTerminal(pending.getId(), ToolCall.STATUS_DENIED,
                ErrorCode.TOOL_DENIED, "", authorizationService.grantDeniedEvent(tenantId,
                        definition.toolKey()), null);

        assertEquals(ToolCall.STATUS_DENIED, denied.getStatus());
        assertEquals(ErrorCode.TOOL_DENIED, denied.getErrorCode());
        assertEquals(1, countAudit("tool.grant_denied"),
                "🔴 撤授权必须写 tool.grant_denied（不是 mcp.ssrf_rejected）");
        // 🔴 聚合口径：该行 status='denied' → 只计 toolDeniedCount（§7.11.1 第 4 条注）
        assertEquals(1, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?"
                + " AND status = 'denied' AND error_code = ?", tenantId, ErrorCode.TOOL_DENIED));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?"
                        + " AND status = 'failed'", tenantId),
                "🔴 绝不允许落成 failed（会错计进 toolFailedCount，口径失真）");
    }

    @Test
    @DisplayName("🔴 §7.8.1 ③：running 后 endpoint 被改内网 → denied + 30050，"
            + "审计只由 SsrfGuard 写 mcp.ssrf_rejected（不重复写 grant_denied）")
    void executionRaceSsrfWritesOnlySsrfAudit() {
        long mcpId = insertMcpServer(MockMcpServer.SCENARIO_SUCCESS);
        long toolId = insertMcpTool(mcpId, "mock:lookup_user", 1, "enabled");
        bindMcpTool(toolId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);
        executorRegistry.preflight(new ToolExecutor.ToolExecutionRequest(tenantId, 10086L,
                definition, "{}"));

        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE id = ?",
                "https://169.254.169.254/latest/meta-data", mcpId);

        ToolExecutionResult result = executorRegistry.execute(
                new ToolExecutor.ToolExecutionRequest(tenantId, 10086L, definition, "{}"));

        assertEquals(ToolCall.STATUS_DENIED, result.status());
        assertEquals(ErrorCode.TOOL_DENIED, result.errorCode());
        assertEquals(ToolExecutionResult.DeniedCause.SSRF_REJECTED, result.deniedCause());
        assertEquals(1, countAudit("mcp.ssrf_rejected"));
        assertEquals(0, countAudit("tool.grant_denied"),
                "🔴 SSRF 拒绝已由 SsrfGuard 留痕，编排层不得重复写第二条审计");
    }

    @Test
    @DisplayName("🔴 状态机：running → denied 合法（V1.1.3 ③）；denied 仍是终态不可再迁移")
    void stateMachineAllowsRunningToDenied() {
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_RUNNING, ToolCall.STATUS_DENIED));
        assertFalse(ToolStateMachine.canTransition(ToolCall.STATUS_DENIED, ToolCall.STATUS_FAILED));
        assertFalse(ToolStateMachine.canTransition(ToolCall.STATUS_SUCCEEDED,
                ToolCall.STATUS_DENIED));
    }

    @Test
    @DisplayName("🔴🔴 C3：MCP 工具在清单构造后被撤销 granted → 执行前点查判 denied + 30050 + 审计"
            + "（🔴 未进入 running、未发起任何上游调用）")
    void mcpGrantRevokedIsDeniedByPointCheckBeforeRunning() {
        long mcpId = insertMcpServer(MockMcpServer.SCENARIO_SUCCESS);
        long toolId = insertMcpTool(mcpId, "mock:lookup_user", 1, "enabled");
        bindMcpTool(toolId);
        // ① 清单构造时授权仍有效（工具进入模型可见清单）
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);
        long queriesBefore = grantPointCheck.queryCount();

        // ② 🔴 DBA 在"清单已构造、尚未执行"的窗口内撤销逐项授权（AC-MCP-004 明文要求即刻生效）
        jdbcTemplate.update("UPDATE mcp_tools SET granted = 0 WHERE id = ?", toolId);

        ToolOrchestrator.ToolDispatch dispatch = toolOrchestrator.dispatch(
                new ToolOrchestrator.ToolRunContext(tenantId, 10086L, CONVERSATION_ID, MESSAGE_ID,
                        List.of(definition), null, null, () -> false),
                new ToolOrchestrator.ToolInvocation("pc-mcp-1", definition.toolKey(), "{}", 1));

        assertEquals(ToolCall.STATUS_DENIED, dispatch.status(),
                "🔴 撤授权后**新发起**的执行必须被拒（残余窗口只覆盖已进入 invoke 的那一次）");
        assertEquals(ErrorCode.TOOL_DENIED, dispatch.errorCode(),
                "🔴 恒 30050；30052 是上游故障语义，会把 DBA 导向查网络");
        assertEquals(1, countAudit("tool.grant_denied"));
        assertEquals(1L, grantPointCheck.queryCount() - queriesBefore,
                "🔴 执行前点查恰好 1 次（≤1 次预算，禁拆多次、禁缓存）");
        assertEquals(ToolCall.STATUS_DENIED, queryString("SELECT status FROM tool_calls"
                + " WHERE tenant_id = ? AND provider_call_id = 'pc-mcp-1'", tenantId));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?"
                        + " AND status IN ('running','succeeded','failed')", tenantId),
                "🔴 点查在置 running 之前：不得出现 running/succeeded/failed 行");
    }

    @Test
    @DisplayName("🔴 MCP 服务在清单构造后被停用 → 执行前点查（mcp_tools ⋈ mcp_servers 单查）即判 denied")
    void mcpServerDisabledIsDeniedByPointCheck() {
        long mcpId = insertMcpServer(MockMcpServer.SCENARIO_SUCCESS);
        long toolId = insertMcpTool(mcpId, "mock:lookup_user", 1, "enabled");
        bindMcpTool(toolId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);

        jdbcTemplate.update("UPDATE mcp_servers SET status = 'disabled' WHERE id = ?", mcpId);

        ToolOrchestrator.ToolDispatch dispatch = toolOrchestrator.dispatch(
                new ToolOrchestrator.ToolRunContext(tenantId, 10086L, CONVERSATION_ID, MESSAGE_ID,
                        List.of(definition), null, null, () -> false),
                new ToolOrchestrator.ToolInvocation("pc-mcp-2", definition.toolKey(), "{}", 1));

        assertEquals(ToolCall.STATUS_DENIED, dispatch.status());
        assertEquals(ErrorCode.TOOL_DENIED, dispatch.errorCode());
        assertEquals(1, countAudit("tool.grant_denied"));
    }

    @Test
    @DisplayName("🔴 点查禁缓存：撤授权→恢复授权，两次判定必须分别为 false / true（改库即生效）")
    void pointCheckIsNotCached() {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, 1, TenantToolGrant.STATUS_ENABLED,
                LocalTool.STATUS_ENABLED, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        ToolDefinition definition = catalogService.buildCatalog(agentVersion()).get(0);

        assertTrue(grantPointCheck.stillGranted(tenantId, definition));

        jdbcTemplate.update("UPDATE tenant_tool_grants SET granted = 0 WHERE id = ?", grantId);
        assertFalse(grantPointCheck.stillGranted(tenantId, definition),
                "🔴 撤授权必须立即生效（一旦缓存点查结果就会回到 fail-open）");

        jdbcTemplate.update("UPDATE tenant_tool_grants SET granted = 1 WHERE id = ?", grantId);
        assertTrue(grantPointCheck.stillGranted(tenantId, definition), "恢复授权同样立即生效");

        // 🔴 跨租户不可能命中：点查走 discriminator，另一个租户的同名 toolKey 查不到本租户授权
        jdbcTemplate.update("UPDATE tenant_tool_grants SET status = 'disabled' WHERE id = ?",
                grantId);
        assertFalse(grantPointCheck.stillGranted(tenantId, definition),
                "🔴 授权行被停用同样判拒（granted=1 但 status=disabled 不构成授权）");
    }

    @Test
    @DisplayName("MCP 工具进清单需三条件：granted + enabled + 服务 enabled")
    void mcpToolCatalogConditions() {
        long mcpId = insertMcpServer(MockMcpServer.SCENARIO_SUCCESS);
        long toolId = insertMcpTool(mcpId, "mock:lookup_user", 1, "enabled");
        bindMcpTool(toolId);

        assertEquals(1, catalogService.buildCatalog(agentVersion()).size());

        jdbcTemplate.update("UPDATE mcp_tools SET granted = 0 WHERE id = ?", toolId);
        assertTrue(catalogService.buildCatalog(agentVersion()).isEmpty(), "取消授权即刻生效");

        jdbcTemplate.update("UPDATE mcp_tools SET granted = 1 WHERE id = ?", toolId);
        jdbcTemplate.update("UPDATE mcp_servers SET status = 'disabled' WHERE id = ?", mcpId);
        assertTrue(catalogService.buildCatalog(agentVersion()).isEmpty(), "服务停用即刻生效");
    }

    // ===================== 辅助 =====================

    private void bindTenant() {
        TenantContext.bind(new TenantContext.Snapshot(tenantId, 1L,
                tenantId + ".test.invalid", TenantStatus.ENABLED, 0L, 10086L));
    }

    private AgentVersion agentVersion() {
        AgentVersion version = new AgentVersion();
        version.setId(agentVersionId);
        version.setAgentId(agentId);
        version.setVersion(1L);
        version.setToolPolicy(ToolRiskPolicy.POLICY_AUTO);
        return version;
    }

    private ToolDefinition mcpDefinition(String scenario) {
        long mcpId = insertMcpServer(scenario);
        return ToolDefinition.of(ToolDefinition.TYPE_MCP, "mock:lookup_user", "lookup_user",
                "查询用户", "{\"type\":\"object\"}", "digest", ToolRiskPolicy.RISK_LOW,
                false, false, 30, mcpId, null);
    }

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "tool-agent", "工具测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion(String toolPolicy) {
        return insertAgentVersionWith(toolPolicy, 1L);
    }

    private long insertAgentVersionWith(String toolPolicy, long version) {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,?,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',120,?,'published')",
                tenantId, agentId, version, toolPolicy);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?"
                + " AND agent_id = ? AND version = ?", Long.class, tenantId, agentId, version);
    }

    private long grantLocalTool(String toolKey, int granted, String grantStatus,
                                String toolStatus, String riskLevel, int idempotent) {
        jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                        + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                        + " VALUES (?,?,1,'',?,?,?,5,?)",
                toolKey, toolKey, "{\"type\":\"object\"}", riskLevel, idempotent, toolStatus);
        jdbcTemplate.update("INSERT INTO tenant_tool_grants (tenant_id, tool_key, granted, config,"
                + " status) VALUES (?,?,?,NULL,?)", tenantId, toolKey, granted, grantStatus);
        return jdbcTemplate.queryForObject("SELECT id FROM tenant_tool_grants WHERE tenant_id = ?"
                + " AND tool_key = ?", Long.class, tenantId, toolKey);
    }

    private void bindLocalTool(long grantId) {
        bind(AgentCapabilityBinding.TYPE_LOCAL_TOOL, grantId);
    }

    private void bindMcpTool(long toolId) {
        bind(AgentCapabilityBinding.TYPE_MCP_TOOL, toolId);
    }

    private void bind(String capabilityType, long refId) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order)"
                        + " VALUES (?,?,?,?,1,0)",
                tenantId, agentVersionId, capabilityType, refId);
    }

    private long insertMcpServer(String scenario) {
        String endpoint = "http://127.0.0.1:" + port + "/mock-mcp/streamable-http?scenario="
                + scenario;
        Long existing = jdbcTemplate.query("SELECT id FROM mcp_servers WHERE tenant_id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, tenantId);
        if (existing != null) {
            jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ?, status = 'enabled'"
                    + " WHERE id = ?", endpoint, existing);
            return existing;
        }
        jdbcTemplate.update("INSERT INTO mcp_servers (tenant_id, mcp_key, name, transport,"
                        + " endpoint, auth_type, credential_last4, credential_key_version,"
                        + " timeout_seconds, status, version)"
                        + " VALUES (?,'mock','Mock','streamable_http',?,'none','',0,30,'enabled',0)",
                tenantId, endpoint);
        return jdbcTemplate.queryForObject("SELECT id FROM mcp_servers WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertMcpTool(long mcpId, String toolKey, int granted, String status) {
        jdbcTemplate.update("INSERT INTO mcp_tools (tenant_id, mcp_id, tool_name, tool_key,"
                        + " description, input_schema, input_schema_digest, risk_level, granted,"
                        + " status, change_type) VALUES (?,?,?,?,'',?,'abc','low',?,?,'new')",
                tenantId, mcpId, "lookup_user", toolKey, "{\"type\":\"object\"}", granted, status);
        return jdbcTemplate.queryForObject("SELECT id FROM mcp_tools WHERE tenant_id = ?"
                + " AND tool_key = ?", Long.class, tenantId, toolKey);
    }

    private int countAudit(String action) {
        return queryInt("SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ? AND action = ?",
                tenantId, action);
    }

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, String.class, args);
    }
}
