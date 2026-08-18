package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiChatRequest;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.ai.AiToolCall;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.SysConfigOverride;
import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.testsupport.AiStreamStub;
import com.eyes.albedo.testsupport.SseRequests;
import com.eyes.albedo.testsupport.MockMcpServer;
import com.eyes.albedo.tool.dto.ToolDefinition;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 🔴 <b>失败诊断回灌 + 上游 schema 原样透传</b>端到端验收
 * （ADR-018 ①③⑤ / api-spec §7.6.4 二分裁决 / §8.3 J 组，V1.4.2 新增）。
 *
 * <p><b>被验收的根因（test-report V4.0 BUG-MCP-001 实测）</b>：模型为
 * {@code web_search} 补入 {@code Mode/FromTime/ToTime}（时间戳还落在 2024 年），
 * 上游回「参数非法」；而编排层把执行器<b>已经拿到</b>的上游诊断丢弃、只回一句
 * "工具执行返回失败" → 模型不知道哪个参数错了 → 换个写法再试 → 每次都要用户再确认一次。
 *
 * <p>覆盖：
 * <ul>
 *   <li>🔴 上游 {@code isError=true}（参数错误文本）→ <b>下一轮上下文</b>的 {@code role=tool}
 *       内容<b>包含</b>该诊断（可自纠）</li>
 *   <li>🔴 <b>反向断言</b>：平台/传输侧失败（{@code 30052}）的回灌<b>只有固定措辞</b> ——
 *       不含 endpoint / IP / 端口 / 异常类名（安全边界，不是体验问题）</li>
 *   <li>🔴 下发给模型的 {@code inputSchema} 与 {@code mcp_tools.input_schema} <b>逐字相等</b>
 *       （反向守护 ADR-018 ① 否决的"schema 规范化"没被偷偷实现 ——
 *       一旦加工并落库，下次 discover 必判 {@code schemaChanged} → 自动撤授权）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import({TestAuthConfig.class, MockMcpServer.class})
class McpFailureFeedbackIT {

    private static final long UID = 900000071L;
    private static final String TOOL_KEY = "mock:web_search";

    /**
     * 🔴 复刻 {@code mcp_tools.id=479} 的上游原文形态：{@code Mode} 是 {@code string} 却无
     * {@code enum}、时间参数要求"精确到秒时间戳"、只有 {@code Query} 必填。
     *
     * <p>🔴 它<b>必须原样进出</b>：Albedo 既无权改写，也无可靠依据推断（ADR-018 ①）。
     */
    private static final String UPSTREAM_SCHEMA = "{\"type\":\"object\",\"properties\":"
            + "{\"Query\":{\"type\":\"string\",\"description\":\"搜索词\"},"
            + "\"Mode\":{\"type\":\"string\",\"description\":\"0=综合 1=新闻 2=学术\"},"
            + "\"FromTime\":{\"type\":\"number\",\"description\":\"精确到秒的时间戳\"}},"
            + "\"required\":[\"Query\"]}";

    @LocalServerPort
    private int port;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ConfigService configService;

    @MockBean
    private AiChatClient aiChatClient;

