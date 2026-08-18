package com.eyes.albedo.tool;

import java.util.Map;
import java.util.Set;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.entity.ToolCall;

/**
 * {@code tool_calls} 状态机（api-spec §7.8.1，🔴 唯一合法流转表）。
 *
 * <pre>
 * pending               → awaiting_confirmation | running | denied | failed | cancelled
 * awaiting_confirmation → running | denied | timed_out | cancelled
 * running               → succeeded | failed | timed_out | cancelled | 🔴 denied（执行期竞态）
 * succeeded / failed / timed_out / cancelled / denied → 🔴 终态，不可再迁移
 * </pre>
 *
 * <p>🔴 <b>为什么要显式建表而不是"随手 set status"</b>：
 * 三方竞态（confirm 接口 / 确认等待超时 / 停止生成）会并发写同一行（AR-010）。
 * 若没有合法流转表，就会出现"已 succeeded 的调用被后到的 timeout 改成 timed_out"
 * 这类<b>终态被覆盖</b>的问题，而它会直接让 §7.8.2 的 {@code 30055} 冲突判定给出错误结论。
 * 🔴 状态机的唯一裁决点是<b>数据库行锁</b>（{@code SELECT … FOR UPDATE}），
 * 本类只提供"这一步流转是否合法"的纯判定。
 *
 * <p><b>🔴 {@code running → denied}（api-spec V1.1.3 §7.8.1 ③ 裁决，本轮新增）</b>：
 * <pre>
 * 场景（真实可达，非理论竞态）：确认/授权校验通过 → 行已置 running → 执行器发起调用时
 * DBA 刚好改库（撤销 mcp_tools.granted / 把 endpoint 改成内网地址），运行时兜底判定拒绝。
 * 该竞态是**架构必然**：§7.6.3 第 4 步的 SSRF 重校验发生在 tool_calls → running **之后**。
 *
 * 🔴 该行必须收敛为 denied + errorCode=30050，**严禁**归一化为 failed + 30052：
 *   ① 语义不能失真：撤授权 = 授权拒绝、改内网地址 = SSRF 拒绝，二者都是**安全拒绝**；
 *      归一化成 30052（MCP 连接/传输/协议/鉴权不可用）会把**安全事件**伪装成**上游故障**——
 *      DBA 看到 30052 会去查网络，永远查不到"是我撤了授权"；
 *   ② §7.11.1 的互斥不变量按 **status** 二分（status='denied' → 只计 toolDeniedCount），
 *      新增一条**进入** denied 的路径**不改变任何聚合公式**，也不产生重复计数；
 *      反而归一化成 failed 会让被撤授权的调用错计进 toolFailedCount（口径失真）。
 * </pre>
 *
 * <p>🔴 {@code timed_out} 的两种语义由 {@code errorCode} 区分（不可混填）：
 * {@code 30050} = 确认等待超时（语义等同拒绝）；{@code 30051}/{@code 30056} = 执行超时。
 */
public final class ToolStateMachine {

    private ToolStateMachine() {
    }

    /** 合法流转表（🔴 与 api-spec §7.8.1 逐行一致）。 */
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            ToolCall.STATUS_PENDING, Set.of(
                    ToolCall.STATUS_AWAITING_CONFIRMATION, ToolCall.STATUS_RUNNING,
                    ToolCall.STATUS_DENIED, ToolCall.STATUS_FAILED, ToolCall.STATUS_CANCELLED),
            ToolCall.STATUS_AWAITING_CONFIRMATION, Set.of(
                    ToolCall.STATUS_RUNNING, ToolCall.STATUS_DENIED,
                    ToolCall.STATUS_TIMED_OUT, ToolCall.STATUS_CANCELLED),
            ToolCall.STATUS_RUNNING, Set.of(
                    ToolCall.STATUS_SUCCEEDED, ToolCall.STATUS_FAILED,
                    ToolCall.STATUS_TIMED_OUT, ToolCall.STATUS_CANCELLED,
                    // 🔴 V1.1.3 ③：执行期竞态（preflight 通过后 DBA 撤授权 / 改内网地址）
                    ToolCall.STATUS_DENIED),
            ToolCall.STATUS_SUCCEEDED, Set.of(),
            ToolCall.STATUS_FAILED, Set.of(),
            ToolCall.STATUS_TIMED_OUT, Set.of(),
            ToolCall.STATUS_CANCELLED, Set.of(),
            ToolCall.STATUS_DENIED, Set.of());

    /**
     * 该流转是否合法。
     */
    public static boolean canTransition(String from, String to) {
        if (from == null || to == null || !ToolCall.ALL_STATUSES.contains(to)) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 是否终态。
     */
    public static boolean terminal(String status) {
        return ToolCall.TERMINAL_STATUSES.contains(status);
    }

    /**
     * 强制流转校验。
     *
     * @throws BusinessException 30060 非法流转（🔴 属实现缺陷，不应对终端用户可见，
     *                           但绝不能静默覆盖终态）
     */
    public static void requireTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具调用状态流转非法：" + from + " → " + to);
        }
    }

    /**
     * {@code timed_out} 是否为"确认等待超时"（语义等同拒绝，用量聚合计入 denied）。
     */
    public static boolean confirmTimeout(String status, Integer errorCode) {
        return ToolCall.STATUS_TIMED_OUT.equals(status)
                && errorCode != null && errorCode == ErrorCode.TOOL_DENIED;
    }

    /**
     * {@code timed_out} 是否为"执行超时"（{@code 30051} / {@code 30056}）。
     */
    public static boolean executionTimeout(String status, Integer errorCode) {
        return ToolCall.STATUS_TIMED_OUT.equals(status) && errorCode != null
                && (errorCode == ErrorCode.TOOL_TIMEOUT || errorCode == ErrorCode.TOOL_RETRY_BLOCKED);
    }
}
