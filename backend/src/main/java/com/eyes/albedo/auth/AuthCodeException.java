package com.eyes.albedo.auth;

import com.eyes.eyesAuth.exception.EyesAuthException;

/**
 * 携带耶瞳原始错误码的鉴权异常。
 *
 * <p>背景：starter 的 {@code EyesAuthException} 只有 message，没有 code，无法区分
 * 20001（Token 非法）/ 20002（过期）/ 20003（冻结）等语义。本类补齐 code，
 * 由 {@code GlobalExceptionHandler} 原样透传给前端（前端据此决定"先提示再跳"还是"直接跳"）。
 *
 * <p>🔴 code 必须落在 20000~20999（耶瞳保留段），业务错误一律用 {@code BusinessException}。
 */
public class AuthCodeException extends EyesAuthException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public AuthCodeException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
