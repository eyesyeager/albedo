package com.eyes.albedo.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.tool.entity.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 租户用量与运行指标接口测试（api-spec §7.11.1，REQ-OBS-001 / AC-OBS-001 / AC-TEN-005）。
 *
 * <p>🔴 <b>本用例的三条核心断言</b>：
 * <ol>
 *   <li>{@code rateLimitedCount} <b>恒 0</b>（一期无数据源，非 0 即缺陷）；</li>
 *   <li>{@code denied} / {@code failed} <b>互斥不变量</b>：
 *       {@code denied + failed + succeeded + cancelled + 非终态 = toolCallCount}，
 *       且 {@code timed_out} 按 {@code errorCode} 二分且仅二分；</li>
 *   <li>🔴 <b>跨租户不串数据</b>：另一租户的消息 / 工具调用绝不出现在本租户聚合里。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class UsageMetricsIT {

    private static final long ADMIN_UID = 900000081L;
    private static final long MEMBER_UID = 900000082L;
    private static final long OTHER_UID = 900000083L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantId;
    private String host;
    private String otherTenantId;
    private String otherHost;
    private long conversationId;
    private long otherConversationId;
    private long messageId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "um" + suffix;
        host = "um-" + suffix + ".test.invalid";
        otherTenantId = "uo" + suffix;
        otherHost = "uo-" + suffix + ".test.invalid";
        insertTenant(tenantId, host);
        insertTenant(otherTenantId, otherHost);
        // 🔴 本租户内赋 tenantAdmin，另一租户不赋（用于权限与隔离双重断言）
        insertMember(tenantId, ADMIN_UID, TenantRoleEnum.TENANT_ADMIN.name());
        conversationId = insertConversation(tenantId, ADMIN_UID);
        otherConversationId = insertConversation(otherTenantId, OTHER_UID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tool_calls WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM conversations WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid IN (?,?,?)",
                ADMIN_UID, MEMBER_UID, OTHER_UID);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
    }

    // ===================== 口径 =====================

    @Test
    @DisplayName("🔴 §7.11.1：messageCount 只含 user/assistant 且 isCurrent=1（不含 system/tool）")
    void messageCountExcludesSystemAndTool() throws Exception {
        insertMessage(tenantId, conversationId, ADMIN_UID, "user", 1, null);
        messageId = insertMessage(tenantId, conversationId, ADMIN_UID, "assistant", 1,
                "{\"promptTokens\":10,\"completionTokens\":20,\"totalTokens\":30}");
        // 🔴 以下三条都不应计入
        insertMessage(tenantId, conversationId, ADMIN_UID, "system", 1, null);
        insertMessage(tenantId, conversationId, ADMIN_UID, "tool", 1, null);
        insertMessage(tenantId, conversationId, ADMIN_UID, "assistant", 0, null);

        JsonNode total = usage().get("total");

        assertEquals(2, total.get("messageCount").asLong(),
                "🔴 只有 user + assistant(isCurrent=1) 计入");
        assertEquals(1, total.get("conversationCount").asLong());
        assertEquals(1, total.get("activeUserCount").asLong());
        assertEquals(10, total.path("tokenUsage").get("promptTokens").asLong());
        assertEquals(20, total.path("tokenUsage").get("completionTokens").asLong());
        assertEquals(30, total.path("tokenUsage").get("totalTokens").asLong());
    }

    @Test
    @DisplayName("🔴 §7.11.1：rateLimitedCount 恒 0（series 与 total 均然，非 0 即缺陷）")
    void rateLimitedCountIsAlwaysZero() throws Exception {
        insertMessage(tenantId, conversationId, ADMIN_UID, "user", 1, null);

        JsonNode data = usage();

        assertEquals(0, data.path("total").get("rateLimitedCount").asLong());
        for (JsonNode bucket : data.get("series")) {
            assertEquals(0, bucket.get("rateLimitedCount").asLong(),
                    "🔴 一期无持久化数据源，严禁伪造/估算");
        }
    }

    @Test
    @DisplayName("🔴 §7.11.1 互斥不变量：denied/failed 二分且求和等于 toolCallCount")
    void deniedAndFailedAreMutuallyExclusive() throws Exception {
        messageId = insertMessage(tenantId, conversationId, ADMIN_UID, "assistant", 1, null);
        // denied 侧：denied(30050) + timed_out(30050 确认等待超时) + running→denied 竞态(30050)
        insertToolCall(ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED);
        insertToolCall(ToolCall.STATUS_TIMED_OUT, ErrorCode.TOOL_DENIED);
        insertToolCall(ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED);
        // failed 侧：failed(30052/30057) + timed_out(30051/30056 执行超时)
        insertToolCall(ToolCall.STATUS_FAILED, ErrorCode.MCP_UNAVAILABLE);
        insertToolCall(ToolCall.STATUS_FAILED, ErrorCode.TOOL_EXECUTION_FAILED);
        insertToolCall(ToolCall.STATUS_TIMED_OUT, ErrorCode.TOOL_TIMEOUT);
        insertToolCall(ToolCall.STATUS_TIMED_OUT, ErrorCode.TOOL_RETRY_BLOCKED);
        // 两者都不计：succeeded / cancelled / pending
        insertToolCall(ToolCall.STATUS_SUCCEEDED, null);
        insertToolCall(ToolCall.STATUS_CANCELLED, null);
        insertToolCall(ToolCall.STATUS_PENDING, null);

        JsonNode total = usage().get("total");

        assertEquals(10, total.get("toolCallCount").asLong(), "🔴 toolCallCount = 全部行");
        assertEquals(3, total.get("toolDeniedCount").asLong(),
                "denied(2) + timed_out@30050(1)");
        assertEquals(4, total.get("toolFailedCount").asLong(),
                "failed(2) + timed_out@30051/30056(2)");
        // ✅ 判据：denied + failed + succeeded(1) + cancelled(1) + 非终态(1) = toolCallCount
        assertEquals(total.get("toolCallCount").asLong(),
                total.get("toolDeniedCount").asLong() + total.get("toolFailedCount").asLong()
                        + 1 + 1 + 1,
                "🔴 互斥不变量被破坏（存在重复计数或漏计）");
    }

    @Test
    @DisplayName("🔴 AC-TEN-005：跨租户不串数据（另一租户的消息与工具调用不进本租户聚合）")
    void tenantIsolation() throws Exception {
        insertMessage(tenantId, conversationId, ADMIN_UID, "user", 1, null);
        long otherMessageId = insertMessage(otherTenantId, otherConversationId, OTHER_UID, "user",
                1, null);
        insertToolCallFor(otherTenantId, otherConversationId, otherMessageId,
                ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED);

        JsonNode total = usage().get("total");

        assertEquals(1, total.get("messageCount").asLong(), "🔴 只能看到本租户消息");
        assertEquals(0, total.get("toolCallCount").asLong(), "🔴 另一租户的工具调用不得出现");
    }

    @Test
    @DisplayName("granularity=hour：series 为连续小时桶（空桶补零），且不是分页结构")
    void hourlyBucketsAreContinuousAndNotPaged() throws Exception {
        insertMessage(tenantId, conversationId, ADMIN_UID, "user", 1, null);

        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(5, ChronoUnit.HOURS);
        JsonNode data = query(host, ADMIN_UID, from, to, "hour").path("data");

        assertEquals("hour", data.get("granularity").asText());
        assertTrue(data.get("series").size() >= 5, "🔴 空桶必须补零，形成连续序列");
        assertFalse(data.has("list"), "🔴 时间序列禁止套 {list,total,page,pageSize} 分页壳");
        assertFalse(data.has("pageSize"), "🔴 禁止伪装成分页");
        assertTrue(data.path("total").isObject(), "🔴 total 是区间合计**对象**，不是行数");
    }

    // ===================== 参数与权限 =====================

    @Test
    @DisplayName("🔴 §7.11.1：from/to 缺失或非法、to<=from、跨度 >31 天 → 10001")
    void invalidRange() throws Exception {
        Instant now = Instant.now();
        assertEquals(ErrorCode.VALIDATION_FAILED, code(host, ADMIN_UID, null, now, null));
        assertEquals(ErrorCode.VALIDATION_FAILED, code(host, ADMIN_UID, now, now, null));
        assertEquals(ErrorCode.VALIDATION_FAILED,
                code(host, ADMIN_UID, now.minus(40, ChronoUnit.DAYS), now, null));
        assertEquals(ErrorCode.VALIDATION_FAILED,
                code(host, ADMIN_UID, now.minus(1, ChronoUnit.DAYS), now, "minute"));
    }

    @Test
    @DisplayName("🔴 §7.11.1：租户内角色不足（普通成员）→ 10003")
    void insufficientTenantRole() throws Exception {
        insertMember(tenantId, MEMBER_UID, TenantRoleEnum.END_USER.name());

        Instant to = Instant.now();
        assertEquals(ErrorCode.PERMISSION_DENIED,
                code(host, MEMBER_UID, to.minus(1, ChronoUnit.DAYS), to, null));
    }

    // ===================== 辅助 =====================

    private JsonNode usage() throws Exception {
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(2, ChronoUnit.DAYS);
        JsonNode response = query(host, ADMIN_UID, from, to, null);
        assertEquals(0, response.get("code").asInt(), String.valueOf(response));
        return response.path("data");
    }

    private JsonNode query(String targetHost, long uid, Instant from, Instant to,
                           String granularity) throws Exception {
        var request = get("/api/v1/admin/metrics/usage")
                .header(HttpHeaders.HOST, targetHost)
                .header(TestAuthConfig.HEADER_TEST_UID, uid);
        if (from != null) {
            request = request.param("from", from.toString());
        }
        if (to != null) {
            request = request.param("to", to.toString());
        }
        if (granularity != null) {
            request = request.param("granularity", granularity);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    private int code(String targetHost, long uid, Instant from, Instant to, String granularity)
            throws Exception {
        return query(targetHost, uid, from, to, granularity).get("code").asInt();
    }

    private void insertTenant(String id, String tenantHost) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                id, "用量测试租户", tenantHost);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", tenantHost, id);
    }

    private void insertMember(String id, long uid, String role) {
        jdbcTemplate.update("INSERT INTO tenant_users (tenant_id, uid, tenant_role, status)"
                        + " VALUES (?,?,?,'active')"
                        + " ON DUPLICATE KEY UPDATE tenant_role = VALUES(tenant_role)",
                id, uid, role);
    }

    private long insertConversation(String id, long uid) {
        jdbcTemplate.update("INSERT INTO conversations (tenant_id, uid, agent_id, agent_version,"
                        + " title, title_source, status, message_count, last_message_at, version)"
                        + " VALUES (?,?,1,1,'用量测试','auto','active',0,UTC_TIMESTAMP(3),0)",
                id, uid);
        return jdbcTemplate.queryForObject("SELECT id FROM conversations WHERE tenant_id = ?"
                + " AND uid = ? ORDER BY id DESC LIMIT 1", Long.class, id, uid);
    }

    private long insertMessage(String id, long conversation, long uid, String role, int isCurrent,
                               String tokenUsage) {
        jdbcTemplate.update("INSERT INTO messages (tenant_id, conversation_id, uid, role, content,"
                        + " status, attempt_no, is_current, model, agent_version, token_usage,"
                        + " finish_reason) VALUES (?,?,?,?,'x','completed',1,?,'m',1,?,'stop')",
                id, conversation, uid, role, isCurrent, tokenUsage);
        return jdbcTemplate.queryForObject("SELECT id FROM messages WHERE tenant_id = ?"
                + " ORDER BY id DESC LIMIT 1", Long.class, id);
    }

    private void insertToolCall(String status, Integer errorCode) {
        insertToolCallFor(tenantId, conversationId, messageId, status, errorCode);
    }

    private void insertToolCallFor(String id, long conversation, long message, String status,
                                   Integer errorCode) {
        jdbcTemplate.update("INSERT INTO tool_calls (tenant_id, conversation_id, message_id,"
                        + " provider_call_id, round, tool_type, tool_key, risk_level, status,"
                        + " error_code, requires_confirmation)"
                        + " VALUES (?,?,?,?,1,'local','it_usage_tool','low',?,?,0)",
                id, conversation, message, "call-" + UUID.randomUUID(), status, errorCode);
    }
}
