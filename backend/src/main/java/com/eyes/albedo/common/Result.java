package com.eyes.albedo.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体（框架 §14.1 唯一形态）。
 *
 * <p>所有 {@code /api/v1/**} 接口<b>无论成功或业务失败一律返回 HTTP 200</b>，业务结果由 code 承载。
 *
 * <pre>
 * { "code": 0, "message": "success", "data": null, "timestamp": 1704067200000 }
 * </pre>
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code = 0} 是唯一成功值（禁止用 200）</li>
 *   <li>{@code timestamp} 所有响应必须包含</li>
 *   <li>构造方法只有 {@code success} / {@code error}，🔴 禁止新增 {@code fail}</li>
 * </ul>
 *
 * <p>⚠️ {@code @JsonInclude(ALWAYS)}：全局 {@code default-property-inclusion: non_null} 会把
 * {@code data: null} 整个字段省略，导致前端读到 {@code undefined} 而非契约约定的 {@code null}。
 * 外层响应体<b>四个字段必须恒定出现</b>；字段裁剪只允许发生在内层业务 DTO。
 *
 * @param <T> data 类型
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Result<T>(int code, String message, T data, long timestamp) {

    private static final String SUCCESS_MESSAGE = "success";

    public static <T> Result<T> success(T data) {
        return new Result<>(ErrorCode.SUCCESS, SUCCESS_MESSAGE, data, System.currentTimeMillis());
    }

    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message == null || message.isBlank() ? ErrorCode.defaultMessage(code) : message,
                null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(int code) {
        return error(code, ErrorCode.defaultMessage(code));
    }

    /**
     * 带附加数据的错误响应（如 10005 的 retryAfterSeconds、30021 的 violations）。
     */
    public static <T> Result<T> error(int code, String message, T data) {
        return new Result<>(code, message == null || message.isBlank() ? ErrorCode.defaultMessage(code) : message,
                data, System.currentTimeMillis());
    }

    /**
     * 是否成功（仅供服务端与测试判断）。
     *
     * <p>⚠️ 必须 {@code @JsonIgnore}：record 的 {@code isSuccess()} 会被 Jackson 当作
     * {@code success} 属性序列化，导致响应体多出契约未定义的字段（api-spec §1.2 只允许
     * code / message / data / timestamp）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS;
    }
}
