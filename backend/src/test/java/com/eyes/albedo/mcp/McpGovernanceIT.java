package com.eyes.albedo.mcp;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.repository.McpServerRepository;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.MockMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * MCP 治理接口与运行时安全测试（api-spec §7.4 / §7.6.3）。
 *
 * <p><b>REQ-MCP-001 / REQ-MCP-002 / REQ-MCP-003 ·
 * AC-MCP-001 / AC-MCP-002 / AC-MCP-004 / AC-MCP-005 / AC-AUD-003 / EX-029</b>
 *
 * <p>覆盖：
 * <ul>
 *   <li>只读查询的<b>凭据口径</b>：只回 {@code configured + last4 + keyVersion}，🔴 零明文/零密文</li>
 *   <li>连接测试的<b>分类结果</b>与<b>强制审计</b>（含成功）</li>
 *   <li>🔴 {@code ssrf_rejected} → {@code 30050}，且审计<b>未被回滚</b>（EX-029 / AC-AUD-003）</li>
 *   <li>工具发现：新工具默认禁用、{@code schema_changed} 自动降级、{@code removed} 保留历史行、
 *       超限整批不落库、{@code dryRun} 不落库</li>
 *   <li>🔴 <b>AC-MCP-004 运行时兜底</b>：先写合法地址 → 改库为内网 → 运行时仍拒绝 + 审计</li>
 *   <li>🔴 <b>AAD 防跨租户搬运</b>：密文复制到别租户 → 解密失败 → {@code 30060}</li>
 *   <li>租户隔离：跨租户 {@code mcpId} → {@code 10004}；租户内角色不足 → {@code 10003}</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestAuthConfig.class, MockMcpServer.class})
class McpGovernanceIT {

    private static final long ADMIN_UID = 900000311L;
    private static final long OTHER_ADMIN_UID = 900000312L;
    private static final long END_USER_UID = 900000313L;
    private static final String CIPHER_PLAINTEXT = "sk-live-abcdef9f2c";

    @LocalServerPort
    private int port;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private CredentialCipher credentialCipher;
    @Autowired
    private McpCredentialResolver credentialResolver;
    @Autowired
    private SsrfGuard ssrfGuard;
    @Autowired
    private McpServerRepository mcpServerRepository;

    private SysConfigOverride override;
    private String tenantA;
    private String hostA;
    private String tenantB;
    private String hostB;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantA = "mga" + suffix;
        hostA = "mga-" + suffix + ".test.invalid";
        tenantB = "mgb" + suffix;
        hostB = "mgb-" + suffix + ".test.invalid";
        createTenant(tenantA, hostA);
        createTenant(tenantB, hostB);
        grantRole(tenantA, ADMIN_UID, "TENANT_ADMIN");
        grantRole(tenantB, OTHER_ADMIN_UID, "TENANT_ADMIN");
        grantRole(tenantA, END_USER_UID, "END_USER");

