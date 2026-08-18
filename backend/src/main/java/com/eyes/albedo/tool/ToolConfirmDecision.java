package com.eyes.albedo.tool;

import com.eyes.albedo.tool.entity.ToolCall;

/**
 * 高风险工具确认的等待结论（ADR-008，api-spec §7.8）。
 *
 * <p>🔴 <b>四态而非两态</b>：除了用户的 {@code allow} / {@code deny}，还必须显式表达
 * "等待超时"与"生成被取消" —— 二者的 {@code tool_calls} 终态与审计动作完全不同：
 * <table border="1">
 *   <caption>收敛口径</caption>
 *   <tr><th>结论</th><th>终态</th><th>errorCode</th><th>审计</th></tr>
 *   <tr><td>{@link #ALLOW}</td><td>{@code running} → 执行结果</td><td>—</td>
 *       <td>{@code tool.confirm_allowed}（confirm 接口写）</td></tr>
 *   <tr><td>{@link #DENY}</td><td>{@code denied}</td><td>{@code 30050}</td>
 *       <td>{@code tool.confirm_denied}（confirm 接口写）</td></tr>
 *   <tr><td>{@link #TIMEOUT}</td><td>{@code timed_out}</td><td>🔴 {@code 30050}（语义等同拒绝）</td>
 *       <td>{@code tool.confirm_timeout}（🔴 由<b>生成线程</b>写）</td></tr>
 *   <tr><td>{@link #CANCELLED}</td><td>{@code cancelled}</td><td>{@code null}</td>
 *       <td>🔴 <b>不写</b> confirm 审计（不是用户对该工具的决定，§9.5.4 ③）</td></tr>
 * </table>
 *
 * <p>🔴 {@code timed_out} 的两种语义靠 {@code errorCode} 区分（{@code 30050} 确认超时 /
 * {@code 30051}·{@code 30056} 执行超时）—— confirm 的冲突判定依赖该区分（api-spec §7.8.2）。
 */
public enum ToolConfirmDecision {

    /** 用户允许执行。 */
    ALLOW(ToolCall.DECISION_ALLOW),
    /** 用户拒绝执行。 */
    DENY(ToolCall.DECISION_DENY),
    /** 等待超过 {@code tool.confirm_wait_seconds}：🔴 按<b>拒绝</b>收敛。 */
    TIMEOUT(null),
    /** 生成被停止 / 会话被删除 / 客户端断连（AR-008：不得白等满等待上限）。 */
    CANCELLED(null);

    private final String literal;

    ToolConfirmDecision(String literal) {
        this.literal = literal;
    }

    /** 对应 {@code tool_calls.decision} 列值；超时与取消不是"用户决定"，故为 {@code null}。 */
    public String literal() {
        return literal;
    }

    /**
     * 解析用户提交的 {@code decision}（🔴 只接受 {@code allow} / {@code deny}）。
     *
     * @return {@code null} 表示取值非法（调用方应返回 {@code 10001}）
     */
    public static ToolConfirmDecision ofUserInput(String raw) {
        if (ToolCall.DECISION_ALLOW.equals(raw)) {
            return ALLOW;
        }
        return ToolCall.DECISION_DENY.equals(raw) ? DENY : null;
    }

    /** Redis 兜底信号值 → 决定（跨实例通道，ADR-008 第 3 条）。 */
    public static ToolConfirmDecision ofSignal(String raw) {
        return ofUserInput(raw);
    }
}
