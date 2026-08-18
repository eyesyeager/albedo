package com.eyes.albedo.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * 🔴 <b>「严格校验 system 形态」的上游模型替身</b>（仅 {@code src/test}；ADR-019 ⑤ / api-spec §8.3 J12）。
 *
 * <p><b>为什么必须有这个替身</b>（BUG-MCP-004 的结构性成因）：既有 IT/E2E 全部用
 * {@code @MockBean AiChatClient} 打桩，桩<b>不校验消息形态</b> —— 于是"纪律段作为第二条
 * {@code system} 消息"这个<b>必然</b>被真实上游 400 拒绝的形态，溜过了 731 个自动化用例，
 * 只能靠人工浏览器复验发现。本类把真实上游的硬约束<b>复刻进桩</b>，并让请求
 * 走<b>真实 HTTP 栈</b>（生产 {@code AiChatClient} + JDK {@code HttpClient} + 真实 JSON 构体），
 * 从而使同类缺陷在 CI 里就必然暴露。
 *
 * <p><b>复刻的上游硬约束（唯一一条）</b>：
 * <pre>
 * messages 中 role=system 多于 1 条，或存在但不在 index 0
 *   → HTTP 400 + {"error":{"message":"messages 中 system 角色必须位于列表的最开始"}}
 * </pre>
 * 🔴 这与混元 OpenAI 兼容接口的实测行为一致（test-report V4.1 BUG-MCP-004）。
 *
 * <p><b>正常应答形态</b>（OpenAI 兼容流式）：
 * <pre>
 * 第一轮（messages 里没有 role=tool）→ 下发 tool_calls 分片 + finish_reason=tool_calls
 * 第二轮（messages 里已有 role=tool）→ 下发正文分片 + finish_reason=stop + usage
 * </pre>
 *
 * <p>🔴 <b>请求体全量留存</b>（{@link #receivedBodies()}）：用例据此断言"桩<b>实收</b>的
 * 请求体里 system 恰 1 条、在 index 0、且内容以纪律段结尾" —— 这是反向守护
 * 「靠删/清空 {@code chat.tool_usage_guideline} 让测试变绿」的唯一手段。
 *
 * <p>⚠️ 测试替身内部用固定线程池承载 HTTP 处理，与生产侧"不新增线程池"的纪律无关。
 */
public final class StrictSystemFormUpstream implements AutoCloseable {

    /** 🔴 上游拒绝形态非法请求时回的状态码（复刻实测）。 */
    public static final int STATUS_BAD_FORM = 400;
    /** 🔴 上游的约束语义文案（复刻实测；只用于桩应答体，不进生产代码）。 */
    public static final String BAD_FORM_MESSAGE = "messages 中 system 角色必须位于列表的最开始";

    private static final String PATH = "/v1/chat/completions";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_TOOL = "tool";

    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 🔴 实收请求体全量留存（顺序 = 轮次顺序）。 */
    private final List<String> receivedBodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger rejectedCount = new AtomicInteger();
    private final AtomicReference<Script> script = new AtomicReference<>(
            new Script(null, null, "好的"));

    public StrictSystemFormUpstream() throws IOException {
        this.executor = Executors.newFixedThreadPool(4);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext(PATH, this::handle);
        this.server.start();
    }

    /** {@code app.ai.base-url} 取值。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 剧本：第一轮请求工具、第二轮出文本。 */
    public void scriptToolThenText(String functionName, String argumentsJson, String finalText) {
        script.set(new Script(functionName, argumentsJson, finalText));
    }

    /** 剧本：只出文本（不请求任何工具）。 */
    public void scriptTextOnly(String finalText) {
        script.set(new Script(null, null, finalText));
    }

    /** 🔴 桩实收的请求体（每轮一条，原样保留）。 */
    public List<String> receivedBodies() {
        return List.copyOf(receivedBodies);
    }

    /**
     * 🔴 因 system 形态非法而被回 400 的次数。
     *
     * <p>用例必须断言它为 {@code 0}：不为 0 即说明装配侧又拼出了多条 system
     * （或 system 不在首位），也就是 BUG-MCP-004 复发。
     */
    public int rejectedCount() {
        return rejectedCount.get();
    }

    public void reset() {
        receivedBodies.clear();
        rejectedCount.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ===================== 请求处理 =====================

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        receivedBodies.add(body);
        try {
            JsonNode messages = objectMapper.readTree(body).path("messages");
            if (violatesSingleLeadingSystem(messages)) {
                rejectedCount.incrementAndGet();
                respond(exchange, STATUS_BAD_FORM, "application/json",
                        "{\"error\":{\"message\":\"" + BAD_FORM_MESSAGE + "\",\"type\":"
                                + "\"invalid_request_error\"}}");
                return;
            }
            respond(exchange, 200, "text/event-stream", frames(messages));
        } catch (RuntimeException e) {
            // 桩内部异常也按 400 回，避免用例卡在等待流上
            respond(exchange, STATUS_BAD_FORM, "application/json",
                    "{\"error\":{\"message\":\"stub failure\"}}");
        }
    }

    /** 🔴 复刻上游硬约束：{@code system} 至多 1 条，且必须位于 {@code index 0}。 */
    private boolean violatesSingleLeadingSystem(JsonNode messages) {
        int systemCount = 0;
        int firstSystemIndex = -1;
        for (int index = 0; index < messages.size(); index++) {
            if (ROLE_SYSTEM.equals(messages.get(index).path("role").asText(""))) {
                systemCount++;
                if (firstSystemIndex < 0) {
                    firstSystemIndex = index;
                }
            }
        }
        return systemCount > 1 || (systemCount == 1 && firstSystemIndex != 0);
    }

    /** 本轮应答帧：已有 {@code role=tool} → 出文本；否则按剧本请求工具。 */
    private String frames(JsonNode messages) {
        Script current = script.get();
        boolean toolResultFedBack = false;
        for (JsonNode message : messages) {
            if (ROLE_TOOL.equals(message.path("role").asText(""))) {
                toolResultFedBack = true;
                break;
            }
        }
        if (current.functionName() != null && !toolResultFedBack) {
            return toolCallFrames(current);
        }
        return textFrames(current.finalText());
    }

    private String toolCallFrames(Script current) {
        String arguments = quote(current.argumentsJson());
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\","
                + "\"tool_calls\":[{\"index\":0,\"id\":\"call-mcp004-1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"" + current.functionName() + "\",\"arguments\":"
                + arguments + "}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private String textFrames(String text) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":" + quote(text)
                + "},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,"
                + "\"completion_tokens\":20,\"total_tokens\":30}}\n\n"
                + "data: [DONE]\n\n";
    }

    /** JSON 字符串字面量（转义交给 Jackson，避免手写转义出错）。 */
    private String quote(String raw) {
        try {
            return objectMapper.writeValueAsString(raw == null ? "" : raw);
        } catch (IOException e) {
            return "\"\"";
        }
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
        exchange.close();
    }

    /**
     * 应答剧本。
     *
     * @param functionName  第一轮要请求的模型函数名（null = 不请求工具）
     * @param argumentsJson 工具入参 JSON 文本
     * @param finalText     最后一轮的正文
     */
    private record Script(String functionName, String argumentsJson, String finalText) {
    }
}