        override = new SysConfigOverride(jdbcTemplate, configService)
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS, "false")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS,
                        "[\"127.0.0.1/32\"]")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_DISCOVER_TIMEOUT_SECONDS, "10");
    }

    @AfterEach
    void tearDown() {
        override.restore();
        for (String tenant : new String[]{tenantA, tenantB}) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM mcp_tools WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM mcp_servers WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenant);
        }
    }

    // ===================== §7.4.1 只读查询与凭据口径 =====================

    @Test
    @DisplayName("🔴 AC-MCP-001：详情只回 configured + last4 + keyVersion，响应体零明文零密文")
    void detailNeverLeaksCredential() throws Exception {
        String cipher = credentialCipher.encrypt(CIPHER_PLAINTEXT, tenantA, "crm");
        long mcpId = insertMcp(tenantA, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_BEARER, cipher, "9f2c");

        MvcResult result = mockMvc.perform(get("/api/v1/admin/mcp/" + mcpId)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.mcpId", is(String.valueOf(mcpId))))
                .andExpect(jsonPath("$.data.credential.configured", is(true)))
                .andExpect(jsonPath("$.data.credential.last4", is("9f2c")))
                .andExpect(jsonPath("$.data.credential.keyVersion", is(1)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(CIPHER_PLAINTEXT), "🔴 响应体出现凭据明文：" + body);
        assertFalse(body.contains(cipher), "🔴 响应体出现完整密文");
        assertFalse(body.contains(cipher.substring(0, 12)), "🔴 响应体出现密文片段");
    }

    @Test
    @DisplayName("authType=none → credential 固定形态（configured=false / last4='' / keyVersion=0）")
    void detailForAuthTypeNone() throws Exception {
        long mcpId = insertMcp(tenantA, "open", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_NONE, null, "");

        mockMvc.perform(get("/api/v1/admin/mcp/" + mcpId)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.credential.configured", is(false)))
                .andExpect(jsonPath("$.data.credential.last4", is("")))
                .andExpect(jsonPath("$.data.credential.keyVersion", is(0)));
    }

    @Test
    @DisplayName("🔴 AC-TEN-004：跨租户 mcpId → 10004（不泄露存在性，data 不出现）")
    void crossTenantReturnsNotFound() throws Exception {
        long mcpInB = insertMcp(tenantB, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_NONE, null, "");

        mockMvc.perform(get("/api/v1/admin/mcp/" + mcpInB)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10004)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("租户内角色不足（END_USER）→ 10003（@TenantRole 准入生效）")
    void endUserRejected() throws Exception {
        long mcpId = insertMcp(tenantA, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_NONE, null, "");

        mockMvc.perform(get("/api/v1/admin/mcp/" + mcpId)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, END_USER_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)));
    }

    // ===================== §7.4.2 连接测试 =====================

    @Test
    @DisplayName("AC-MCP-001｜连接测试成功 → code=0 / result=success / 强制审计 mcp.connection_test")
    void connectionTestSuccess() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        MvcResult result = mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/test")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.result", is("success")))
                .andExpect(jsonPath("$.data.healthy", is(true)))
                .andExpect(jsonPath("$.data.toolCount", is(2)))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        // 🔴 auditEventId 必须是 32 位小写 hex，原样返回、禁止截断（api-spec §7.14 不变量 5）
        assertTrue(data.get("auditEventId").asText().matches("^[0-9a-f]{32}$"),
                data.get("auditEventId").asText());
        assertEquals(1, countAudit(tenantA, "mcp.connection_test"));
        assertEquals("healthy", queryString(
                "SELECT last_check_status FROM mcp_servers WHERE id = ?", mcpId));
        assertEquals("success", queryString(
                "SELECT last_check_result FROM mcp_servers WHERE id = ?", mcpId));
    }

    @Test
    @DisplayName("AC-NFR-001：环回 Mock MCP test/discover 非 AI 接口 P95 均小于 500ms")
    void mockMcpNonAiEndpointsP95() throws Exception {
        final int samples = 20;
        long mcpId = insertMockMcp(tenantA, "perf", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);
        List<Long> testMillis = new ArrayList<>();
        List<Long> discoverMillis = new ArrayList<>();

        for (int sample = 0; sample < samples; sample++) {
            long began = System.nanoTime();
            mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/test")
                            .header(HttpHeaders.HOST, hostA)
                            .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(0)))
                    .andExpect(jsonPath("$.data.result", is("success")));
            testMillis.add((System.nanoTime() - began) / 1_000_000L);

            began = System.nanoTime();
            mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                            .header(HttpHeaders.HOST, hostA)
                            .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"dryRun\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", is(0)))
                    .andExpect(jsonPath("$.data.newCount", is(2)));
            discoverMillis.add((System.nanoTime() - began) / 1_000_000L);
        }

        long testP95 = percentile95(testMillis);
        long discoverP95 = percentile95(discoverMillis);
        System.out.printf("PERF mcp-non-ai n=%d test-p95=%dms discover-dry-run-p95=%dms%n",
                samples, testP95, discoverP95);
        assertTrue(testP95 <= 500L, "MCP test P95 超过 500ms：" + testP95 + "ms");
        assertTrue(discoverP95 <= 500L, "MCP discover P95 超过 500ms：" + discoverP95 + "ms");
    }

    @Test
    @DisplayName("AC-MCP-006｜鉴权失败 → code=0 + result=auth_failed + unhealthy（诊断语义）")
    void connectionTestAuthFailed() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_AUTH_FAILED,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/test")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.result", is("auth_failed")))
                .andExpect(jsonPath("$.data.healthy", is(false)));

        assertEquals("unhealthy", queryString(
                "SELECT last_check_status FROM mcp_servers WHERE id = ?", mcpId));
    }

    @Test
    @DisplayName("握手成功但无工具 → result=no_tools_available（不是 success）")
    void connectionTestNoTools() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_NO_TOOLS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/test")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result", is("no_tools_available")))
                .andExpect(jsonPath("$.data.healthy", is(false)));
    }

    @Test
    @DisplayName("🔴 EX-029：连接测试遇非法地址 → 30050 + result=ssrf_rejected，且两条审计都未被回滚")
    void connectionTestSsrfRejected() throws Exception {
        // 云元数据地址：SSRF 的头号目标
        long mcpId = insertMcp(tenantA, "evil", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "http://169.254.169.254/latest/meta-data", McpServer.AUTH_TYPE_NONE, null, "");

        MvcResult result = mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/test")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30050)))
                .andExpect(jsonPath("$.data.result", is("ssrf_rejected")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // 🔴 不回显解析出的地址
        assertFalse(body.contains("169.254.169.254"), "🔴 响应体回显了内部地址：" + body);
        // 🔴 审计必须存在（在 30050 之前提交），否则 EX-029 / AC-AUD-003 不成立
        assertEquals(1, countAudit(tenantA, "mcp.ssrf_rejected"));
        assertEquals(1, countAudit(tenantA, "mcp.connection_test"));
    }

    // ===================== §7.4.3 工具发现 =====================

    @Test
    @DisplayName("🔴 AC-MCP-002/005：新发现工具一律 granted=0 + disabled（不进可调用清单）")
    void discoveredToolsDefaultDisabled() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.newCount", is(2)))
                .andExpect(jsonPath("$.data.tools[0].granted", is(false)))
                .andExpect(jsonPath("$.data.tools[0].status", is("disabled")))
                .andExpect(jsonPath("$.data.tools[0].changeType", is("new")))
                // 🔴 未知风险一律 high，租户不可下调
                .andExpect(jsonPath("$.data.tools[0].riskLevel", is("high")));

        assertEquals(0, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ? "
                + "AND granted = 1", tenantA));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));
    }

    @Test
    @DisplayName("dryRun=true → 只回比对结果，🔴 不落库")
    void dryRunDoesNotPersist() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newCount", is(2)));

        assertEquals(0, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));
    }

    @Test
    @DisplayName("🔴 AC-MCP-002：schema_changed 的已授权工具自动降级为 granted=0/disabled + 审计")
    void schemaChangedDowngradesGrant() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);
        discover(mcpId, "{}");
        // DBA 手工授权（一期授权即写库，DEC-010）
        jdbcTemplate.update("UPDATE mcp_tools SET granted = 1, status = 'enabled' "
                + "WHERE tenant_id = ? AND tool_key = ?", tenantA, "mock:lookup_user");

        // 上游偷换 Schema（新增必填参数）
        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE id = ?",
                mockEndpoint(MockMcpServer.SCENARIO_SCHEMA_CHANGED,
                        McpServer.TRANSPORT_STREAMABLE_HTTP), mcpId);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.schemaChangedCount", is(1)));

        assertEquals(0, queryInt("SELECT granted FROM mcp_tools WHERE tenant_id = ? "
                + "AND tool_key = ?", tenantA, "mock:lookup_user"));
        assertEquals("disabled", queryString("SELECT status FROM mcp_tools WHERE tenant_id = ? "
                + "AND tool_key = ?", tenantA, "mock:lookup_user"));
        // 🔴 G3 已裁决：系统主动撤销授权用独立 action，不再复用 tool.grant_denied
        assertEquals(1, countAudit(tenantA, "mcp.tool_grant_revoked"),
                "🔴 自动降级必须写 mcp.tool_grant_revoked（api-spec §7.14 / §7.4.3 G3）");
        assertEquals(0, countAudit(tenantA, "tool.grant_denied"),
                "🔴 不得复用 tool.grant_denied：那会让真实越权事件被系统例行降级淹没");
        assertEquals("schemaChanged", queryString("SELECT reason FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'mcp.tool_grant_revoked'", tenantA));
        assertEquals("system", queryString("SELECT actor_type FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'mcp.tool_grant_revoked'", tenantA));
        assertEquals("success", queryString("SELECT result FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'mcp.tool_grant_revoked'", tenantA));
        assertEquals("mcpTool", queryString("SELECT object_type FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'mcp.tool_grant_revoked'", tenantA));
    }

    @Test
    @DisplayName("上游新增工具 → changeType=new 且默认禁用；未变化工具保持 unchanged")
    void newToolDiscovered() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);
        discover(mcpId, "{}");

        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE id = ?",
                mockEndpoint(MockMcpServer.SCENARIO_NEW_TOOL,
                        McpServer.TRANSPORT_STREAMABLE_HTTP), mcpId);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newCount", is(1)))
                .andExpect(jsonPath("$.data.unchangedCount", is(2)));

        assertEquals(0, queryInt("SELECT granted FROM mcp_tools WHERE tenant_id = ? "
                + "AND tool_key = ?", tenantA, "mock:export_all"));
    }

    @Test
    @DisplayName("🔴 上游移除工具 → 保留历史行 + status=disabled + removed_at（不物理删除）")
    void removedToolKeepsHistoryRow() throws Exception {
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_NEW_TOOL,
                McpServer.TRANSPORT_STREAMABLE_HTTP);
        discover(mcpId, "{}");
        assertEquals(3, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));

        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE id = ?",
                mockEndpoint(MockMcpServer.SCENARIO_SUCCESS,
                        McpServer.TRANSPORT_STREAMABLE_HTTP), mcpId);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removedCount", is(1)));

        // 🔴 历史行仍在（tool_calls 的可追溯性依赖它）
        assertEquals(3, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));
        assertEquals("disabled", queryString("SELECT status FROM mcp_tools WHERE tenant_id = ? "
                + "AND tool_key = ?", tenantA, "mock:export_all"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ? "
                + "AND removed_at IS NOT NULL", tenantA));
    }

    @Test
    @DisplayName("🔴 工具数超 mcp.max_tools_per_server → 30060 且整批不落库")
    void tooManyToolsRejected() throws Exception {
        override.set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_MAX_TOOLS_PER_SERVER, "1");
        long mcpId = insertMockMcp(tenantA, "mock", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30060)));

        assertEquals(0, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));
    }

    @Test
    @DisplayName("🔴 发现遇非法地址 → 30050 + 审计（不发起任何连接）")
    void discoverSsrfRejected() throws Exception {
        long mcpId = insertMcp(tenantA, "evil", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "http://10.0.0.9/v1", McpServer.AUTH_TYPE_NONE, null, "");

        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30050)));

        assertEquals(1, countAudit(tenantA, "mcp.ssrf_rejected"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM mcp_tools WHERE tenant_id = ?", tenantA));
    }

    // ===================== §7.6.3 运行时兜底与凭据 AAD =====================

    @Test
    @DisplayName("🔴 AC-MCP-004：先写合法地址 → 改库为内网 → 运行时兜底仍拒绝 30050 + 审计")
    void runtimeGuardRejectsAfterDbTampering() {
        // 合法地址用环回 Mock（已在 allowed_internal_cidrs 白名单内），
        // 🔴 有意不用公网域名：避免把安全断言建立在"测试机 DNS 可用"这种外部条件上
        long mcpId = insertMockMcp(tenantA, "crm", MockMcpServer.SCENARIO_SUCCESS,
                McpServer.TRANSPORT_STREAMABLE_HTTP);
        McpServer legit = loadInTenant(tenantA, mcpId);
        // 保存时校验通过
        ssrfGuard.requireAllowed(legit, SsrfGuard.AuditMode.NONE);

        // DBA 改库为内网地址（一期没有"保存"这个应用层入口，这正是运行时兜底存在的理由）
        jdbcTemplate.update("UPDATE mcp_servers SET endpoint = ? WHERE id = ?",
                "https://192.168.10.10/v1", mcpId);
        McpServer tampered = loadInTenant(tenantA, mcpId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ssrfGuard.requireAllowed(tampered, SsrfGuard.AuditMode.NEW_TRANSACTION));

        assertEquals(ErrorCode.TOOL_DENIED, ex.getCode());
        assertEquals(1, countAudit(tenantA, "mcp.ssrf_rejected"));
    }

    @Test
    @DisplayName("🔴 ADR-012：凭据密文被跨租户搬运 → AAD 不匹配 → 解密失败 30060")
    void credentialCipherIsTenantBound() {
        String cipherForA = credentialCipher.encrypt(CIPHER_PLAINTEXT, tenantA, "crm");
        // 把 A 的密文原样复制到 B 的配置里（DBA 误操作 / 恶意复制）
        long mcpInB = insertMcp(tenantB, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_BEARER, cipherForA, "9f2c");
        McpServer serverInB = loadInTenant(tenantB, mcpInB);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> credentialResolver.authHeaders(serverInB));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertFalse(ex.getMessage().contains(CIPHER_PLAINTEXT));
    }

    @Test
    @DisplayName("凭据解密正确 → bearer 头可用；🔴 明文不出现在异常与视图中")
    void credentialDecryptsForRuntime() {
        String cipher = credentialCipher.encrypt(CIPHER_PLAINTEXT, tenantA, "crm");
        long mcpId = insertMcp(tenantA, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_BEARER, cipher, "9f2c");
        McpServer server = loadInTenant(tenantA, mcpId);

        assertEquals("Bearer " + CIPHER_PLAINTEXT,
                credentialResolver.authHeaders(server).get(McpCredentialResolver.BEARER_HEADER));

        McpCredentialResolver.CredentialView view = credentialResolver.view(server);
        assertTrue(view.configured());
        assertEquals("9f2c", view.last4());
        assertEquals(1, view.keyVersion());
    }

    @Test
    @DisplayName("🔴 authType=bearer 但未配凭据 → 30060（绝不降级为匿名调用）")
    void missingCredentialRejected() {
        long mcpId = insertMcp(tenantA, "crm", McpServer.TRANSPORT_STREAMABLE_HTTP,
                "https://mcp.example.com/v1", McpServer.AUTH_TYPE_BEARER, null, "");
        McpServer server = loadInTenant(tenantA, mcpId);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> credentialResolver.authHeaders(server));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    // ===================== 辅助 =====================

    private long percentile95(List<Long> samples) {
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(Long::compareTo);
        int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95D) - 1);
        return sorted.get(index);
    }

    private void discover(long mcpId, String body) throws Exception {
        mockMvc.perform(post("/api/v1/admin/mcp/" + mcpId + "/discover")
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
    }

    private McpServer loadInTenant(String tenantId, long mcpId) {
        com.eyes.albedo.tenant.TenantContext.bind(new com.eyes.albedo.tenant.TenantContext.Snapshot(
                tenantId, 1L, "host", com.eyes.albedo.tenant.TenantStatus.ENABLED, 0L, null));
        try {
            return mcpServerRepository.findOneById(mcpId).orElseThrow();
        } finally {
            com.eyes.albedo.tenant.TenantContext.clear();
        }
    }

    private String mockEndpoint(String scenario, String transport) {
        String path = McpServer.TRANSPORT_SSE.equals(transport)
                ? "/mock-mcp/sse" : "/mock-mcp/streamable-http";
        return "http://127.0.0.1:" + port + path + "?scenario=" + scenario;
    }

    private long insertMockMcp(String tenantId, String mcpKey, String scenario, String transport) {
        return insertMcp(tenantId, mcpKey, transport, mockEndpoint(scenario, transport),
                McpServer.AUTH_TYPE_NONE, null, "");
    }

    private long insertMcp(String tenantId, String mcpKey, String transport, String endpoint,
                           String authType, String cipher, String last4) {
        jdbcTemplate.update("INSERT INTO mcp_servers (tenant_id, mcp_key, name, transport, endpoint,"
                        + " auth_type, credential_cipher, credential_last4, credential_key_version,"
                        + " timeout_seconds, status, version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,30,'enabled',0)",
                tenantId, mcpKey, mcpKey, transport, endpoint, authType, cipher,
                last4 == null ? "" : last4,
                McpServer.AUTH_TYPE_NONE.equals(authType) ? 0 : 1);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_servers WHERE tenant_id = ? AND mcp_key = ?",
                Long.class, tenantId, mcpKey);
    }

    private void createTenant(String tenantId, String host) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "MCP 治理测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
    }

    private void grantRole(String tenantId, long uid, String role) {
        jdbcTemplate.update("INSERT INTO tenant_users (tenant_id, uid, tenant_role, status)"
                + " VALUES (?,?,?,'active')", tenantId, uid, role);
    }

    private int countAudit(String tenantId, String action) {
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
