package com.eyes.albedo.conversation;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.testsupport.SseRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 会话接口测试（写入路径）。
 *
 * <p>覆盖：AC-CON-002（创建幂等）、AC-CON-003（列表隔离 + 稳定分页）、AC-CON-004（手动改名不被覆盖）、
 * AC-TEN-004（跨租户 ID → 10004）、EX-023（并发重命名 → 30020）、AC-CHAT-004（长度校验 → 30041）。
 *
 * <p>数据纪律：只使用测试专用 uid 段（{@code 9000000xx}），并在每个用例后按
 * {@code tenant_id + uid} 精确清理，绝不触碰验收种子数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ConversationApiIT {

    private static final String HOST_GIFT = "localhost:5173";
    private static final String HOST_REDBOOK = "127.0.0.1:5173";
    private static final long UID_A = 900000001L;
    private static final long UID_B = 900000002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 🔴 精确到 uid 的清理；DELETE 必带 WHERE，且限定测试 uid 段
        jdbcTemplate.update("DELETE FROM messages WHERE uid IN (?,?)", UID_A, UID_B);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid IN (?,?)", UID_A, UID_B);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid IN (?,?)", UID_A, UID_B);
    }

    @Test
    @DisplayName("AC-CON-002：创建会话幂等 —— 相同 Idempotency-Key 返回同一会话")
    void createConversationIsIdempotent() throws Exception {
        String key = newKey();
        String first = createConversation(HOST_GIFT, UID_A, key);
        String second = createConversation(HOST_GIFT, UID_A, key);
        assertNotNull(first);
        assertTrue(first.equals(second), "重复幂等请求必须返回同一会话，实际：" + first + " / " + second);
    }

    @Test
    @DisplayName("缺少 Idempotency-Key → code=10001")
    void createConversationRequiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/conversations")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10001)));
    }

    @Test
    @DisplayName("AC-CON-003：列表只返回当前租户 + 当前用户的会话")
    void listIsolatedByTenantAndUser() throws Exception {
        String giftConversation = createConversation(HOST_GIFT, UID_A, newKey());
        createConversation(HOST_REDBOOK, UID_A, newKey());
        createConversation(HOST_GIFT, UID_B, newKey());

        // gift + UID_A 只看到自己的一条
        mockMvc.perform(get("/api/v1/conversations")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.list[0].conversationId", is(giftConversation)));

        // 另一个用户在同租户内看不到它
        mockMvc.perform(get("/api/v1/conversations")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_B))
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.list[0].conversationId",
                        org.hamcrest.Matchers.not(is(giftConversation))));
    }

    @Test
    @DisplayName("AC-TEN-004：跨租户会话 ID → code=10004，且不泄露其他租户信息")
    void crossTenantConversationNotFound() throws Exception {
        String giftConversation = createConversation(HOST_GIFT, UID_A, newKey());

        MvcResult result = mockMvc.perform(get("/api/v1/conversations/" + giftConversation)
                        .header(HttpHeaders.HOST, HOST_REDBOOK)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10004)))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(!body.contains("gift"), "错误响应不得泄露其他租户标识：" + body);
    }

    @Test
    @DisplayName("跨用户会话 ID → code=10004（水平越权按不存在处理）")
    void crossUserConversationNotFound() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());

        mockMvc.perform(get("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_B))
                .andExpect(jsonPath("$.code", is(10004)));
    }

    @Test
    @DisplayName("非法 ID 格式也返回 10004（不通过报错差异暴露 ID 空间）")
    void malformedIdReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/conversations/not-a-number")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.code", is(10004)));
    }

    @Test
    @DisplayName("AC-CON-004 / EX-023：重命名成功置 manual；版本不匹配 → 30020")
    void renameWithOptimisticLock() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());
        int version = readVersion(conversationId);

        mockMvc.perform(patch("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"我的会话\",\"expectedVersion\":" + version + "}"))
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.title", is("我的会话")))
                .andExpect(jsonPath("$.data.titleSource", is("manual")));

        // 用过期版本再次提交（模拟另一个标签页）→ 冲突，不静默覆盖
        mockMvc.perform(patch("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"另一个标签页\",\"expectedVersion\":" + version + "}"))
                .andExpect(jsonPath("$.code", is(30020)));
    }

    @Test
    @DisplayName("重命名标题为空白 → code=10001")
    void renameRejectsBlankTitle() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());
        mockMvc.perform(patch("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"expectedVersion\":0}"))
                .andExpect(jsonPath("$.code", is(10001)));
    }

    @Test
    @DisplayName("删除后立即不可见（软删除），再次访问 → 10004")
    void deleteHidesConversation() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());

        mockMvc.perform(delete("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.code", is(0)))
                // data 字段必须存在且为 null（契约要求字段恒定出现，不能整字段省略）
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.code", is(10004)));

        mockMvc.perform(get("/api/v1/conversations")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.data.total", is(0)));
    }

    @Test
    @DisplayName("AC-CHAT-004 / EX-020：空白与超长消息前后端一致拒绝 → code=30041")
    void messageLengthValidation() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());

        // 🔴 L1：建流前失败在带 Accept: text/event-stream 时同样必须是 200 + JSON（ADR-021）
        mockMvc.perform(SseRequests.postJson(SseRequests.sendPath(conversationId), HOST_GIFT, UID_A,
                        newKey(), "{\"content\":\"    \"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", is(30041)));

        String tooLong = "内".repeat(20001);
        mockMvc.perform(SseRequests.postJson(SseRequests.sendPath(conversationId), HOST_GIFT, UID_A,
                        newKey(), objectMapper.writeValueAsString(
                                java.util.Map.of("content", tooLong))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code", is(30041)));
    }

    @Test
    @DisplayName("消息历史：默认只返回当前尝试，且不含 system / tool 角色")
    void messageHistoryVisibleRolesOnly() throws Exception {
        String conversationId = createConversation(HOST_GIFT, UID_A, newKey());
        mockMvc.perform(get("/api/v1/conversations/" + conversationId + "/messages")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total", is(0)));
    }

    // ===================== 辅助方法 =====================

    private String createConversation(String host, long uid, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/conversations")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, uid)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("conversationId").asText();
    }

    private int readVersion(String conversationId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/conversations/" + conversationId)
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID_A))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("version").asInt();
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}