    private SysConfigOverride override;
    private String tenantId;
    private String host;
    private long agentId;
    private long agentVersionId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "mf" + suffix;
        host = "mf-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "回灌测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        agentId = insertAgent();
        agentVersionId = insertAgentVersion();
        // 🔴 Mock MCP 位于环回地址：按 api-spec §7.13 临时放宽两项，@AfterEach 还原
        override = new SysConfigOverride(jdbcTemplate, configService)
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS, "false")
                .set(ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS,
                        "[\"127.0.0.1/32\"]")
                // 🔴 V1.4.5（ADR-020）：平台默认已收紧为 QPM=3 / 日额度=50，而本类验收对象是
                //    失败回灌的诊断内容，不是限流；同样临时放宽并由 restore() 还原。
                .set(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, "1000")
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
        jdbcTemplate.update("DELETE FROM mcp_tools WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM mcp_servers WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agent_versions WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM agents WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("🔴🔴 上游参数错误（isError=true）→ 下一轮上下文的 role=tool **包含上游诊断**"
            + "（模型据此可自纠，不再换写法瞎试）")
    void upstreamParamErrorIsRelayedToNextRound() throws Exception {
        prepareMcpTool(MockMcpServer.SCENARIO_PARAM_ERROR);
        List<AiChatRequest> captured = scriptToolThenText("我改成只传 Query 再试");

        String body = sendAndCollect();

        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, lastToolFrame(body).get("errorCode").asInt(),
                "🔴 状态机与错误码口径不变（本裁决只改回灌文本）");
        String feedback = toolFeedback(captured.get(1));
        assertTrue(feedback.contains("工具执行返回失败"), "固定措辞必须保留：" + feedback);
        assertTrue(feedback.contains(MockMcpServer.PARAM_ERROR_TEXT),
                "🔴 上游诊断必须如实回灌，否则模型只知道\"失败了\"：" + feedback);
        assertEquals("done", eventNames(body).get(eventNames(body).size() - 1), "🔴 done 必发");
    }

    @Test
    @DisplayName("🔴🔴 反向断言：MCP 鉴权失败（30052）回灌**只有固定措辞** —— "
            + "不含 endpoint / IP / 端口 / 异常类名")
    void platformDiagnosticNeverReachesModel() throws Exception {
        prepareMcpTool(MockMcpServer.SCENARIO_AUTH_FAILED);
        List<AiChatRequest> captured = scriptToolThenText("工具不可用，我直接回答");

        String body = sendAndCollect();

        assertEquals(ErrorCode.MCP_UNAVAILABLE, lastToolFrame(body).get("errorCode").asInt());
        String feedback = toolFeedback(captured.get(1));
        assertEquals("工具服务当前不可用", feedback,
                "🔴 传输/平台侧诊断一律只给固定措辞（api-spec §7.6.4 不可回灌集合）");
        for (String forbidden : List.of("127.0.0.1", "http://", "mock-mcp", String.valueOf(port),
                "Exception", "401")) {
            assertFalse(feedback.contains(forbidden),
                    "🔴 回灌内容不得含平台诊断片段（" + forbidden + "）：" + feedback);
        }
    }

    @Test
    @DisplayName("🔴🔴 下发给模型的 inputSchema 与 mcp_tools.input_schema **逐字相等**"
            + "（反向守护：ADR-018 ① 否决 schema 规范化）")
    void downstreamSchemaIsVerbatim() throws Exception {
        prepareMcpTool(MockMcpServer.SCENARIO_SUCCESS);
        List<AiChatRequest> captured = scriptToolThenText("查到了");

        sendAndCollect();

        String stored = jdbcTemplate.queryForObject("SELECT input_schema FROM mcp_tools"
                + " WHERE tenant_id = ? AND tool_key = ?", String.class, tenantId, TOOL_KEY);
        List<ToolDefinition> tools = captured.get(0).tools();
        assertEquals(1, tools.size(), "本次生成必须下发该工具");
        assertEquals(stored, tools.get(0).inputSchema(),
                "🔴 逐字相等：一旦加工 schema，下次 discover 必判 schemaChanged → 自动撤授权"
                        + "（§13.5.4），且\"模型看到的\"与\"校验/审计依据的\"会分叉");
        assertEquals("联网搜索", tools.get(0).description(),
                "🔴 description 同样原样透传，禁止\"增强\"");
    }

    // ===================== 辅助 =====================

    private String toolFeedback(AiChatRequest request) {
        return request.messages().stream()
                .filter(message -> AiMessage.ROLE_TOOL.equals(message.role()))
                .findFirst().orElseThrow(() -> new AssertionError("下一轮上下文缺少 role=tool 回灌"))
                .content();
    }

    private List<AiChatRequest> scriptToolThenText(String finalText) {
        List<AiChatRequest> captured = new ArrayList<>();
        AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> {
            AiChatRequest request = AiStreamStub.request(invocation);
            captured.add(request);
            AiStreamStub.openStream(invocation);
            boolean hasToolResult = request.messages().stream()
                    .anyMatch(message -> AiMessage.ROLE_TOOL.equals(message.role()));
            if (!hasToolResult) {
                return new AiStreamOutcome("tool_calls", null, false,
                        List.of(new AiToolCall("call-mf-1", ToolFunctionNames.normalize(TOOL_KEY),
                                "{\"Query\":\"DeepSeek 新闻\",\"Mode\":\"3\"}")));
            }
            AiStreamStub.onDelta(invocation).accept(finalText);
            return new AiStreamOutcome("stop", new TokenUsage(1, 1, 2), false);
        });
        return captured;
    }

    private String sendAndCollect() throws Exception {
        // 🔴 L5：SSE 端点请求一律经 SseRequests 构造（强制 Accept: text/event-stream）
        MvcResult result = mockMvc.perform(SseRequests.postJson(SseRequests.sendNewPath(), host, UID,
                        "{\"content\":\"帮我联网搜索最近关于 DeepSeek 的新闻\"}"))
                .andExpect(status().isOk())
                .andReturn();
        for (int i = 0; i < 300; i++) {
            String body = bodyOf(result);
            if (body.contains("event:done")) {
                return body;
            }
            Thread.sleep(100);
        }
        return bodyOf(result);
    }

    private void prepareMcpTool(String scenario) {
        long mcpId = insertMcpServer(scenario);
        long toolId = insertMcpTool(mcpId);
        jdbcTemplate.update("INSERT INTO agent_capability_bindings (tenant_id, agent_version_id,"
                        + " capability_type, ref_id, ref_version, sort_order) VALUES (?,?,?,?,1,0)",
                tenantId, agentVersionId, AgentCapabilityBinding.TYPE_MCP_TOOL, toolId);
    }

    private long insertMcpServer(String scenario) {
        String endpoint = "http://127.0.0.1:" + port + "/mock-mcp/streamable-http?scenario="
                + scenario;
        jdbcTemplate.update("INSERT INTO mcp_servers (tenant_id, mcp_key, name, transport,"
                        + " endpoint, auth_type, credential_last4, credential_key_version,"
                        + " timeout_seconds, status, version)"
                        + " VALUES (?,'mock','Mock','streamable_http',?,'none','',0,10,'enabled',0)",
                tenantId, endpoint);
        return jdbcTemplate.queryForObject("SELECT id FROM mcp_servers WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertMcpTool(long mcpId) {
        jdbcTemplate.update("INSERT INTO mcp_tools (tenant_id, mcp_id, tool_name, tool_key,"
                        + " description, input_schema, input_schema_digest, risk_level, granted,"
                        + " status, change_type) VALUES (?,?,?,?,?,?,'digest479','low',1,"
                        + "'enabled','new')",
                tenantId, mcpId, "web_search", TOOL_KEY, "联网搜索", UPSTREAM_SCHEMA);
        return jdbcTemplate.queryForObject("SELECT id FROM mcp_tools WHERE tenant_id = ?"
                + " AND tool_key = ?", Long.class, tenantId, TOOL_KEY);
    }

    private long insertAgent() {
        jdbcTemplate.update("INSERT INTO agents (tenant_id, agent_key, name, description,"
                        + " status, is_default, current_version, version, sort_order)"
                        + " VALUES (?,?,?,'','enabled',1,1,0,0)",
                tenantId, "mf-agent", "回灌测试助手");
        return jdbcTemplate.queryForObject("SELECT id FROM agents WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    private long insertAgentVersion() {
        jdbcTemplate.update("INSERT INTO agent_versions (tenant_id, agent_id, version,"
                        + " system_prompt, provider_key, model, temperature, max_output_tokens,"
                        + " context_strategy, request_timeout_seconds, tool_policy, status)"
                        + " VALUES (?,?,1,'系统提示','hunyuan','hunyuan-a13b',0.70,4096,"
                        + "'summary_then_window',60,?,'published')",
                tenantId, agentId, ToolRiskPolicy.POLICY_AUTO);
        return jdbcTemplate.queryForObject("SELECT id FROM agent_versions WHERE tenant_id = ?",
                Long.class, tenantId);
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

    private JsonNode lastToolFrame(String body) throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        String[] lines = body.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().equals("event:tool")) {
                continue;
            }
            for (int j = i + 1; j < lines.length; j++) {
                String candidate = lines[j].trim();
                if (candidate.startsWith("data:")) {
                    frames.add(objectMapper.readTree(candidate.substring("data:".length()).trim()));
                    break;
                }
            }
        }
        assertFalse(frames.isEmpty(), "未收到任何 tool 帧：" + body);
        return frames.get(frames.size() - 1);
    }
}
