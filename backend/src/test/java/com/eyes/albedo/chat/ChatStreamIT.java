package com.eyes.albedo.chat;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiStreamException;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 流式对话接口测试（SSE 事件序列 + 停止 + 幂等 + 重新生成）。
 *
 * <p>上游模型用 {@link MockBean} 替换：SSE 契约测试要断言<b>事件顺序与负载结构</b>，
 * 依赖真实模型会引入不确定的时延与内容，无法稳定断言（真实上游连通性由手工验证与
 * {@code AiChatClientRealIT} 覆盖）。
 *
 * <p>覆盖：api-spec §5 事件序列（meta → delta* → done）、AC-CHAT-001（内容最终一致）、
 * AC-CHAT-002（停止 → stopped）、AC-CHAT-003（重新生成保留旧尝试且不重复用户消息）、
 * AC-CON-002（首发原子建会话）、EX-013（幂等不重复创建）、EX-015（失败保留已生成内容）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ChatStreamIT {

    private static final String HOST_GIFT = "localhost:5173";
    private static final long UID = 900000011L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private AiChatClient aiChatClient;

    @Autowired
    private ConfigService configService;

    private SysConfigOverride override;

    /**
     * 🔴 V1.4.5（ADR-020）：本类<b>单个测试方法</b>会连续发送多条消息，而平台默认已收紧为
     * QPM=3 / 日额度=50。本类的验收对象是 <b>SSE 契约</b>（事件序列 / 幂等 / 停止 / 重新生成），
     * 不是限流 —— 因此临时放宽两项阈值并在 {@code @AfterEach} <b>还原</b>
     * （🔴 {@code SysConfigOverride} 是共享库上唯一被允许的覆盖方式，api-spec §7.13 G12）。
     *
     * <p>🔴 这不是"为求绿放宽断言"：QPM / 日额度的边界判据由
     * {@code MessageRateLimiterIT} 与 {@code QuotaAdmissionIT} 以**精确阈值**独立覆盖。
     */
    @BeforeEach
    void relaxQuotaForSseContractTests() {
        override = new SysConfigOverride(jdbcTemplate, configService);
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");
    }

    @AfterEach
    void cleanUp() {
        override.restore();
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM messages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
    }

    @Test
    @DisplayName("AC-CON-002 + api-spec §5：conversationId=new 原子建会话，事件序列 meta → delta* → done")
    void sendToNewConversationProducesFullEventSequence() throws Exception {
        stubUpstream("你好", "，我是助手");

        String body = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"帮我挑一份生日礼物\"}", newKey());

        List<String> events = eventNames(body);
        assertEquals(List.of("meta", "delta", "delta", "done"), events,
                "SSE 事件顺序必须是 meta → delta* → done，实际：" + events + "\n原始：" + body);

        JsonNode meta = payloadOf(body, "meta");
        assertTrue(meta.hasNonNull("conversationId"), "meta 必须带真实 conversationId");
        assertTrue(meta.hasNonNull("messageId"), "meta 必须带 assistant messageId");
        assertTrue(meta.hasNonNull("userMessageId"), "meta 必须带 userMessageId");
        assertTrue(meta.get("conversationId").isTextual(), "ID 必须序列化为 string（ADR-004）");
        assertBoundToPublishedAgentVersion(meta);

        JsonNode done = payloadOf(body, "done");
        assertEquals("completed", done.get("status").asText());
        assertEquals("stop", done.get("finishReason").asText());
        assertTrue(done.has("title"), "done 必须包含 title 字段（首轮生成标题）");
        assertEquals("帮我挑一份生日礼物", done.get("title").asText(), "标题应取首条用户消息");

        // 落库内容与前端拼接结果一致（AC-CHAT-001）
        String conversationId = meta.get("conversationId").asText();
        MvcResult history = mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(jsonPath("$.code", is(0)))
                .andReturn();
        JsonNode list = objectMapper.readTree(bodyOf(history))
                .path("data").path("list");
        assertEquals(2, list.size(), "应有一条 user 与一条 assistant 消息");
        assertEquals("user", list.get(0).get("role").asText());
        assertEquals("sent", list.get(0).get("status").asText());
        assertEquals("assistant", list.get(1).get("role").asText());
        assertEquals("你好，我是助手", list.get(1).get("content").asText());
        assertEquals("completed", list.get(1).get("status").asText());
    }

    @Test
    @DisplayName("EX-013：相同 Idempotency-Key 重复发送不创建新消息，回放原结果")
    void sendIsIdempotent() throws Exception {
        stubUpstream("回答内容");
        String key = newKey();

        String first = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"第一次发送\"}", key);
        String conversationId = payloadOf(first, "meta").get("conversationId").asText();

        // 同一幂等键重放
        String second = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"第一次发送\"}", key);
        String replayConversationId = payloadOf(second, "meta").get("conversationId").asText();
        assertEquals(conversationId, replayConversationId, "幂等回放必须指向原会话");

        Integer userMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE uid = ? AND role = 'user'", Integer.class, UID);
        assertEquals(1, userMessages, "🔴 幂等重放绝不能重复创建用户消息");
    }

    @Test
    @DisplayName("AC-CHAT-002：停止生成 → 已生成内容保存为 stopped，done.finishReason=stopped")
    void stopGeneration() throws Exception {
        // 模拟上游持续吐字，直到检测到取消标记
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    BooleanSupplier cancelCheck = AiStreamStub.cancelCheck(invocation);
                    AiStreamStub.openStream(invocation);
                    onDelta.accept("已经生成的部分");
                    // 等待停止请求写入取消标记（最多 3 秒）
                    for (int i = 0; i < 30 && !cancelCheck.getAsBoolean(); i++) {
                        Thread.sleep(100);
                    }
                    return new AiStreamOutcome("", null, cancelCheck.getAsBoolean());
                });

        MvcResult streaming = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(),
                        HOST_GIFT, UID, "{\"content\":\"写一段很长的内容\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // 先拿到 meta 中的 messageId，再调用停止接口
        String messageId = waitForMeta(streaming);
        mockMvc.perform(post("/api/v1/messages/" + messageId + "/stop")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.status", is("stopped")))
                .andExpect(jsonPath("$.data.messageId", is(messageId)));

        String body = waitForDone(streaming);
        JsonNode done = payloadOf(body, "done");
        assertEquals("stopped", done.get("status").asText());
        assertEquals("stopped", done.get("finishReason").asText());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE id = ?", String.class, Long.parseLong(messageId));
        assertEquals("stopped", status, "已生成内容必须保存为 stopped");
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM messages WHERE id = ?", String.class, Long.parseLong(messageId));
        assertTrue(content != null && content.contains("已经生成的部分"), "停止前已生成内容必须保留");
    }

    @Test
    @DisplayName("停止接口幂等：对已终态消息重复调用不报错")
    void stopIsIdempotent() throws Exception {
        stubUpstream("完整回答");
        String body = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"你好\"}", newKey());
        String messageId = payloadOf(body, "meta").get("messageId").asText();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/messages/" + messageId + "/stop")
                            .header(HttpHeaders.HOST, HOST_GIFT)
                            .header(TestAuthConfig.HEADER_TEST_UID, UID))
                    .andExpect(jsonPath("$.code", is(0)))
                    .andExpect(jsonPath("$.data.status", is("completed")));
        }
    }

    @Test
    @DisplayName("AC-CHAT-003：重新生成新建尝试，旧尝试保留且不重复用户消息")
    void regenerateKeepsOldAttempt() throws Exception {
        stubUpstream("第一次回答");
        String body = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"介绍一下你自己\"}", newKey());
        String firstAssistantId = payloadOf(body, "meta").get("messageId").asText();

        stubUpstream("第二次回答");
        String regenerated = streamAndCollect(SseRequests.regeneratePath(firstAssistantId),
                null, newKey());
        JsonNode meta = payloadOf(regenerated, "meta");
        String secondAssistantId = meta.get("messageId").asText();
        assertTrue(!firstAssistantId.equals(secondAssistantId), "重新生成必须产生新的尝试 ID");

        Integer userMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE uid = ? AND role = 'user'", Integer.class, UID);
        assertEquals(1, userMessages, "🔴 重新生成不得重复保存用户消息");

        Integer oldIsCurrent = jdbcTemplate.queryForObject(
                "SELECT is_current FROM messages WHERE id = ?", Integer.class,
                Long.parseLong(firstAssistantId));
        assertEquals(0, oldIsCurrent, "旧尝试必须置 isCurrent=0 并保留");
        Integer attemptNo = jdbcTemplate.queryForObject(
                "SELECT attempt_no FROM messages WHERE id = ?", Integer.class,
                Long.parseLong(secondAssistantId));
        assertEquals(2, attemptNo, "新尝试的 attemptNo 必须递增");

        // 默认只返回当前尝试；includeSuperseded=true 才返回历史尝试
        MvcResult current = mockMvc.perform(get("/api/v1/conversations/"
                        + meta.get("conversationId").asText() + "/messages")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andReturn();
        assertEquals(2, objectMapper.readTree(bodyOf(current))
                .path("data").path("total").asInt());

        MvcResult withHistory = mockMvc.perform(get("/api/v1/conversations/"
                        + meta.get("conversationId").asText() + "/messages")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID)
                        .param("includeSuperseded", "true"))
                .andReturn();
        assertEquals(3, objectMapper.readTree(bodyOf(withHistory))
                .path("data").path("total").asInt());
    }

    @Test
    @DisplayName("EX-015 / AC-CHAT-006：上游故障 → error(50002) + done(failed)，已接收内容保留")
    void upstreamFailureEmitsErrorThenDone() throws Exception {
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    onDelta.accept("部分内容");
                    throw AiStreamException.upstream("模型连接中断，已保留已生成内容");
                });

        String body = streamAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"测试故障\"}", newKey());

        List<String> events = eventNames(body);
        assertEquals(List.of("meta", "delta", "error", "done"), events,
                "故障时必须先 error 再 done，实际：" + events);
        JsonNode error = payloadOf(body, "error");
        assertTrue(error.get("code").isNumber(), "🔴 error.code 必须是数字业务码");
        assertEquals(50002, error.get("code").asInt());

        JsonNode done = payloadOf(body, "done");
        assertEquals("failed", done.get("status").asText());

        String messageId = payloadOf(body, "meta").get("messageId").asText();
        String content = jdbcTemplate.queryForObject("SELECT content FROM messages WHERE id = ?",
                String.class, Long.parseLong(messageId));
        assertEquals("部分内容", content, "失败也必须保留已接收内容");
    }

    @Test
    @DisplayName("🔴 L1：缺少 Idempotency-Key → 建流之前返回 JSON（HTTP 200 + code=10001），"
            + "且请求恒带 Accept: text/event-stream")
    void missingIdempotencyKeyReturnsJson() throws Exception {
        // 🔴 idempotencyKey=null → 刻意不带该头；Accept 由 SseRequests 强制注入（ADR-021 / L1）
        mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), HOST_GIFT, UID, null,
                        "{\"content\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", is(10001)));
    }

    // ===================== 辅助方法 =====================

    /**
     * 断言 {@code meta.agentVersion} 绑定的是该 Agent<b>当前的已发布版本</b>（RISK-005 / AC-AGT-002）。
     *
     * <p>🔴 这里<b>不能</b>写 {@code assertEquals(1, ...)}：本 IT 跑在共享库上，
     * {@code agents.current_version} 是会随运营发布而前进的<b>真实数据</b>（本轮实测已是 2）。
     * 把种子数据的当次取值硬编码进断言，只能证明"库还没被发布过"，
     * 而契约要断言的是"绑定 = 当前发布指针"。因此改为与 {@code agents.current_version}
     * 逐值比对 —— 既不放宽（仍然精确到具体版本号），又不会被无关的发布动作误伤。
     */
    private void assertBoundToPublishedAgentVersion(JsonNode meta) {
        long conversationId = Long.parseLong(meta.get("conversationId").asText());
        Long boundAgentVersion = jdbcTemplate.queryForObject(
                "SELECT agent_version FROM conversations WHERE id = ?", Long.class, conversationId);
        Long publishedVersion = jdbcTemplate.queryForObject(
                "SELECT a.current_version FROM agents a"
                        + " JOIN conversations c ON c.agent_id = a.id WHERE c.id = ?",
                Long.class, conversationId);

        assertTrue(publishedVersion != null && publishedVersion > 0,
                "前置条件：该 Agent 必须已有发布版本，实际 current_version=" + publishedVersion);
        assertEquals(publishedVersion.longValue(), meta.get("agentVersion").asLong(),
                "🔴 meta.agentVersion 必须等于 Agent 当前已发布版本");
        assertEquals(publishedVersion, boundAgentVersion,
                "🔴 落库的 conversations.agent_version 必须与下发的 meta.agentVersion 同源");
    }

    private void stubUpstream(String... deltas) {
        AiStreamStub.whenStream(aiChatClient)
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
                    AiStreamStub.openStream(invocation);
                    for (String delta : deltas) {
                        onDelta.accept(delta);
                    }
                    return new AiStreamOutcome("stop", new TokenUsage(10, 20, 30), false);
                });
    }

    private String streamAndCollect(String path, String jsonBody, String idempotencyKey) throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        MvcResult result = mockMvc.perform(
                        SseRequests.postJson(path, HOST_GIFT, UID, idempotencyKey, jsonBody))
                .andExpect(status().isOk())
                .andReturn();
        return waitForDone(result);
    }

    /**
     * 以 UTF-8 读取响应体。
     *
     * <p>⚠️ 不能用 {@code getContentAsString()}：{@code application/json} 按 RFC 8259 恒为 UTF-8，
     * Spring Boot 因此不再在 Content-Type 上附 charset，而 {@code MockHttpServletResponse}
     * 在缺少 charset 时会退回 ISO-8859-1，导致中文断言出现乱码（纯测试侧问题，
     * 真实 HTTP 客户端与浏览器不受影响）。
     */
    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * 等待流写完（SSE 通过 MockHttpServletResponse 累积输出）。
     */
    private String waitForDone(MvcResult result) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = bodyOf(result);
            if (body.contains("event:done")) {
                return body;
            }
            Thread.sleep(100);
        }
        return bodyOf(result);
    }

    private String waitForMeta(MvcResult result) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = bodyOf(result);
            if (body.contains("event:meta")) {
                return payloadOf(body, "meta").get("messageId").asText();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未收到 meta 事件：" + bodyOf(result));
    }

    /**
     * 解析 SSE 文本中的事件名序列。
     */
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

    /**
     * 取指定事件的 data 负载。
     */
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

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}
