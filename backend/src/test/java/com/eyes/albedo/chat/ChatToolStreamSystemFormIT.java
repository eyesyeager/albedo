package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.testsupport.SseRequests;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.testsupport.StrictSystemFormUpstream;
import com.eyes.albedo.tool.ToolFunctionNames;
import com.eyes.albedo.tool.ToolRiskPolicy;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.handler.CalculatorHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 🔴 <b>BUG-MCP-004 回归守护（签署前置）</b>：单一前导 {@code system} 不变量的端到端验收
 * （ADR-019 ⑤ + 落点 #5 / api-spec §8.3 J11 · J12）。
 *
 * <p><b>与其它 IT 的本质区别（也是本类存在的唯一理由）</b>：本类<b>不</b>用
 * {@code @MockBean AiChatClient}，而是让请求走<b>真实 HTTP 栈</b>
 * （生产 {@code AiChatClient} + JDK {@code HttpClient} + 真实 JSON 构体）打到一个
 * <b>复刻了上游硬约束</b>的桩上游 {@link StrictSystemFormUpstream}：
 * <pre>
 * 桩收到 messages 中 system &gt;1 条、或 system 不在 index 0 → 回 HTTP 400（同真实上游）
 * </pre>
 * 🔴 BUG-MCP-004 之所以能溜过 731 个自动化用例，<b>唯一原因</b>就是既有桩不校验消息形态。
 * 因此本类的判据<b>不得</b>被"单测已断言只有 1 条 system"替代（单测断言的是装配结果，
 * 桩校验的是"上游若挑剔我们也不会挂"，两者层级不同）。
 *
 * <p><b>覆盖</b>：
 * <ul>
 *   <li>ⓐ 桩复刻 400（{@link StrictSystemFormUpstream#rejectedCount()} 必须恒为 0）</li>
 *   <li>ⓑ {@code calculator}（本地 Tool）含工具生成：🔴 <b>不出现</b> {@code error(50002)}，
 *       {@code tool} 帧走完 {@code pending → running → succeeded}，{@code done(status=completed)}</li>
 *   <li>ⓒ 断言桩<b>实收</b>请求体里 {@code system} 恰 1 条、在 {@code index 0}、
 *       内容以纪律段结尾（🔴 反向守护"靠删/清空 {@code chat.tool_usage_guideline} 让测试变绿"）</li>
 *   <li>ⓓ 🔴 长会话 + 已缓存摘要 + 有工具（三块同时存在）→ 仍恰 1 条 system 且生成成功
 *       （守护 ADR-019 一并修掉的<b>同源既有隐患</b>：摘要此前是第 2/3 条 system）</li>
 * </ul>
 *
 * <p>数据纪律：临时租户 + 独立 uid 段，{@code @AfterEach} 精确清理；
 * {@code sys_config} 仅做<b>临时覆盖并还原</b>（{@link SysConfigOverride}），键与文案零变更。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ChatToolStreamSystemFormIT {

    /** 🔴 桩上游必须在 Spring 上下文启动前就绪：{@code app.ai.base-url} 要指向它的动态端口。 */
    private static final StrictSystemFormUpstream UPSTREAM = startUpstream();

    private static final long UID = 900000131L;
    private static final String CURRENT_TIME_PLACEHOLDER = "{{currentTime}}";
    private static final String SUMMARY_PREFIX_MARK = "以下是本次对话更早内容的摘要";
    private static final String CACHED_SUMMARY = "用户：我预算 5000 元，帮我挑礼物\n助手：好的，先确认收礼人";

    @DynamicPropertySource
    static void upstreamProperties(DynamicPropertyRegistry registry) {
        // 🔴 只改「上游地址」这一个基础设施参数：其余链路（构体 / SSE 解析 / 工具编排）全是生产实现
        registry.add("app.ai.base-url", UPSTREAM::baseUrl);
    }

    @AfterAll
    static void stopUpstream() {
        UPSTREAM.close();
    }

    private static StrictSystemFormUpstream startUpstream() {
        try {
            return new StrictSystemFormUpstream();
        } catch (IOException e) {
            throw new UncheckedIOException("桩上游启动失败", e);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;
    @Autowired
    private BusinessConfig businessConfig;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TenantCacheKeys cacheKeys;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        UPSTREAM.reset();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "sf" + suffix;
        host = "sf-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "system 形态回归租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        agentId = insertAgent();
        agentVersionId = insertAgentVersion();
        override = new SysConfigOverride(jdbcTemplate, configService);
        // 🔴 V1.4.5（ADR-020）：平台默认已收紧为 QPM=3 / 日额度=50，而本类的验收对象不是限流；
        //    临时放宽两项阈值并由 @AfterEach 的 override.restore() 还原（api-spec §7.13 G12 的唯一合法方式）。
        //    🔴 不是"为求绿放宽断言"：阈值边界由 MessageRateLimiterIT / QuotaAdmissionIT 精确覆盖。
        override.set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000")
                .set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, "1000");
    }

    @AfterEach
    void tearDown() {
        override.restore();
        // 🔴 V1.4.5：额度账本行按临时租户清理，避免在共享库里留下孤儿数据
        jdbcTemplate.update("DELETE FROM user_daily_quota_usages WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tool_calls WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM audit_logs WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM messages WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM conversations WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        jdbcTemplate.update("DELETE FROM agent_capability_bindings WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    // ===================== ⓐⓑⓒ calculator 恢复 + 实收请求体断言 =====================

    @Test
    @DisplayName("🔴🔴 J12：calculator 含工具生成不再 error(50002)；桩实收 system 恰 1 条、index 0、以纪律段结尾")
    void calculatorRunsAgainstStrictUpstream() throws Exception {
        bindCalculator();
        UPSTREAM.scriptToolThenText(ToolFunctionNames.normalize(CalculatorHandler.TOOL_KEY),
                "{\"expression\":\"(1+2)*3\"}", "结果是 9");

        String body = sendAndCollect(SseRequests.sendNewPath(),
                "{\"content\":\"帮我算一下 (1+2)*3\"}");
        List<String> events = eventNames(body);

        // ⓑ 🔴 关键回归判据：BUG-MCP-004 的现场是 [meta, error(50002), done(failed)]
        assertFalse(events.contains("error"),
                "🔴 含工具的生成不得出现任何 error 帧（BUG-MCP-004 复发判据）：" + body);
        assertEquals(List.of("pending", "running", "succeeded"), toolStatuses(body),
                "🔴 tool 帧必须走完 pending → running → succeeded：" + body);
        assertEquals("completed", payloadOf(body, "done").get("status").asText(),
                "🔴 done 必发且必须是 completed：" + body);

        // ⓐ 桩复刻了上游 400；一次都没触发 = 我们发出的形态被真实上游接受
        assertEquals(0, UPSTREAM.rejectedCount(),
                "🔴 桩按真实上游硬约束回了 400 → 说明装配侧又拼出了多条 system（回归）");
        assertEquals(2, UPSTREAM.receivedBodies().size(), "模型必须被请求两轮（工具轮 + 收口轮）");

        // ⓒ 逐轮断言桩**实收**的请求体形态
        for (String raw : UPSTREAM.receivedBodies()) {
            assertSingleLeadingSystemEndingWithGuideline(raw);
        }
    }

    // ===================== ⓓ 三块同时存在（租户段 + 摘要块 + 纪律段） =====================

    @Test
    @DisplayName("🔴🔴 J11：长会话 + 已缓存摘要 + 有工具（三块齐备）→ 仍恰 1 条 system 且生成成功")
    void longConversationWithSummaryAndToolsStillSingleSystem() throws Exception {
        bindCalculator();
        // 🔴 把窗口预算压到极小 → 必然产生"窗口之外的更早内容"，摘要块才会被注入
        override.set(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_CHARS, "20")
                .set(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_SUMMARY_ENABLED, "true");
        long conversationId = insertConversation();
        insertHistory(conversationId);
        // 已缓存摘要（生产路径由 refreshSummary 异步写入，这里直接铺设前置状态）
        redis.opsForValue().set(cacheKeys.chatSummary(tenantId, conversationId), CACHED_SUMMARY,
                Duration.ofMinutes(5));
        UPSTREAM.scriptToolThenText(ToolFunctionNames.normalize(CalculatorHandler.TOOL_KEY),
                "{\"expression\":\"5000/3\"}", "大约 1666.67");

        String body = sendAndCollect(SseRequests.sendPath(String.valueOf(conversationId)),
                "{\"content\":\"预算平摊到 3 份是多少\"}");

        assertFalse(eventNames(body).contains("error"),
                "🔴 摘要块曾是第 2 条 system → 该会话在摘要 TTL 内每轮必 400（同源既有隐患）：" + body);
        assertEquals("completed", payloadOf(body, "done").get("status").asText(), body);
        assertEquals(0, UPSTREAM.rejectedCount(), "🔴 三块同时存在时仍必须只有 1 条 system");

        // 🔴 三块齐备的直接证据：租户段前缀 + 摘要块 + 纪律段末块，全在同一条 system 里
        String system = systemContentOf(UPSTREAM.receivedBodies().get(0));
        assertTrue(system.startsWith("你是礼遇顾问"), "🔴 租户段仍为前缀：" + system);
        assertTrue(system.contains(SUMMARY_PREFIX_MARK), "🔴 摘要块必须在同一条 system 内：" + system);
        assertTrue(system.contains("预算 5000 元"), "🔴 已缓存摘要正文必须被注入：" + system);
        for (String raw : UPSTREAM.receivedBodies()) {
            assertSingleLeadingSystemEndingWithGuideline(raw);
        }
        redis.delete(cacheKeys.chatSummary(tenantId, conversationId));
    }

    // ===================== 桩自检（🔴 否决"桩不校验形态"） =====================

    @Test
    @DisplayName("🔴 桩上游自检：2 条 system / system 不在 index 0 → 各回 400（证明本类的守护不是空转）")
    void stubUpstreamReallyEnforcesUpstreamHardConstraint() throws Exception {
        // 🔴 若没有这条自检，桩的 400 分支可能被改坏而无人发现 →
        //    本类的其余断言（rejectedCount==0）就退化为"永远为真"，等于回到 BUG-MCP-004 的覆盖缺口。
        assertEquals(StrictSystemFormUpstream.STATUS_BAD_FORM, postRaw("{\"model\":\"m\","
                + "\"messages\":[{\"role\":\"system\",\"content\":\"a\"},"
                + "{\"role\":\"system\",\"content\":\"b\"},"
                + "{\"role\":\"user\",\"content\":\"c\"}]}"));
        assertEquals(StrictSystemFormUpstream.STATUS_BAD_FORM, postRaw("{\"model\":\"m\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"c\"},"
                + "{\"role\":\"system\",\"content\":\"a\"}]}"));
        assertEquals(2, UPSTREAM.rejectedCount(), "🔴 两种非法形态都必须被桩拒绝");

        // 反向：合法形态（恰 1 条 system 且在 index 0）必须被接受
        assertEquals(200, postRaw("{\"model\":\"m\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"a\"},{\"role\":\"user\",\"content\":\"c\"}]}"));
        assertEquals(2, UPSTREAM.rejectedCount(), "🔴 合法形态不得被误拒");
    }

    private int postRaw(String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(UPSTREAM.baseUrl()
                        + "/chat/completions"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .statusCode();
    }

    // ===================== 断言辅助 =====================

    /**
     * 🔴 对桩<b>实收</b>请求体断言：{@code system} 恰 1 条、位于 {@code index 0}、
     * 内容以<b>替换后的纪律段</b>结尾。
     *
     * <p>🔴 <b>反向守护</b>：纪律段文案取自 {@code sys_config}，并先断言它<b>非空且有实质长度</b> ——
     * 若有人为了"让测试变绿"删键或清空该值，这里立即失败（且 {@code StartupChecker} 也会拒绝启动）。
     */
    private void assertSingleLeadingSystemEndingWithGuideline(String rawBody) throws Exception {
        JsonNode messages = objectMapper.readTree(rawBody).path("messages");
        List<Integer> systemIndexes = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if ("system".equals(messages.get(index).path("role").asText(""))) {
                systemIndexes.add(index);
            }
        }
        assertEquals(1, systemIndexes.size(), "🔴 上游实收的 system 必须恰 1 条：" + systemIndexes);
        assertEquals(0, systemIndexes.get(0).intValue(), "🔴 唯一那条 system 必须位于 index 0");

        String system = messages.get(0).path("content").asText();
        String template = businessConfig.requireString(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE);
        assertFalse(template.isBlank(),
                "🔴 前置条件：chat.tool_usage_guideline 不得为空（清空它 = 回退 BUG-MCP-001 的修复）");
        assertTrue(template.codePointCount(0, template.length()) >= 20,
                "🔴 前置条件：纪律段必须是实质文案，不得被删减为占位内容");

        int at = template.indexOf(CURRENT_TIME_PLACEHOLDER);
        assertFalse(system.contains(CURRENT_TIME_PLACEHOLDER),
                "🔴 占位符必须已替换为服务器当前时间：" + system);
        if (at < 0) {
            assertTrue(system.endsWith(template), "🔴 system 必须以纪律段结尾：" + system);
            return;
        }
        String head = template.substring(0, at);
        String tail = template.substring(at + CURRENT_TIME_PLACEHOLDER.length());
        assertTrue(system.contains(head), "🔴 纪律段前半段必须完整注入：" + system);
        assertTrue(system.endsWith(tail),
                "🔴 纪律段必须是 system 的**末块**（endsWith 判据，ADR-019 ②）：" + system);
        assertTrue(system.contains(today()),
                "🔴 {{currentTime}} 必须替换为**当前**时间：" + system);
    }

    private String systemContentOf(String rawBody) throws Exception {
        return objectMapper.readTree(rawBody).path("messages").get(0).path("content").asText();
    }

    private String today() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    // ===================== 请求与 SSE 解析 =====================

    private String sendAndCollect(String path, String jsonBody) throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        MvcResult result = mockMvc.perform(SseRequests.postJson(path, host, UID, jsonBody))
                .andExpect(status().isOk())
                .andReturn();
        for (int i = 0; i < 150; i++) {
            if (bodyOf(result).contains("event:done")) {
                break;
            }
            Thread.sleep(100);
        }
        return bodyOf(result);
    }

    private String bodyOf(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private List<String> eventNames(String body) {
        List<String> names = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("event:")) {
                names.add(trimmed.substring("event:".length()).trim());
            }
        }
        return names;
    }

    private List<String> toolStatuses(String body) throws Exception {
        List<String> statuses = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("event:tool")) {
                continue;
            }
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.startsWith("data:")) {
                    statuses.add(objectMapper.readTree(candidate.substring("data:".length()).trim())
                            .get("status").asText());
                    break;
                }
            }
        }
        return statuses;
    }

    private JsonNode payloadOf(String body, String eventName) throws Exception {
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("event:" + eventName)) {
                for (int j = i + 1; j < lines.length; j++) {
                    String candidate = lines[j].trim();
                    if (candidate.startsWith("data:")) {
                        return objectMapper.readTree(candidate.substring("data:".length()).trim());
                    }
                }
            }
        }
        throw new AssertionError("未找到事件 " + eventName + "：" + body);
    }

    // ===================== 数据铺设 =====================

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "sf-agent", "形态回归助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion() {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'你是礼遇顾问','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',60,?,'published')",
                tenantId, agentId, ToolRiskPolicy.POLICY_AUTO);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertConversation() {
        jdbcTemplate.update("INSERT INTO conversations (tenant_id, uid, agent_id, agent_version,"
                        + " title, title_source, status, message_count, last_message_at, version)"
                        + " VALUES (?,?,?,1,'形态回归会话','auto','active',0,UTC_TIMESTAMP(3),0)",
                tenantId, UID, agentId);
        return jdbcTemplate.queryForObject("SELECT id FROM conversations WHERE tenant_id = ?"
                + " AND uid = ? ORDER BY id DESC LIMIT 1", Long.class, tenantId, UID);
    }

    /** 铺设足够多的历史消息，使窗口预算必然把一部分挤到"更早内容"（→ 摘要块生效）。 */
    private void insertHistory(long conversationId) {
        for (int round = 0; round < 4; round++) {
            insertMessage(conversationId, "user", "历史提问" + round + "：" + "问".repeat(30), "sent");
            insertMessage(conversationId, "assistant", "历史回答" + round + "：" + "答".repeat(30),
                    "completed");
        }
    }

    private void insertMessage(long conversationId, String role, String content, String messageStatus) {
        jdbcTemplate.update("INSERT INTO messages (tenant_id, conversation_id, uid, role, content,"
                        + " status, attempt_no, is_current, model, agent_version, finish_reason)"
                        + " VALUES (?,?,?,?,?,?,1,1,'hunyuan-a13b',1,'stop')",
                tenantId, conversationId, UID, role, content, messageStatus);
    }

    /** 授权并绑定内置 {@code calculator}（平台行沿用共享库既有数据，缺失时按既有 IT 惯例补齐）。 */
    private void bindCalculator() {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM local_tools WHERE tool_key = ?", Integer.class,
                CalculatorHandler.TOOL_KEY);
        if (exists == null || exists == 0) {
            jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                            + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                            + " VALUES (?,?,1,'',?,?,1,5,?)",
                    CalculatorHandler.TOOL_KEY, CalculatorHandler.TOOL_KEY,
                    "{\"type\":\"object\",\"properties\":{\"expression\":{\"type\":\"string\"}},"
                            + "\"required\":[\"expression\"]}",
                    ToolRiskPolicy.RISK_LOW, LocalTool.STATUS_ENABLED);
        }
        jdbcTemplate.update("INSERT INTO tenant_tool_grants (tenant_id, tool_key, granted, config,"
                        + " status) VALUES (?,?,1,NULL,?)",
                tenantId, CalculatorHandler.TOOL_KEY, TenantToolGrant.STATUS_ENABLED);
        Long grantId = jdbcTemplate.queryForObject("SELECT id FROM tenant_tool_grants"
                + " WHERE tenant_id = ? AND tool_key = ?", Long.class, tenantId,
                CalculatorHandler.TOOL_KEY);
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,1,0)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_LOCAL_TOOL, grantId);
    }
}
