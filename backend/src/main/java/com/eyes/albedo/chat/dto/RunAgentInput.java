package com.eyes.albedo.chat.dto;

import java.util.List;
import java.util.Map;

/**
 * AG-UI 协议请求体 {@code RunAgentInput}（ag-ui-protocol / docs.ag-ui.com/sdk/js/core/types）。
 *
 * <p>🔴 与既有 {@link SendMessageRequest} 的差异：AG-UI 由客户端把完整 {@code messages[]}、
 * {@code threadId} / {@code runId} / {@code tools} / {@code context} 一并上传，后端据此
 * 建立一次 Run 并返回事件流。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code threadId}：对话线程 ID（映射本项目的 conversationId）</li>
 *   <li>{@code runId}：本次运行 ID（客户端生成，用于幂等与 resume）</li>
 *   <li>{@code parentRunId}：父运行 ID（可选，时间旅行/分支）</li>
 *   <li>{@code state}：代理状态（可选，本项目暂不启用）</li>
 *   <li>{@code messages}：对话消息数组（本项目仅取最后一条 user 消息作为本次输入，
 *       历史仍由 REST 拉取，见契约决策）</li>
 *   <li>{@code tools}：客户端提供的工具（本项目工具由后端 Agent 配置管理，此处仅做透传占位）</li>
 *   <li>{@code context}：上下文对象</li>
 *   <li>{@code forwardedProps}：透传附加属性</li>
 *   <li>{@code resume}：针对上次以 interrupt 结束的运行，逐中断的响应（用于工具确认恢复）</li>
 * </ul>
 */
public record RunAgentInput(
        String threadId,
        String runId,
        String parentRunId,
        Object state,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        List<Map<String, Object>> context,
        Map<String, Object> forwardedProps,
        List<ResumeEntry> resume) {

    /**
     * 中断恢复条目：表达"用户对上次 Run 的中断（如工具确认）作出的响应"。
     *
     * @param interruptId 中断标识（本项目用工具调用 toolCallId）
     * @param status      {@code resolved}（确认通过）| {@code cancelled}（拒绝/取消）
     * @param payload     附加载荷（可选）
     */
    public record ResumeEntry(String interruptId, String status, Object payload) {
    }
}
