package com.eyes.albedo.chat.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.chat.dto.MessageDTO;
import com.eyes.albedo.chat.dto.MessageSegment;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.sse.SseEvents;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.tool.dto.ToolProgress;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消息实体 → 对外 DTO 的唯一转换入口。
 *
 * <p>集中在一处的原因：字段裁剪（user 不返回模型信息）与 ID 字符串化（ADR-004）
 * 一旦散落在多个 Controller，必然出现某个接口漏裁剪、漏转换的契约漂移。
 *
 * <p>🔴 <b>为什么工具调用回显放在这里（而不是 {@code conversation} 包）</b>：
 * {@code tool} 包已依赖 {@code conversation}（{@code ToolCallQueryService → ConversationService}），
 * 若让 {@code ConversationService} 反向依赖 {@code tool} 即成环。
 * 而 {@code chat → tool} 是 architecture §5 明确许可的方向（禁的是 {@code tool → chat}），
 * 故装配点落在本类。
 */
@Slf4j
@Component
public class MessageMapper {

    private final ObjectMapper objectMapper;
    private final ToolCallRepository toolCallRepository;

    public MessageMapper(ObjectMapper objectMapper, ToolCallRepository toolCallRepository) {
        this.objectMapper = objectMapper;
        this.toolCallRepository = toolCallRepository;
    }

    /**
     * 批量转换（🔴 <b>列表/分页场景必须走这里</b>：一页消息只查一次 {@code tool_calls}）。
     */
    public List<MessageDTO> toDTOs(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Object>> toolCalls = loadToolCalls(messages);
        List<MessageDTO> list = new ArrayList<>(messages.size());
        for (Message message : messages) {
            list.add(toDTO(message, toolCalls.getOrDefault(message.getId(), List.of())));
        }
        return list;
    }

    /**
     * 单条转换（🔴 会为这一条消息单独查一次 {@code tool_calls}；
     * <b>列表场景请用 {@link #toDTOs(List)}</b>，否则构成 N+1）。
     */
    public MessageDTO toDTO(Message message) {
        return toDTOs(List.of(message)).get(0);
    }

    private MessageDTO toDTO(Message message, List<Object> toolCalls) {
        boolean assistant = Message.ROLE_ASSISTANT.equals(message.getRole());
        return new MessageDTO(
                Ids.toStr(message.getId()),
                message.getRole(),
                message.getContent(),
                // 🔴 仅 assistant 下发：user 消息不存在思考过程，返回 null 由 non_null 策略省略
                assistant ? message.getReasoning() : null,
                message.getStatus(),
                message.getAttemptNo() == null ? 1 : message.getAttemptNo(),
                message.current(),
                assistant ? message.getModel() : null,
                assistant ? message.getAgentVersion() : null,
                assistant ? message.getFinishReason() : null,
                assistant ? parseUsage(message.getTokenUsage()) : null,
                // 🔴 保持空数组而非 null，前端可无条件迭代（契约样例即为 []）
                assistant ? toolCalls : null,
                assistant ? parseSegments(message.getSegments()) : null,
                TimeFormat.iso(message.getCreatedAt()));
    }

    /**
     * 批量载入并按 {@code messageId} 分组工具调用。
     *
     * <p>🔴 <b>只查 assistant 消息</b>：user 消息不可能有工具调用，纳入只会放大 {@code IN} 列表。
     * <p>🔴 <b>查询失败不得连带整页历史失败</b>：工具调用是<b>可追溯信息</b>，
     * 而正文才是用户真正要读的内容 —— 降级为空列表并留 WARN，好过整个会话打不开。
     */
    private Map<Long, List<Object>> loadToolCalls(List<Message> messages) {
        List<Long> assistantIds = new ArrayList<>();
        for (Message message : messages) {
            if (Message.ROLE_ASSISTANT.equals(message.getRole()) && message.getId() != null) {
                assistantIds.add(message.getId());
            }
        }
        if (assistantIds.isEmpty()) {
            return Map.of();
        }
        try {
            return groupByMessageId(
                    toolCallRepository.findByMessageIdInOrderByCreatedAtAscIdAsc(assistantIds));
        } catch (RuntimeException e) {
            log.warn("历史工具调用载入失败，本页降级为不回显：messageCount={}", assistantIds.size(), e);
            return Map.of();
        }
    }

