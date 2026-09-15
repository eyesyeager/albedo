package com.eyes.albedo.chat.sse;

import com.eyes.albedo.tool.dto.ToolProgress;

/**
 * 流式生成写出门面（🔴 为支持 AG-UI 协议改造而引入的统一抽象）。
 *
 * <p>历史：{@link ChatStreamRunner} 直接依赖 {@link SseWriter}，事件名集合冻结为
 * {@code meta|delta|tool|error|done}。为「彻底替换为 AG-UI 协议」，把写出语义抽象为本接口，
 * 由两个实现分别承载：
 * <ul>
 *   <li>{@link SseWriter}：旧自定义 SSE 契约（保留兼容，渐进迁移期使用）</li>
 *   <li>{@link AgUiStreamSink}：AG-UI 协议事件流（新契约）</li>
 * </ul>
 *
 * <p>方法签名沿用旧 {@code SseWriter} 的语义（meta/delta/tool/error/done），
 * 由 {@link AgUiStreamSink} 内部翻译为 AG-UI 事件，使 {@link ChatStreamRunner} 编排逻辑零改动。
 */
public interface StreamSink {

    /** {@code meta}：会话/消息/版本锚点（AG-UI 侧翻译为 RUN_STARTED）。 */
    void meta(String conversationId, String messageId, long agentVersion, String userMessageId);

    /** 正文增量分片。 */
    void delta(String text);

    /** 思考过程增量分片（与正文互斥承载）。 */
    void reasoning(String reasoning);

    /** 工具状态流转帧。 */
    void tool(ToolProgress progress);

    /** 错误（数字业务码 + 可展示语义）。 */
    void error(int code, String message);

    /** 最终态。 */
    void done(String finishReason, String messageId, String status, String title);

    /** 心跳注释帧。 */
    void ping();

    /** 连接是否已失效（客户端断开）。 */
    boolean broken();

    /** 结束响应（释放 Servlet 异步上下文）。 */
    void complete();
}
