package com.eyes.albedo.tool;

/**
 * 取消信号（🔴 {@code tool} 感知"停止生成"的<b>唯一方式</b>，architecture.md §5.1.3 末行）。
 *
 * <p>为什么是函数式接口而不是注入 {@code CancelRegistry}：
 * {@code CancelRegistry} / {@code ChatCancelService} 属 {@code chat} 包，
 * 而 {@code tool ✗→ chat}（§5.1.2）。用回调把依赖方向反过来，
 * 由 {@code ChatStreamRunner} 在提交任务时把"如何判断已取消"注入进来。
 */
public interface ToolCancellation {

    /** 永不取消（单测 / 非流式调用方使用）。 */
    ToolCancellation NEVER = () -> false;

    /**
     * 是否已被取消（用户 {@code POST /messages/{id}/stop}、会话被删除 EX-022、客户端断连）。
     *
     * <p>🔴 实现必须是<b>廉价</b>的：确认等待期间会按
     * {@code tool.confirm_poll_interval_millis} 反复调用它。
     */
    boolean cancelled();
}