    private Map<Long, List<Object>> groupByMessageId(Collection<ToolCall> rows) {
        Map<Long, List<Object>> grouped = new HashMap<>();
        for (ToolCall row : rows) {
            grouped.computeIfAbsent(row.getMessageId(), key -> new ArrayList<>())
                    .add(toEventShape(row));
        }
        return grouped;
    }

    /**
     * {@code tool_calls} 行 → <b>与 SSE {@code tool} 帧完全一致</b>的对外形状。
     *
     * <p>🔴 <b>为什么刻意复用 {@link SseEvents.Tool} 而不是 {@code ToolCallDTO}</b>：
     * 前端用<b>同一个</b>归一函数（{@code normalizeToolCall}）与<b>同一套</b>组件
     * 渲染「实时流」与「历史回显」。形状一旦分叉就会出现只在刷新后复现的渲染差异 ——
     * 例如 {@code ToolCallDTO} 没有 §5.4.1 第 2 条要求永久保留的兼容字段 {@code summary}，
     * 用它回显会让状态条摘要在历史里凭空变空白。
     * 复用 {@code Tool.from(ToolProgress)} 还顺带复用了 {@code summary}
     * 「非终态取 args、终态取 result」的取值口径，杜绝第二份实现。
     *
     * <p>🔴 {@code retryAfterSeconds} 恒为 {@code null}：它是限流的<b>瞬时</b>运行时信息，
     * 不落库、也不应在历史里复活成一个早已过期的倒计时。
     *
     * <p>🔴 只读 {@code tool_calls} 的摘要列，绝不 join 消息或 MCP 配置表
     * （字段禁含清单见 {@code ToolCallDTO} 类注释）。
     */
    private Object toEventShape(ToolCall row) {
        ToolProgress progress = new ToolProgress(
                Ids.toStr(row.getId()),
                row.getToolType(),
                row.getToolKey(),
                row.getRiskLevel(),
                row.getStatus(),
                row.getRound() == null ? 1 : row.getRound(),
                row.getArgsSummary(),
                row.getResultSummary(),
                Integer.valueOf(1).equals(row.getTruncated()),
                row.getErrorCode(),
                null);
        return SseEvents.Tool.from(progress);
    }

    private TokenUsage parseUsage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, TokenUsage.class);
        } catch (JsonProcessingException e) {
            log.warn("token 用量解析失败，按未知处理");
            return null;
        }
    }

    /**
     * 序列化用量，失败返回 {@code null}（🔴 绝不因为统计字段导致消息落库失败）。
     */
    public String writeUsage(TokenUsage usage) {
        if (usage == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(usage);
        } catch (JsonProcessingException e) {
            log.warn("token 用量序列化失败，按空处理");
            return null;
        }
    }

    /**
     * 序列化按轮次段落，失败返回 {@code null}。
     *
     * <p>🔴 <b>绝不因为排版字段导致正文落库失败</b>：{@code segments} 只是渲染投影，
     * 序列化异常时宁可退回旧版布局（全部思考 → 全部工具 → 全部正文），
     * 也不能让一次生成的正文写不进库。
     * <p>🔴 全空段落一律返回 {@code null}：单轮无工具的普通对话不必存这一列。
     */
    public String writeSegments(List<MessageSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return null;
        }
        List<MessageSegment> effective = segments.stream().filter(s -> !s.blank()).toList();
        if (effective.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(effective);
        } catch (JsonProcessingException e) {
            log.warn("消息段落序列化失败，本条消息退回旧版布局：segmentCount={}", effective.size());
            return null;
        }
    }

    /**
     * 解析按轮次段落；🔴 失败或为空一律返回 {@code null}，让前端走旧版布局降级。
     */
    private List<MessageSegment> parseSegments(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            List<MessageSegment> parsed = objectMapper.readValue(raw,
                    new TypeReference<List<MessageSegment>>() {
                    });
            return parsed == null || parsed.isEmpty() ? null : parsed;
        } catch (JsonProcessingException e) {
            log.warn("消息段落解析失败，本条消息退回旧版布局");
            return null;
        }
    }
}
