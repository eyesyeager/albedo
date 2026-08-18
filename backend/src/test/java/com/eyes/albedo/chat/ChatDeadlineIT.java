package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiChatRequest;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.ai.AiToolCall;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.config.StartupChecker;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.eyes.albedo.tool.LocalToolHandler;
import com.eyes.albedo.tool.ToolFunctionNames;
import com.eyes.albedo.tool.ToolRiskPolicy;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.entity.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 🔴 <b>单次生成的统一超时预算</b>端到端验收（ADR-017 / api-spec §8.3 J 组，V1.4.2 新增）。
 *
 * <p><b>被验收的根因（test-report V4.0 BUG-MCP-002 实测）</b>：{@code SseEmitter} 曾用 Agent 的
 * <b>单轮</b>模型超时（{@code requestTimeoutSeconds}）当整条流的连接寿命 →
 * 连接在第一次确认等待期间被传输层掐断 → 生成线程之后写的 {@code done} 被静默丢弃 →
 * 客户端永久 loading，后端只留一条未处理的 {@code AsyncRequestTimeoutException}。
 *
 * <p>覆盖（逐条对应 api-spec §8.3 的 J1~J4）：
 * <ul>
 *   <li><b>J1</b> 预算耗尽 → {@code error(50002)} + {@code done(timeout, failed)}、消息落库
 *       {@code failed}、{@code tool_calls} 无非终态残留</li>
 *   <li><b>J2</b> 🔴 <b>反向断言</b>：常规路径日志中<b>不出现</b>
 *       {@code AsyncRequestTimeoutException}（本轮 FAIL 项的锚点）</li>
 *   <li><b>J4</b> 确认倒计时不骗人：{@code confirmExpiresInSeconds} 被预算收紧且
 *       {@code < tool.confirm_wait_seconds}</li>
 *   <li>🔴 StartupChecker 的两条<b>拒绝启动</b>不等式各自生效</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestAuthConfig.class, ChatDeadlineIT.TestTools.class})
class ChatDeadlineIT {

    /** 测试专用高风险实现体（生产内置两个工具都是 low，高风险生产路径由 MCP 承载，ADR-015 ⑤）。 */
    @TestConfiguration
    static class TestTools {

        static final String HIGH_KEY = "it_dl_high";

        @Bean
        LocalToolHandler itDeadlineHighRiskHandler() {
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
    }

    private static final long UID = 900000061L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private StartupChecker startupChecker;

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
        tenantId = "dl" + suffix;
        host = "dl-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "预算测试租户", host);
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
    void tearDown() {
        override.restore();
        // 🔴 V1.4.5：额度账本行按临时租户清理，避免在共享库里留下孤儿数据
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tool_calls WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE 'it_dl_%'");
    }

    // ===================== J1：预算耗尽的收敛形态 =====================

    @Test
    @DisplayName("🔴🔴 J1：生成预算耗尽 → error(50002) + done(timeout, failed)，内容落库 failed，"
            + "tool_calls 无非终态残留（🔴 零新错误码）")
    void deadlineExhaustedConvergesWithTimeout() throws Exception {
        // 🔴 预算被压到远小于宽限：第一轮"进入模型之前"即判耗尽（§9.5.4 不变量 4 ④）
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, "1");
        scriptTextOnly("这段回答不应该出现");

        String body = sendAndCollect();
        List<String> events = eventNames(body);

        JsonNode error = payloadOf(body, "error");
        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, error.get("code").asInt(),
                "🔴 复用 50002：对用户就是\"本次生成没能在时限内完成、可重试\"（ADR-017 ④）");
        assertEquals(3, error.size(), "🔴 error 帧恒 3 键（§5.2 G-3 不受本变更影响）");
        JsonNode done = payloadOf(body, "done");
        assertEquals(Message.FINISH_TIMEOUT, done.get("finishReason").asText());
        assertEquals(Message.STATUS_FAILED, done.get("status").asText());
        assertEquals("done", events.get(events.size() - 1), "🔴 done 必发且在最后");

