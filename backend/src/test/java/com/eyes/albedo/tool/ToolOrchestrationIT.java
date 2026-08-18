package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.handler.CalculatorHandler;
import com.eyes.albedo.tool.handler.DateTimeNowHandler;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 工具编排端到端测试（api-spec §5.4.2 多轮时序 / §7.6.3 校验顺序 / §7.9.1 查询）。
 *
 * <p><b>REQ-TOL-002 / REQ-CHAT-003 · AC-TOL-001 / AC-TOL-002 / AC-CHAT-007 / AC-AUD-003</b>
 *
 * <p>覆盖：
 * <ul>
 *   <li>🔴 多轮闭环：{@code meta → tool(pending) → tool(running) → tool(succeeded) → delta → done}</li>
 *   <li>🔴 轮次上限 {@code tool.max_rounds} → {@code error(30054)} 且 <b>done 必发</b></li>
 *   <li>🔴 未授权工具 → {@code 30050} + 审计 {@code tool.grant_denied} 落库</li>
 *   <li>🔴 参数非法 → {@code 30053} 且<b>未发起执行</b></li>
 *   <li>🔴 无可用工具时<b>不下发</b> {@code tools} 字段（不得下发空数组）</li>
 *   <li>🔴 超大结果字节截断 → {@code truncated=true}；摘要按字符截断（两类互不混用）</li>
 *   <li>🔴 {@code tool} 帧 12 字段齐备（含兼容字段 {@code summary}）</li>
 *   <li>🔴 工具调用查询接口：分页 / 过滤 / 字段禁含 / 跨租户 {@code 10004}</li>
 * </ul>
 *
 * <p>数据纪律：临时租户 + {@code it_or_%} 前缀的平台 Tool 行，{@code @AfterEach} 精确清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestAuthConfig.class, ToolOrchestrationIT.TestTools.class})
class ToolOrchestrationIT {

    /** 测试专用实现体（生产内置只有 datetime_now / calculator，见 ADR-015）。 */
    @TestConfiguration
    static class TestTools {

        static final String ECHO_KEY = "it_or_echo";
        static final String BIG_KEY = "it_or_big";

        @Bean
        LocalToolHandler itEchoHandler() {
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

        @Bean
        LocalToolHandler itBigHandler() {
            return new LocalToolHandler() {
                @Override
                public String toolKey() {
                    return BIG_KEY;
                }

                @Override
                public String execute(LocalToolInvocation invocation) {
                    return "{\"data\":\"" + "x".repeat(4096) + "\"}";
                }
            };
        }
    }

    private static final long UID = 900000031L;
    private static final long OTHER_UID = 900000032L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private ToolGrantPointCheck grantPointCheck;

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
        tenantId = "or" + suffix;
        host = "or-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "编排测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        agentId = insertAgent();
        agentVersionId = insertAgentVersion(ToolRiskPolicy.POLICY_AUTO);
        override = new SysConfigOverride(jdbcTemplate, configService);
    }

    @AfterEach
    void tearDown() {
        override.restore();
        // 🔴 V1.4.5：额度账本行按临时租户清理，避免在共享库里留下孤儿数据
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
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
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE 'it_or_%'");
    }

    // ===================== 多轮闭环 =====================

    @Test
    @DisplayName("🔴 多轮闭环：tool(pending→running→succeeded) → delta → done(completed)，结果回灌模型")
    void multiRoundToolLoop() throws Exception {
        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        List<AiChatRequest> captured = scriptToolThenText(TestTools.ECHO_KEY,
                "{\"city\":\"上海\"}", "查到了：晴");

        String body = sendAndCollect("{\"content\":\"上海天气\"}");
        List<String> events = eventNames(body);

        assertEquals("meta", events.get(0), "🔴 meta 必须首发：" + events);
        assertEquals(List.of("meta", "tool", "tool", "tool", "delta", "done"), events,
                "🔴 状态迁移必须逐帧下发、不得跳帧：" + events + "\n原始：" + body);
        assertEquals(List.of("pending", "running", "succeeded"), toolStatuses(body));
        assertEquals("completed", payloadOf(body, "done").get("status").asText());

        // 🔴 第一轮下发了 tools 字段；第二轮把 assistant(tool_calls) + role=tool 结果回灌
        assertEquals(2, captured.size(), "模型必须被请求两轮");
        assertTrue(captured.get(0).hasTools(), "🔴 有可用工具时必须下发 tools 字段");
        List<String> roles = captured.get(1).messages().stream().map(m -> m.role()).toList();
        assertTrue(roles.contains("tool"), "🔴 工具结果必须以 role=tool 回灌：" + roles);
        String toolContent = captured.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow().content();
        assertTrue(toolContent.contains("echo"), "回灌内容应为工具结果体：" + toolContent);
        String toolCallId = captured.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow().toolCallId();
        assertEquals("call-1", toolCallId, "🔴 tool_call_id 必须与模型给出的 id 一致");

        // 落库：状态机终态 + 脱敏摘要
        assertEquals("succeeded", queryString("SELECT status FROM tool_calls WHERE tenant_id = ?",
                tenantId));
        assertEquals(1, queryInt("SELECT round FROM tool_calls WHERE tenant_id = ?", tenantId));
    }

