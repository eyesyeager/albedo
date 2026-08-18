package com.eyes.albedo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 审计契约测试：action 枚举、eventId 格式、仓储只写不删（AC-AUD-001 / AC-AUD-003）。
 */
class AuditContractTest {

    @Test
    @DisplayName("AC-AUD-003：action 枚举与 api-spec §7.14 的 12 项逐字一致"
            + "（V1.1.2 新增 mcp.tool_grant_revoked；V1.1.3 新增 tool.confirm_conflict）")
    void actionEnumMatchesContract() {
        // 🔴 字面量直接照抄 api-spec §7.14 表格，避免"用常量校验常量"的空转
        Set<String> expected = Set.of(
                "tool.grant_denied",
                "tool.confirm_allowed",
                "tool.confirm_denied",
                "tool.confirm_timeout",
                // 🔴 V1.1.3（⑤ 裁决）：决定冲突（30055）必须留痕 ——
                //    "翻转一个已生效的高风险决定"是安全相关行为，不留痕等于放弃举证能力
                "tool.confirm_conflict",
                "mcp.ssrf_rejected",
                // 🔴 V1.1.2（G3 裁决）：系统主动撤销授权，与"模型请求调用被拒"语义不同
                "mcp.tool_grant_revoked",
                "mcp.connection_test",
                "mcp.credential_changed",
                "tenant.cross_probe",
                "platform.access_grant_issued",
                "platform.cache_evict");

        assertEquals(expected, AuditActions.ALL, "action 枚举必须与契约完全一致，无多无缺");
        assertEquals(12, AuditActions.ALL.size());
        assertTrue(AuditActions.isRegistered(AuditActions.PLATFORM_CACHE_EVICT));
        assertTrue(AuditActions.isRegistered(AuditActions.MCP_TOOL_GRANT_REVOKED));
        assertTrue(AuditActions.isRegistered(AuditActions.TOOL_CONFIRM_CONFLICT));
        assertFalse(AuditActions.isRegistered("tool.something_new"), "未登记 action 必须被拒绝");
        assertFalse(AuditActions.isRegistered(null));
    }

    @Test
    @DisplayName("result 取值仅 success / failed / denied")
    void resultEnum() {
        assertTrue(AuditResults.isRegistered(AuditResults.SUCCESS));
        assertTrue(AuditResults.isRegistered(AuditResults.FAILED));
        assertTrue(AuditResults.isRegistered(AuditResults.DENIED));
        assertFalse(AuditResults.isRegistered("ok"));
    }

    @Test
    @DisplayName("🔴 eventId = 32 位小写 UUID hex（无连字符），对外原样返回禁止截断")
    void eventIdFormat() {
        for (int i = 0; i < 50; i++) {
            String eventId = AuditWriter.newEventId();
            assertTrue(eventId.matches("^[0-9a-f]{32}$"), "eventId 必须匹配 ^[0-9a-f]{32}$：" + eventId);
        }
    }

    @Test
    @DisplayName("🔴 AuditLogRepository 不得暴露任何 update / delete 入口（审计不可篡改）")
    void repositoryHasNoMutationApi() {
        for (Method method : AuditLogRepository.class.getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.startsWith("delete"), "禁止出现删除方法：" + method.getName());
            assertFalse(name.startsWith("remove"), "禁止出现删除方法：" + method.getName());
            assertFalse(name.equals("saveall"), "禁止批量保存（易被误用为覆盖写）：" + method.getName());
            assertFalse(name.startsWith("flush"), "禁止暴露 flush：" + method.getName());
        }
        // 仍必须能写入
        assertTrue(java.util.Arrays.stream(AuditLogRepository.class.getMethods())
                .anyMatch(m -> "save".equals(m.getName())), "必须保留 save 入口");
    }

    @Test
    @DisplayName("🔴 AuditLog 实体的业务字段全部 updatable=false（仅追加）")
    void entityFieldsAreNotUpdatable() {
        for (java.lang.reflect.Field field : AuditLog.class.getDeclaredFields()) {
            jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
            if (column == null) {
                continue;
            }
            assertFalse(column.updatable(),
                    "审计字段禁止可更新：" + field.getName());
        }
    }

    @Test
    @DisplayName("scope 语义：platform → tenant_id 必须为 NULL；tenant → 必填")
    void scopeValues() {
        assertEquals("platform", AuditScope.PLATFORM.value());
        assertEquals("tenant", AuditScope.TENANT.value());

        AuditEvent platform = AuditEvent.platform(AuditActions.PLATFORM_CACHE_EVICT,
                AuditResults.SUCCESS, "cacheScope", "gift", "工单 OPS-1", null);
        assertEquals(AuditScope.PLATFORM, platform.scope());
        assertEquals(null, platform.tenantId());

        AuditEvent tenant = AuditEvent.tenant("gift", AuditActions.TOOL_GRANT_DENIED,
                AuditResults.DENIED, "toolCall", "1", "未授权", 30050);
        assertEquals("gift", tenant.tenantId());
        assertEquals(AuditScope.TENANT, tenant.scope());
    }
}
