package com.eyes.albedo.common;

/**
 * 业务异常：携带已在 {@code docs/api-spec.md} 登记的业务错误码。
 *
 * <p>使用纪律：
 * <ul>
 *   <li>🔴 code 必须来自 {@link ErrorCode}，且必须已在 api-spec 错误码登记表登记</li>
 *   <li>🔴 禁止使用 20000~20999（耶瞳保留段），否则前端会误判为鉴权失效而清退登录</li>
 *   <li>message 面向用户，禁止包含内部地址、堆栈、密钥、其他租户标识或资源存在性细节</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    /**
     * 可选的附加数据（如 10005 的 retryAfterSeconds、30021 的 violations），会原样放入 Result.data。
     */
    private final transient Object payload;

    public BusinessException(int code) {
        this(code, ErrorCode.defaultMessage(code), null);
    }

    public BusinessException(int code, String message) {
        this(code, message, null);
    }

    public BusinessException(int code, String message, Object payload) {
        super(message == null || message.isBlank() ? ErrorCode.defaultMessage(code) : message);
        if (ErrorCode.isAuthSegment(code)) {
            throw new IllegalArgumentException(
                    "业务异常禁止占用耶瞳 SSO 保留段 20000~20999，非法 code=" + code);
        }
        this.code = code;
        this.payload = payload;
    }

    public int getCode() {
        return code;
    }

    public Object getPayload() {
        return payload;
    }

    /** 参数校验失败（10001）。 */
    public static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }

    /** 资源不存在（10004）—— 跨租户 / 跨用户访问统一走这里，不暴露存在性。 */
    public static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 权限不足（10003）。 */
    public static BusinessException permissionDenied(String message) {
        return new BusinessException(ErrorCode.PERMISSION_DENIED, message);
    }
}
