package com.eyes.albedo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统一响应体与错误码基线单测（框架 §十四）。
 */
class ResultTest {

    @Test
    @DisplayName("成功响应：code=0、message=success、timestamp 必带")
    void success() {
        Result<String> result = Result.success("ok");

        assertEquals(0, result.code());
        assertEquals("success", result.message());
        assertEquals("ok", result.data());
        assertTrue(result.timestamp() > 0);
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("错误响应：使用登记的错误码与默认语义")
    void error() {
        Result<Void> result = Result.error(ErrorCode.TENANT_SUSPENDED);

        assertEquals(30011, result.code());
        assertEquals("站点暂停服务", result.message());
        assertFalse(result.isSuccess());
        assertTrue(result.timestamp() > 0);
    }

    @Test
    @DisplayName("业务异常禁止占用耶瞳保留段 20000~20999")
    void businessExceptionMustNotUseAuthSegment() {
        assertThrows(IllegalArgumentException.class, () -> new BusinessException(20001, "不允许"));
        assertThrows(IllegalArgumentException.class, () -> new BusinessException(20999, "不允许"));
    }

    @Test
    @DisplayName("错误码段位判定")
    void authSegment() {
        assertTrue(ErrorCode.isAuthSegment(ErrorCode.AUTH_TOKEN_EXPIRED));
        assertFalse(ErrorCode.isAuthSegment(ErrorCode.VALIDATION_FAILED));
        assertFalse(ErrorCode.isAuthSegment(ErrorCode.TENANT_NOT_FOUND));
    }
}
