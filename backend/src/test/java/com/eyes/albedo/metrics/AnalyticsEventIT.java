package com.eyes.albedo.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 产品埋点上报接口测试（api-spec §7.10.1，REQ-OBS-001 / AC-OBS-001）。
 *
 * <p>覆盖：白名单过滤 / {@code clientEventId} 去重 / 租户强制补齐 / 敏感字段拦截 /
 * 批量上限 / 匿名开关 / 跨租户隔离 / {@code pagePath} 去 query。
 *
 * <p>🔴 <b>本用例最重要的两条断言</b>：
 * <ol>
 *   <li>请求体里的 {@code tenantId} <b>完全不生效</b>（服务端按 Host 强制补齐）——
 *       这是 EX-003 的防线，一旦失效就是跨租户写入；</li>
 *   <li>禁止字段命中 → <b>整条丢弃且库内查不到</b>（不是"抹掉字段后入库"）——
 *       正文一旦落库就不可逆。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class AnalyticsEventIT {

    private static final long UID = 900000071L;
    /** 白名单内的事件名（api-spec §7.10.1 默认值）。 */
    private static final String ALLOWED_EVENT = "messageSend";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private com.eyes.albedo.config.StartupChecker startupChecker;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private String otherTenantId;
    private String otherHost;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "ev" + suffix;
        host = "ev-" + suffix + ".test.invalid";
        otherTenantId = "ex" + suffix;
        otherHost = "ex-" + suffix + ".test.invalid";
        insertTenant(tenantId, host);
        insertTenant(otherTenantId, otherHost);
        override = new SysConfigOverride(jdbcTemplate, configService);
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM analytics_events WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id IN (?,?)",
                tenantId, otherTenantId);
    }

    // ===================== 基本受理 =====================

    @Test
    @DisplayName("AC-OBS-001｜登录用户上报 → accepted=1，落库含租户维度与 uid")
    void acceptsLoggedInEvent() throws Exception {
        String clientEventId = newClientEventId();
        JsonNode data = report(host, UID, "{\"events\":[" + event(clientEventId, ALLOWED_EVENT)
                + "]}");

        assertEquals(1, data.get("accepted").asInt());
        assertEquals(0, data.get("duplicated").asInt());
        assertEquals(0, data.get("discarded").asInt());
        assertEquals(1, countEvents(tenantId));
        assertEquals("logged_in", queryString(
                "SELECT login_state FROM analytics_events WHERE tenant_id = ?", tenantId));
        assertEquals(UID, (long) jdbcTemplate.queryForObject(
                "SELECT uid FROM analytics_events WHERE tenant_id = ?", Long.class, tenantId));
    }

    @Test
    @DisplayName("匿名上报（无 authorization）→ uid=NULL 且 loginState 被强制为 anonymous")
    void acceptsAnonymousEvent() throws Exception {
        // 🔴 客户端自称 logged_in 也必须被服务端改写为 anonymous（不信任客户端自述身份）
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"tenantSiteView\",\"occurredAt\":\"" + nowIso()
                + "\",\"loginState\":\"logged_in\"}]}";
        JsonNode data = report(host, null, body);

        assertEquals(1, data.get("accepted").asInt());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT uid FROM analytics_events WHERE tenant_id = ?", Long.class, tenantId));
        assertEquals("anonymous", queryString(
                "SELECT login_state FROM analytics_events WHERE tenant_id = ?", tenantId));
    }

    @Test
    @DisplayName("🔴 analytics_anonymous_enabled=false → 匿名事件全部 discarded（仍 code=0）")
    void anonymousDisabledDiscards() throws Exception {
        override.set(ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.ANALYTICS_ANONYMOUS_ENABLED,
                "false");

        JsonNode data = report(host, null, "{\"events\":["
                + event(newClientEventId(), "tenantSiteView") + "]}");

        assertEquals(0, data.get("accepted").asInt());
        assertEquals(1, data.get("discarded").asInt());
        assertEquals(0, countEvents(tenantId));
    }

    // ===================== 白名单 =====================

    @Test
    @DisplayName("🔴 §7.10.1：事件名不在白名单 → 该条 discarded，🔴 但整批不失败（其余照常接受）")
    void unknownEventNameIsDiscardedWithoutFailingBatch() throws Exception {
        String good = newClientEventId();
        String body = "{\"events\":[" + event(newClientEventId(), "notInWhitelist") + ","
                + event(good, ALLOWED_EVENT) + "]}";

        JsonNode data = report(host, UID, body);

        assertEquals(1, data.get("accepted").asInt(), "🔴 白名单外的一条不得拖累其余事件");
        assertEquals(1, data.get("discarded").asInt());
        assertEquals(1, countEvents(tenantId));
        assertEquals(good, queryString(
                "SELECT client_event_id FROM analytics_events WHERE tenant_id = ?", tenantId));
    }

    // ===================== 去重 =====================

    @Test
    @DisplayName("🔴 §1.4：同一 clientEventId 重复上报 → 静默丢弃并计入 duplicated（库内仍 1 行）")
    void duplicatedClientEventIdIsCounted() throws Exception {
        String clientEventId = newClientEventId();
        assertEquals(1, report(host, UID, "{\"events\":["
                + event(clientEventId, ALLOWED_EVENT) + "]}").get("accepted").asInt());

        JsonNode second = report(host, UID, "{\"events\":["
                + event(clientEventId, ALLOWED_EVENT) + "]}");

        assertEquals(0, second.get("accepted").asInt());
        assertEquals(1, second.get("duplicated").asInt());
        assertEquals(0, second.get("discarded").asInt(), "🔴 重复计 duplicated，不得混入 discarded");
        assertEquals(1, countEvents(tenantId));
    }

    @Test
    @DisplayName("同一批内重复的 clientEventId 也按 duplicated 处理（不撞唯一键、不整批失败）")
    void duplicatedWithinSameBatch() throws Exception {
        String clientEventId = newClientEventId();
        JsonNode data = report(host, UID, "{\"events\":[" + event(clientEventId, ALLOWED_EVENT)
                + "," + event(clientEventId, ALLOWED_EVENT) + "]}");

        assertEquals(1, data.get("accepted").asInt());
        assertEquals(1, data.get("duplicated").asInt());
        assertEquals(1, countEvents(tenantId));
    }

    // ===================== 租户隔离 =====================

    @Test
    @DisplayName("🔴 EX-003：请求体内 tenantId 一律忽略，落库租户恒为 Host 解析结果")
    void bodyTenantIdIsIgnored() throws Exception {
        String clientEventId = newClientEventId();
        String body = "{\"events\":[{\"clientEventId\":\"" + clientEventId
                + "\",\"eventName\":\"" + ALLOWED_EVENT + "\",\"occurredAt\":\"" + nowIso()
                // 🔴 恶意/错误地指定别的租户
                + "\",\"tenantId\":\"" + otherTenantId + "\"}]}";

        assertEquals(1, report(host, UID, body).get("accepted").asInt());

        assertEquals(1, countEvents(tenantId), "🔴 必须落在 Host 对应租户");
        assertEquals(0, countEvents(otherTenantId), "🔴 绝不允许写入请求体指定的租户");
    }

    @Test
    @DisplayName("🔴 跨租户不可见：同一 clientEventId 在另一租户可独立受理（uk 含 tenant_id）")
    void sameClientEventIdAcrossTenants() throws Exception {
        String clientEventId = newClientEventId();
        assertEquals(1, report(host, UID, "{\"events\":["
                + event(clientEventId, ALLOWED_EVENT) + "]}").get("accepted").asInt());
        assertEquals(1, report(otherHost, UID, "{\"events\":["
                + event(clientEventId, ALLOWED_EVENT) + "]}").get("accepted").asInt());

        assertEquals(1, countEvents(tenantId));
        assertEquals(1, countEvents(otherTenantId));
    }

    // ===================== 敏感字段 =====================

    @Test
    @DisplayName("🔴 §7.10.1：禁止字段（messageContent）命中 → 整条 discarded，库内查不到")
    void forbiddenFieldDiscardsWholeEvent() throws Exception {
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"" + ALLOWED_EVENT + "\",\"occurredAt\":\"" + nowIso()
                + "\",\"messageContent\":\"用户的真实提问正文\"}]}";

        JsonNode data = report(host, UID, body);

        assertEquals(0, data.get("accepted").asInt());
        assertEquals(1, data.get("discarded").asInt());
        assertEquals(0, countEvents(tenantId), "🔴 命中禁止字段必须整条丢弃，绝不落库");
    }

    @Test
    @DisplayName("🔴 白名单字段里塞手机号/邮箱/长数字 → 值级拦截，整条 discarded")
    void sensitiveValueInAllowedFieldDiscards() throws Exception {
        for (String value : List.of("13812345678", "user@example.com", "6222020200123456")) {
            String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                    + "\",\"eventName\":\"" + ALLOWED_EVENT + "\",\"occurredAt\":\"" + nowIso()
                    + "\",\"source\":\"" + value + "\"}]}";
            JsonNode data = report(host, UID, body);
            assertEquals(1, data.get("discarded").asInt(), "未拦截敏感取值：" + value);
        }
        assertEquals(0, countEvents(tenantId));
    }

    @Test
    @DisplayName("🔴 AC-AUTH-002：pagePath 强制去 query 与 hash（防 Token 经 URL 落库），事件仍被受理")
    void pagePathIsStripped() throws Exception {
        // 🔴 query 里带的是 JWT 形态的 Token —— SSO 回跳后 URL 里本来就会出现（PRD §6.2.2）。
        //    正确行为是**剥离后受理**，而不是把整条页面浏览埋点丢掉。
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"tenantSiteView\",\"occurredAt\":\"" + nowIso()
                + "\",\"pagePath\":\"/chat/new?authorization=abcdefghij.klmnopqrst.uvwxyz1234"
                + "#frag\"}]}";

        assertEquals(1, report(host, null, body).get("accepted").asInt(),
                "🔴 剥离 query 后应正常受理（否则所有页面浏览埋点都会被误丢）");

        String stored = queryString("SELECT page_path FROM analytics_events WHERE tenant_id = ?",
                tenantId);
        assertEquals("/chat/new", stored);
        assertTrue(!stored.contains("authorization"), "🔴 pagePath 内不得残留 Token 参数");
    }

    @Test
    @DisplayName("🔴 剥离 query 之后**路径本身**仍含敏感值（手机号）→ 整条 discarded")
    void pagePathWithSensitivePathIsDiscarded() throws Exception {
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"tenantSiteView\",\"occurredAt\":\"" + nowIso()
                + "\",\"pagePath\":\"/user/13812345678/profile\"}]}";

        assertEquals(1, report(host, null, body).get("discarded").asInt());
        assertEquals(0, countEvents(tenantId));
    }

    // ===================== 批量与参数 =====================

    @Test
    @DisplayName("🔴 §7.10.1：events 为空 → 10001；超过 analytics_batch_max → 10001")
    void batchLimits() throws Exception {
        assertEquals(ErrorCode.VALIDATION_FAILED,
                code(host, UID, "{\"events\":[]}"));

        override.set(ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.ANALYTICS_BATCH_MAX, "2");
        String body = "{\"events\":[" + event(newClientEventId(), ALLOWED_EVENT) + ","
                + event(newClientEventId(), ALLOWED_EVENT) + ","
                + event(newClientEventId(), ALLOWED_EVENT) + "]}";
        assertEquals(ErrorCode.VALIDATION_FAILED, code(host, UID, body));
        assertEquals(0, countEvents(tenantId));
    }

    @Test
    @DisplayName("缺 clientEventId / eventName → 10001（契约违约要让前端立刻发现）")
    void missingRequiredFields() throws Exception {
        assertEquals(ErrorCode.VALIDATION_FAILED, code(host, UID,
                "{\"events\":[{\"eventName\":\"" + ALLOWED_EVENT + "\",\"occurredAt\":\""
                        + nowIso() + "\"}]}"));
        assertEquals(ErrorCode.VALIDATION_FAILED, code(host, UID,
                "{\"events\":[{\"clientEventId\":\"" + newClientEventId() + "\",\"occurredAt\":\""
                        + nowIso() + "\"}]}"));
    }

    @Test
    @DisplayName("occurredAt 与服务端偏差 >24h → 整条 discarded（不报错）")
    void clockSkewDiscards() throws Exception {
        String stale = Instant.now().minus(48, ChronoUnit.HOURS).toString();
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"" + ALLOWED_EVENT + "\",\"occurredAt\":\"" + stale + "\"}]}";

        JsonNode data = report(host, UID, body);

        assertEquals(0, data.get("accepted").asInt());
        assertEquals(1, data.get("discarded").asInt());
    }

    @Test
    @DisplayName("🔴 errorCode 未登记 → 置空（禁止把前端自造码存进统计口径）")
    void unregisteredErrorCodeIsNulled() throws Exception {
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"toolCallResult\",\"occurredAt\":\"" + nowIso()
                + "\",\"errorCode\":-1}]}";

        assertEquals(1, report(host, UID, body).get("accepted").asInt());

        assertNull(jdbcTemplate.queryForObject(
                "SELECT error_code FROM analytics_events WHERE tenant_id = ?", Integer.class,
                tenantId));
    }

    @Test
    @DisplayName("已登记 errorCode（30050）→ 原样保留")
    void registeredErrorCodeIsKept() throws Exception {
        String body = "{\"events\":[{\"clientEventId\":\"" + newClientEventId()
                + "\",\"eventName\":\"toolCallResult\",\"occurredAt\":\"" + nowIso()
                + "\",\"errorCode\":" + ErrorCode.TOOL_DENIED + "}]}";

        assertEquals(1, report(host, UID, body).get("accepted").asInt());

        assertEquals(ErrorCode.TOOL_DENIED, (int) jdbcTemplate.queryForObject(
                "SELECT error_code FROM analytics_events WHERE tenant_id = ?", Integer.class,
                tenantId));
    }

    // ===================== 🔴 开关与采样率的 fail-closed（V1.1.4 #1） =====================

    @Test
    @DisplayName("🔴 E6：analytics_enabled=false → 全部丢弃且 accepted=0（仍 code=0）")
    void analyticsDisabledDiscardsAll() throws Exception {
        override.set(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED, "false");

        JsonNode data = report(host, UID, "{\"events\":[" + event(newClientEventId(), ALLOWED_EVENT)
                + "," + event(newClientEventId(), ALLOWED_EVENT) + "]}");

        assertEquals(0, data.get("accepted").asInt());
        assertEquals(2, data.get("discarded").asInt());
        assertEquals(0, countEvents(tenantId));
    }

    @Test
    @DisplayName("🔴 E6 反向断言：analytics_enabled 被清空 / 写坏 → **全部丢弃**（🔴 不得全量接收）")
    void analyticsEnabledUnreadableFailsClosed() throws Exception {
        // ⓐ 值被清空（等价于"配置行被误删"）
        override.set(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED, "");
        JsonNode blank = report(host, UID, "{\"events\":["
                + event(newClientEventId(), ALLOWED_EVENT) + "]}");
        assertEquals(0, blank.get("accepted").asInt(),
                "🔴 fail-closed：配置缺失时**绝不允许**转为全量采集（隐私损失不可撤销）");
        assertEquals(1, blank.get("discarded").asInt());

        // ⓑ 值不可解析
        override.set(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED, "yes-please");
        JsonNode broken = report(host, UID, "{\"events\":["
                + event(newClientEventId(), ALLOWED_EVENT) + "]}");
        assertEquals(0, broken.get("accepted").asInt());
        assertEquals(1, broken.get("discarded").asInt());

        assertEquals(0, countEvents(tenantId), "🔴 fail-closed 期间一条都不许落库");
    }

    @Test
    @DisplayName("🔴 E6：analytics_sample_rate 越界 / 不可解析 → 采样率按 0.0 处理，全部丢弃")
    void sampleRateInvalidFailsClosed() throws Exception {
        for (String value : List.of("1.5", "-0.2", "abc", "")) {
            override.set(ConfigKeys.GROUP_OBSERVABILITY,
                    ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE, value);
            JsonNode data = report(host, UID, "{\"events\":["
                    + event(newClientEventId(), ALLOWED_EVENT) + "]}");
            assertEquals(0, data.get("accepted").asInt(),
                    "🔴 越界采样率必须按 0.0（全丢）处理，禁止夹取为全量：sample_rate=" + value);
            assertEquals(1, data.get("discarded").asInt());
        }
        assertEquals(0, countEvents(tenantId));
    }

    @Test
    @DisplayName("🔴 E7：analytics_sample_rate 越界 / 不可解析 → StartupChecker **拒绝启动**（非 WARN）")
    void startupFailsOnOutOfRangeSampleRate() {
        for (String value : List.of("1.5", "-0.2", "abc")) {
            override.set(ConfigKeys.GROUP_OBSERVABILITY,
                    ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE, value);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> startupChecker.run(null),
                    "🔴 采样率 " + value + " 没有任何合法语义，必须启动即失败"
                            + "（⚠️ 与 chat.system_prompt_max_chars 的 WARN 规格有意不同）");
            assertTrue(error.getMessage().contains(ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE),
                    "启动失败原因必须点名具体配置键：" + error.getMessage());
        }
    }

    @Test
    @DisplayName("🔴 E5：埋点两键缺失（值为空）→ StartupChecker 判为缺键并**拒绝启动**")
    void startupFailsWhenAnalyticsKeysMissing() {
        override.set(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED, "");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> startupChecker.run(null));
        assertTrue(error.getMessage().contains("observability.analytics_enabled"),
                "🔴 两键已纳入 REQUIRED_CONFIG，缺失必须启动失败：" + error.getMessage());
    }

    // ===================== 辅助 =====================

    private String event(String clientEventId, String eventName) {
        return "{\"clientEventId\":\"" + clientEventId + "\",\"eventName\":\"" + eventName
                + "\",\"occurredAt\":\"" + nowIso() + "\"}";
    }

    private String nowIso() {
        return Instant.now().toString();
    }

    private String newClientEventId() {
        return "ce" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private JsonNode report(String targetHost, Long uid, String body) throws Exception {
        JsonNode response = objectMapper.readTree(bodyOf(perform(targetHost, uid, body)));
        assertEquals(0, response.get("code").asInt(),
                "🔴 埋点接口一律 code=0（永不影响主流程）：" + response);
        return response.path("data");
    }

    private int code(String targetHost, Long uid, String body) throws Exception {
        return objectMapper.readTree(bodyOf(perform(targetHost, uid, body))).get("code").asInt();
    }

    private MvcResult perform(String targetHost, Long uid, String body) throws Exception {
        var request = post("/api/v1/events")
                .header(HttpHeaders.HOST, targetHost)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (uid != null) {
            request = request.header(TestAuthConfig.HEADER_TEST_UID, uid);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn();
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private int countEvents(String targetTenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_events WHERE tenant_id = ?", Integer.class,
                targetTenantId);
        return count == null ? 0 : count;
    }

    private String queryString(String sql, Object... args) {
        List<String> values = jdbcTemplate.queryForList(sql, String.class, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private void insertTenant(String id, String tenantHost) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                id, "埋点测试租户", tenantHost);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", tenantHost, id);
    }
}
