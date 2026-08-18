package com.eyes.albedo.chat.dto;

import java.util.List;

/**
 * 消息对象（api-spec.md §4.5.6）。
 *
 * <p>字段裁剪规则：user 消息不返回 {@code model/agentVersion/finishReason/tokenUsage/toolCalls}
 * （置为 {@code null}，由 Jackson 的 {@code non_null} 策略省略），避免前端出现无意义空值。
 *
 * @param messageId    消息 ID（string，ADR-004）
 * @param role         user / assistant（🔴 system / tool 不下发）
 * @param content      正文
 * @param reasoning    思考过程（仅 assistant，可空；🔴 与 content 分列存储，见 api-spec §5.2）
 * @param status       pending / sent / queued / streaming / completed / stopped / failed
 * @param attemptNo    尝试序号（重新生成递增）
 * @param isCurrent    是否当前尝试
 * @param model        模型标识（仅 assistant）
 * @param agentVersion 生成所用 Agent 版本（仅 assistant）
 * @param finishReason 结束原因（仅 assistant）
 * @param tokenUsage   用量（仅 assistant，可空）
 * @param toolCalls    工具调用摘要（仅 assistant；🔴 元素形状与 SSE {@code tool} 帧<b>完全一致</b>，
 *                     使前端用同一套归一与组件渲染实时流与历史回显）
 * @param segments     按轮次的思考/正文段落（仅 assistant，可空）。🔴 与 {@code toolCalls.round}
 *                     配合还原「思考 → 工具 → 思考 → 正文」的真实时序；缺失时前端降级为旧版布局
 * @param createdAt    创建时间（ISO-8601 UTC）
 */
public record MessageDTO(String messageId,
                         String role,
                         String content,
                         String reasoning,
                         String status,
                         int attemptNo,
                         boolean isCurrent,
                         String model,
                         Long agentVersion,
                         String finishReason,
                         TokenUsage tokenUsage,
                         List<Object> toolCalls,
                         List<MessageSegment> segments,
                         String createdAt) {
}