    @Test
    @DisplayName("🔴 生产内置 datetime_now 端到端可达（ADR-015：注册表不再为空）")
    void builtinDatetimeToolRunsEndToEnd() throws Exception {
        bindLocalTool(grantLocalTool(DateTimeNowHandler.TOOL_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(DateTimeNowHandler.TOOL_KEY, "{\"timezone\":\"Asia/Shanghai\"}",
                "现在是北京时间");

        String body = sendAndCollect("{\"content\":\"现在几点\"}");

        assertEquals(List.of("pending", "running", "succeeded"), toolStatuses(body));
        JsonNode succeeded = lastToolFrame(body);
        assertTrue(succeeded.get("resultSummary").asText().contains("Asia/Shanghai"),
                "结果摘要应含时区：" + succeeded);
    }

    @Test
    @DisplayName("🔴 内置 calculator 端到端：结果回灌为字符串，避免精度丢失")
    void builtinCalculatorRunsEndToEnd() throws Exception {
        bindLocalTool(grantLocalTool(CalculatorHandler.TOOL_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(CalculatorHandler.TOOL_KEY, "{\"expression\":\"(1+2)*3\"}", "等于 9");

        String body = sendAndCollect("{\"content\":\"算一下\"}");

        assertEquals("succeeded", lastToolFrame(body).get("status").asText());
        assertTrue(lastToolFrame(body).get("resultSummary").asText().contains("9"),
                "结果摘要应含计算结果：" + lastToolFrame(body));
    }

    @Test
    @DisplayName("🔴 tool 帧字段齐备（12 项，含 V1.0 兼容字段 summary）")
    void toolFrameFieldsComplete() throws Exception {
        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(TestTools.ECHO_KEY, "{\"phone\":\"13812345678\"}", "好的");

        String body = sendAndCollect("{\"content\":\"脱敏检查\"}");
        JsonNode frame = lastToolFrame(body);

        for (String field : List.of("toolCallId", "toolType", "toolKey", "riskLevel", "status",
                "round", "summary", "argsSummary", "resultSummary", "truncated", "errorCode",
                "retryAfterSeconds")) {
            assertTrue(frame.has(field), "🔴 tool 帧缺字段 " + field + "：" + frame);
        }
        assertTrue(frame.get("errorCode").isNull(), "成功时 errorCode 必须为 null（字段仍存在）");
        assertFalse(frame.get("argsSummary").asText().contains("13812345678"),
                "🔴 入参摘要必须脱敏：" + frame.get("argsSummary").asText());
        assertEquals(frame.get("resultSummary").asText(), frame.get("summary").asText(),
                "🔴 终态时兼容字段 summary = resultSummary");
    }

    // ===================== 轮次上限 =====================

    @Test
    @DisplayName("🔴 轮次超上限 → error(30054) 且 done 必发（不得让前端永久 loading）")
    void roundLimitExceeded() throws Exception {
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_ROUNDS, "1");
        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        // 模型每轮都请求工具 → 第 2 批必然越限
        AtomicInteger round = new AtomicInteger();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiStreamStub.openStream(invocation);
                    int index = round.incrementAndGet();
                    return new AiStreamOutcome("tool_calls", null, false,
                            List.of(new AiToolCall("call-" + index, functionName(TestTools.ECHO_KEY),
                                    "{}")));
                });

        String body = sendAndCollect("{\"content\":\"死循环\"}");
        List<String> events = eventNames(body);

        assertTrue(events.contains("error"), "必须下发 error：" + events);
        assertEquals(ErrorCode.TOOL_LOOP_LIMIT_EXCEEDED, payloadOf(body, "error").get("code").asInt());
        assertEquals("done", events.get(events.size() - 1), "🔴 done 必发且在最后：" + events);
        assertEquals("failed", payloadOf(body, "done").get("status").asText());
        // 第 2 批不再下发新的 tool 帧（只有第 1 轮的三帧）
        assertEquals(List.of("pending", "running", "succeeded"), toolStatuses(body));
    }

    // ===================== 授权与参数 =====================

    @Test
    @DisplayName("🔴 AC-AUD-003：模型请求未授权工具 → tool(denied,30050) + 审计 tool.grant_denied 落库")
    void unauthorizedToolDenied() throws Exception {
        // 故意不建授权与绑定：清单为空
        scriptToolThenText("it_or_ghost", "{}", "我无法调用该工具");

        String body = sendAndCollect("{\"content\":\"调用幽灵工具\"}");

        assertEquals(List.of("pending", "denied"), toolStatuses(body));
        JsonNode denied = lastToolFrame(body);
        assertEquals(ErrorCode.TOOL_DENIED, denied.get("errorCode").asInt());
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'tool.grant_denied'", tenantId));
        // 🔴 被拒绝的调用也必须落库（§7.11.1：toolCallCount 含 denied）
        assertEquals("denied", queryString("SELECT status FROM tool_calls WHERE tenant_id = ?",
                tenantId));
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1));
    }

    @Test
    @DisplayName("🔴 参数不符 Schema → tool(failed,30053) 且未发起执行（工具实现体未被调用）")
    void invalidArgsRejectedBeforeExecution() throws Exception {
        // calculator 的 Schema 要求 expression 必填且字符集受限
        bindLocalTool(grantLocalTool(CalculatorHandler.TOOL_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(CalculatorHandler.TOOL_KEY, "{\"expression\":\"drop table\"}", "参数不对");

        String body = sendAndCollect("{\"content\":\"非法表达式\"}");

        assertEquals(List.of("pending", "failed"), toolStatuses(body));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, lastToolFrame(body).get("errorCode").asInt());
        // 🔴 未进入 running：说明没发起执行
        assertFalse(toolStatuses(body).contains("running"), "参数非法不得进入执行阶段");
        assertEquals("failed", queryString("SELECT status FROM tool_calls WHERE tenant_id = ?",
                tenantId));
    }

    @Test
    @DisplayName("🔴 无可用工具时不下发 tools 字段（不得下发空数组）")
    void noToolsFieldWhenCatalogEmpty() throws Exception {
        List<AiChatRequest> captured = scriptTextOnly("你好");

        sendAndCollect("{\"content\":\"闲聊\"}");

        assertEquals(1, captured.size());
        assertFalse(captured.get(0).hasTools(), "🔴 清单为空时不得下发 tools 字段");
    }

    @Test
    @DisplayName("🔴🔴 C3：清单构造后撤销本地 Tool 授权 → 执行前点查判 denied + 30050 + 审计"
            + "（🔴 不是 failed/30052，且**未进入 running**）")
    void localToolGrantRevokedBetweenCatalogAndExecution() throws Exception {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);
        long queriesBefore = grantPointCheck.queryCount();

        // 🔴 模拟 DBA 在"清单已构造、工具尚未执行"的窗口内撤销授权：
        //    在模型返回 tool_calls 的**同一时刻**改库（这正是 §7.6.3 第 2/3 步要覆盖的窗口）
        AtomicInteger round = new AtomicInteger();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiStreamStub.openStream(invocation);
                    if (round.getAndIncrement() == 0) {
                        jdbcTemplate.update("UPDATE tenant_tool_grants SET granted = 0"
                                + " WHERE id = ?", grantId);
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-1", functionName(TestTools.ECHO_KEY),
                                        "{\"city\":\"上海\"}")));
                    }
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept("该工具当前不可用");
                    return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
                });

        String body = sendAndCollect("{\"content\":\"撤授权竞态\"}");

        assertEquals(List.of("pending", "denied"), toolStatuses(body),
                "🔴 必须在置 running **之前**拒绝（点查发生在 running 之前）：" + body);
        assertEquals(ErrorCode.TOOL_DENIED, lastToolFrame(body).get("errorCode").asInt(),
                "🔴 安全拒绝恒 30050，绝不是 30052/30060");
        assertEquals("denied", queryString("SELECT status FROM tool_calls WHERE tenant_id = ?",
                tenantId));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ?"
                        + " AND action = 'tool.grant_denied'", tenantId),
                "🔴 撤授权拒绝必须写 tool.grant_denied 审计（与状态流转同一独立短事务）");
        assertEquals(0, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?"
                        + " AND status = 'failed'", tenantId),
                "🔴 绝不允许落成 failed（会错计进 toolFailedCount）");
        // 🔴 查询预算：本次生成只执行了 1 次工具 → 点查恰好 1 次（禁止拆多次、禁止缓存）
        assertEquals(1L, grantPointCheck.queryCount() - queriesBefore,
                "🔴 执行前授权点查必须 ≤1 次/次执行（api-spec §7.1.2 查询次数表第 3 行）");
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1), "🔴 done 必发");
    }

    @Test
    @DisplayName("🔴 平台侧停用本地 Tool（local_tools.status=disabled）→ 执行前点查同样判 denied + 30050")
    void localToolPlatformDisabledBetweenCatalogAndExecution() throws Exception {
        long grantId = grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(grantId);

        AtomicInteger round = new AtomicInteger();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiStreamStub.openStream(invocation);
                    if (round.getAndIncrement() == 0) {
                        jdbcTemplate.update("UPDATE local_tools SET status = 'disabled'"
                                + " WHERE tool_key = ?", TestTools.ECHO_KEY);
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-1", functionName(TestTools.ECHO_KEY),
                                        "{}")));
                    }
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept("好的");
                    return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
                });

        String body = sendAndCollect("{\"content\":\"平台停用竞态\"}");

        assertEquals(List.of("pending", "denied"), toolStatuses(body));
        assertEquals(ErrorCode.TOOL_DENIED, lastToolFrame(body).get("errorCode").asInt());
        assertEquals(1, queryInt("SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ?"
                + " AND action = 'tool.grant_denied'", tenantId));
    }

    @Test
    @DisplayName("🔴 A4：SSE error 事件字段恒为 code/message/retryAfterSeconds 三项，30060 **不含** violations")
    void sseErrorCarriesNoViolations() throws Exception {
        // 🔴 制造运行时 30060：注册表有行但平台无实现体（清单构造阶段即失败，进模型之前）
        grantLocalTool("it_or_no_handler", ToolRiskPolicy.RISK_LOW, 1);
        bindLocalTool(queryInt("SELECT id FROM tenant_tool_grants WHERE tenant_id = ?"
                + " AND tool_key = 'it_or_no_handler'", tenantId));
        scriptTextOnly("不会走到这里");

        String body = sendAndCollect("{\"content\":\"非法配置\"}");
        JsonNode error = payloadOf(body, "error");

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, error.get("code").asInt());
        assertEquals(3, error.size(), "🔴 error 事件字段集合恒为三项：" + error);
        for (String forbidden : List.of("violations", "objectType", "objectId", "rule",
                "checkedObjects", "field", "warnings", "valid")) {
            assertFalse(error.has(forbidden),
                    "🔴 终端用户路径禁止下发字段级明细（配置拓扑泄露）：" + forbidden + " in " + error);
        }
        assertFalse(error.toString().contains("it_or_no_handler"),
                "🔴 message 不得回显内部对象标识：" + error);
        assertTrue(error.get("retryAfterSeconds").isNull(),
                "🔴 一期流内 10005 不可达，retryAfterSeconds 恒 null（字段仍须存在）");
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1), "🔴 done 必发");
    }

    // ===================== 截断（两类分离） =====================

    @Test
    @DisplayName("🔴 EX-017：超大结果按字节截断 truncated=true；摘要按字符截断（两类不混用）")
    void oversizeResultTruncation() throws Exception {
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_MAX_BYTES, "512");
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_SUMMARY_MAX_CHARS, "64");
        bindLocalTool(grantLocalTool(TestTools.BIG_KEY, ToolRiskPolicy.RISK_LOW, 1));
        List<AiChatRequest> captured = scriptToolThenText(TestTools.BIG_KEY, "{}", "收到");

        String body = sendAndCollect("{\"content\":\"大结果\"}");
        JsonNode frame = lastToolFrame(body);

        assertTrue(frame.get("truncated").asBoolean(), "🔴 超字节上限必须标记 truncated");
        assertTrue(frame.get("resultSummary").asText().length() <= 64,
                "🔴 摘要受字符阈值约束：" + frame.get("resultSummary").asText().length());
        String fedBack = captured.get(1).messages().stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow().content();
        assertTrue(fedBack.length() > 64,
                "🔴 回灌模型用**字节**阈值（512B），绝不能被摘要的 64 字符阈值截断");
        assertTrue(fedBack.getBytes(StandardCharsets.UTF_8).length <= 512 + 32,
                "回灌体必须落在字节阈值内（含截断标记）：" + fedBack.length());
        assertEquals(1, queryInt("SELECT truncated FROM tool_calls WHERE tenant_id = ?", tenantId));
    }

    @Test
    @DisplayName("🔴 §5.4.2：工具被拒且模型无内容可产出 → error(30050) + done(tool_denied)（不给空白回答）")
    void deniedAndModelCannotContinue() throws Exception {
        // 未授权工具 → denied；第二轮模型也不产出任何文本
        AtomicInteger round = new AtomicInteger();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiStreamStub.openStream(invocation);
                    if (round.getAndIncrement() == 0) {
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-1", "it_or_ghost", "{}")));
                    }
                    return new AiStreamOutcome("stop", null, false);
                });

        String body = sendAndCollect("{\"content\":\"必须调用工具才能回答\"}");

        assertEquals(ErrorCode.TOOL_DENIED, payloadOf(body, "error").get("code").asInt());
        JsonNode done = payloadOf(body, "done");
        assertEquals("tool_denied", done.get("finishReason").asText());
        assertEquals("failed", done.get("status").asText());
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1));
    }

    // ===================== 首字观测锚点（§5.4.2） =====================

    @Test
    @DisplayName("🔴 §5.4.2 锚点自检：首轮即工具调用时，首个可见帧是 tool（不是 delta），且在 5s 内到达")
    void firstVisibleFrameIsToolWhenFirstRoundCallsTool() throws Exception {
        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(TestTools.ECHO_KEY, "{}", "查完了");

        long began = System.currentTimeMillis();
        MvcResult result = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(),
                        host, UID, "{\"content\":\"首轮即工具调用\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // 🔴 锚点 = 第一个「用户可见帧」= 第一个 delta 或第一个 tool，**取先到者**。
        //    若只监听 delta，本场景会误判为"首字永不到达"（V1.1 的口径缺口，V1.1.2 已订正）。
        long firstVisibleAt = 0L;
        String firstVisibleEvent = null;
        for (int i = 0; i < 100 && firstVisibleEvent == null; i++) {
            List<String> events = eventNames(bodyOf(result));
            for (String event : events) {
                if ("delta".equals(event) || "tool".equals(event)) {
                    firstVisibleEvent = event;
                    firstVisibleAt = System.currentTimeMillis();
                    break;
                }
            }
            if (firstVisibleEvent == null) {
                Thread.sleep(20);
            }
        }
        String body = waitForDone(result);

        assertEquals("tool", firstVisibleEvent,
                "🔴 首轮即工具调用时首个可见帧必须是 tool 帧：" + eventNames(body));
        assertEquals("meta", eventNames(body).get(0), "🔴 meta 仍必须首发（但它不是可见帧）");
        long firstVisibleMillis = firstVisibleAt - began;
        assertTrue(firstVisibleMillis < 5_000L,
                "🔴 首字 P95 ≤5s 红线：首个可见帧耗时 " + firstVisibleMillis + "ms");
        // 确认等待与工具执行不计入首字：它们必然发生在首个可见帧之后
        assertTrue(toolStatuses(body).indexOf("running") > toolStatuses(body).indexOf("pending"));
    }

    @Test
    @DisplayName("AC-NFR-001 性能采样：无工具/工具清单无调用/首轮工具调用的首个可见帧 P95 均小于 5s")
    void firstVisibleFrameP95AcrossThreePaths() throws Exception {
        final int samples = 20;
        // 性能采样共 60 次请求；隔离覆盖限流阈值，finally/tearDown 由 SysConfigOverride 恢复。
        // 🔴 V1.4.5（ADR-020 ⑦）：小时窗业务规则已废除，原 message_per_hour 覆盖随之删除；
        //    日额度改用 daily_quota_limit 放宽（60 次采样必须不被日限额拦下）。
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");

        scriptTextOnly("无工具直接回答");
        long noToolsP95 = sampleFirstVisibleP95(samples, "无工具");

        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptTextOnly("有清单但本轮不调用");
        long catalogNoCallP95 = sampleFirstVisibleP95(samples, "工具清单但首轮无调用");

        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    AiChatRequest request = AiStreamStub.request(invocation);
                    AiStreamStub.openStream(invocation);
                    boolean hasToolResult = request.messages().stream()
                            .anyMatch(message -> "tool".equals(message.role()));
                    if (!hasToolResult) {
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-perf", functionName(TestTools.ECHO_KEY), "{}")));
                    }
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept("工具完成后的回答");
                    return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
                });
        long firstRoundToolP95 = sampleFirstVisibleP95(samples, "首轮即工具调用");

        System.out.printf("PERF first-visible-frame n=%d no-tools-p95=%dms catalog-no-call-p95=%dms"
                        + " first-round-tool-p95=%dms anchor=delta-or-tool%n",
                samples, noToolsP95, catalogNoCallP95, firstRoundToolP95);
        assertTrue(noToolsP95 < 5_000L, "无工具首字 P95 超限：" + noToolsP95 + "ms");
        assertTrue(catalogNoCallP95 < 5_000L, "工具清单无调用首字 P95 超限：" + catalogNoCallP95 + "ms");
        assertTrue(firstRoundToolP95 < 5_000L, "首轮工具调用首字 P95 超限：" + firstRoundToolP95 + "ms");
    }

    // ===================== 查询接口（§7.9.1） =====================

    @Test
    @DisplayName("§7.9.1：工具调用查询分页 + 状态过滤 + 字段禁含")
    void toolCallQuery() throws Exception {
        bindLocalTool(grantLocalTool(TestTools.ECHO_KEY, ToolRiskPolicy.RISK_LOW, 1));
        scriptToolThenText(TestTools.ECHO_KEY, "{\"token\":\"secret-value\"}", "好的");
        String body = sendAndCollect("{\"content\":\"查询用\"}");
        String conversationId = payloadOf(body, "meta").get("conversationId").asText();

        JsonNode data = getJson("/api/v1/conversations/" + conversationId + "/tool-calls", UID)
                .path("data");
        assertEquals(1, data.get("total").asInt());
        JsonNode item = data.path("list").get(0);
        assertTrue(item.get("toolCallId").isTextual(), "🔴 ID 对外必须是 string");
        assertEquals("succeeded", item.get("status").asText());
        assertEquals("local", item.get("toolType").asText());
        assertFalse(item.toString().contains("secret-value"),
                "🔴 查询接口禁含入参明文敏感值：" + item);
        for (String forbidden : List.of("endpoint", "credential", "content", "systemPrompt")) {
            assertFalse(item.has(forbidden), "🔴 禁含字段 " + forbidden);
        }

        // 状态过滤命中与不命中
        assertEquals(1, getJson("/api/v1/conversations/" + conversationId
                + "/tool-calls?status=succeeded", UID).path("data").get("list").size());
        assertEquals(0, getJson("/api/v1/conversations/" + conversationId
                + "/tool-calls?status=denied", UID).path("data").get("list").size());
        // 非法状态 → 10001
        assertEquals(ErrorCode.VALIDATION_FAILED, getJson("/api/v1/conversations/" + conversationId
                + "/tool-calls?status=bogus", UID).get("code").asInt());
        // 🔴 非本人 → 10004（不泄露存在性）
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, getJson("/api/v1/conversations/" + conversationId
                + "/tool-calls", OTHER_UID).get("code").asInt());
    }

    // ===================== 辅助 =====================

    private long sampleFirstVisibleP95(int samples, String scenario) throws Exception {
        List<Long> elapsedMillis = new ArrayList<>();
        for (int sample = 0; sample < samples; sample++) {
            long began = System.nanoTime();
            MvcResult result = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(),
                            host, UID,
                            "{\"content\":\"性能采样-" + scenario + "-" + sample + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();

            boolean visible = false;
            // 🔴 采样窗口必须覆盖到**契约红线本身**（首字 P95 ≤5s，§14.1 / §9.5.3）：
            //    原实现只等 250×2ms ≈ 0.5s，而共享库为**远端** MySQL/Redis，
            //    单次生成的实测 p95 本就落在 0.6~0.9s（见下方 PERF 输出）——
            //    于是"采样窗口先到"会把一次**完全合规**的生成误判为缺陷（环境敏感的假失败）。
            // 🔴 判据没有被放宽：红线仍由本方法返回值上的 `< 5_000ms` p95 断言把关，
            //    这里只是把"测量量程"从 0.5s 扩到 6s，让超红线的真实回归以 p95 失败暴露。
            for (int attempt = 0; attempt < 1200 && !visible; attempt++) {
                visible = eventNames(bodyOf(result)).stream()
                        .anyMatch(event -> "delta".equals(event) || "tool".equals(event));
                if (!visible) {
                    Thread.sleep(5);
                }
            }
            long elapsed = (System.nanoTime() - began) / 1_000_000L;
            assertTrue(visible, scenario + " 未在 6s 采样窗口内产生 delta/tool 可见帧"
                    + "（🔴 5s 红线由 p95 断言把关，本窗口只是量程）");
            elapsedMillis.add(elapsed);
            assertTrue(waitForDone(result).contains("event:done"), scenario + " 流必须以 done 收敛");
        }
        elapsedMillis.sort(Long::compareTo);
        int p95Index = Math.max(0, (int) Math.ceil(samples * 0.95D) - 1);
        return elapsedMillis.get(p95Index);
    }

    /**
     * 第一轮请求工具、第二轮出文本。
     *
     * @return 捕获到的上游请求（用于断言 tools 下发与回灌形态）
     */
    private List<AiChatRequest> scriptToolThenText(String toolKey, String argumentsJson,
                                                   String finalText) {
        List<AiChatRequest> captured = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    captured.add(AiStreamStub.request(invocation));
                    AiStreamStub.openStream(invocation);
                    if (round.getAndIncrement() == 0) {
                        return new AiStreamOutcome("tool_calls", null, false,
                                List.of(new AiToolCall("call-1", functionName(toolKey),
                                        argumentsJson)));
                    }
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept(finalText);
                    return new AiStreamOutcome("stop", new TokenUsage(10, 20, 30), false);
                });
        return captured;
    }

    private List<AiChatRequest> scriptTextOnly(String text) {
        List<AiChatRequest> captured = new ArrayList<>();
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    captured.add(AiStreamStub.request(invocation));
                    AiStreamStub.openStream(invocation);
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept(text);
                    return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
                });
        return captured;
    }

    /** 🔴 归一化必须与生产实现同源（api-spec §7.6.5），避免测试自己复制一份规则后与实现漂移。 */
    private String functionName(String toolKey) {
        return com.eyes.albedo.tool.ToolFunctionNames.normalize(toolKey);
    }

    private String sendAndCollect(String jsonBody) throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        MvcResult result = mockMvc.perform(
                        SseRequests.postJson(SseRequests.sendNewPath(), host, UID, jsonBody))
                .andExpect(status().isOk())
                .andReturn();
        return waitForDone(result);
    }

    private JsonNode getJson(String path, long uid) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, uid))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(bodyOf(result));
    }

    private String waitForDone(MvcResult result) throws Exception {
        for (int i = 0; i < 150; i++) {
            String body = bodyOf(result);
            if (body.contains("event:done")) {
                return body;
            }
            Thread.sleep(100);
        }
        return bodyOf(result);
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
                tenantId, "or-agent", "编排测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion(String toolPolicy) {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',60,?,'published')",
                tenantId, agentId, toolPolicy);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long grantLocalTool(String toolKey, String riskLevel, int idempotent) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_tools WHERE tool_key = ?", Integer.class, toolKey);
        if (exists == null || exists == 0) {
            jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                            + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                            + " VALUES (?,?,1,'',?,?,?,5,?)",
                    toolKey, toolKey, "{\"type\":\"object\"}", riskLevel, idempotent,
                    LocalTool.STATUS_ENABLED);
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

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        assertNotNull(values);
        return values.isEmpty() ? null : values.get(0);
    }
}
