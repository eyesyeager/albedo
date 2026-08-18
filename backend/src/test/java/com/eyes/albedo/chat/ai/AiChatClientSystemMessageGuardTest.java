package com.eyes.albedo.chat.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.List;

import com.eyes.albedo.config.AppProperties;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>适配层结构性守护</b>：单一前导 {@code system} 不变量的 fail-fast 断言
 * （ADR-019 ④ / api-spec §8.3 J11 反向守护）。
 *
 * <p><b>为什么这层断言不可省</b>：真实上游对 {@code messages} 的 {@code system} 形态有硬约束
 * （&gt;1 条或不在 {@code index 0} → {@code status=400}），若装配侧再次出现"多加一条 system"，
 * 用户只会拿到 {@code 50002}，而平台侧日志里<b>没有自己的判定</b> —— 排障成本极高。
 * 因此传输层在构体前就把契约违反炸出来：
 * <ul>
 *   <li>❌ 不做静默自动合并/重排（那会把"谁负责拼 system"从 {@code ContextAssembler} 漂移到传输层）</li>
 *   <li>❌ 不做"WARN 后照发"（上游必然 400）</li>
 * </ul>
 *
 * <p>🔴 <b>零 Spring、零 DB、零网络</b>：直接对 {@code buildBody} 断言 ——
 * 它是"发往上游前的最后一道门"，一个字节都不会出网。
 */
class AiChatClientSystemMessageGuardTest {

    private static final String TENANT_SECTION = "你是礼遇顾问，请只回答与礼遇相关的问题。";
    private static final String GUIDELINE_SECTION = "调用工具时请遵守以下纪律：只传必填参数。";

    private ObjectMapper objectMapper;
    private AiChatClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        AppProperties properties = new AppProperties();
        properties.getAi().setBaseUrl("http://127.0.0.1:1/v1");
        properties.getAi().setApiKey("test-key");
        client = new AiChatClient(HttpClient.newHttpClient(), properties, objectMapper,
                new StreamWatchdog());
    }

    @Test
    @DisplayName("🔴 2 条 system → IllegalStateException（绝不静默合并、绝不照发）")
    void twoSystemMessagesRejected() {
        AiChatRequest request = request(List.of(
                AiMessage.system(TENANT_SECTION),
                AiMessage.system(GUIDELINE_SECTION),
                AiMessage.user("帮我搜一下最近的新闻")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.buildBody(request));

        assertTrue(ex.getMessage().contains("systemCount=2"),
                "🔴 message 必须含结构性事实：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("total=3"), ex.getMessage());
    }

    @Test
    @DisplayName("🔴 system 不在 index 0（前面有 user）→ IllegalStateException")
    void systemNotAtIndexZeroRejected() {
        AiChatRequest request = request(List.of(
                AiMessage.user("帮我搜一下最近的新闻"),
                AiMessage.system(TENANT_SECTION)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.buildBody(request));

        assertTrue(ex.getMessage().contains("firstSystemIndex=1"),
                "🔴 必须报出首个 system 的下标：" + ex.getMessage());
    }

    @Test
    @DisplayName("🔴🔴 异常 message 不含任何消息正文（system 与 user 都是内部/用户数据）")
    void exceptionMessageCarriesNoContent() {
        AiChatRequest request = request(List.of(
                AiMessage.system(TENANT_SECTION),
                AiMessage.system(GUIDELINE_SECTION),
                AiMessage.user("我的手机号是 13812345678")));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.buildBody(request));

        String message = ex.getMessage();
        assertFalse(message.contains(TENANT_SECTION), "🔴 不得含租户段正文：" + message);
        assertFalse(message.contains(GUIDELINE_SECTION), "🔴 不得含纪律段正文：" + message);
        assertFalse(message.contains("13812345678"), "🔴 不得含用户输入：" + message);
        assertFalse(message.contains("礼遇"), "🔴 不得含正文片段：" + message);
    }

    @Test
    @DisplayName("1 条 system 在 index 0 → 正常构体，且 tools 下发行为不受影响")
    void singleLeadingSystemPassesAndToolsUnaffected() throws Exception {
        AiChatRequest request = new AiChatRequest("hunyuan-a13b",
                List.of(AiMessage.system(TENANT_SECTION + "\n\n" + GUIDELINE_SECTION),
                        AiMessage.user("算一下 (1+2)*3")),
                new BigDecimal("0.70"), 4096, 60, 30,
                List.of(ToolDefinition.of(ToolDefinition.TYPE_LOCAL, "calculator", "calculator",
                        "四则运算", "{\"type\":\"object\",\"properties\":{\"expression\":"
                                + "{\"type\":\"string\"}},\"required\":[\"expression\"]}",
                        "digest", "low", false, true, 5, null, null)));

        JsonNode body = objectMapper.readTree(client.buildBody(request));

        JsonNode messages = body.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText(),
                "🔴 唯一那条 system 必须是第一条");
        assertTrue(messages.get(0).get("content").asText().endsWith(GUIDELINE_SECTION),
                "🔴 纪律段恒为 system 末块（合并由 ContextAssembler 完成）");
        assertEquals(1, body.get("tools").size(), "🔴 tools 下发行为不受守卫影响");
        assertEquals("calculator",
                body.get("tools").get(0).get("function").get("name").asText());
        assertEquals("auto", body.get("tool_choice").asText());
    }

    @Test
    @DisplayName("完全没有 system（纯 user）→ 合法（「无 system 也合法」的既有行为不变）")
    void noSystemIsLegal() throws Exception {
        AiChatRequest request = request(List.of(AiMessage.user("你好")));

        JsonNode body = objectMapper.readTree(client.buildBody(request));

        assertEquals(1, body.get("messages").size());
        assertFalse(body.has("tools"), "🔴 无可用工具时不下发 tools 字段（不得下发空数组）");
    }

    private AiChatRequest request(List<AiMessage> messages) {
        return new AiChatRequest("hunyuan-a13b", messages, new BigDecimal("0.70"), 4096, 60, 30);
    }
}
