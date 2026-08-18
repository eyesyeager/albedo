package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiChatRequest;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.ai.AiToolCall;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantResolver;
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 高风险工具逐次确认闭环测试（🔴 ADR-008 / ADR-010，api-spec §7.8）。
 *
 * <p><b>REQ-TOL-002 / REQ-CHAT-003 · AC-TOL-002 / AC-AUD-003 / AR-008 / AR-010 / AR-011</b>
 *
 * <p>覆盖 §7.8.2 冲突矩阵与三路收敛：
 * <ul>
 *   <li>allow → {@code running → succeeded}；deny → {@code denied}(30050) 且模型继续作答</li>
 *   <li>等待超时 → {@code timed_out}(30050) + 审计 {@code tool.confirm_timeout}</li>
 *   <li>重复同一 decision → {@code code=0} + {@code replayed=true}；相反 decision → {@code 30055}</li>
 *   <li>非本人 / 不存在 / 无待确认调用 → {@code 10004}</li>
 *   <li>🔴 <b>并发 confirm + stop 专项</b>（死锁与竞态）：必须在秒级收敛，绝不互等</li>
 *   <li>🔴 <b>确认等待期间断开连接</b>：必须立即收敛 {@code cancelled}，不白等满等待上限</li>
 * </ul>
 *
 * <p>🔴 <b>为什么必须有"并发"与"断连"两个专项</b>：ADR-010 的致命自锁与 AR-008 的线程挂满
 * 都<b>不会</b>在顺序执行的用例里暴露 —— 前者需要 confirm 与生成线程同时争同一行，
 * 后者需要客户端真的消失。这两个场景一旦上线才发现，代价是整个高风险确认功能不可用。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestAuthConfig.class, ToolConfirmIT.TestTools.class})
class ToolConfirmIT {

    /** 测试专用高风险实现体（生产内置的两个工具都是 low，高风险生产路径由 MCP 工具承载，ADR-015 ⑤）。 */
    @TestConfiguration
    static class TestTools {

        static final String HIGH_KEY = "it_cf_high";
        static final String LOW_KEY = "it_cf_low";

        @Bean
        LocalToolHandler itHighRiskHandler() {
            return new LocalToolHandler() {
                @Override
                public String toolKey() {
                    return HIGH_KEY;
                }

                @Override
                public String execute(LocalToolInvocation invocation) {
                    return "{\"state\":\"submitted\"}";
                }
            };
        }

        @Bean
        LocalToolHandler itLowRiskHandler() {
            return new LocalToolHandler() {
                @Override
                public String toolKey() {
                    return LOW_KEY;
                }

                @Override
                public String execute(LocalToolInvocation invocation) {
                    return "{\"ok\":true}";
                }
            };
        }
    }

    private static final long UID = 900000041L;
    private static final long OTHER_UID = 900000042L;

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
    private TenantResolver tenantResolver;
    @Autowired
    private ToolConfirmRegistry confirmRegistry;

