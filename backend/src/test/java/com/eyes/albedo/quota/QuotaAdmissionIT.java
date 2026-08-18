package com.eyes.albedo.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiStreamException;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.chat.service.GenerationAdmission;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.quota.dto.QuotaSnapshotDTO;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 生成准入五步与计数口径（🔴 K1 / K3 / K4 / K6 / K7 / K8 / K9 / K16，api-spec §7.15.3/§7.15.4）。
 *
 * <p>🔴 <b>本类是 ADR-020 最关键的守护</b>：
 * <ul>
 *   <li><b>K4 双向断言</b>直接读 Redis 分钟窗计数值 —— "日额度用尽不增 QPM 计数"
 *       在响应体上<b>无法观测</b>，只能这样断言</li>
 *   <li><b>K9 并发不超发</b>是单条 Lua 原子性的唯一守护（{@code CountDownLatch} 同时释放）</li>
 *   <li><b>K16</b> 删镜像键后仍从 DB 账本重建 —— 反向断言"Redis 丢数据 = 白得额度"不存在</li>
 * </ul>
 *
 * <p>数据纪律：临时租户 + 独立 uid 段，{@code @AfterEach} 精确清理；
 * 阈值一律用 {@link SysConfigOverride} 临时覆盖并还原（🔴 用小阈值让用例可快速跑完）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class QuotaAdmissionIT {

    private static final long UID = 900000411L;
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
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
    @Autowired
    private GenerationAdmission admission;

    @MockBean
    private AiChatClient aiChatClient;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private long tenantPk;
    private AtomicInteger upstreamCalls;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "qc" + suffix;
        host = "qc-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "额度准入测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        tenantPk = jdbcTemplate.queryForObject("SELECT id FROM tenants WHERE tenant_id = ?",
                Long.class, tenantId);
        insertAgent();
        override = new SysConfigOverride(jdbcTemplate, configService);
        // 🔴 默认放宽 QPM，只有 QPM 专项用例才收紧它（避免用例间互相干扰）
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000");
        upstreamCalls = new AtomicInteger();
    }

    @AfterEach
    void tearDown() {
        override.restore();
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_quota_policies WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
        redis.keys(cacheKeys.dailyQuotaCount(tenantId, UID, "*")).forEach(redis::delete);
        redis.keys(cacheKeys.dailyQuotaHold(tenantId, UID, "*")).forEach(redis::delete);
        redis.keys(cacheKeys.messageRateLimit(tenantId, UID, "*")).forEach(redis::delete);
        TenantContext.clear();
    }

    // ===================== K3：日限额边界 =====================

    @Test
    @DisplayName("🔴 K3：日额度 N 时前 N 次结算成功、第 N+1 次 → 30070 + 快照恰 9 键，且**模型未被调用**")
    void dailyLimitBoundaryRejectsBeforeModelCall() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "2");
        stubTextUpstream();

        sendOk("第 1 条");
        sendOk("第 2 条");
        assertEquals(2, settledCount(), "🔴 每次达到资源消耗边界必须恰结算 1 次");
        int callsBefore = upstreamCalls.get();

        JsonNode rejected = sendExpectingJson("第 3 条");

        assertEquals(ErrorCode.DAILY_QUOTA_EXHAUSTED, rejected.get("code").asInt());
        JsonNode data = rejected.path("data");
        assertEquals(SNAPSHOT_KEYS, keysOf(data), "🔴 30070 的 data 必须是同形快照（恰 9 键）");
        assertEquals(2, data.get("used").asInt());
        assertEquals(2, data.get("limit").asInt());
        assertEquals(0, data.get("remaining").asInt());
        assertEquals(QuotaSnapshotDTO.STATUS_EXHAUSTED, data.get("status").asText());
        assertFalse(keysOf(data).contains("retryAfterSeconds"),
                "🔴 K5：30070 携带 retryAfterSeconds 会被前端误表现为秒级倒计时");
        assertEquals(callsBefore, upstreamCalls.get(), "🔴 判定必须发生在模型调用之前");
        assertEquals(2, settledCount(), "🔴 被拒的尝试不得计数");
        Integer orphanMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE uid = ? AND content = ?", Integer.class,
                UID, "第 3 条");
        assertEquals(0, orphanMessages, "🔴 预占在建消息之前 → 被拒时绝不留下孤儿消息");
    }

    // ===================== K1：QPM 边界 =====================

    @Test
    @DisplayName("🔴 K1：QPM=3 时第 4 次 → 10005 + retryAfterSeconds ≥1，且**模型未被调用**")
    void qpmBoundaryRejectsFourthRequest() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "3");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");
        stubTextUpstream();

        sendOk("第 1 条");
        sendOk("第 2 条");
        sendOk("第 3 条");
        int callsBefore = upstreamCalls.get();

        JsonNode rejected = sendExpectingJson("第 4 条");

        assertEquals(ErrorCode.RATE_LIMITED, rejected.get("code").asInt());
        assertTrue(rejected.path("data").get("retryAfterSeconds").asLong() >= 1,
                "🔴 retryAfterSeconds 必填且 ≥1");
        assertEquals(callsBefore, upstreamCalls.get());
    }

    // ===================== K4：优先级双向断言 =====================

    @Test
    @DisplayName("🔴 K4ⓐ：日额度已用尽 + QPM 也会超限 → 只回 30070，且**分钟窗计数值未增加**")
    void exhaustedDailyQuotaNeverTouchesQpmCounter() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1");
        stubTextUpstream();

        sendOk("唯一一条");
        String minuteKey = cacheKeys.messageRateLimit(tenantId, UID,
                "m" + MINUTE.format(Instant.now()));
        String before = redis.opsForValue().get(minuteKey);

        JsonNode rejected = sendExpectingJson("第二条");

        assertEquals(ErrorCode.DAILY_QUOTA_EXHAUSTED, rejected.get("code").asInt(),
                "🔴 日额度优先：绝不返回 10005");
        assertEquals(before, redis.opsForValue().get(minuteKey),
                "🔴 日额度只读预检必须早于 QPM 计数 —— 分钟窗计数值不得有任何变化");
    }

    @Test
    @DisplayName("🔴 K4ⓑ：日额度可用但 QPM 超限 → 回 10005，且 used 与在途预占**均未变化**")
    void qpmRejectionNeverConsumesDailyQuota() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "10");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1");
        stubTextUpstream();

        sendOk("唯一一条");
        long usedBefore = settledCount();

        JsonNode rejected = sendExpectingJson("第二条");

        assertEquals(ErrorCode.RATE_LIMITED, rejected.get("code").asInt());
        assertEquals(usedBefore, settledCount(), "🔴 QPM 超限时不得占用日额度");
        assertEquals(0L, inFlightHolds(), "🔴 也不得留下在途预占");
    }

    // ===================== K6 / K7：计数口径 =====================

    @Test
    @DisplayName("🔴 K6：生成前失败（会话不存在 10004）→ 预占已释放、used 不增加")
    void preGenerationFailureReleasesReservation() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "2");
        stubTextUpstream();

        MvcResult result = mockMvc.perform(SseRequests.postJson(
                        SseRequests.sendPath("999999999"), host, UID, "{\"content\":\"不存在的会话\"}"))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                objectMapper.readTree(bodyOf(result)).get("code").asInt());
        assertEquals(0L, settledCount(), "🔴 生成前失败不得计数");
        assertEquals(0L, inFlightHolds(),
                "🔴 建消息失败必须在 catch 中释放预占，否则 remaining 会白占到预占过期");
    }

    @Test
    @DisplayName("🔴 K7：完成一次生成恰计 1 次（标题生成与摘要刷新等平台派生调用**不计数**）")
    void completedGenerationCountsExactlyOnce() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "5");
        stubTextUpstream();

        sendOk("只发一条");

        assertEquals(1L, settledCount(),
                "🔴 用户只发 1 条就只能扣 1 次（标题生成 / 摘要刷新是平台派生调用）");
        assertEquals(0L, inFlightHolds(), "🔴 结算后预占必须已被移除");
    }

    @Test
    @DisplayName("🔴 K7：已产生正文后模型报错 → 仍恰计 1 次（已消耗必计）")
    void failedGenerationAfterFirstDeltaStillCountsOnce() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "5");
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            upstreamCalls.incrementAndGet();
            Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
            AiStreamStub.openStream(invocation);
            onDelta.accept("已经生成的部分");
            throw AiStreamException.upstream("模型连接中断");
        });

        String body = streamAndCollect("模型中途失败");

        assertTrue(body.contains("event:error") && body.contains("event:done"));
        assertEquals(1L, settledCount(), "🔴 首个正文分片就是资源已消耗证据，失败也必计 1 次");
    }

    @Test
    @DisplayName("🔴 K8：同 Idempotency-Key 重放 → 只 1 次结算，且回放**不消费** QPM")
    void idempotentReplayNeverDoubleCounts() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "5");
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "2");
        stubTextUpstream();
        String key = UUID.randomUUID().toString();

        streamAndCollect("幂等内容", key);
        String minuteKey = cacheKeys.messageRateLimit(tenantId, UID,
                "m" + MINUTE.format(Instant.now()));
        String qpmAfterFirst = redis.opsForValue().get(minuteKey);

        streamAndCollect("幂等内容", key);
        streamAndCollect("幂等内容", key);

        assertEquals(1L, settledCount(), "🔴 幂等重放不得重复计数");
        assertEquals(qpmAfterFirst, redis.opsForValue().get(minuteKey),
                "🔴 回放发生在准入之前 → 不计 QPM（否则 2 次重放就会把用户打成限流）");
        Integer userMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE uid = ? AND role = 'user'", Integer.class, UID);
        assertEquals(1, userMessages, "🔴 幂等重放不得重复创建用户消息");
    }

    // ===================== K9：并发不超发 =====================

    @Test
    @DisplayName("🔴 K9：仅剩 1 次额度时 4 个请求并发 → **恰 1 个**取得资格，其余 30070，无超发")
    void concurrentRequestsNeverOverspend() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1");
        final int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                bindTenant();
                ready.countDown();
                try {
                    fire.await(5, TimeUnit.SECONDS);
                    admission.admit(tenantId, UID);
                    granted.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getCode() == ErrorCode.DAILY_QUOTA_EXHAUSTED) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    TenantContext.clear();
                }
            });
            workers.add(worker);
            worker.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未全部就绪");
        fire.countDown();
        for (Thread worker : workers) {
            worker.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertEquals(1, granted.get(), "🔴 只能有一个请求取得生成资格（单条 Lua 的原子性）");
        assertEquals(threads - 1, rejected.get(), "🔴 其余必须全部 30070");
        assertEquals(1L, inFlightHolds(), "🔴 在途预占恰 1 个，绝不出现超发");
        assertEquals(0L, settledCount(), "🔴 预占不计 used");
    }

    // ===================== K16：Redis 丢数据不白得额度 =====================

    @Test
    @DisplayName("🔴 K16：人为删除计数镜像键 → 下一次预占**从 DB 账本重建**，used 不回退为 0")
    void mirrorLossIsRebuiltFromLedger() throws Exception {
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1");
        stubTextUpstream();
        sendOk("唯一一条");
        assertEquals(1L, settledCount());

        // 🔴 模拟 Redis 重启 / maxmemory 驱逐
        redis.keys(cacheKeys.dailyQuotaCount(tenantId, UID, "*")).forEach(redis::delete);

        JsonNode rejected = sendExpectingJson("第二条");

        assertEquals(ErrorCode.DAILY_QUOTA_EXHAUSTED, rejected.get("code").asInt(),
                "🔴 镜像缺失必须先从 DB 账本重建再判定 —— 否则 Redis 丢数据 = 白得额度");
        assertEquals(1, rejected.path("data").get("used").asInt(), "🔴 used 不得回退为 0");
    }

    // ===================== 辅助 =====================

    private void bindTenant() {
        TenantContext.bind(new TenantContext.Snapshot(tenantId, tenantPk, host,
                TenantStatus.ENABLED, 0L, UID));
    }

    private void stubTextUpstream() {
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            upstreamCalls.incrementAndGet();
            Consumer<String> onDelta = AiStreamStub.onDelta(invocation);
            AiStreamStub.openStream(invocation);
            onDelta.accept("回答内容");
            return new AiStreamOutcome("stop", new TokenUsage(10, 20, 30), false);
        });
    }

    private void sendOk(String content) throws Exception {
        String body = streamAndCollect(content, UUID.randomUUID().toString());
        assertTrue(body.contains("event:done"), "必须收到 done 帧：" + body);
    }

    private String streamAndCollect(String content) throws Exception {
        return streamAndCollect(content, UUID.randomUUID().toString());
    }

    private String streamAndCollect(String content, String idempotencyKey) throws Exception {
        MvcResult result = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), host, UID,
                        idempotencyKey, "{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        for (int i = 0; i < 150; i++) {
            if (bodyOf(result).contains("event:done")) {
                break;
            }
            Thread.sleep(100);
        }
        // 🔴 结算发生在帧 flush **之后**，给异步段留出落账时间窗（不放宽任何断言）
        Thread.sleep(200);
        return bodyOf(result);
    }

    /**
     * 建流之前被拒 → 响应是 {@code application/json} 的标准 Result（两段式约定）。
     *
     * <p>🔴 <b>L1</b>（api-spec §8.3，ADR-021）：请求经 {@link SseRequests} 构造，
     * 因此<b>恒带</b> {@code Accept: text/event-stream}（真实浏览器形态）。
     * 修复前此处必然拿到 500 + 空体（内容协商失败），
     * 故本方法额外断言 {@code content-type} 含 {@code application/json} —— 🔴 不得放宽。
     */
    private JsonNode sendExpectingJson(String content) throws Exception {
        MvcResult result = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), host, UID,
                        "{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String contentType = result.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE),
                "🔴 建流前失败必须以 application/json 写回（Accept 不得改变响应形态，§1.2.1 ①），"
                        + "实际 content-type=" + contentType);
        return objectMapper.readTree(bodyOf(result));
    }

    private long settledCount() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate localDate = Instant.now().atZone(zone).toLocalDate();
        Integer value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(settled_count),0) FROM user_daily_quota_usages"
                        + " WHERE tenant_id = ? AND uid = ? AND quota_date = ?",
                Integer.class, tenantId, UID, localDate);
        return value == null ? 0L : value;
    }

    private long inFlightHolds() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        String dateKey = "d" + DateTimeFormatter.ofPattern("yyyyMMdd")
                .format(Instant.now().atZone(zone).toLocalDate());
        Long count = redis.opsForZSet().count(cacheKeys.dailyQuotaHold(tenantId, UID, dateKey),
                Instant.now().toEpochMilli() + 1d, Double.MAX_VALUE);
        return count == null ? 0L : count;
    }

    private Set<String> keysOf(JsonNode data) {
        List<String> names = new ArrayList<>();
        data.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "qc-agent", "额度测试助手");
        Long agentId = jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',60,'auto','published')",
                tenantId, agentId);
    }
}
