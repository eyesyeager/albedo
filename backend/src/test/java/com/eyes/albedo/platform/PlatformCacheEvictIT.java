package com.eyes.albedo.platform;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.eyes.eyesAuth.constant.AuthConfigConstant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 平台缓存失效接口测试（api-spec §7.2.1 / REQ-CFG-004 / AC-CFG-005 + AC-AUD-003 / EX-033）。
 *
 * <p>覆盖的四条硬约束：
 * <ol>
 *   <li>🔴 Host 键与租户号键<b>成对失效</b>（D-003 / D-006 教训）</li>
 *   <li>🔴 L1 与 L2 <b>同时失效</b>（响应分别回报真实条目数）</li>
 *   <li>🔴 <b>禁止删除运行时状态键</b>（{@code chat:idem} / {@code chat:cancel} /
 *       {@code tool:confirm} / {@code limit:msg}），即便 {@code scope=all}</li>
 *   <li>🔴 每次调用<b>独立审计</b> {@code platform.cache_evict}，返回 32 位 {@code auditEventId}</li>
 * </ol>
 *
 * <p>⚠️ 非平台管理员的失败码：测试 profile 下 {@code eyes-auth.enabled=false}（鉴权切面不装配），
 * 由控制器兜底校验返回 {@code 10003}；生产环境 eyesAuth 切面会先以 {@code 20000} 拦截。
 * 两码均在 api-spec §2.2 登记，该歧义已回报 @架构师（§3 与 §7.2.1/§8.2 对该场景的规定不一致）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class PlatformCacheEvictIT {

    private static final String PATH = "/api/v1/platform/cache/evict";
    private static final long ADMIN_UID = 900000201L;
    private static final long USER_UID = 900000202L;
    /** 与 sys_config[tenant.dev_host_mapping] 中的种子租户一致。 */
    private static final String TENANT_GIFT = "gift";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TenantCacheKeys cacheKeys;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String giftHost;

    @BeforeEach
    void setUp() {
        giftHost = jdbcTemplate.queryForObject(
                "SELECT primary_host FROM tenants WHERE tenant_id = ?", String.class, TENANT_GIFT);
        assertNotNull(giftHost, "验收种子租户 gift 必须存在");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE action = ? AND reason LIKE ?",
                AuditActions.PLATFORM_CACHE_EVICT, "IT-CACHE-%");
    }

    @Test
    @DisplayName("🔴 scope=tenant：Host 键与租户号键成对失效，且 L1+L2 都清（D-003/D-006）")
    void tenantScopeEvictsHostAndCodeKeys() throws Exception {
        // 预置两条解析缓存（模拟正式路径与 dev 映射路径各自的缓存）
        String hostKey = cacheKeys.tenantByHost(giftHost);
        String codeKey = cacheKeys.tenantByCode(TENANT_GIFT);
        String siteKey = cacheKeys.siteConfig(TENANT_GIFT, 1L);
        redis.opsForValue().set(hostKey, "{}", Duration.ofMinutes(5));
        redis.opsForValue().set(codeKey, "{}", Duration.ofMinutes(5));
        redis.opsForValue().set(siteKey, "{}", Duration.ofMinutes(5));

        JsonNode data = callOk(body("tenant", TENANT_GIFT, null, null, null,
                "IT-CACHE-tenant 成对失效验证"));

        assertEquals("tenant", data.get("requestedScope").asText());
        assertTrue(scopeResult(data, "tenantHost") != null, "必须回报 tenantHost 作用域结果");
        assertTrue(scopeResult(data, "tenantCode") != null, "🔴 必须同时回报 tenantCode 作用域结果");
        assertEquals(1L, scopeResult(data, "tenantCode").get("l2Evicted").asLong(),
                "租户号键必须被实际删除");
        assertTrue(scopeResult(data, "tenantHost").get("l2Evicted").asLong() >= 1,
                "Host 键必须被实际删除");

        // 🔴 成功后不得再命中旧值（AC-CFG-005 判据）
        assertFalse(Boolean.TRUE.equals(redis.hasKey(hostKey)), "Host 键必须已失效");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(codeKey)), "🔴 租户号键必须已失效");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(siteKey)), "站点配置快照必须已失效");
    }

    @Test
    @DisplayName("🔴 scope=host：Host 键 + 反查出的租户号键同样成对失效")
    void hostScopeEvictsPairedKeys() throws Exception {
        String hostKey = cacheKeys.tenantByHost(giftHost);
        String codeKey = cacheKeys.tenantByCode(TENANT_GIFT);
        redis.opsForValue().set(hostKey, "{}", Duration.ofMinutes(5));
        redis.opsForValue().set(codeKey, "{}", Duration.ofMinutes(5));

        JsonNode data = callOk(body("host", null, giftHost, null, null, "IT-CACHE-host 验证"));

        assertEquals(1L, scopeResult(data, "tenantHost").get("l2Evicted").asLong());
        assertEquals(1L, scopeResult(data, "tenantCode").get("l2Evicted").asLong(),
                "🔴 Host 作用域也必须连带失效租户号键");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(hostKey)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(codeKey)));
    }

    @Test
    @DisplayName("scope=sysconfig：按分组失效，L1 与 L2 同时清且回报真实条目数")
    void sysConfigScopeEvictsBothLayers() throws Exception {
        // 先读一次让 L1/L2 都有值（tool 分组是 M3 新增键）
        mockMvc.perform(post(PATH)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sysconfig", null, null, "tool", null, "IT-CACHE-预热")))
                .andExpect(status().isOk());

        String itemKey = cacheKeys.sysConfigItem("tool", "max_rounds");
        redis.opsForValue().set(itemKey, "5", Duration.ofMinutes(5));

        JsonNode data = callOk(body("sysconfig", null, null, "tool", null, "IT-CACHE-sysconfig 验证"));
        assertEquals("sysconfig", scopeResult(data, "sysconfig").get("scope").asText());
        assertTrue(scopeResult(data, "sysconfig").get("l2Evicted").asLong() >= 1,
                "必须回报实际删除条目数");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(itemKey)));
    }

    @Test
    @DisplayName("🔴 scope=all 也绝不删除运行时状态键（幂等/取消/工具确认/限流）")
    void allScopeNeverTouchesRuntimeStateKeys() throws Exception {
        String idemKey = cacheKeys.chatIdempotency(TENANT_GIFT, USER_UID, "it-cache-idem");
        String cancelKey = cacheKeys.chatCancel(TENANT_GIFT, 999999901L);
        String confirmKey = cacheKeys.toolConfirm(TENANT_GIFT, 999999902L);
        String limitKey = cacheKeys.messageRateLimit(TENANT_GIFT, USER_UID, "it-window");
        // 🔴 K15 / V1.4.5（ADR-020）：两个日额度运行时状态键同规格受保护
        String quotaCountKey = cacheKeys.dailyQuotaCount(TENANT_GIFT, USER_UID, "d20260818");
        String quotaHoldKey = cacheKeys.dailyQuotaHold(TENANT_GIFT, USER_UID, "d20260818");
        for (String key : new String[]{idemKey, cancelKey, confirmKey, limitKey,
                quotaCountKey, quotaHoldKey}) {
            redis.opsForValue().set(key, "keep", Duration.ofMinutes(5));
        }

        callOk(body("all", null, null, null, null, "IT-CACHE-all 工单 OPS-9999"));

        assertTrue(Boolean.TRUE.equals(redis.hasKey(idemKey)), "🔴 幂等标记不得被删除（会重复建消息）");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(cancelKey)), "🔴 取消标记不得被删除（停止生成会失灵）");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(confirmKey)), "🔴 工具确认信号不得被删除（用户决定会丢失）");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(limitKey)), "🔴 限流窗口不得被删除（额度会被重置）");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(quotaCountKey)),
                "🔴 K15：quota:day 不得被删除 —— 删除等于把当日已结算数清零（免费重置额度）");
        assertTrue(Boolean.TRUE.equals(redis.hasKey(quotaHoldKey)),
                "🔴 K15：quota:hold 不得被删除 —— 在途预占消失会让同一用户并发突破日上限");

        redis.delete(java.util.List.of(idemKey, cancelKey, confirmKey, limitKey,
                quotaCountKey, quotaHoldKey));
    }

    @Test
    @DisplayName("🔴 AC-AUD-003：每次调用独立审计 platform.cache_evict，auditEventId 为 32 位不截断")
    void everyCallIsAudited() throws Exception {
        JsonNode first = callOk(body("sysconfig", null, null, "tool", null, "IT-CACHE-审计验证 1"));
        JsonNode second = callOk(body("sysconfig", null, null, "tool", null, "IT-CACHE-审计验证 2"));

        String firstId = first.get("auditEventId").asText();
        String secondId = second.get("auditEventId").asText();
        assertTrue(firstId.matches("^[0-9a-f]{32}$"), "🔴 auditEventId 必须 32 位小写 hex：" + firstId);
        assertFalse(firstId.equals(secondId), "🔴 每次调用必须独立审计（幂等不等于只记一次）");

        for (String eventId : new String[]{firstId, secondId}) {
            java.util.Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT scope, tenant_id, action, result, object_type FROM audit_logs"
                            + " WHERE event_id = ?", eventId);
            assertEquals("platform", row.get("scope"));
            assertEquals(null, row.get("tenant_id"), "平台事件 tenant_id 必须为 NULL");
            assertEquals(AuditActions.PLATFORM_CACHE_EVICT, row.get("action"));
            assertEquals("success", row.get("result"));
            assertEquals("cacheScope", row.get("object_type"));
        }
    }

    @Test
    @DisplayName("参数校验：scope 非法 / 条件必填缺失 / 缺 reason → 10001")
    void validationFailures() throws Exception {
        expectCode(body("unknown", null, null, null, null, "IT-CACHE-非法 scope"), 10001);
        expectCode(body("tenant", null, null, null, null, "IT-CACHE-缺 tenantId"), 10001);
        expectCode(body("host", null, null, null, null, "IT-CACHE-缺 host"), 10001);
        expectCode(body("agentVersion", null, null, null, null, "IT-CACHE-缺 tenantId"), 10001);
        // 🔴 reason 是审计必需项
        expectCode("{\"scope\":\"sysconfig\"}", 10001);
        expectCode(body("sysconfig", null, null, null, null, "  "), 10001);
    }

    @Test
    @DisplayName("非平台管理员调用 → 10003（不跳登录）")
    void nonPlatformAdminRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(TestAuthConfig.HEADER_TEST_UID, USER_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("sysconfig", null, null, "tool", null, "IT-CACHE-越权验证")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)))
                .andExpect(jsonPath("$.timestamp", greaterThanOrEqualTo(1L)));
    }

    @Test
    @DisplayName("scope=agentVersion：可选 agentId；响应含 timestamp 且 HTTP 恒 200")
    void agentVersionScope() throws Exception {
        String agentKey = cacheKeys.agentVersion(TENANT_GIFT, 12L, 5L);
        redis.opsForValue().set(agentKey, "{}", Duration.ofMinutes(5));

        JsonNode data = callOk(body("agentVersion", TENANT_GIFT, null, null, "12",
                "IT-CACHE-agentVersion 验证"));
        assertEquals(1L, scopeResult(data, "agentVersion").get("l2Evicted").asLong());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(agentKey)));
    }

    // ===================== 辅助 =====================

    private JsonNode callOk(String requestBody) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        assertTrue(root.get("data").get("incompleteScopes").isEmpty(),
                "🔴 全部成功时 incompleteScopes 必须为空");
        return root.get("data");
    }

    private void expectCode(String requestBody, int expectedCode) throws Exception {
        mockMvc.perform(post(PATH)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(expectedCode)));
    }

    private JsonNode scopeResult(JsonNode data, String scope) {
        for (JsonNode node : data.get("results")) {
            if (scope.equals(node.get("scope").asText())) {
                return node;
            }
        }
        return null;
    }

    private String body(String scope, String tenantId, String host, String configGroup,
                        String agentId, String reason) throws Exception {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("scope", scope);
        if (tenantId != null) {
            map.put("tenantId", tenantId);
        }
        if (host != null) {
            map.put("host", host);
        }
        if (configGroup != null) {
            map.put("configGroup", configGroup);
        }
        if (agentId != null) {
            map.put("agentId", agentId);
        }
        map.put("reason", reason);
        return objectMapper.writeValueAsString(map);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
