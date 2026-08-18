package com.eyes.albedo.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审计写入测试（REQ-AUD-001 / AC-AUD-001 / AC-AUD-002 / AR-013 / ADR-010）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>🔴 AR-013：{@code scope=tenant} 漏填 {@code tenantId} 必须抛异常（平台表无 discriminator 保护）</li>
 *   <li>🔴 {@code scope=platform} 混入 {@code tenantId} 同样拒绝（防污染租户维度统计）</li>
 *   <li>action / result 白名单校验</li>
 *   <li>eventId = 32 位小写 hex，且落库值与返回值一致（对外原样返回、禁止截断）</li>
 *   <li>🔴 六类禁记项在写入前被脱敏（AC-AUD-002）</li>
 *   <li>🔴 ADR-010 事务边界：{@code write} 随调用方回滚；{@code writeInNewTransaction} 独立提交</li>
 * </ul>
 */
@SpringBootTest
class AuditWriterIT {

    private static final String TEST_TENANT = "it-audit-tenant";

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        // DELETE 必带 WHERE，且只清本测试写入的租户维度数据
        jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", TEST_TENANT);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE object_type = ?", "itAuditPlatform");
    }

    @Test
    @DisplayName("🔴 AR-013：scope=tenant 漏填 tenantId → 抛 AuditWriteException（绝不静默写入）")
    void tenantScopeRequiresTenantId() {
        AuditEvent missing = new AuditEvent(AuditScope.TENANT, null,
                AuditActions.TOOL_GRANT_DENIED, AuditResults.DENIED,
                "toolCall", "1", "", "", "未授权", 30050);

        AuditWriteException e = assertThrows(AuditWriteException.class,
                () -> auditWriter.write(missing, AuditContext.system()));
        assertTrue(e.getMessage().contains("tenantId"));

        AuditEvent blank = new AuditEvent(AuditScope.TENANT, "  ",
                AuditActions.TOOL_GRANT_DENIED, AuditResults.DENIED,
                "toolCall", "1", "", "", "未授权", 30050);
        assertThrows(AuditWriteException.class, () -> auditWriter.write(blank, AuditContext.system()));
    }

    @Test
    @DisplayName("🔴 scope=platform 混入 tenantId → 拒绝（平台表不得混入非空 tenant_id）")
    void platformScopeRejectsTenantId() {
        AuditEvent polluted = new AuditEvent(AuditScope.PLATFORM, TEST_TENANT,
                AuditActions.PLATFORM_CACHE_EVICT, AuditResults.SUCCESS,
                "cacheScope", "gift", "", "", "工单 OPS-1", null);
        assertThrows(AuditWriteException.class,
                () -> auditWriter.write(polluted, AuditContext.system()));
    }

    @Test
    @DisplayName("未登记的 action / 非法 result → 拒绝写入（防拼写漂移导致断言不到事件）")
    void unregisteredActionRejected() {
        AuditEvent badAction = AuditEvent.tenant(TEST_TENANT, "tool.made_up",
                AuditResults.DENIED, "toolCall", "1", "x", null);
        assertThrows(AuditWriteException.class,
                () -> auditWriter.write(badAction, AuditContext.system()));

        AuditEvent badResult = AuditEvent.tenant(TEST_TENANT, AuditActions.TOOL_CONFIRM_DENIED,
                "rejected", "toolCall", "1", "x", null);
        assertThrows(AuditWriteException.class,
                () -> auditWriter.write(badResult, AuditContext.system()));
    }

    @Test
    @DisplayName("租户事件落库：eventId 32 位小写 hex，scope/tenant_id/action 正确")
    void tenantEventPersisted() {
        String eventId = transactionTemplate.execute(status -> auditWriter.write(
                AuditEvent.tenant(TEST_TENANT, AuditActions.TOOL_CONFIRM_DENIED,
                        AuditResults.DENIED, "toolCall", "9001", "用户拒绝", 30050),
                new AuditContext("req-it-1", AuditActorTypes.END_USER, 900000101L,
                        "203.0.113.45", "Mozilla/5.0")));

        assertNotNull(eventId);
        assertTrue(eventId.matches("^[0-9a-f]{32}$"), "eventId 必须是 32 位小写 hex：" + eventId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM audit_logs WHERE event_id = ?", eventId);
        assertEquals("tenant", row.get("scope"));
        assertEquals(TEST_TENANT, row.get("tenant_id"));
        assertEquals(AuditActions.TOOL_CONFIRM_DENIED, row.get("action"));
        assertEquals(AuditResults.DENIED, row.get("result"));
        assertEquals("req-it-1", row.get("request_id"));
        assertEquals(900000101L, ((Number) row.get("actor_id")).longValue());
        assertEquals(30050, ((Number) row.get("error_code")).intValue());
        // IP 必须按 /24 截断入库
        assertEquals("203.0.113.0/24", row.get("ip"));
        assertNotNull(row.get("occurred_at"));

        // 租户维度查询（🔴 手写 tenant_id 条件）可查到
        assertEquals(1L, auditLogRepository.countTenantEvents(TEST_TENANT,
                AuditActions.TOOL_CONFIRM_DENIED));
        assertTrue(auditLogRepository.findByEventId(eventId).isPresent());
    }

    @Test
    @DisplayName("🔴 AC-AUD-002：六类禁记项在写入前被脱敏（凭据键名 → redact；摘要形态强制化）")
    void forbiddenContentIsSanitizedBeforeInsert() {
        String eventId = transactionTemplate.execute(status -> auditWriter.write(
                new AuditEvent(AuditScope.TENANT, TEST_TENANT,
                        AuditActions.MCP_CREDENTIAL_CHANGED, AuditResults.SUCCESS,
                        "mcpServer", "12",
                        "sk-live-plaintext-before", "sk-live-plaintext-after",
                        "credential=sk-live-should-not-be-logged", null),
                AuditContext.system("req-it-2")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT reason, before_digest, after_digest FROM audit_logs WHERE event_id = ?",
                eventId);
        assertEquals(AuditSanitizer.REDACTED, row.get("reason"), "🔴 命中禁记键名必须整体 redact");
        assertFalse(String.valueOf(row.get("before_digest")).contains("sk-live"),
                "🔴 疑似明文必须被摘要化");
        assertFalse(String.valueOf(row.get("after_digest")).contains("sk-live"));
        assertEquals(AuditDigest.digest("sk-live-plaintext-before"), row.get("before_digest"));
    }

    @Test
    @DisplayName("🔴 ADR-010 非流式：write 加入调用方事务 —— 业务回滚时审计一并回滚")
    void writeJoinsCallerTransaction() {
        String eventId = AuditWriter.newEventId();
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            auditWriter.write(AuditEvent.tenant(TEST_TENANT, AuditActions.PLATFORM_ACCESS_GRANT_ISSUED,
                            AuditResults.SUCCESS, "itAuditRollback", eventId, "回滚验证", null),
                    AuditContext.system());
            // 模拟业务动作失败：整体回滚（EX-024 的镜像 —— 审计不能独立留存）
            throw new IllegalStateException("业务失败");
        }));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ? AND object_type = ?",
                Long.class, TEST_TENANT, "itAuditRollback");
        assertEquals(0L, count, "🔴 审计必须与业务同事务回滚，不允许留下孤立记录");
    }

    @Test
    @DisplayName("🔴 ADR-010 流式内：writeInNewTransaction 独立短事务 —— 外层回滚不影响审计")
    void writeInNewTransactionCommitsIndependently() {
        assertThrows(IllegalStateException.class, () -> transactionTemplate.execute(status -> {
            auditWriter.writeInNewTransaction(
                    AuditEvent.tenant(TEST_TENANT, AuditActions.TOOL_GRANT_DENIED,
                            AuditResults.DENIED, "itAuditShortTx", "9002", "未授权", 30050),
                    AuditContext.system("req-it-3"));
            throw new IllegalStateException("生成后续失败");
        }));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE tenant_id = ? AND object_type = ?",
                Long.class, TEST_TENANT, "itAuditShortTx");
        assertEquals(1L, count,
                "🔴 流式内安全事件必须独立提交：审计不可篡改，且不能被后续生成失败抹掉");
    }

    @Test
    @DisplayName("平台事件落库：tenant_id 为 NULL，可按平台维度查询")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void platformEventPersisted() {
        String eventId = transactionTemplate.execute(status -> auditWriter.write(
                AuditEvent.platform(AuditActions.PLATFORM_CACHE_EVICT, AuditResults.SUCCESS,
                        "itAuditPlatform", "*", "工单 OPS-2026", null),
                AuditContext.system("req-it-4")));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT scope, tenant_id FROM audit_logs WHERE event_id = ?", eventId);
        assertEquals("platform", row.get("scope"));
        assertEquals(null, row.get("tenant_id"), "🔴 平台事件 tenant_id 必须为 NULL");
    }
}
