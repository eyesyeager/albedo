package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.eyes.albedo.chat.dto.MessageDTO;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.sse.SseEvents;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 历史消息的工具调用回显单测（api-spec §4.5.6 {@code message.toolCalls}）。
 *
 * <p>🔴 锁定的核心不变量：
 * <ul>
 *   <li><b>形状与 SSE {@code tool} 帧完全一致</b> —— 前端用同一个归一函数与同一套组件
 *       渲染实时流与历史回显；形状分叉会造成"只在刷新后复现"的渲染差异</li>
 *   <li><b>兼容字段 {@code summary} 必须有值</b>（§5.4.1 第 2 条）：
 *       非终态取 {@code argsSummary}、终态取 {@code resultSummary}，与 SSE 同一份口径</li>
 *   <li><b>整页只查一次 {@code tool_calls}</b> —— 逐条查即 N+1（§14.2 性能门禁）</li>
 *   <li><b>工具调用查询失败不得连带整页历史失败</b>：正文比可追溯信息更重要</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageMapperToolCallsTest {

    @Mock
    private ToolCallRepository toolCallRepository;

    private MessageMapper mapper;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mapper = new MessageMapper(objectMapper, toolCallRepository);
    }

    // ===================== 构造工具 =====================

    private Message message(long id, String role) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent("正文");
        message.setStatus(Message.STATUS_COMPLETED);
        message.setAttemptNo(1);
        message.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z"));
        return message;
    }

    private ToolCall toolCall(long id, long messageId, String toolKey, String status) {
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setMessageId(messageId);
        call.setToolType(ToolCall.TOOL_TYPE_LOCAL);
        call.setToolKey(toolKey);
        call.setStatus(status);
        call.setRound(1);
        call.setArgsSummary("timezone=Asia/Shanghai");
        call.setResultSummary("iso8601=2026-08-16T08:00:00Z");
        call.setTruncated(0);
        call.setCreatedAt(Instant.parse("2026-08-16T00:00:01Z"));
        return call;
    }

    private List<SseEvents.Tool> toolsOf(MessageDTO dto) {
        return dto.toolCalls().stream().map(SseEvents.Tool.class::cast).toList();
    }

    // ===================== 回显 =====================

    @Test
    @DisplayName("assistant 历史消息回显工具调用，形状即 SSE tool 帧")
    void assistantHistoryCarriesToolCalls() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_SUCCEEDED)));

        MessageDTO dto = mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT));

        List<SseEvents.Tool> calls = toolsOf(dto);
        assertEquals(1, calls.size());
        SseEvents.Tool call = calls.get(0);
        assertEquals("9001", call.toolCallId(), "🔴 ID 必须字符串化（ADR-004）");
        assertEquals("datetime_now", call.toolKey());
        assertEquals("local", call.toolType());
        assertEquals(ToolCall.STATUS_SUCCEEDED, call.status());
        assertEquals(1, call.round());
    }

    @Test
    @DisplayName("🔴 兼容字段 summary：终态取结果摘要（与 SSE 同一口径，否则历史里摘要为空白）")
    void summaryUsesResultSummaryOnTerminal() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_SUCCEEDED)));

        SseEvents.Tool call = toolsOf(mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT))).get(0);

        assertEquals("iso8601=2026-08-16T08:00:00Z", call.summary());
    }

    @Test
    @DisplayName("🔴 兼容字段 summary：非终态取入参摘要")
    void summaryUsesArgsSummaryBeforeResult() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_RUNNING)));

        SseEvents.Tool call = toolsOf(mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT))).get(0);

        assertEquals("timezone=Asia/Shanghai", call.summary());
    }

    @Test
    @DisplayName("🔴 11 个字段一个不能少（含允许为 null 的 errorCode / retryAfterSeconds）")
    void allContractFieldsArePresentInJson() throws Exception {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_SUCCEEDED)));

        MessageDTO dto = mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT));
        var node = objectMapper.valueToTree(dto.toolCalls().get(0));

        for (String field : List.of("toolCallId", "toolType", "toolKey", "status",
                "round", "summary", "argsSummary", "resultSummary", "truncated", "errorCode",
                "retryAfterSeconds")) {
            assertTrue(node.has(field), "缺字段：" + field);
        }
        assertTrue(node.get("errorCode").isNull(), "errorCode 允许为 null，但必须存在");
        assertTrue(node.get("retryAfterSeconds").isNull(),
                "🔴 retryAfterSeconds 是瞬时限流信息，历史里必须为 null（不复活过期倒计时）");
    }

    @Test
    @DisplayName("多个工具调用保持 created_at ASC 顺序（与实时到达顺序一致）")
    void orderIsPreserved() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_SUCCEEDED),
                        toolCall(9002L, 5002L, "calculator", ToolCall.STATUS_SUCCEEDED)));

        List<SseEvents.Tool> calls = toolsOf(mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT)));

        assertEquals(List.of("datetime_now", "calculator"),
                calls.stream().map(SseEvents.Tool::toolKey).toList());
    }

    // ===================== 裁剪与批量 =====================

    @Test
    @DisplayName("user 消息不返回 toolCalls（字段裁剪）")
    void userMessageHasNoToolCalls() {
        assertNull(mapper.toDTO(message(5001L, Message.ROLE_USER)).toolCalls());
    }

    @Test
    @DisplayName("🔴 整页消息只查一次 tool_calls，且 IN 列表只含 assistant 消息 ID")
    void pageIsLoadedInSingleBatchQuery() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of(
                        toolCall(9001L, 5002L, "datetime_now", ToolCall.STATUS_SUCCEEDED),
                        toolCall(9002L, 5004L, "calculator", ToolCall.STATUS_SUCCEEDED)));

        List<MessageDTO> list = mapper.toDTOs(List.of(
                message(5001L, Message.ROLE_USER),
                message(5002L, Message.ROLE_ASSISTANT),
                message(5003L, Message.ROLE_USER),
                message(5004L, Message.ROLE_ASSISTANT)));

        verify(toolCallRepository, times(1))
                .findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection());

        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(toolCallRepository).findByMessageIdInOrderByCreatedAtAscIdAsc(captor.capture());
        assertEquals(List.of(5002L, 5004L), List.copyOf(captor.getValue()),
                "🔴 user 消息不可能有工具调用，不得进 IN 列表");

        // 分组正确：各归其位，不串message
        assertEquals("datetime_now", toolsOf(list.get(1)).get(0).toolKey());
        assertEquals("calculator", toolsOf(list.get(3)).get(0).toolKey());
    }

    @Test
    @DisplayName("整页均为 user 消息时不查库（省掉一次无意义查询）")
    void noQueryWhenNoAssistantMessage() {
        mapper.toDTOs(List.of(message(5001L, Message.ROLE_USER)));

        verify(toolCallRepository, times(0))
                .findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection());
    }

    @Test
    @DisplayName("assistant 无工具调用时为空数组而非 null（前端可无条件迭代）")
    void emptyArrayWhenNoToolCall() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of());

        MessageDTO dto = mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT));

        assertNotNull(dto.toolCalls());
        assertTrue(dto.toolCalls().isEmpty());
    }

    // ===================== 降级 =====================

    @Test
    @DisplayName("🔴 tool_calls 查询失败 → 降级为不回显，绝不让整页历史打不开")
    void toolCallQueryFailureDegradesGracefully() {
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("模拟数据库超时"));

        MessageDTO dto = mapper.toDTO(message(5002L, Message.ROLE_ASSISTANT));

        assertEquals("正文", dto.content(), "🔴 正文必须照常返回");
        assertTrue(dto.toolCalls().isEmpty());
    }
}
