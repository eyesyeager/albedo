package com.eyes.albedo.tool.dto;

/**
 * 一次工具执行的结果（🔴 <b>不抛异常的统一返回形态</b>）。
 *
 * <p>为什么用返回值而不是异常表达失败：工具失败<b>不中断 SSE 流</b>
 * （api-spec §7.6.4 末尾）—— 它必须被翻译成一个 {@code tool}(终态) 帧后继续生成。
 * 用异常表达会诱导调用方写出"try/catch 里补一帧"的结构，
 * 而遗漏 catch 就会让整条流断掉。返回值形态使"必须处理失败"成为编译期可见的事实。
 *
 * @param status      {@code tool_calls.status} 的目标终态
 * @param errorCode   失败时的已登记数字码；成功为 {@code null}
 * @param content     回灌模型的结果体（已截断；失败时为可回灌的失败说明）
 * @param truncated   结果是否因超 {@code tool.result_max_bytes} 被截断（EX-017）
 * @param durationMs  执行耗时
 * @param deniedCause 🔴 {@code status=denied} 时的<b>拒绝来源</b>（决定写哪条审计，见
 *                    {@link DeniedCause}）；其它状态恒为 {@code null}
 */
public record ToolExecutionResult(String status,
                                  Integer errorCode,
                                  String content,
                                  boolean truncated,
                                  long durationMs,
                                  DeniedCause deniedCause) {

    /**
     * 执行期<b>安全拒绝</b>的来源（api-spec V1.1.3 §7.8.1 ③ 裁决）。
     *
     * <p>🔴 <b>为什么必须区分</b>：两者的审计 action 不同，而审计是排障的唯一线索。
     * 若混为一谈，DBA 在 {@code audit_logs} 里看不出"到底是我撤了授权，还是我把地址改内网了"。
     * 🔴 同时它还决定<b>要不要再写一条审计</b>：SSRF 拒绝已由 {@code mcp/SsrfGuard} 在
     * 独立短事务内写过 {@code mcp.ssrf_rejected}，编排层重复写会产生两条同事实的审计行。
     */
    public enum DeniedCause {
        /**
         * 授权/服务状态在 preflight 之后被撤销（DBA 停用 MCP 服务 / 取消工具授权）。
         *
         * <p>🔴 编排层必须补写 {@code AuditActions.TOOL_GRANT_DENIED}（与状态流转同一短事务）。
         */
        GRANT_REVOKED,
        /**
         * preflight 之后 endpoint 被改为内网/元数据地址，运行时 SSRF 兜底拒绝。
         *
         * <p>🔴 审计 {@code mcp.ssrf_rejected} <b>已由 {@code SsrfGuard} 写过</b>，
         * 编排层<b>不得重复写</b>。
         */
        SSRF_REJECTED
    }

    /**
     * 兼容构造（非 {@code denied} 场景）：{@code deniedCause} 恒为 {@code null}。
     */
    public ToolExecutionResult(String status, Integer errorCode, String content,
                               boolean truncated, long durationMs) {
        this(status, errorCode, content, truncated, durationMs, null);
    }

    public boolean succeeded() {
        return errorCode == null;
    }
}
