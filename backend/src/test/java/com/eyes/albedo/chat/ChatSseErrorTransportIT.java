package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantResolver;
import com.eyes.albedo.testsupport.SseRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.eyes.albedo.support.TestAuthConfig;

/**
 * 🔴🔴 <b>SSE 端点「建流前失败」的真实 HTTP 报文形态</b>
 * （api-spec §8.3 <b>L2 / L3 / L4</b>，§1.2.1 传输层不变量，architecture ADR-021 / §9.3.1 / AR-029）。
 *
 * <p><b>被验收的缺陷（test-report V5.0 BUG-QUOTA-001，已复现 + 日志确证）</b>：
 * <pre>
 * 同一请求，唯一差异是 Accept 头：
 *   不带 Accept: text/event-stream        → ✅ 200 + {"code":10005,...,"data":{"retryAfterSeconds":33}}
 *   带 Accept: text/event-stream（浏览器） → ❌ HTTP/1.1 500 + Content-Length: 0（空体）
 * </pre>
 * 根因在<b>写出阶段</b>：{@code GlobalExceptionHandler} 直接返回 {@code Result} 时会执行内容协商，
 * {@code {text/event-stream}} ∩ {@code {application/json,…}} = ∅ →
 * {@code HttpMediaTypeNotAcceptableException} → 解析链耗尽 → Tomcat 转 {@code /error} →
 * {@code BasicErrorController} 对同一 {@code Accept} <b>二次协商同样失败</b> → 500 + 空体。
 *
 * <p>🔴 <b>为什么必须是真实 HTTP 栈，不能由 MockMvc 替代（L2 的不可替代性 / AR-029 ①）</b>：
 * 「500 + 空体」是 <b>Tomcat + {@code BasicErrorController} 二次协商</b>的产物，
 * MockMvc <b>物理上观测不到</b>（它不经过容器的 {@code /error} 分派，只会把原始异常抛给调用方）。
 * 因此凡断言对象是 <b>HTTP 报文形态</b>（状态码 / {@code Content-Type} / 是否空体 / 键集合），
 * MockMvc 与前端 E2E 桩<b>一律不构成证据</b>。
 *
 * <p>覆盖矩阵（🔴 {@code 错误码 × Accept} 全组合逐一比对）：
 * <table border="1">
 *   <caption>覆盖矩阵</caption>
 *   <tr><th>错误码</th><th>触发方式</th><th>判据</th></tr>
 *   <tr><td>{@code 10005}（限流）</td><td>预置分钟窗计数 &gt; QPM 阈值</td><td>L2：四字段齐备 +
 *       {@code data.retryAfterSeconds ≥ 1}</td></tr>
 *   <tr><td>{@code 10001}（缺 {@code Idempotency-Key}）</td><td>刻意不带该头</td>
 *       <td>🔴 L3：<b>非限流码</b>，证明修复在 advice 层<b>全局</b>生效</td></tr>
 * </table>
 * 三种 {@code Accept}（通配 / {@code application/json} / {@code text/event-stream}）逐一比对
 * <b>状态码 + Content-Type + body 键集合 + code</b>（L4），并反向断言<b>绝不</b>出现
 * 406 / 415 / 5xx / 空体。
 *
 * <p>🔴 <b>为什么用"预置 Redis 分钟窗计数"触发限流</b>：本类的验收对象是<b>传输层形态</b>，
 * 不是限流算法（后者由 {@code QuotaAdmissionIT} / {@code MessageRateLimiterIT} 精确覆盖）。
 * 预置计数让用例<b>无需真的跑一次生成</b>（不调模型、不建消息、不落额度账本），
 * 因此既快又完全确定 —— 🔴 同时预置<b>当前分钟与下一分钟</b>两个窗口键，
 * 消除"请求恰好跨过分钟边界"这一唯一不确定性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestAuthConfig.class)
class ChatSseErrorTransportIT {

    private static final long UID = 900000481L;

    /** 🔴 L4 的三种 Accept 取值（通配 / JSON / SSE）。 */
    private static final List<String> ACCEPT_VALUES = List.of(
            MediaType.ALL_VALUE,
            MediaType.APPLICATION_JSON_VALUE,
            SseRequests.ACCEPT_SSE);

    /** 契约响应体的四个字段（§1.2，恒定出现）。 */
    private static final Set<String> RESULT_KEYS = Set.of("code", "message", "data", "timestamp");

    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    @LocalServerPort
    private int port;
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
    @Autowired
    private TenantResolver tenantResolver;

    private HttpClient client;
    private SysConfigOverride override;
    private String tenantId;
    private String host;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "tr" + suffix;
        host = "tr-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "传输层契约测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);

        override = new SysConfigOverride(jdbcTemplate, configService);
        // 🔴 真实请求的 Host 是 localhost:{randomPort}，故借 dev host 映射把它指到本用例租户
        //    （做法与 ToolConfirmIT 的断连专项一致，@AfterEach 还原 + 失效解析缓存）
        override.set(ConfigKeys.GROUP_TENANT, ConfigKeys.DEV_HOST_MAPPING,
                "{\"localhost:" + port + "\":\"" + tenantId + "\"}");
        // 日额度放宽（🔴 准入顺序里日额度预检**早于** QPM，不放宽会先撞 30070 而测不到 10005）
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1");
        tenantResolver.evictHost("localhost:" + port);
        tenantResolver.evictTenantId(tenantId);

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        override.restore();
        tenantResolver.evictHost("localhost:" + port);
        tenantResolver.evictTenantId(tenantId);
        redis.keys(cacheKeys.messageRateLimit(tenantId, UID, "*")).forEach(redis::delete);
        redis.keys(cacheKeys.dailyQuotaCount(tenantId, UID, "*")).forEach(redis::delete);
        redis.keys(cacheKeys.dailyQuotaHold(tenantId, UID, "*")).forEach(redis::delete);
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    // ===================== L2 + L4：限流码 10005 =====================

    @Test
    @DisplayName("🔴🔴 L2/L4：限流（10005）在真实 HTTP 栈下恒 200 + application/json + 非空体，"
            + "三种 Accept 形态完全一致")
    void rateLimitedResponseKeepsJsonShapeUnderEveryAccept() throws Exception {
        exhaustMinuteWindow();

        List<JsonNode> bodies = new ArrayList<>();
        for (String accept : ACCEPT_VALUES) {
            HttpResponse<String> response = send(accept, UUID.randomUUID().toString());
            JsonNode body = assertContractShape(response, accept);

            assertEquals(ErrorCode.RATE_LIMITED, body.get("code").asInt(),
                    "🔴 限流必须回 10005（Accept=" + accept + "）：" + response.body());
            assertTrue(body.path("data").path("retryAfterSeconds").asLong() >= 1,
                    "🔴 10005 必须携带 retryAfterSeconds ≥1（Accept=" + accept + "）：" + response.body());
            bodies.add(body);
        }
        assertAcceptInvariance(bodies);
    }

    // ===================== L3 + L4：非限流码 10001（全局性证明） =====================

    @Test
    @DisplayName("🔴🔴 L3/L4：缺 Idempotency-Key（10001，**非限流码**）同样恒 200 + JSON —— "
            + "证明修复在 advice 层全局生效，而非只治了 10005")
    void missingIdempotencyKeyKeepsJsonShapeUnderEveryAccept() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        for (String accept : ACCEPT_VALUES) {
            // 🔴 idempotencyKey=null → 刻意不带该头
            HttpResponse<String> response = send(accept, null);
            JsonNode body = assertContractShape(response, accept);

            assertEquals(ErrorCode.VALIDATION_FAILED, body.get("code").asInt(),
                    "🔴 缺幂等键必须回 10001（Accept=" + accept + "）：" + response.body());
            bodies.add(body);
        }
        assertAcceptInvariance(bodies);
    }

    // ===================== L3 + L4：AG-UI 端点 10001（全局性证明） =====================

    @Test
    @DisplayName("🔴🔴 L3/L4：AG-UI /run 缺 runId（10001，非限流码）同样恒 200 + JSON —— "
            + "新协议端点也受 ADR-021 全局约束")
    void aguiRunMissingRunIdKeepsJsonShapeUnderEveryAccept() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        for (String accept : ACCEPT_VALUES) {
            HttpResponse<String> response = client.send(SseRequests.realHttpPost(port,
                            SseRequests.PATTERN_AGUI_RUN, UID, accept, null,
                            "{\"threadId\":\"new\",\"messages\":[{\"role\":\"user\","
                                    + "\"content\":\"hi\"}]}")
                            .build(),
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            JsonNode body = assertContractShape(response, accept);
            assertEquals(ErrorCode.VALIDATION_FAILED, body.get("code").asInt(),
                    "🔴 AG-UI 缺 runId 必须回 10001（Accept=" + accept + "）：" + response.body());
            bodies.add(body);
        }
        assertAcceptInvariance(bodies);
    }

    // ===================== 辅助 =====================

    /**
     * 🔴 一次请求的<b>全部报文形态断言</b>（L2 的四条 + L4 的反向断言）。
     *
     * @return 解析后的响应体
     */
    private JsonNode assertContractShape(HttpResponse<String> response, String accept)
            throws Exception {
        String label = "（Accept=" + accept + "）";
        // 🔴 反向断言：任一 Accept 取值下出现 406 / 415 / 5xx 即缺陷（L4）
        assertEquals(200, response.statusCode(),
                "🔴 §1.2.1 ②：SSE 端点的建流前失败同样恒 HTTP 200" + label
                        + "，实际=" + response.statusCode() + " body=" + response.body());
        assertFalse(response.body().isEmpty(),
                "🔴 空体即缺陷（BUG-QUOTA-001 的原始症状是 500 + Content-Length: 0）" + label);
        long contentLength = response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH)
                .orElse(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(contentLength > 0, "🔴 Content-Length 必须 >0" + label);
        String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("");
        assertTrue(contentType.contains(MediaType.APPLICATION_JSON_VALUE),
                "🔴 §1.2.1 ①：Accept 不得改变响应形态，必须以 application/json 写回" + label
                        + "，实际 content-type=" + contentType);

        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(RESULT_KEYS, keysOf(body),
                "🔴 响应体四字段必须恒定出现（§1.2）" + label + "：" + response.body());
        assertTrue(body.get("timestamp").asLong() > 0, "🔴 timestamp 必填" + label);
        return body;
    }

    /**
     * 🔴 <b>L4 不变量</b>：三种 {@code Accept} 的响应必须在
     * <b>状态码 + Content-Type + body 键集合 + code</b> 上完全一致
     * （{@code retryAfterSeconds} 等动态值除外）。
     */
    private void assertAcceptInvariance(List<JsonNode> bodies) {
        Set<Integer> codes = new LinkedHashSet<>();
        Set<Set<String>> shapes = new LinkedHashSet<>();
        for (JsonNode body : bodies) {
            codes.add(body.get("code").asInt());
            shapes.add(keysOf(body));
        }
        assertEquals(1, codes.size(), "🔴 不同 Accept 得到了不同的业务码：" + codes);
        assertEquals(1, shapes.size(), "🔴 不同 Accept 得到了不同的响应体键集合：" + shapes);
    }

    /**
     * 预置分钟窗计数使其<b>必然超限</b>（QPM 阈值已被覆盖为 1）。
     *
     * <p>🔴 同时写入<b>当前分钟与下一分钟</b>：请求若恰好跨过分钟边界仍然命中限流，
     * 用例因此没有任何时间竞态。
     */
    private void exhaustMinuteWindow() {
        Instant now = Instant.now();
        for (Instant moment : List.of(now, now.plusSeconds(60))) {
            String key = cacheKeys.messageRateLimit(tenantId, UID, "m" + MINUTE.format(moment));
            redis.opsForValue().set(key, "99", Duration.ofSeconds(50));
        }
    }

    private HttpResponse<String> send(String accept, String idempotencyKey) throws Exception {
        return client.send(SseRequests.realHttpPost(port, SseRequests.sendNewPath(), UID, accept,
                                idempotencyKey, "{\"content\":\"传输层契约用例\"}")
                        .build(),
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Set<String> keysOf(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
