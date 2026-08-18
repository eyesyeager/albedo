package com.eyes.albedo.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.eyes.albedo.quota.dto.QuotaSnapshotDTO;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/v1/me/quota} 与配置分层（🔴 K11 / K12 / K13 / K14，api-spec §7.15.1/2/5/6）。
 *
 * <p>数据纪律：每个用例一个<b>临时租户</b> + 独立 uid 段，{@code @AfterEach} 精确清理；
 * {@code sys_config} 只做<b>临时覆盖并还原</b>（{@link SysConfigOverride}）。
 *
 * <p>🔴 <b>平台默认刻意覆盖为非库内默认值</b>（{@code daily_quota_limit=7}）：
 * 这样"实现里若写了 50 兜底"就会立刻被断言抓住，而不是因为库里恰好也是 50 而蒙对。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class UserQuotaIT {

    private static final String PATH = "/api/v1/me/quota";
    private static final long UID = 900000401L;
    private static final long OTHER_UID = 900000402L;
    /** 🔴 平台默认覆盖值（非 50）：证明代码内无阈值兜底。 */
    private static final String PLATFORM_DAILY_LIMIT = "7";
    /** 🔴 api-spec §7.15.2 的 9 个键，逐字照抄。 */
    private static final Set<String> SNAPSHOT_KEYS = Set.of(
            "enabled", "limit", "used", "remaining", "status",
            "periodStart", "resetsAt", "timezone", "asOf");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TenantCacheKeys cacheKeys;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private String otherTenantId;
    private String otherHost;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "qa" + suffix;
        host = "qa-" + suffix + ".test.invalid";
        otherTenantId = "qb" + suffix;
        otherHost = "qb-" + suffix + ".test.invalid";
        insertTenant(tenantId, host, "Asia/Shanghai");
        insertTenant(otherTenantId, otherHost, "Asia/Shanghai");
        override = new SysConfigOverride(jdbcTemplate, configService);
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, PLATFORM_DAILY_LIMIT);
    }

    @AfterEach
    void tearDown() {
        override.restore();
        for (String tenant : new String[]{tenantId, otherTenantId}) {
            jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_quota_policies WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenant);
            for (long uid : new long[]{UID, OTHER_UID}) {
                // 🔴 运行时状态键由测试自行精确清理（缓存失效接口禁止删它们）
                redis.keys(cacheKeys.dailyQuotaCount(tenant, uid, "*")).forEach(redis::delete);
                redis.keys(cacheKeys.dailyQuotaHold(tenant, uid, "*")).forEach(redis::delete);
            }
        }
    }

    // ===================== K13：快照形状 =====================

    @Test
    @DisplayName("🔴 K13：data 键集合**恰 9 项**、不含 QPM 阈值、时间为 ISO-8601 UTC + IANA 时区")
    void snapshotHasExactlyNineKeys() throws Exception {
        JsonNode data = okData(host, UID);

        assertEquals(SNAPSHOT_KEYS, keysOf(data), "🔴 多一键或少一键均为缺陷");
        assertTrue(data.get("enabled").asBoolean());
        assertEquals(7, data.get("limit").asInt(), "🔴 必须来自 sys_config（本用例覆盖为 7）");
        assertEquals(0, data.get("used").asInt());
        assertEquals(7, data.get("remaining").asInt());
        assertEquals(QuotaSnapshotDTO.STATUS_AVAILABLE, data.get("status").asText());
        assertEquals("Asia/Shanghai", data.get("timezone").asText());
        for (String field : new String[]{"periodStart", "resetsAt", "asOf"}) {
            assertTrue(data.get(field).asText().endsWith("Z"),
                    "🔴 " + field + " 必须是 ISO-8601 UTC：" + data.get(field).asText());
        }
        assertTrue(Instant.parse(data.get("resetsAt").asText())
                        .isAfter(Instant.parse(data.get("periodStart").asText())),
                "🔴 resetsAt 必须晚于 periodStart");
        // 🔴 反向断言：绝不下发限流阈值与秒级重试提示
        assertFalse(keysOf(data).contains("qpmLimit"));
        assertFalse(keysOf(data).contains("retryAfterSeconds"));
    }

    @Test
    @DisplayName("🔴 K13：日窗口按**租户当地**日期计算（periodStart 为当地 00:00 对应的 UTC 时刻）")
    void windowFollowsTenantTimezone() throws Exception {
        JsonNode data = okData(host, UID);

        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate localDate = Instant.parse(data.get("asOf").asText()).atZone(zone).toLocalDate();
        assertEquals(localDate.atStartOfDay(zone).toInstant(),
                Instant.parse(data.get("periodStart").asText()));
        assertEquals(localDate.plusDays(1).atStartOfDay(zone).toInstant(),
                Instant.parse(data.get("resetsAt").asText()));
    }

    @Test
    @DisplayName("🔴 K13 / K16：used 取自账本（DB 权威），remaining 相应递减")
    void usedComesFromLedger() throws Exception {
        insertUsage(tenantId, UID, 3);

        JsonNode data = okData(host, UID);

        assertEquals(3, data.get("used").asInt(), "🔴 used 的唯一权威是 user_daily_quota_usages");
        assertEquals(4, data.get("remaining").asInt());
        assertEquals(QuotaSnapshotDTO.STATUS_AVAILABLE, data.get("status").asText());
    }

    @Test
    @DisplayName("🔴 K13：用尽态 used=limit / remaining=0 / status=exhausted（静止态取样）")
    void exhaustedSnapshot() throws Exception {
        insertUsage(tenantId, UID, 7);

        JsonNode data = okData(host, UID);

        assertEquals(7, data.get("used").asInt());
        assertEquals(0, data.get("remaining").asInt());
        assertEquals(QuotaSnapshotDTO.STATUS_EXHAUSTED, data.get("status").asText());
    }

    @Test
    @DisplayName("🔴 K13：daily_quota_enabled=false → unlimited + limit/remaining 为 null，"
            + "但 used 仍是**真实**已结算数（不伪造 0）")
    void unlimitedSnapshotKeepsRealUsed() throws Exception {
        insertUsage(tenantId, UID, 4);
        insertPolicy(tenantId, null, null, false, null, Instant.now().minusSeconds(60));

        JsonNode data = okData(host, UID);

        assertEquals(SNAPSHOT_KEYS, keysOf(data), "🔴 null 值的键必须仍然存在（恰 9 键）");
        assertFalse(data.get("enabled").asBoolean());
        assertTrue(data.get("limit").isNull(), "🔴 禁止伪造数值上限");
        assertTrue(data.get("remaining").isNull());
        assertEquals(QuotaSnapshotDTO.STATUS_UNLIMITED, data.get("status").asText());
        assertEquals(4, data.get("used").asInt(), "🔴 禁止把真实已结算数伪造成 0");
    }

    // ===================== K14：配置分层与即时生效 =====================

    @Test
    @DisplayName("🔴 K14：租户覆盖**无需重启、无需失效缓存**，下一次查询即生效（直读 MySQL）")
    void tenantOverrideTakesEffectImmediately() throws Exception {
        assertEquals(7, okData(host, UID).get("limit").asInt());

        insertPolicy(tenantId, null, null, null, 2, Instant.now().minusSeconds(60));

        assertEquals(2, okData(host, UID).get("limit").asInt(),
                "🔴 零缓存 → 改库即生效（AC-QUOTA-015 免实现）");
    }

    @Test
    @DisplayName("🔴 K14：effective_at 为**未来**的行对当前查询完全无效（读取时过滤，无定时任务）")
    void futureRowHasNoEffect() throws Exception {
        insertPolicy(tenantId, null, null, null, 1, Instant.now().plus(1, ChronoUnit.DAYS));

        assertEquals(7, okData(host, UID).get("limit").asInt());
    }

    @Test
    @DisplayName("🔴 K14：取**最新一行**（effective_at desc）——后插入的生效行覆盖较早的")
    void latestEffectiveRowWins() throws Exception {
        insertPolicy(tenantId, null, null, null, 2, Instant.now().minus(2, ChronoUnit.DAYS));
        insertPolicy(tenantId, null, null, null, 5, Instant.now().minus(1, ChronoUnit.DAYS));

        assertEquals(5, okData(host, UID).get("limit").asInt());
    }

    @Test
    @DisplayName("🔴 K14：撤销覆盖 = 插入一条该列为 NULL 的**新行**（不是删历史行）→ 回到平台默认")
    void nullColumnInNewerRowRestoresPlatformDefault() throws Exception {
        insertPolicy(tenantId, null, null, null, 2, Instant.now().minus(2, ChronoUnit.DAYS));
        insertPolicy(tenantId, null, null, null, null, Instant.now().minus(1, ChronoUnit.DAYS));

        assertEquals(7, okData(host, UID).get("limit").asInt(),
                "🔴 NULL = 未覆盖 → 继承平台默认");
    }

    @Test
    @DisplayName("🔴 K14：当日下调 limit ≤ used → 立即 exhausted；上调后按新总量恢复且 used 未被重算")
    void loweringLimitBelowUsedYieldsExhausted() throws Exception {
        insertUsage(tenantId, UID, 5);
        insertPolicy(tenantId, null, null, null, 3, Instant.now().minus(2, ChronoUnit.DAYS));

        JsonNode lowered = okData(host, UID);
        assertEquals(5, lowered.get("used").asInt(), "🔴 历史 used 不重算、不清零");
        assertEquals(0, lowered.get("remaining").asInt(), "🔴 remaining 取 max(...,0)，不为负");
        assertEquals(QuotaSnapshotDTO.STATUS_EXHAUSTED, lowered.get("status").asText());

        insertPolicy(tenantId, null, null, null, 10, Instant.now().minusSeconds(30));
        JsonNode raised = okData(host, UID);
        assertEquals(5, raised.get("used").asInt());
        assertEquals(5, raised.get("remaining").asInt());
        assertEquals(QuotaSnapshotDTO.STATUS_AVAILABLE, raised.get("status").asText());
    }

    // ===================== K11：fail-closed =====================

    @Test
    @DisplayName("🔴 K11：租户覆盖非法（daily_quota_limit=0）→ 50003，且响应不含表名 / 键名 / 取值")
    void illegalOverrideFailsClosed() throws Exception {
        insertPolicy(tenantId, null, null, null, 0, Instant.now().minusSeconds(60));

        JsonNode body = call(host, UID);

        assertEquals(50003, body.get("code").asInt(),
                "🔴 非法覆盖必须暴露，禁止静默继承平台默认");
        String message = body.get("message").asText();
        assertFalse(message.contains("tenant_quota_policies") || message.contains("daily_quota_limit")
                        || message.contains("sys_config"),
                "🔴 错误响应不得泄露表名 / 键名 / 内部取值：" + message);
    }

    @Test
    @DisplayName("🔴 K11：租户 timezone 非法 → 50003，**不回落 UTC**（静默错误比失败更糟）")
    void illegalTimezoneFailsClosed() throws Exception {
        jdbcTemplate.update("UPDATE tenants SET timezone = 'Asia/Atlantis' WHERE tenant_id = ?",
                tenantId);

        JsonNode body = call(host, UID);

        assertEquals(50003, body.get("code").asInt());
        assertFalse(body.get("message").asText().contains("Atlantis"),
                "🔴 不得回显非法配置取值：" + body.get("message").asText());
    }

    // ===================== K12：接口与隔离 =====================

    @Test
    @DisplayName("🔴 K12：同一 uid 在两个租户的 used / limit **完全独立**")
    void quotaIsIndependentPerTenant() throws Exception {
        insertUsage(tenantId, UID, 6);
        insertPolicy(otherTenantId, null, null, null, 3, Instant.now().minusSeconds(60));

        JsonNode first = okData(host, UID);
        JsonNode second = okData(otherHost, UID);

        assertEquals(6, first.get("used").asInt());
        assertEquals(7, first.get("limit").asInt());
        assertEquals(0, second.get("used").asInt(), "🔴 另一租户的账本必须是独立的一行");
        assertEquals(3, second.get("limit").asInt(), "🔴 策略也按租户独立解析");
    }

    @Test
    @DisplayName("🔴 K12：同租户不同 uid 互不可见（账本按 uid 独立）")
    void quotaIsIndependentPerUser() throws Exception {
        insertUsage(tenantId, UID, 6);

        assertEquals(6, okData(host, UID).get("used").asInt());
        assertEquals(0, okData(host, OTHER_UID).get("used").asInt());
    }

    @Test
    @DisplayName("🔴 K12：接口**零参数** —— 构造 ?tenantId= / ?uid= 一律被忽略，读不到他人额度")
    void requestParametersAreIgnored() throws Exception {
        insertUsage(tenantId, UID, 6);
        insertUsage(tenantId, OTHER_UID, 1);

        MvcResult result = mockMvc.perform(get(PATH)
                        .param("tenantId", otherTenantId)
                        .param("uid", String.valueOf(OTHER_UID))
                        .param("date", "2026-01-01")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(bodyOf(result)).path("data");

        assertEquals(6, data.get("used").asInt(),
                "🔴 必须返回**认证 uid + 可信 Host** 对应的快照，参数一律忽略（EX-003）");
        assertEquals("Asia/Shanghai", data.get("timezone").asText());
    }

    @Test
    @DisplayName("🔴 K12：匿名调用绝不 code=0 —— 不返回任何\"unlimited\"假响应")
    void anonymousIsRejected() throws Exception {
        MvcResult result = mockMvc.perform(get(PATH).header(HttpHeaders.HOST, host))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(bodyOf(result));

        assertNotEquals(0, body.get("code").asInt(),
                "🔴 匿名必须被拒（真实环境 20001/20002；test profile 因鉴权切面未装配走程序化兜底 10003，"
                        + "共同不变量 = 绝不 code=0，判据同 api-spec §3 的 profile 二分）");
        assertTrue(body.get("data").isNull(), "🔴 不得返回任何额度数值");
    }

    // ===================== 辅助 =====================

    private JsonNode okData(String tenantHost, long uid) throws Exception {
        JsonNode body = call(tenantHost, uid);
        assertEquals(0, body.get("code").asInt(), "响应体：" + body);
        return body.path("data");
    }

    private JsonNode call(String tenantHost, long uid) throws Exception {
        MvcResult result = mockMvc.perform(get(PATH)
                        .header(HttpHeaders.HOST, tenantHost)
                        .header(TestAuthConfig.HEADER_TEST_UID, uid))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(bodyOf(result));
    }

    private Set<String> keysOf(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertTenant(String tenant, String tenantHost, String timezone) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version) VALUES (?,?,?,'enabled',?,'zh-CN',0,0)",
                tenant, "额度测试租户", tenantHost, timezone);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", tenantHost, tenant);
    }

    /**
     * 直接写账本（模拟"已结算 N 次"）。
     *
     * <p>🔴 {@code quota_date} 必须是<b>租户当地</b>日历日，否则查询会命中不到本行 ——
     * 这本身就是"窗口按租户时区计算"的一条隐式断言。
     */
    private void insertUsage(String tenant, long uid, int settled) {
        ZoneId zone = ZoneId.of(jdbcTemplate.queryForObject(
                "SELECT timezone FROM tenants WHERE tenant_id = ?", String.class, tenant));
        LocalDate localDate = Instant.now().atZone(zone).toLocalDate();
        jdbcTemplate.update("INSERT INTO user_daily_quota_usages (tenant_id, uid, quota_date,"
                        + " timezone, period_start_at, resets_at, settled_count, first_settled_at,"
                        + " last_settled_at) VALUES (?,?,?,?,?,?,?,?,?)",
                tenant, uid, localDate, zone.getId(),
                java.sql.Timestamp.from(localDate.atStartOfDay(zone).toInstant()),
                java.sql.Timestamp.from(localDate.plusDays(1).atStartOfDay(zone).toInstant()),
                settled, java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
    }

    private void insertPolicy(String tenant, Boolean qpmEnabled, Integer qpmLimit,
                              Boolean dailyEnabled, Integer dailyLimit, Instant effectiveAt) {
        jdbcTemplate.update("INSERT INTO tenant_quota_policies (tenant_id, qpm_enabled, qpm_limit,"
                        + " daily_quota_enabled, daily_quota_limit, effective_at, note, version)"
                        + " VALUES (?,?,?,?,?,?,'IT',0)",
                tenant, qpmEnabled, qpmLimit, dailyEnabled, dailyLimit,
                java.sql.Timestamp.from(effectiveAt));
    }
}
