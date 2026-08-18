package com.eyes.albedo.audit;

/**
 * 审计写入不可用（🔴 fail-closed 信号）。
 *
 * <p>语义：审计是安全动作的必要组成部分，写不进去就意味着"做了但没留痕"。
 * 按 EX-024，这种情况<b>必须失败关闭</b>：
 * <ul>
 *   <li><b>非流式</b>安全/管理操作：本异常向上传播 → 业务事务回滚 → 全局异常处理返回 {@code 50003}</li>
 *   <li><b>流式内</b>安全事件：由调用方捕获，使该次工具调用判 {@code denied}/{@code failed}
 *       （{@code errorCode=50003}），🔴 但<b>绝不中断 SSE 流</b>（ADR-010）</li>
 * </ul>
 *
 * <p>🔴 本异常<b>不携带业务码</b>：它不是 {@code BusinessException}，以免有人顺手给它一个
 * 未登记的错误码。对外码由上述两条路径分别决定。
 */
public class AuditWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditWriteException(String message) {
        super(message);
    }

    public AuditWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
