package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import java.util.List;

import com.eyes.albedo.chat.dto.MessageSegment;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.tool.repository.ToolCallRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 按轮次段落（{@code messages.segments}）读写的单测。
 *
 * <p>🔴 锁定的核心不变量：<b>段落只是排版投影，绝不能有能力破坏正文</b>。
 * 序列化/解析的任何异常都必须降级为 {@code null}（前端走旧版布局），
 * 而不是抛异常连带整条消息落库失败或整页历史打不开。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageSegmentsTest {

    @Mock
    private ToolCallRepository toolCallRepository;

    private MessageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new MessageMapper(new ObjectMapper(), toolCallRepository);
        when(toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(anyCollection()))
                .thenReturn(List.of());
    }

    private Message assistant(String segments) {
        Message message = new Message();
        message.setId(5002L);
        message.setRole(Message.ROLE_ASSISTANT);
        message.setContent("答案");
        message.setStatus(Message.STATUS_COMPLETED);
        message.setAttemptNo(1);
        message.setSegments(segments);
        message.setCreatedAt(java.time.Instant.parse("2026-08-16T09:00:00Z"));
        return message;
    }

    // ===================== 序列化 =====================

    @Test
    @DisplayName("按轮次段落往返一致（轮号必须原样保留，否则与 tool_calls.round 错位）")
    void roundTripPreservesRounds() {
        String json = mapper.writeSegments(List.of(
                new MessageSegment(1, "先算前9个", ""),
                new MessageSegment(2, "工具结果正确", "总价1230.96元")));

        List<MessageSegment> parsed = mapper.toDTO(assistant(json)).segments();

        assertEquals(2, parsed.size());
        assertEquals(1, parsed.get(0).round());
        assertEquals("先算前9个", parsed.get(0).reasoning());
        assertEquals("", parsed.get(0).text());
        assertEquals(2, parsed.get(1).round());
        assertEquals("总价1230.96元", parsed.get(1).text());
    }

    @Test
    @DisplayName("全空段落不落库（单轮普通对话不必占这一列）")
    void blankSegmentsAreNotPersisted() {
        assertNull(mapper.writeSegments(List.of(new MessageSegment(1, "", ""))));
        assertNull(mapper.writeSegments(List.of()));
        assertNull(mapper.writeSegments(null));
    }

    @Test
    @DisplayName("空轮被剔除，非空轮保留原轮号（轮号不得因剔除而重排）")
    void blankRoundsAreDroppedWithoutRenumbering() {
        String json = mapper.writeSegments(List.of(
                new MessageSegment(1, "", ""),
                new MessageSegment(2, "想", "答案")));

        List<MessageSegment> parsed = mapper.toDTO(assistant(json)).segments();

        assertEquals(1, parsed.size());
        assertEquals(2, parsed.get(0).round(), "🔴 剔除空轮后不得把 round 2 改写成 1");
    }

    // ===================== 降级 =====================

    @Test
    @DisplayName("🔴 segments 为非法 JSON → 降级为 null，正文照常返回")
    void malformedJsonDegradesToNull() {
        var dto = mapper.toDTO(assistant("{不是合法的JSON"));

        assertNull(dto.segments(), "解析失败必须降级，让前端走旧版布局");
        assertEquals("答案", dto.content(), "🔴 排版字段绝不能影响正文");
    }

    @Test
    @DisplayName("segments 为 null / 空串（V1.1.8 之前的历史行）→ null，触发旧版布局")
    void legacyRowsYieldNull() {
        assertNull(mapper.toDTO(assistant(null)).segments());
        assertNull(mapper.toDTO(assistant("")).segments());
        assertNull(mapper.toDTO(assistant("[]")).segments());
    }

    @Test
    @DisplayName("user 消息不下发 segments（字段裁剪）")
    void userMessageHasNoSegments() {
        Message user = assistant(mapper.writeSegments(List.of(new MessageSegment(1, "想", "问"))));
        user.setRole(Message.ROLE_USER);

        assertNull(mapper.toDTO(user).segments());
    }
}