    @MockBean
    private AiChatClient aiChatClient;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "cf" + suffix;
        host = "cf-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "确认闭环测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        agentId = insertAgent();
        agentVersionId = insertAgentVersion();
        override = new SysConfigOverride(jdbcTemplate, configService);
        // 🔴 V1.4.5（ADR-020）：平台默认已收紧为 QPM=3 / 日额度=50，而本类的验收对象不是限流；
        //    临时放宽两项阈值并由 @AfterEach 的 override.restore() 还原（api-spec §7.13 G12 的唯一合法方式）。
        //    🔴 不是"为求绿放宽断言"：阈值边界由 MessageRateLimiterIT / QuotaAdmissionIT 精确覆盖。
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000")
                .set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");
    }

    @AfterEach
    void tearDown() throws Exception {
        override.restore();
        // 🔴 V1.4.5：额度账本行按临时租户清理，避免在共享库里留下孤儿数据
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
        // 🔴 dev 映射覆盖后必须失效解析缓存，否则下一个用例读到旧映射（D-003/D-006 同类问题）
        tenantResolver.evictTenantId(tenantId);
        drainConfirmWaiters();
        jdbcTemplate.update("DELETE FROM tool_calls WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE uid IN (?,?)", UID, OTHER_UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid IN (?,?)", UID, OTHER_UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid IN (?,?)", UID, OTHER_UID);
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE 'it_cf_%'");
    }

    /**
     * 收敛本用例产生的确认等待者，并断言<b>本用例的</b> toolCall 不再有等待者。
     *
     * <p>🔴 为什么按 toolCallId 断言而不是全局 {@code pendingCount()==0}：
     * 全局计数会被<b>其它已失败用例</b>残留的等待者污染，导致"一个失败引发满屏失败"，
     * 掩盖真正的问题。按本用例的行断言既能抓到线程泄漏，又不会跨用例串味。
     */
    private void drainConfirmWaiters() throws Exception {
        List<Long> toolCallIds = jdbcTemplate.queryForList(
                "SELECT id FROM tool_calls WHERE tenant_id = ?", Long.class, tenantId);
        List<Long> messageIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT message_id FROM tool_calls WHERE tenant_id = ?", Long.class,
                tenantId);
        messageIds.forEach(confirmRegistry::cancelByMessage);
        for (Long toolCallId : toolCallIds) {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (confirmRegistry.waiting(toolCallId) && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(confirmRegistry.waiting(toolCallId),
                    "🔴 确认等待者未被注销（线程泄漏）：toolCallId=" + toolCallId);
        }
    }

    // ===================== allow / deny =====================

    @Test
    @DisplayName("🔴 AC-TOL-002 allow：awaiting_confirmation → running → succeeded，审计 tool.confirm_allowed")
    void allowFlow() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        JsonNode response = confirm(messageId, toolCallId, "allow", null, UID);
        assertEquals(0, response.get("code").asInt());
        assertEquals("running", response.path("data").get("status").asText());
        assertFalse(response.path("data").get("replayed").asBoolean());
        assertEquals(32, response.path("data").get("auditEventId").asText().length(),
                "🔴 auditEventId 必须是 32 位 hex，禁止截断");

        String body = waitForDone(stream);
        assertEquals(List.of("pending", "awaiting_confirmation", "running", "succeeded"),
                toolStatuses(body), "🔴 状态迁移逐帧下发，不得跳帧");
        assertEquals("completed", payloadOf(body, "done").get("status").asText());
        assertEquals("succeeded", toolCallStatus());
        assertEquals("allow", queryString("SELECT decision FROM tool_calls WHERE tenant_id = ?",
                tenantId));
        assertEquals(1, countAudit("tool.confirm_allowed"));
    }

    @Test
    @DisplayName("🔴 deny：denied(30050) + 审计 tool.confirm_denied；模型在无工具结果下继续作答")
    void denyFlow() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        JsonNode response = confirm(messageId, toolCallId, "deny", "我不同意", UID);
        assertEquals(0, response.get("code").asInt());
        assertEquals("denied", response.path("data").get("status").asText());

        String body = waitForDone(stream);
        assertEquals(List.of("pending", "awaiting_confirmation", "denied"), toolStatuses(body));
        assertEquals(ErrorCode.TOOL_DENIED, lastToolFrame(body).get("errorCode").asInt());
        // 🔴 工具被拒 ≠ 流失败：模型仍应继续作答，done 必发
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1));
        assertEquals(ErrorCode.TOOL_DENIED,
                queryInt("SELECT error_code FROM tool_calls WHERE tenant_id = ?", tenantId));
        assertEquals(1, countAudit("tool.confirm_denied"));
    }

    // ===================== 超时 =====================

    @Test
    @DisplayName("🔴 等待超时 → timed_out + errorCode=30050 + 审计 tool.confirm_timeout（按拒绝收敛）")
    void confirmTimeout() throws Exception {
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS, "1");
        prepareHighRiskTool();
        MvcResult stream = startStream();

        String body = waitForDone(stream);

        assertEquals(List.of("pending", "awaiting_confirmation", "timed_out"), toolStatuses(body));
        assertEquals(ErrorCode.TOOL_DENIED, lastToolFrame(body).get("errorCode").asInt(),
                "🔴 确认等待超时的 errorCode 必须是 30050（语义等同拒绝，§7.11.1 计入 denied）");
        assertEquals("timed_out", toolCallStatus());
        assertEquals(1, countAudit("tool.confirm_timeout"));
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1), "done 必发");
    }

    // ===================== 幂等与冲突矩阵 =====================

    @Test
    @DisplayName("🔴 §7.8.2：重复提交同一 decision → code=0 + replayed=true（回放原结果）")
    void repeatedSameDecisionIsReplayed() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        assertEquals(0, confirm(messageId, toolCallId, "allow", null, UID).get("code").asInt());
        waitForDone(stream);

        JsonNode replay = confirm(messageId, toolCallId, "allow", null, UID);
        assertEquals(0, replay.get("code").asInt());
        assertTrue(replay.path("data").get("replayed").asBoolean(), "🔴 必须标记 replayed=true");
        assertEquals("succeeded", replay.path("data").get("status").asText(),
                "回放的是服务端**当前**状态");
    }

    @Test
    @DisplayName("🔴🔴 §7.8.2 ④：回放**不产生**审计行，且 data.auditEventId 恒为 null")
    void replayWritesNoAuditAndReturnsNullEventId() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        JsonNode first = confirm(messageId, toolCallId, "allow", null, UID);
        assertEquals(32, first.path("data").get("auditEventId").asText().length(),
                "首次决定必须返回 32 位 hex");
        waitForDone(stream);
        assertEquals(1, countAudit("tool.confirm_allowed"), "首次决定产生且仅产生 1 条审计");

        // 🔴 连续回放 3 次（模拟多标签页 / 网络抖动重试 / 用户连点）
        for (int i = 0; i < 3; i++) {
            JsonNode replay = confirm(messageId, toolCallId, "allow", null, UID);
            assertEquals(0, replay.get("code").asInt());
            assertTrue(replay.path("data").get("replayed").asBoolean());
            assertTrue(replay.path("data").get("auditEventId").isNull(),
                    "🔴 回放时 auditEventId 必须为 null（禁止编造、禁止回查）");
        }

        assertEquals(1, countAudit("tool.confirm_allowed"),
                "🔴 回放不得写审计：3 次回放后仍只有首次那 1 条（否则前端重试会把审计刷成洪水）");
        assertEquals(0, countAudit("tool.confirm_conflict"), "同向回放不是冲突，不得写冲突审计");
    }

    @Test
    @DisplayName("🔴 §7.8.2：提交相反 decision → 30055，且不改变服务端既有决定")
    void oppositeDecisionConflicts() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        assertEquals(0, confirm(messageId, toolCallId, "deny", null, UID).get("code").asInt());
        waitForDone(stream);

        JsonNode conflict = confirm(messageId, toolCallId, "allow", null, UID);
        assertEquals(ErrorCode.TOOL_CONFIRM_CONFLICT, conflict.get("code").asInt());
        assertEquals("denied", toolCallStatus(), "🔴 冲突不得改写既有终态");
    }

    @Test
    @DisplayName("🔴🔴 §7.8.2 ⑤：连续 3 次相反决定 → 仍只产生 1 条 tool.confirm_conflict 审计")
    void conflictAuditIsWrittenAtMostOnce() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        assertEquals(0, confirm(messageId, toolCallId, "deny", "我不同意", UID).get("code").asInt());
        waitForDone(stream);
        String statusBefore = toolCallStatus();
        Integer errorCodeBefore = queryInt("SELECT error_code FROM tool_calls WHERE tenant_id = ?",
                tenantId);

        for (int i = 0; i < 3; i++) {
            assertEquals(ErrorCode.TOOL_CONFIRM_CONFLICT,
                    confirm(messageId, toolCallId, "allow", null, UID).get("code").asInt(),
                    "🔴 每次都必须返回 30055（去重只影响审计写入，不影响返回码）");
        }

        assertEquals(1, countAudit("tool.confirm_conflict"),
                "🔴 同一 toolCallId 至多一条冲突审计（行锁内点查去重，防前端重试刷审计）");
        // 🔴 冲突路径不得改动已终态的行（改动即篡改历史并破坏 §7.11.1 聚合）
        assertEquals(statusBefore, toolCallStatus());
        assertEquals(errorCodeBefore,
                queryInt("SELECT error_code FROM tool_calls WHERE tenant_id = ?", tenantId));
        assertEquals("deny", queryString("SELECT decision FROM tool_calls WHERE tenant_id = ?",
                tenantId), "🔴 既有决定必须保持不变");
    }

    @Test
    @DisplayName("🔴 §7.8.2 ⑤：冲突审计字段 = endUser/toolCall/denied/30055 + before·after 决定字面量")
    void conflictAuditFields() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        confirm(messageId, toolCallId, "deny", null, UID);
        waitForDone(stream);
        confirm(messageId, toolCallId, "allow", null, UID);

        java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_type, object_type, object_id, result, error_code, before_digest,"
                        + " after_digest FROM audit_logs WHERE tenant_id = ? AND action = ?",
                tenantId, "tool.confirm_conflict");

        assertEquals("endUser", row.get("actor_type"));
        assertEquals("toolCall", row.get("object_type"));
        assertEquals(toolCallId, row.get("object_id"));
        assertEquals("denied", row.get("result"));
        assertEquals(ErrorCode.TOOL_CONFIRM_CONFLICT, ((Number) row.get("error_code")).intValue());
        assertEquals("deny", row.get("before_digest"), "🔴 既有决定原样记（枚举字面量非敏感值）");
        assertEquals("allow", row.get("after_digest"), "🔴 被拒绝的提交值原样记");
    }

    @Test
    @DisplayName("🔴 非本人 / 不存在 / 不属该消息 → 一律 10004（不泄露存在性）")
    void notFoundCases() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                confirm(messageId, toolCallId, "allow", null, OTHER_UID).get("code").asInt(),
                "🔴 非本人必须 10004");
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                confirm(messageId, "999999999", "allow", null, UID).get("code").asInt());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                confirm("888888888", toolCallId, "allow", null, UID).get("code").asInt(),
                "🔴 toolCallId 不隶属该 messageId 也必须 10004");

        // 收尾：让流正常结束
        confirm(messageId, toolCallId, "deny", null, UID);
        waitForDone(stream);
    }

    @Test
    @DisplayName("🔴 decision 非法 / reason 超长 → 10001")
    void invalidRequest() throws Exception {
        prepareHighRiskTool();
        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        String toolCallId = waitForAwaitingConfirmation(stream);

        assertEquals(ErrorCode.VALIDATION_FAILED,
                confirm(messageId, toolCallId, "maybe", null, UID).get("code").asInt());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                confirm(messageId, toolCallId, "deny", "x".repeat(201), UID).get("code").asInt());

        confirm(messageId, toolCallId, "deny", null, UID);
        waitForDone(stream);
    }

    @Test
    @DisplayName("🔴 §7.8.2 矩阵：低风险自动执行后 confirm → 回放（succeeded 行）；pending 行 → 10004")
    void confirmOnAutoExecutedTool() throws Exception {
        bindLocalTool(grantLocalTool(TestTools.LOW_KEY, ToolRiskPolicy.RISK_LOW));
        scriptToolThenText(TestTools.LOW_KEY, "已完成");
        MvcResult stream = startStream();
        String body = waitForDone(stream);
        String messageId = payloadOf(body, "meta").get("messageId").asText();
        String toolCallId = lastToolFrame(body).get("toolCallId").asText();

        assertEquals(List.of("pending", "running", "succeeded"), toolStatuses(body),
                "low + auto → 不进确认流程（历史同意也无从沿用）");

        // 矩阵行「succeeded × allow」→ code=0 + replayed=true（以服务端既有状态回放）
        JsonNode replay = confirm(messageId, toolCallId, "allow", null, UID);
        assertEquals(0, replay.get("code").asInt());
        assertTrue(replay.path("data").get("replayed").asBoolean());
        assertEquals("succeeded", replay.path("data").get("status").asText());
        // 矩阵行「succeeded × deny」→ 30055（相反决定）
        assertEquals(ErrorCode.TOOL_CONFIRM_CONFLICT,
                confirm(messageId, toolCallId, "deny", null, UID).get("code").asInt());

        // 🔴 矩阵行「pending × 任意」→ 10004（无待确认的工具调用）。
        //    pending 是转瞬即逝的中间态，端到端难以稳定命中，故直接构造该状态的行来断言这一格。
        jdbcTemplate.update("INSERT INTO tool_calls (tenant_id, conversation_id, message_id,"
                        + " provider_call_id, round, tool_type, tool_key, risk_level, status,"
                        + " requires_confirmation) SELECT tenant_id, conversation_id, message_id,"
                        + " 'call-pending', round, tool_type, tool_key, risk_level, 'pending', 0"
                        + " FROM tool_calls WHERE id = ?", Long.parseLong(toolCallId));
        Long pendingId = jdbcTemplate.queryForObject("SELECT id FROM tool_calls WHERE tenant_id = ?"
                + " AND provider_call_id = 'call-pending'", Long.class, tenantId);
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                confirm(messageId, String.valueOf(pendingId), "allow", null, UID).get("code").asInt());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                confirm(messageId, String.valueOf(pendingId), "deny", null, UID).get("code").asInt());
    }

    // ===================== 🔴 死锁与竞态专项 =====================

    @Test
    @DisplayName("🔴🔴 ADR-010 死锁专项：确认等待期间并发 confirm + stop，必须秒级收敛且终态唯一")
    void concurrentConfirmAndStopDoesNotDeadlock() throws Exception {
        // 等待上限拉长：若真的死锁，用例会卡在这里而不是"恰好超时通过"
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS, "60");
        prepareHighRiskTool();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long suiteBegan = System.currentTimeMillis();
        try {
            // 🔴 终验要求真跑至少 20 次，避免单次调度恰好绕过 confirm/stop 竞态窗口。
            for (int iteration = 0; iteration < 20; iteration++) {
                MvcResult stream = startStream();
                String messageId = metaMessageId(stream);
                String toolCallId = waitForAwaitingConfirmation(stream);
                CountDownLatch start = new CountDownLatch(1);
                long began = System.currentTimeMillis();

                var confirmTask = pool.submit(() -> {
                    start.await();
                    return confirm(messageId, toolCallId, "allow", null, UID).get("code").asInt();
                });
                var stopTask = pool.submit(() -> {
                    start.await();
                    return stop(messageId).get("code").asInt();
                });
                start.countDown();

                int confirmCode = confirmTask.get(30, TimeUnit.SECONDS);
                int stopCode = stopTask.get(30, TimeUnit.SECONDS);
                // 🔴 两个请求都必须在秒级返回：任一超时即说明行锁与确认等待互等（ADR-010 致命自锁）
                assertTrue(System.currentTimeMillis() - began < 30_000,
                        "🔴 第 " + iteration + " 轮并发 confirm + stop 出现互等，疑似死锁");
                assertTrue(confirmCode == 0 || confirmCode == ErrorCode.TOOL_CONFIRM_CONFLICT,
                        "confirm 只允许成功或按冲突收敛，实际 code=" + confirmCode);
                assertEquals(0, stopCode);

                String body = waitForDone(stream);
                // 🔴 每轮按当前 toolCallId 查询，证明行锁只裁决出一个终态。
                String finalStatus = jdbcTemplate.queryForObject(
                        "SELECT status FROM tool_calls WHERE id = ?", String.class, Long.parseLong(toolCallId));
                assertTrue(ToolCall.TERMINAL_STATUSES.contains(finalStatus),
                        "🔴 第 " + iteration + " 轮竞态后必须收敛到终态，实际=" + finalStatus);
                assertEquals(iteration + 1,
                        queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?", tenantId),
                        "每轮只能新增一个工具调用行（uk_tenant_msg_call）");
                assertEquals("done", eventNames(body).get(eventNames(body).size() - 1), "done 必发");
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("CONCURRENCY confirm-stop iterations=20 elapsed=%dms result=no-deadlock%n",
                System.currentTimeMillis() - suiteBegan);
    }

    @Test
    @DisplayName("🔴🔴 AR-008 断连专项：确认等待中关闭 SSE 连接 → 立即收敛 cancelled（不白等等待上限）")
    void closingSseDuringConfirmConvergesToCancelled() throws Exception {
        // 等待上限 60s；心跳 1s（心跳写失败是"发现客户端已消失"的机制）
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS, "60");
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.STREAM_HEARTBEAT_SECONDS, "1");
        // 🔴 用真实 HTTP 连接才能"真的断开"；MockMvc 无法模拟 socket 关闭。
        //    真实请求的 Host 头是 localhost:{randomPort}，故临时借 dev host 映射把它指到本用例租户。
        override.set(ConfigKeys.GROUP_TENANT, ConfigKeys.DEV_HOST_MAPPING,
                "{\"localhost:" + port + "\":\"" + tenantId + "\"}");
        tenantResolver.evictTenantId(tenantId);
        prepareHighRiskTool();

        long began = System.currentTimeMillis();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // 🔴 L5：真实 HTTP 的 SSE 请求同样只能经 SseRequests 构造（Accept 恒 text/event-stream）
        HttpRequest request = SseRequests.realHttpPost(port, SseRequests.sendNewPath(), UID,
                        "{\"content\":\"高风险确认后断连\"}")
                .build();
        HttpResponse<InputStream> response = client.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());

        // 读到 awaiting_confirmation 帧后立刻关闭连接（等价于用户关掉浏览器）
        try (InputStream in = response.body()) {
            StringBuilder received = new StringBuilder();
            byte[] chunk = new byte[512];
            long deadline = System.currentTimeMillis() + 20_000L;
            while (System.currentTimeMillis() < deadline) {
                int read = in.read(chunk);
                if (read < 0) {
                    break;
                }
                received.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                if (received.toString().contains("awaiting_confirmation")) {
                    break;
                }
            }
            assertTrue(received.toString().contains("awaiting_confirmation"),
                    "未收到 awaiting_confirmation 帧：" + received);
        }

        // 🔴 断连后必须在秒级收敛为 cancelled，而不是白等满 60s
        String status = awaitToolCallStatus(ToolCall.STATUS_CANCELLED, 20_000L);
        long elapsed = System.currentTimeMillis() - began;
        assertEquals(ToolCall.STATUS_CANCELLED, status,
                "🔴 断连后工具调用必须收敛为 cancelled，实际=" + status);
        assertTrue(elapsed < 30_000L,
                "🔴 断连收敛耗时 " + elapsed + "ms，接近/超过等待上限说明唤醒链路没生效");
        assertEquals(0, countAudit("tool.confirm_allowed"));
        assertEquals(0, countAudit("tool.confirm_denied"));
        assertEquals(0, countAudit("tool.confirm_timeout"),
                "🔴 取消不是用户决定，不得写 confirm 类审计（§9.5.4 ③）");
    }

    // ===================== 辅助 =====================

    private void prepareHighRiskTool() {
        bindLocalTool(grantLocalTool(TestTools.HIGH_KEY, ToolRiskPolicy.RISK_HIGH));
        scriptToolThenText(TestTools.HIGH_KEY, "退款已处理完毕");
    }

    /** 第一轮请求高风险工具，第二轮出文本。 */
    private void scriptToolThenText(String toolKey, String finalText) {
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiChatRequest request = AiStreamStub.request(invocation);
                    AiStreamStub.openStream(invocation);
                    boolean hasToolResult = request.messages().stream()
                            .anyMatch(message -> "tool".equals(message.role()));
                    if (!hasToolResult) {
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-cf-1",
                                        toolKey.replaceAll("[^a-zA-Z0-9_-]", "_"),
                                        // 🔴 故意塞入手机号与长数字：确认卡片的摘要必须已脱敏
                                        "{\"orderId\":\"123456789012\",\"phone\":\"13812345678\"}")));
                    }
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept(finalText);
                    return new AiStreamOutcome("stop", new TokenUsage(5, 5, 10), false);
                });
    }

    private MvcResult startStream() throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        return mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), host, UID,
                        "{\"content\":\"请帮我退款\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private JsonNode confirm(String messageId, String toolCallId, String decision, String reason,
                             long uid) throws Exception {
        String body = reason == null
                ? "{\"decision\":\"" + decision + "\"}"
                : objectMapper.writeValueAsString(java.util.Map.of("decision", decision,
                "reason", reason));
        MvcResult result = mockMvc.perform(post("/api/v1/messages/" + messageId + "/tool-calls/"
                        + toolCallId + "/confirm")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, uid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(bodyOf(result));
    }

    private JsonNode stop(String messageId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/messages/" + messageId + "/stop")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(bodyOf(result));
    }

    private String metaMessageId(MvcResult result) throws Exception {
        for (int i = 0; i < 150; i++) {
            String body = bodyOf(result);
            if (body.contains("event:meta")) {
                return payloadOf(body, "meta").get("messageId").asText();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未收到 meta 事件：" + bodyOf(result));
    }

    private String waitForAwaitingConfirmation(MvcResult result) throws Exception {
        for (int i = 0; i < 200; i++) {
            String body = bodyOf(result);
            if (body.contains("awaiting_confirmation")) {
                for (JsonNode frame : toolFrames(body)) {
                    if ("awaiting_confirmation".equals(frame.get("status").asText())) {
                        assertNotNull(frame.get("argsSummary"));
                        String args = frame.get("argsSummary").asText();
                        // 🔴 确认卡片上的摘要必须已脱敏（§5.4.3：手机号保留首尾、≥12 位长数字只留后 4）
                        assertFalse(args.contains("13812345678"),
                                "🔴 手机号未脱敏：" + args);
                        assertFalse(args.contains("123456789012"),
                                "🔴 长数字未脱敏：" + args);
                        return frame.get("toolCallId").asText();
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未收到 awaiting_confirmation 帧：" + bodyOf(result));
    }

    private String waitForDone(MvcResult result) throws Exception {
        for (int i = 0; i < 300; i++) {
            String body = bodyOf(result);
            if (body.contains("event:done")) {
                return body;
            }
            Thread.sleep(100);
        }
        return bodyOf(result);
    }

    private String awaitToolCallStatus(String expected, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = toolCallStatus();
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(200);
        }
        return status;
    }

    private String toolCallStatus() {
        List<String> values = jdbcTemplate.queryForList(
                "SELECT status FROM tool_calls WHERE tenant_id = ?", String.class, tenantId);
        return values.isEmpty() ? null : values.get(0);
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private List<String> eventNames(String body) {
        List<String> names = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("event:")) {
                names.add(trimmed.substring("event:".length()).trim());
            }
        }
        return names;
    }

    private List<String> toolStatuses(String body) throws Exception {
        List<String> statuses = new ArrayList<>();
        for (JsonNode frame : toolFrames(body)) {
            statuses.add(frame.get("status").asText());
        }
        return statuses;
    }

    private JsonNode lastToolFrame(String body) throws Exception {
        List<JsonNode> frames = toolFrames(body);
        assertFalse(frames.isEmpty(), "未收到任何 tool 帧：" + body);
        return frames.get(frames.size() - 1);
    }

    private List<JsonNode> toolFrames(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("event:tool")) {
                continue;
            }
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.startsWith("data:")) {
                    frames.add(objectMapper.readTree(candidate.substring("data:".length()).trim()));
                    break;
                }
            }
        }
        return frames;
    }

    private JsonNode payloadOf(String body, String eventName) throws Exception {
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("event:" + eventName)) {
                for (int j = i + 1; j < lines.length; j++) {
                    String candidate = lines[j].trim();
                    if (candidate.startsWith("data:")) {
                        return objectMapper.readTree(candidate.substring("data:".length()).trim());
                    }
                }
            }
        }
        throw new AssertionError("未找到事件 " + eventName + "：" + body);
    }

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "cf-agent", "确认测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion() {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',120,?,'published')",
                tenantId, agentId, ToolRiskPolicy.POLICY_AUTO);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long grantLocalTool(String toolKey, String riskLevel) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_tools WHERE tool_key = ?", Integer.class, toolKey);
        if (exists == null || exists == 0) {
            jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                            + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                            + " VALUES (?,?,1,'',?,?,1,5,?)",
                    toolKey, toolKey, "{\"type\":\"object\"}", riskLevel, LocalTool.STATUS_ENABLED);
        }
        jdbcTemplate.update("INSERT INTO tenant_tool_grants (tenant_id, tool_key, granted, config,"
                        + " status) VALUES (?,?,1,NULL,?)",
                tenantId, toolKey, TenantToolGrant.STATUS_ENABLED);
        return jdbcTemplate.queryForObject("SELECT id FROM tenant_tool_grants WHERE tenant_id = ?"
                + " AND tool_key = ?", Long.class, tenantId, toolKey);
    }

    private void bindLocalTool(long grantId) {
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,1,0)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_LOCAL_TOOL, grantId);
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
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }
}
