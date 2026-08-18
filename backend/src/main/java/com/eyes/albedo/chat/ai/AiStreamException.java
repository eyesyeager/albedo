package com.eyes.albedo.chat.ai;

import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.common.ErrorCode;

/**
 * 上游模型调用失败。
 *
 * <p>🔴 对外只暴露<b>已登记的业务码</b>与可展示语义：
 * 内部地址、上游原始报文、api_key 一律不进入 message（架构 §11）。
 */
public class AiStreamException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String finishReason;
    /** 限流时的建议重试秒数；非限流为 null（前端据此决定是否倒计时，🔴 不猜测等待时长时保持 null）。 */
    private final Integer retryAfterSeconds;

    public AiStreamException(int code, String finishReason, String message) {
        this(code, finishReason, message, null);
    }

    public AiStreamException(int code, String finishReason, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.finishReason = finishReason;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 上游不可用 / 协议异常 / HTTP 非 200（非限流）→ 50002。 */
    public static AiStreamException upstream(String message) {
        return new AiStreamException(ErrorCode.UPSTREAM_UNAVAILABLE, Message.FINISH_FAILED, message);
    }

    /**
     * 上游模型限流（rate_limit_exceeded / 429）→ 10005。
     *
     * <p>🔴 用 {@link ErrorCode#RATE_LIMITED} 而非 {@link ErrorCode#UPSTREAM_UNAVAILABLE}：
     * 前端 {@code rateLimitStore} 会据此进入"限流等待"倒计时并提示用户「模型限流，请稍后再试」，
     * 而不是把限流误报成「模型服务暂不可用」。恢复条件是"稍后重试"（秒级自愈），
     * 与 10005 的 QPM 语义一致；🔴 严禁用 {@link ErrorCode#DAILY_QUOTA_EXHAUSTED}(30070)，
     * 那是"今日额度用尽、明日零点重置"，前端状态机物理隔离。
     */
    public static AiStreamException rateLimited(String message) {
        return rateLimited(message, null);
    }

    /**
     * 上游模型限流（rate_limit_exceeded / 429）→ 10005，附带建议重试秒数。
     *
     * @param retryAfterSeconds 服务端给出的限流恢复秒数；上游未告知（多为混元「请求频繁」仅提示稍后）传 null。
     *                          🔴 这是"服务端值"，前端只读取、不猜测；null 时前端降级为「请稍后再试」文案（无倒计时）。
     */
    public static AiStreamException rateLimited(String message, Integer retryAfterSeconds) {
        return new AiStreamException(ErrorCode.RATE_LIMITED, Message.FINISH_FAILED, message, retryAfterSeconds);
    }

    /** 首字超时或整体超时 → 50002 + finishReason=timeout（EX-014）。 */
    public static AiStreamException timeout(String message) {
        return new AiStreamException(ErrorCode.UPSTREAM_UNAVAILABLE, Message.FINISH_TIMEOUT, message);
    }

    public int getCode() {
        return code;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