        String messageId = payloadOf(body, "meta").get("messageId").asText();
        assertEquals(Message.STATUS_FAILED,
                queryString("SELECT status FROM messages WHERE id = ?", Long.parseLong(messageId)));
        assertEquals(Message.FINISH_TIMEOUT, queryString("SELECT finish_reason FROM messages"
                + " WHERE id = ?", Long.parseLong(messageId)));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM tool_calls WHERE tenant_id = ?"
                        + " AND status NOT IN ('succeeded','failed','timed_out','cancelled','denied')",
                tenantId), "🔴 §9.5.4 不变量 3：不留非终态 tool_calls");
    }

    @Test
    @DisplayName("🔴 预算耗尽但已生成内容必须落库保留（EX-015：不能因超时把用户已看到的字丢掉）")
    void partialContentIsPersistedOnDeadline() throws Exception {
        // 🔴 预算刚够跑完首轮模型，但不足以再发起一次工具执行（工具 timeout_seconds=5）：
        //    命中"工具执行准入"分支（§9.5.4 不变量 4 ③），此前已下发的正文必须落库
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, "20");
        bindLocalTool(grantLocalTool(TestTools.HIGH_KEY, ToolRiskPolicy.RISK_HIGH));
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            AiStreamStub.openStream(invocation);
            Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
            onDelta.accept("我先查一下：");
            return new AiStreamOutcome("tool_calls", null, false,
                    List.of(new AiToolCall("call-dl-1",
                            ToolFunctionNames.normalize(TestTools.HIGH_KEY), "{}")));
        });

        String body = sendAndCollect();

        assertEquals(ErrorCode.UPSTREAM_UNAVAILABLE, payloadOf(body, "error").get("code").asInt());
        assertEquals(Message.FINISH_TIMEOUT, payloadOf(body, "done").get("finishReason").asText());
        String messageId = payloadOf(body, "meta").get("messageId").asText();
        assertEquals("我先查一下：", queryString("SELECT content FROM messages WHERE id = ?",
                        Long.parseLong(messageId)),
                "🔴 已生成内容必须保留（EX-015）");
        assertFalse(toolStatuses(body).contains(ToolCall.STATUS_RUNNING),
                "🔴 工具执行准入不通过 → 绝不发起执行：" + toolStatuses(body));
    }

    // ===================== J4：确认倒计时不得骗人 =====================

    @Test
    @DisplayName("🔴🔴 J4：确认等待被预算收紧 → awaiting_confirmation 帧的 "
            + "confirmExpiresInSeconds < tool.confirm_wait_seconds 且 ≥1")
    void confirmCountdownIsTightenedByBudget() throws Exception {
        override.set(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS, "120");
        // 剩余 ≈ 45 − 15(宽限) ≈ 30s ⇒ 必须显著小于 120s
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, "45");
        bindLocalTool(grantLocalTool(TestTools.HIGH_KEY, ToolRiskPolicy.RISK_HIGH));
        scriptToolThenText(TestTools.HIGH_KEY, "确认后的回答");

        MvcResult stream = startStream();
        String messageId = metaMessageId(stream);
        JsonNode awaiting = waitForAwaitingConfirmationFrame(stream);

        int countdown = awaiting.get("confirmExpiresInSeconds").asInt();
        assertTrue(countdown >= 1, "🔴 服务端保证 ≥1：" + countdown);
        assertTrue(countdown < 120,
                "🔴 必须是**本次实际**等待上限；仍显示 120s 就是骗人（§7.8.1 ④ / ADR-017 ③ⓑ）："
                        + countdown);
        // 收尾：允许执行，让流正常收敛（避免占着线程等满倒计时）
        confirm(messageId, awaiting.get("toolCallId").asText());
        String body = waitForDone(stream);
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1));
        for (JsonNode frame : toolFrames(body)) {
            if (!ToolCall.STATUS_AWAITING_CONFIRMATION.equals(frame.get("status").asText())) {
                assertTrue(frame.get("confirmExpiresInSeconds").isNull(),
                        "🔴 非 awaiting_confirmation 帧该字段恒 null：" + frame);
            }
        }
    }

    // ===================== J2：反向断言 =====================

    @Test
    @DisplayName("🔴🔴 J2 反向断言：常规路径（成功 + 预算耗尽）日志中**不出现** "
            + "AsyncRequestTimeoutException（业务必须先于传输层收敛）")
    void normalPathNeverLogsAsyncRequestTimeout() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);
        try {
            scriptTextOnly("一切正常");
            assertEquals("completed", payloadOf(sendAndCollect(), "done").get("status").asText());

            override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, "1");
            assertEquals(Message.FINISH_TIMEOUT,
                    payloadOf(sendAndCollect(), "done").get("finishReason").asText());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> offenders = appender.list.stream()
                .map(event -> event.getFormattedMessage() + " "
                        + (event.getThrowableProxy() == null ? ""
                        : event.getThrowableProxy().getClassName()))
                .filter(line -> line.contains("AsyncRequestTimeoutException"))
                .toList();
        assertTrue(offenders.isEmpty(),
                "🔴 出现即说明预算不等式被打破或存在未按 remaining 收敛的阻塞点（AR-022）：" + offenders);
    }

    // ===================== StartupChecker 的两条拒绝启动不等式 =====================

    @Test
    @DisplayName("🔴 不等式①：generation_deadline + grace > spring.mvc.async.request-timeout "
            + "→ **拒绝启动**（否则 done 帧物理上写不出去）")
    void startupFailsWhenBudgetExceedsTransportTimeout() {
        // 测试 profile 的传输层兜底为 600s（application.yml），故 700 + 15 必然违规
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, "700");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> startupChecker.run(null));

        assertTrue(error.getMessage().contains(ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS),
                "启动失败原因必须点名具体配置键：" + error.getMessage());
        assertTrue(error.getMessage().contains("request-timeout"), error.getMessage());
    }

    @Test
    @DisplayName("🔴 不等式②：deadline_grace_seconds < 5 → **拒绝启动**（来不及落库终态 + 写 error/done）")
    void startupFailsWhenGraceTooSmall() {
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS, "1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> startupChecker.run(null));

        assertTrue(error.getMessage().contains(ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS),
                error.getMessage());
    }

    @Test
    @DisplayName("默认取值（300 + 15 ≤ 600）→ 启动自检通过（守护『改坏默认值』）")
    void startupPassesWithContractDefaults() {
        startupChecker.run(null);
    }

    // ===================== 辅助 =====================

    private void scriptTextOnly(String text) {
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            AiStreamStub.openStream(invocation);
            AiStreamStub.onDelta(invocation).accept(text);
            return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
        });
    }

    private void scriptToolThenText(String toolKey, String finalText) {
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            AiChatRequest request = AiStreamStub.request(invocation);
            AiStreamStub.openStream(invocation);
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(message -> "tool".equals(message.role()));
            if (!hasToolResult) {
                return new AiStreamOutcome("tool_calls", null, false,
                        List.of(new AiToolCall("call-dl-2", ToolFunctionNames.normalize(toolKey),
                                "{}")));
            }
            AiStreamStub.onDelta(invocation).accept(finalText);
            return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
        });
    }

    private String sendAndCollect() throws Exception {
        return waitForDone(startStream());
    }

    private MvcResult startStream() throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        return mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), host, UID,
                        "{\"content\":\"帮我联网搜索最近的新闻\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void confirm(String messageId, String toolCallId) throws Exception {
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/tool-calls/" + toolCallId
                        + "/confirm")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"allow\"}"))
                .andExpect(status().isOk());
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

    private JsonNode waitForAwaitingConfirmationFrame(MvcResult result) throws Exception {
        for (int i = 0; i < 200; i++) {
            for (JsonNode frame : toolFrames(bodyOf(result))) {
                if (ToolCall.STATUS_AWAITING_CONFIRMATION.equals(frame.get("status").asText())) {
                    return frame;
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
                tenantId, "dl-agent", "预算测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion() {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',60,?,'published')",
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

    private int queryInt(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private String queryString(String sql, Object... args) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }
}
