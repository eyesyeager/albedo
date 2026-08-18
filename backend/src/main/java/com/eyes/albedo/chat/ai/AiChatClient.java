package com.eyes.albedo.chat.ai;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.config.AppProperties;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上游模型流式客户端（OpenAI 兼容 / 混元）。
 *
 * <p>技术选择（ADR-006）：JDK17 {@link HttpClient} + {@code BodyHandlers.ofInputStream()} 逐行解析 SSE。
 * 不引入 WebFlux / OkHttp / spring-ai：本项目只有"流式输出"一个长连接场景，
 * 引入响应式栈会让 JPA 阻塞与响应式混用，是典型事故源。
 *
 * <p>本类<b>必须在 {@code aiStreamExecutor} 线程内调用</b>（阻塞式读取）。
 *
 * <p>🔴 <b>上游硬约束：单一前导 {@code system} 消息</b>（ADR-019 ① / api-spec §7.5.2）：
 * 发往上游的 {@code messages} 中 {@code role=system} <b>至多 1 条且必须位于 index 0</b>。
 * 违反时上游直接返回 {@code status=400}（约束语义：「system 角色必须位于列表的最开始」），
 * 本次生成在<b>进入工具调用之前</b>就以 {@code 50002} 收敛（BUG-MCP-004 实测）。
 * 🔴 因此 {@link #buildBody} 在构造请求体前做 <b>fail-fast 断言</b>
 * （{@link #assertSingleLeadingSystem}）：
 * <ul>
 *   <li>❌ <b>禁止</b>在传输层"静默自动合并 / 重排" —— 那会把契约违反藏起来，
 *       并让"谁负责拼 system"从 {@code ContextAssembler} 漂移到传输层（顺序由传输层猜，
 *       哪块在前 = 哪块被覆盖，语义不可控）</li>
 *   <li>❌ <b>禁止</b>"WARN 后照发" —— 上游必然 400，用户拿到 {@code 50002}，
 *       而日志里只有上游报错、没有平台自己的判定，排障更难</li>
 * </ul>
 * 违反即抛 {@link IllegalStateException}，由 {@code ChatStreamRunner} 既有的
 * {@code catch (RuntimeException)} 收敛为 {@code error(50003)} + {@code done(failed)}
 * （🔴 {@code done} 必发不变、零新错误码）。
 *
 * <p>可靠性要点：
 * <ul>
 *   <li>首字超时 + 整体超时由 {@link StreamWatchdog} 关流实现（{@code HttpRequest.timeout} 只管到响应头）</li>
 *   <li>用户停止：{@code cancelCheck} 每 N 个分片检查一次，命中即返回 {@code cancelled=true}</li>
 *   <li>🔴 日志与异常<b>绝不</b>包含 api_key、完整上游 URL 或消息正文</li>
 * </ul>
 */
@Slf4j
@Component
public class AiChatClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";
    private static final int ERROR_BODY_PREVIEW_LIMIT = 512;

    /**
     * 思维链字段名候选（🔴 均为 OpenAI 兼容实现的**非标准扩展**，官方规范里没有这个字段）。
     *
     * <p>顺序即探测优先级：{@code reasoning_content} 为混元/DeepSeek 系用法（本项目实测），
     * {@code reasoning} 为部分网关的透传命名。
     */
    private static final List<String> REASONING_FIELDS = List.of("reasoning_content", "reasoning");

    private final HttpClient httpClient;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final StreamWatchdog watchdog;

    public AiChatClient(HttpClient httpClient,
                        AppProperties properties,
                        ObjectMapper objectMapper,
                        StreamWatchdog watchdog) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.watchdog = watchdog;
    }

    /**
     * 发起流式生成。
     *
     * <p>🔴 <b>本方法必须是 {@code stream} 的唯一签名，禁止再加"便捷重载"</b>。
     * 曾经存在一个不含 {@code onReasoning} 的 5 参重载（内部委托到本方法），
     * 生产代码只用 6 参版，而它成了测试侧的默认桩位：
     * 「思考过程」特性上线后，各 IT 的 {@code @MockBean} 仍只桩 5 参版，
     * 未被桩住的 6 参版返回 {@code null} → {@code outcome.cancelled()} NPE →
     * 32 个 IT 集体退化为 {@code [meta, error, done]} + 50003，且因为编译期毫无提示而静默红了一段时间。
     * 保持"唯一签名"可让编译器强制所有调用方与桩位对齐（守护见 {@code AiStreamStubDisciplineTest}）。
     *
     * @param request       生成请求
     * @param onDelta       正文分片回调（按到达顺序调用）
     * @param onReasoning   思考过程分片回调（推理型模型的 {@code reasoning_content}；
     *                      🔴 与 {@code onDelta} 严格分流，绝不混入正文 —— 否则会污染落库内容与会话标题）
     * @param onOpen        上游流建立后回调，交出可关闭句柄供「停止生成」立即关流
     * @param cancelCheck   取消检查（每 {@code checkIntervalChunks} 个分片调用一次）
     * @param checkIntervalChunks 取消检查间隔（分片数）
     * @return 生成结果
     * @throws AiStreamException 上游不可用或超时
     */
    public AiStreamOutcome stream(AiChatRequest request,
                                  Consumer<String> onDelta,
                                  Consumer<String> onReasoning,
                                  Consumer<Closeable> onOpen,
                                  BooleanSupplier cancelCheck,
                                  int checkIntervalChunks) {
        HttpResponse<InputStream> response = send(request);
        int status = response.statusCode();
        if (status != 200) {
            String preview = readErrorPreview(response.body());
            // 只记录状态码与截断后的上游提示，不记录请求体（含用户正文）与凭据
            // 🔴 限流（429 / rate_limit_exceeded）必须区分对待：抛 RATE_LIMITED 让前端进入"限流等待"，
            //    而不是误报成"模型服务暂不可用"（EX-LOG-001 实测：混元 400 rate_limit_exceeded 被误报）
            if (status == 429 || isRateLimited(preview)) {
                log.warn("上游模型限流：status={} preview={}", status, preview);
                // 混元限流多只提示「请求频繁，请稍后再试」而不带具体秒数；
                // 🔴 这里以服务端身份给出保守重试估计（30s），供前端倒计时与文案插值，
                //    不是前端硬编码、也不是猜测——是平台侧的统一限流窗口估算。
                throw AiStreamException.rateLimited("模型请求过于频繁，已被限流，请稍后再试", 30);
            }
            log.error("上游模型返回非 200：status={} preview={}", status, preview);
            throw AiStreamException.upstream("模型服务暂不可用，请稍后重试");
        }

        InputStream body = response.body();
        AtomicBoolean firstActivity = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        onOpen.accept(body);
        ScheduledFuture<?> firstTokenGuard = watchdog.schedule(() -> {
            if (!firstActivity.get()) {
                timedOut.set(true);
                closeQuietly(body);
                log.warn("上游首帧超时，已关闭上游流：timeout={}s", request.firstTokenTimeoutSeconds());
            }
        }, request.firstTokenTimeoutSeconds());
        ScheduledFuture<?> overallGuard = watchdog.schedule(() -> {
            timedOut.set(true);
            closeQuietly(body);
            log.warn("生成整体超时，已关闭上游流：timeout={}s", request.requestTimeoutSeconds());
        }, request.requestTimeoutSeconds());

        String finishReason = "";
        TokenUsage usage = null;
        int chunkCount = 0;
        int interval = Math.max(checkIntervalChunks, 1);
        // 🔴 流式 tool_calls 必须按 index 累积：上游把 function.arguments 切成多个分片下发，
        //    单帧里拿到的往往只是 "{\"or" 这种半截 JSON（M1 没有这段逻辑，是 M3 新增的坑）
        Map<Integer, ToolCallAccumulator> toolCallBuffer = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || !line.startsWith(SSE_DATA_PREFIX)) {
                    // 忽略 SSE 注释帧（: ping）与 event: 行
                    continue;
                }
                String payload = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (SSE_DONE.equals(payload)) {
                    break;
                }
                JsonNode node = parse(payload);
                if (node == null) {
                    continue;
                }
                if (node.hasNonNull("error")) {
                    JsonNode err = node.path("error");
                    String type = err.path("type").asText("");
                    String code = err.path("code").asText("");
                    // 🔴 流中错误帧也可能是限流，同样区分对待
                    if (isRateLimited(type) || isRateLimited(code)) {
                        log.warn("上游模型限流（流中错误帧）：type={} code={}", type, code);
                        throw AiStreamException.rateLimited("模型请求过于频繁，已被限流，请稍后再试", 30);
                    }
                    log.error("上游模型返回错误帧：type={} code={}", type, code);
                    throw AiStreamException.upstream("模型服务返回错误，请稍后重试");
                }

                // 「首字看门狗」以<b>上游是否有响应</b>为判据，而不是"是否已产出正文"：
                // 推理型模型（如 hunyuan-a13b）会先输出 reasoning_content 思维链，content 长时间为空，
                // 若按正文判定会把正常的推理过程误杀为超时。上游彻底无响应仍由整体超时兜住。
                if (firstActivity.compareAndSet(false, true)) {
                    watchdog.cancel(firstTokenGuard);
                }

                String delta = extractDelta(node);
                if (delta != null && !delta.isEmpty()) {
                    onDelta.accept(delta);
                    chunkCount++;
                    if (chunkCount % interval == 0 && cancelCheck.getAsBoolean()) {
                        cancelled.set(true);
                        break;
                    }
                }
                // 思考过程独立通道（🔴 不进 onDelta：那会让思维链被拼进落库正文与会话标题）。
                // 🔴 同样计入 chunkCount：推理阶段可能持续数秒且不产出正文，
                //    若不在此检查取消，用户点「停止生成」要等到正文开始才生效。
                String reasoning = extractReasoning(node);
                if (reasoning != null && !reasoning.isEmpty()) {
                    onReasoning.accept(reasoning);
                    chunkCount++;
                    if (chunkCount % interval == 0 && cancelCheck.getAsBoolean()) {
                        cancelled.set(true);
                        break;
                    }
                }
                // 🔴 工具调用分片：累积但**不立即**回调（参数不完整时无法校验，更不能执行）
                accumulateToolCalls(node, toolCallBuffer);
                String reason = extractFinishReason(node);
                if (reason != null && !reason.isBlank()) {
                    finishReason = reason;
                }
                TokenUsage parsedUsage = extractUsage(node);
                if (parsedUsage != null) {
                    usage = parsedUsage;
                }
            }
        } catch (IOException e) {
            // 关流导致的 IOException：区分「用户停止」「超时」「真实故障」三种收敛路径
            if (cancelCheck.getAsBoolean()) {
                cancelled.set(true);
            } else if (timedOut.get()) {
                throw AiStreamException.timeout("模型响应超时，请重试");
            } else {
                log.error("读取上游流失败：cause={}", e.getClass().getSimpleName());
                throw AiStreamException.upstream("模型连接中断，已保留已生成内容");
            }
        } finally {
            watchdog.cancel(firstTokenGuard);
            watchdog.cancel(overallGuard);
            closeQuietly(body);
        }

        if (!cancelled.get() && timedOut.get()) {
            throw AiStreamException.timeout("模型响应超时，请重试");
        }
        List<AiToolCall> toolCalls = cancelled.get() ? List.of() : finishToolCalls(toolCallBuffer);
        return new AiStreamOutcome(finishReason, usage, cancelled.get(), toolCalls);
    }

    // ===================== 内部实现 =====================

    private HttpResponse<InputStream> send(AiChatRequest request) {
        String baseUrl = properties.getAi().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw AiStreamException.upstream("模型服务未配置");
        }
        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl)
                        + CHAT_COMPLETIONS_PATH))
                .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // 该超时只覆盖到响应头；响应体阶段由 StreamWatchdog 负责
                .timeout(Duration.ofSeconds(Math.max(request.firstTokenTimeoutSeconds(), 1)))
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(request), StandardCharsets.UTF_8))
                .build();
        try {
            return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw AiStreamException.timeout("模型响应超时，请重试");
        } catch (IOException e) {
            log.error("请求上游模型失败：cause={}", e.getClass().getSimpleName());
            throw AiStreamException.upstream("模型服务暂不可用，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AiStreamException.upstream("生成被中断");
        }
    }

    /**
     * 构造请求体（🔴 包级可见仅为 {@code AiChatClientSystemMessageGuardTest} 直接验证结构守卫，
     * 生产调用点只有 {@link #send}）。
     */
    String buildBody(AiChatRequest request) {
        // 🔴 结构性守护必须在构体之前：违反单一前导 system 不变量时，一个字节都不发给上游
        assertSingleLeadingSystem(request.messages());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("stream", true);
        body.put("temperature", request.temperature());
        body.put("max_tokens", request.maxOutputTokens());
        List<Map<String, Object>> messages = new ArrayList<>(request.messages().size());
        for (AiMessage message : request.messages()) {
            messages.add(messagePayload(message));
        }
        body.put("messages", messages);
        // 🔴 无可用工具时**不下发** tools 字段（不得下发空数组：部分兼容实现会直接 400）
        if (request.hasTools()) {
            body.put("tools", toolsPayload(request));
            body.put("tool_choice", "auto");
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw AiStreamException.upstream("生成请求构造失败");
        }
    }

    /**
     * 🔴 <b>单一前导 {@code system} 不变量的 fail-fast 断言</b>（ADR-019 ④）。
     *
     * <p>{@code role=system} 计数 &gt;1，或计数 ==1 但下标 !=0 → 抛 {@link IllegalStateException}。
     * 🔴 异常 {@code message} 只含<b>结构性事实</b>（system 条数、首个 system 下标、消息总数），
     * 🔴 <b>严禁</b>包含任何消息正文 —— 正文里有租户 {@code systemPrompt}、Skill 指令与用户输入。
     *
     * <p>🔴 <b>为什么在这里 fail-fast 而不是静默修补</b>：契约违反必须在测试/预发阶段就炸出来。
     * 生产侧"精确的 {@code 50003} + 平台侧判定日志"严格优于"上游 400 → {@code 50002} +
     * 无平台侧线索"（与 §11「静默降级最危险」一致）。
     * 🔴 正常路径<b>永不可达</b>：一旦出现即判 {@code ContextAssembler} 装配缺陷。
     */
    private void assertSingleLeadingSystem(List<AiMessage> messages) {
        int systemCount = 0;
        int firstSystemIndex = -1;
        for (int index = 0; index < messages.size(); index++) {
            if (AiMessage.ROLE_SYSTEM.equals(messages.get(index).role())) {
                systemCount++;
                if (firstSystemIndex < 0) {
                    firstSystemIndex = index;
                }
            }
        }
        if (systemCount > 1 || (systemCount == 1 && firstSystemIndex != 0)) {
            // 🔴 只记结构性事实，绝不记正文
            log.error("🔴 违反单一前导 system 不变量（ADR-019 ①），本次生成不发往上游："
                    + "systemCount={} firstSystemIndex={} total={}",
                    systemCount, firstSystemIndex, messages.size());
            throw new IllegalStateException("违反单一前导 system 不变量：systemCount=" + systemCount
                    + " firstSystemIndex=" + firstSystemIndex + " total=" + messages.size());
        }
    }

    /**
     * 单条消息的 OpenAI 兼容形态（含工具调用链）。
     */
    private Map<String, Object> messagePayload(AiMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("role", message.role());
        payload.put("content", message.content());
        if (AiMessage.ROLE_TOOL.equals(message.role())) {
            // 🔴 必须带 tool_call_id，且与 assistant.tool_calls[].id 一一对应
            payload.put("tool_call_id", message.toolCallId());
        }
        if (message.hasToolCalls()) {
            List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
            for (AiToolCall call : message.toolCalls()) {
                calls.add(Map.of("id", call.id(), "type", "function",
                        "function", Map.of("name", call.name(),
                                "arguments", call.argumentsJson())));
            }
            payload.put("tool_calls", calls);
        }
        return payload;
    }

    /**
     * 工具定义下发（🔴 只下发名称 / 说明 / 入参 Schema —— 不含风险等级、幂等性、超时、
     * MCP endpoint、凭据：那些是<b>平台侧判定依据</b>，让模型看见毫无用处且扩大泄露面）。
     */
    private List<Map<String, Object>> toolsPayload(AiChatRequest request) {
        List<Map<String, Object>> tools = new ArrayList<>(request.tools().size());
        for (ToolDefinition definition : request.tools()) {
            Map<String, Object> function = new LinkedHashMap<>();
            // 🔴 下发**归一化后的 functionName**，不是 toolKey（后者含 : 会被上游 400，api-spec §7.6.5）
            function.put("name", definition.functionName());
            function.put("description", definition.description() == null
                    ? "" : definition.description());
            function.put("parameters", parseSchema(definition));
            tools.add(Map.of("type", "function", "function", function));
        }
        return tools;
    }

    /**
     * 入参 Schema 文本 → JSON 结构（Schema 自身合法性已在清单构造阶段以 {@code 30060} 保证）。
     */
    private Object parseSchema(ToolDefinition definition) {
        String schema = definition.inputSchema();
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readTree(schema);
        } catch (JsonProcessingException e) {
            // 理论上不可达（清单构造已校验）；兜底给一个空对象 Schema，绝不把非法文本塞进请求
            log.warn("工具 Schema 无法解析，按无参下发：toolKey={}", definition.toolKey());
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    /**
     * 累积流式 {@code tool_calls} 分片（🔴 按 {@code index} 聚合，参数文本按到达顺序拼接）。
     */
    private void accumulateToolCalls(JsonNode node, Map<Integer, ToolCallAccumulator> buffer) {
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode calls = choices.get(0).path("delta").path("tool_calls");
        if (!calls.isArray()) {
            // 兼容非流式返回形态（部分实现在 message.tool_calls 一次性给全）
            calls = choices.get(0).path("message").path("tool_calls");
            if (!calls.isArray()) {
                return;
            }
        }
        for (JsonNode call : calls) {
            int index = call.path("index").isInt() ? call.path("index").asInt() : buffer.size();
            ToolCallAccumulator accumulator =
                    buffer.computeIfAbsent(index, key -> new ToolCallAccumulator());
            // 🔴 空串**不得覆盖**已累积值：混元会在后续分片把 id/name 回传为 ""（只带 arguments 增量），
            //    若按"字段存在即赋值"处理，首片拿到的 name 会被清空 → finishToolCalls 误判缺 name 而丢弃整次调用
            String id = call.path("id").asText("");
            if (!id.isBlank()) {
                accumulator.id = id;
            }
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (!name.isBlank()) {
                accumulator.name = name;
            }
            if (function.hasNonNull("arguments")) {
                accumulator.arguments.append(function.get("arguments").asText());
            }
        }
    }

    /**
     * 收尾：把累积结果转为不可变列表（🔴 缺 id 或 name 的分片一律丢弃并告警 ——
     * 那种调用无法回灌 {@code tool_call_id}，硬塞给上游会让整轮请求失败）。
     */
    private List<AiToolCall> finishToolCalls(Map<Integer, ToolCallAccumulator> buffer) {
        if (buffer.isEmpty()) {
            return List.of();
        }
        List<AiToolCall> calls = new ArrayList<>(buffer.size());
        for (Map.Entry<Integer, ToolCallAccumulator> entry : buffer.entrySet()) {
            ToolCallAccumulator accumulator = entry.getValue();
            AiToolCall call = new AiToolCall(accumulator.id, accumulator.name,
                    accumulator.arguments.toString());
            if (call.usable()) {
                calls.add(call);
            } else {
                // 🔴 只记结构性事实（是否缺 id / 缺 name），不打印 arguments —— 那里可能有用户数据
                log.warn("忽略缺少 id/name 的工具调用分片（无法回灌 tool_call_id）："
                                + "index={} idPresent={} namePresent={} argsLen={}",
                        entry.getKey(), !accumulator.id.isBlank(), !accumulator.name.isBlank(),
                        accumulator.arguments.length());
            }
        }
        return calls;
    }

    /** 单个 {@code tool_calls[index]} 的累积器。 */
    private static final class ToolCallAccumulator {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            // 单帧解析失败不终止整个生成（上游可能插入非标准帧）
            log.debug("忽略无法解析的上游分片");
            return null;
        }
    }

    private String extractDelta(JsonNode node) {
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode delta = choices.get(0).path("delta");
        if (delta.hasNonNull("content")) {
            return delta.get("content").asText();
        }
        // 兼容部分实现在非流式字段返回内容
        JsonNode message = choices.get(0).path("message");
        return message.hasNonNull("content") ? message.get("content").asText() : null;
    }

    /**
     * 提取推理型模型的思维链增量（OpenAI 兼容实现的<b>非标准扩展字段</b>）。
     *
     * <p>字段名各家不一，按出现频次依次探测；🔴 任一家都没有时返回 {@code null}
     * （非推理模型不受影响，不会凭空产出思考帧）。
     */
    private String extractReasoning(JsonNode node) {
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode choice = choices.get(0);
        for (String field : REASONING_FIELDS) {
            JsonNode delta = choice.path("delta");
            if (delta.hasNonNull(field)) {
                return delta.get(field).asText();
            }
            JsonNode message = choice.path("message");
            if (message.hasNonNull(field)) {
                return message.get(field).asText();
            }
        }
        return null;
    }

    private String extractFinishReason(JsonNode node) {
        JsonNode choices = node.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode reason = choices.get(0).path("finish_reason");
        return reason.isNull() || reason.isMissingNode() ? null : reason.asText();
    }

    private TokenUsage extractUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return null;
        }
        return new TokenUsage(
                usage.path("prompt_tokens").isMissingNode() ? null : usage.path("prompt_tokens").asInt(),
                usage.path("completion_tokens").isMissingNode() ? null : usage.path("completion_tokens").asInt(),
                usage.path("total_tokens").isMissingNode() ? null : usage.path("total_tokens").asInt());
    }

    /**
     * 判断上游错误是否属「模型限流」。
     *
     * <p>覆盖的常见形态（OpenAI 兼容 / 混元）：
     * <ul>
     *   <li>HTTP {@code 429}</li>
     *   <li>错误体 {@code type=rate_limit_error} 或 {@code code=rate_limit_exceeded}（混元实测）</li>
     *   <li>错误体 {@code code=rate_limit} / 含 {@code "rate_limit"} 子串</li>
     * </ul>
     * 🔴 入参可能为空或不区分大小写，统一按小写包含匹配；只匹配关键子串，不记录也无法记录请求体。
     */
    private boolean isRateLimited(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("rate_limit") || lower.contains("rate limited")
                || lower.contains("too many requests") || lower.contains("请求频繁");
    }

    private String readErrorPreview(InputStream body) {
        if (body == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1 && sb.length() < ERROR_BODY_PREVIEW_LIMIT) {
                sb.append((char) ch);
            }
            return sb.toString().replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "";
        }
    }

    private void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            log.debug("关闭上游流时忽略异常：{}", e.getClass().getSimpleName());
        }
    }

    private String trimTrailingSlash(String url) {
        String value = url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
