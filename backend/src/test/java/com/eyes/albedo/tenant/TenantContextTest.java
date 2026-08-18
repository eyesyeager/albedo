package com.eyes.albedo.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户上下文与 Host 规范化单测（AC-TEN-001 / AC-TEN-002 / AC-TEN-006 的最小保障）。
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Host 规范化：小写 + 去末尾点 + 保留端口")
    void normalizeRawHost() {
        assertEquals("localhost:5173", TenantResolver.normalizeRawHost("  LocalHost:5173 "));
        assertEquals("albedo-gift.eyescode.top",
                TenantResolver.normalizeRawHost("Albedo-Gift.EyesCode.Top."));
    }

    @Test
    @DisplayName("去端口：兼容 IPv6 字面量")
    void stripPort() {
        assertEquals("albedo-gift.eyescode.top",
                TenantResolver.stripPort("albedo-gift.eyescode.top:8080"));
        assertEquals("localhost", TenantResolver.stripPort("localhost:5173"));
        assertEquals("[::1]", TenantResolver.stripPort("[::1]:8080"));
    }

    @Test
    @DisplayName("无上下文：tenantId 返回 fail-closed 哨兵，require 抛 30010")
    void missingContext() {
        assertEquals(TenantContext.NONE_TENANT, TenantContext.tenantIdOrNone());
        BusinessException e = assertThrows(BusinessException.class, TenantContext::require);
        assertEquals(ErrorCode.TENANT_NOT_FOUND, e.getCode());
    }

    @Test
    @DisplayName("租户暂停：requireEnabled 抛 30011")
    void suspendedTenant() {
        TenantContext.bind(new TenantContext.Snapshot("gift", 1L, "albedo-gift.eyescode.top",
                TenantStatus.SUSPENDED, 3L, null));

        BusinessException e = assertThrows(BusinessException.class, TenantContext::requireEnabled);
        assertEquals(ErrorCode.TENANT_SUSPENDED, e.getCode());
    }

    @Test
    @DisplayName("draft / archived 租户对外等同不存在：30010")
    void draftTenant() {
        TenantContext.bind(new TenantContext.Snapshot("gift", 1L, "albedo-gift.eyescode.top",
                TenantStatus.DRAFT, 0L, null));

        BusinessException e = assertThrows(BusinessException.class, TenantContext::requireEnabled);
        assertEquals(ErrorCode.TENANT_NOT_FOUND, e.getCode());
    }

    @Test
    @DisplayName("鉴权成功后回填 uid，快照保持不可变语义")
    void bindUid() {
        TenantContext.Snapshot original = new TenantContext.Snapshot("gift", 1L,
                "albedo-gift.eyescode.top", TenantStatus.ENABLED, 3L, null);
        TenantContext.bind(original);

        TenantContext.bindUid(10086L);

        assertTrue(TenantContext.current().isPresent());
        assertEquals(10086L, TenantContext.current().orElseThrow().uid());
        assertEquals("gift", TenantContext.requireEnabled().tenantId());
        // 原快照未被修改
        assertEquals(null, original.uid());
    }
}
