package com.eyes.albedo.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.dto.McpCallResult;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP JSON-RPC 2.0 客户端（api-spec §7.6.2 / §7.6.4，REQ-MCP-003 / AC-MCP-003）。
 *
 * <p>🔴 <b>事务纪律（ADR-010，本类最容易被误用的地方）</b>：
 * 本类的所有公开方法都做<b>网络 I/O</b>，🔴 <b>严禁</b>在 {@code @Transactional} 方法内调用。
 * 工具执行必须切三段：① 短事务提交 {@code running} → ② <b>事务外</b>调用本类 →
 * ③ 短事务写终态 + 审计。违反会把工具执行时长算进数据库事务，
 * 并与 confirm 接口的 {@code SELECT … FOR UPDATE} 互锁（ADR-010 致命自锁）。
 *
 * <p>🔴 <b>上游结果映射（api-spec §7.6.4，唯一实现）</b>：
 * <pre>
 *   HTTP 2xx + isError=false        → 正常返回
 *   result.isError=true             → McpCallResult.isError=true（调用方判 30057）
 *   JSON-RPC -32602                 → 30053（入参不合法，🔴 不重试同参）
 *   JSON-RPC -32601 / 非 JSON-RPC   → 30052
 *   HTTP 401/403                    → 30052（写审计）
 *   连接失败 / 5xx / 3xx            → 30052
 *   超时                            → 30051
 * </pre>
 *
 * <p>🔴 <b>超时口径</b>：
 * {@code tools/call} = {@code min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)}
 * （architecture.md §13.5.3 取较小值）；{@code tools/list} = {@code mcp.discover_timeout_seconds}。
 *
 * <p>🔴 <b>传输取舍</b>：按 {@code mcp_servers.transport} 选择实现；
 * 该列非法（含 {@code stdio}）→ {@code 30060}，🔴 不落库、不调用。
 * {@code sys_config: mcp.transport_preferred} 用于"上游两种都支持时的偏好"。
 * ✅ <b>已裁决（api-spec V1.1.2 §7.6.2 G9）</b>：一期<b>不发送</b> {@code initialize}、
 * <b>不维护</b> {@code sessionId} / {@code Mcp-Session-Id} —— 因此没有能力协商，
 * 实际取舍以 {@code mcp_servers.transport} 为准，偏好项只在该列缺失时兜底。
 * 🔴 上游若强制要求先 {@code initialize}（或强制会话头）→ {@code protocol_incompatible} /
 * {@code 30052}；@后端 <b>不得</b>私自引入会话状态。
 *
 * <p>🔴 <b>V1.2.0 限定修订（api-spec §7.6.2 G9′ / ADR-016 ⑩）</b>：
 * <ul>
 *   <li>{@code streamable_http}：G9 原文<b>逐字不变</b>（仍不发 {@code initialize}、
 *       不维护 {@code Mcp-Session-Id}）</li>
 *   <li>{@code sse}：允许在<b>单次 exchange 内</b>做一次性握手（{@code initialize} 兼作形态探测），
 *       会话生命周期严格 ⊂ 该次 exchange —— 🔴 这层细节完全封装在 {@link SseTransport} 内，
 *       <b>本类不感知会话</b>，"系统不持有任何跨请求 MCP 会话状态"这条不变量仍然成立</li>
 * </ul>
 */
@Slf4j
@Component
public class McpJsonRpcClient implements McpClient {

    private static final String METHOD_TOOLS_LIST = "tools/list";
    private static final String METHOD_TOOLS_CALL = "tools/call";

    /** JSON-RPC 标准错误码：invalid params → 30053（api-spec §7.6.4）。 */
    private static final int RPC_INVALID_PARAMS = -32602;
    /** JSON-RPC 标准错误码：method not found → 30052。 */
    private static final int RPC_METHOD_NOT_FOUND = -32601;

    /** 上游鉴权类错误的 JSON-RPC 码区间（社区实现常用 -32000 段自定义）。 */
    private static final int RPC_SERVER_ERROR_MIN = -32099;
    private static final int RPC_SERVER_ERROR_MAX = -32000;

    /** 翻页保护：即便上游 {@code nextCursor} 永不为空也必须收敛。 */
    private static final int MAX_PAGES = 50;

    private final Map<String, McpTransport> transports = new LinkedHashMap<>();
    private final McpCredentialResolver credentialResolver;
    private final BusinessConfig businessConfig;
    private final ObjectMapper objectMapper;

    public McpJsonRpcClient(List<McpTransport> transportList,
                            McpCredentialResolver credentialResolver,
                            BusinessConfig businessConfig,
                            ObjectMapper objectMapper) {
        for (McpTransport transport : transportList) {
            this.transports.put(transport.transport(), transport);
        }
        this.credentialResolver = credentialResolver;
        this.businessConfig = businessConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<McpToolDescriptor> listTools(McpServer server) {
        McpTransport transport = requireTransport(server);
        Map<String, String> authHeaders = credentialResolver.authHeaders(server);
        Duration timeout = Duration.ofSeconds(businessConfig.requireInt(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_DISCOVER_TIMEOUT_SECONDS));
        // 🔴 ADR-016 ⑤：discover 的**翻页不得重置预算** —— 整个 tools/list（含全部翻页）
        //    共用 mcp.discover_timeout_seconds 这一个 deadline，否则 50 页 × 完整超时
        //    可让一次"工具发现"占用线程数分钟（api-spec §7.4.3 V1.2.0 补注）。
        AbstractMcpTransport.Deadline deadline = AbstractMcpTransport.Deadline.after(timeout);

        List<McpToolDescriptor> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode result = invoke(transport, server, authHeaders,
                    METHOD_TOOLS_LIST, listParams(cursor), deadline.remaining());
            JsonNode toolsNode = result.get("tools");
            if (toolsNode == null || !toolsNode.isArray()) {
                // 缺 tools 能力 → 协议不兼容（api-spec §7.4.2 protocol_incompatible）
                throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
            }
            for (JsonNode tool : toolsNode) {
                JsonNode nameNode = tool.get("name");
                if (nameNode == null || nameNode.asText().isBlank()) {
                    // 无名工具无法形成 toolKey，视为协议不兼容（否则会落一条查不回来的脏行）
                    throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
                }
                tools.add(new McpToolDescriptor(nameNode.asText(),
                        tool.hasNonNull("description") ? tool.get("description").asText() : "",
                        tool.get("inputSchema")));
            }
            JsonNode next = result.get("nextCursor");
            if (next == null || next.isNull() || next.asText().isBlank()) {
                return tools;
            }
            cursor = next.asText();
        }
        log.warn("MCP tools/list 翻页超过上限，已强制收敛：mcpId={} pages={}",
                server.getId(), MAX_PAGES);
        return tools;
    }

    @Override
    public McpCallResult callTool(McpServer server, String toolName, String argumentsJson) {
        McpTransport transport = requireTransport(server);
        Map<String, String> authHeaders = credentialResolver.authHeaders(server);
        Duration timeout = callTimeout(server);

        JsonNode result = invoke(transport, server, authHeaders, METHOD_TOOLS_CALL,
                callParams(toolName, argumentsJson), timeout);
        boolean isError = result.hasNonNull("isError") && result.get("isError").asBoolean();
        return new McpCallResult(extractContent(result), isError);
    }

    /**
     * {@code tools/call} 的有效超时 = {@code min(sys_config, mcp_servers.timeout_seconds)}。
     */
    public Duration callTimeout(McpServer server) {
        int configured = businessConfig.requireInt(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_CALL_TIMEOUT_SECONDS);
        Integer perServer = server.getTimeoutSeconds();
        int effective = perServer == null || perServer <= 0
                ? configured : Math.min(configured, perServer);
        return Duration.ofSeconds(effective);
    }

    /**
     * 握手 / 连接测试阶段的<b>请求级</b>总超时（{@code mcp.connect_timeout_seconds}）。
     *
     * <p>🔴 <b>已裁决（api-spec V1.1.2 §7.6.1 G7）</b>：本值<b>不是</b> TCP 建连超时 ——
     * 建连超时是基础设施级参数 {@code app.ai.connect-timeout-seconds}（JDK17 只能设在
     * {@code HttpClient} Bean 上、无法按请求覆盖）。运维需保持
     * {@code app.ai.connect-timeout-seconds ≤ mcp.connect_timeout_seconds}，
     * 否则会出现"请求级超时先到、诊断结果误判为 {@code timeout} 而非 {@code connect_failed}"。
     *
     * <p>🔴 <b>V1.4.1：一期无调用点</b> —— 连接测试的 exchange 预算已订正为
     * {@code mcp.discover_timeout_seconds}（连接测试执行的就是一次 {@code tools/list}，
     * 用 discover 预算才是名实相符）。本键<b>保留</b>，理由有二：
     * <ol>
     *   <li>① 它是上面那条 <b>G7 运维不等式的参照值</b>（运维据此校核
     *       {@code app.ai.connect-timeout-seconds} 是否配得过大）；</li>
     *   <li>② 📋 <b>二期</b>独立 {@code initialize} 握手阶段的预算键 —— 届时握手与
     *       {@code tools/list} 是两次独立 exchange，各需自己的请求级预算。</li>
     * </ol>
     * 因此它<b>仍在</b> {@code StartupChecker.REQUIRED_CONFIG}（缺键启动即失败）。
     *
     * <p>🔴 <b>本方法与该配置键请勿删除、请勿标注 {@code @Deprecated}</b>：
     * "当前无人调用"是<b>预期状态</b>，不是遗留垃圾。删方法或把键移出必需集，
     * 会让二期握手阶段静默失去预算约束，并使 G7 不等式失去可校核的参照值。
     */
    public Duration connectTimeout() {
        return Duration.ofSeconds(businessConfig.requireInt(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_CONNECT_TIMEOUT_SECONDS));
    }

    /**
     * 发送一次 JSON-RPC 请求并返回 {@code result} 节点。
     *
     * @throws McpTransportException 传输失败或 JSON-RPC error（已分类）
     */
    private JsonNode invoke(McpTransport transport, McpServer server,
                            Map<String, String> authHeaders, String method,
                            Map<String, Object> params, Duration timeout) {
        // 🔴 请求构造与 id 生成统一走 McpRpcMessages（传输层要用同一份 id 在 SSE 流上匹配结果）
        McpRpcRequest request = McpRpcMessages.request(objectMapper, method, params);
        String responseJson = transport.exchange(server, authHeaders, request, timeout);

        JsonNode root;
        try {
            root = objectMapper.readTree(responseJson);
        } catch (Exception e) {
            // 非 JSON 响应 → 协议不兼容（🔴 不回显响应正文）
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE, e);
        }
        if (!McpRpcMessages.isJsonRpc(root)) {
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        JsonNode error = root.get("error");
        if (error != null && !error.isNull()) {
            McpFailure failure = classifyRpcError(error);
            // 🔴 ADR-018 ③：只有 -32602（参数非法）才把上游 error.message 作为**参数诊断**带出，
            //    交给 McpToolExecutor 回灌模型（🔴 它不进 getMessage()、不进日志、不进审计）。
            //    其余分类一律不带任何上游文本（可能含 endpoint / 内网地址 / 凭据线索）。
            throw failure == McpFailure.INVALID_PARAMS
                    ? new McpTransportException(failure, text(error, "message"))
                    : new McpTransportException(failure);
        }
        JsonNode result = root.get("result");
        if (result == null || result.isNull() || !result.isObject()) {
            throw new McpTransportException(McpFailure.PROTOCOL_INCOMPATIBLE);
        }
        return result;
    }

    /**
     * JSON-RPC {@code error} 的分类（api-spec §7.6.4）。
     */
    private McpFailure classifyRpcError(JsonNode error) {
        int code = error.hasNonNull("code") ? error.get("code").asInt() : 0;
        if (code == RPC_INVALID_PARAMS) {
            // 🔴 与本地 Tool 的 Schema 校验失败同码（30053）：前端"不重试同参"的动作一致
            return McpFailure.INVALID_PARAMS;
        }
        if (code == RPC_METHOD_NOT_FOUND) {
            return McpFailure.PROTOCOL_INCOMPATIBLE;
        }
        if (code >= RPC_SERVER_ERROR_MIN && code <= RPC_SERVER_ERROR_MAX
                && looksLikeAuthError(error)) {
            return McpFailure.AUTH_FAILED;
        }
        return McpFailure.PROTOCOL_INCOMPATIBLE;
    }

    /**
     * 上游自定义段（-32000~-32099）里的鉴权错误识别。
     *
     * <p>🔴 只看 message 的<b>关键词</b>，且<b>不把 message 本身带出去</b>
     * （api-spec §7.4.2：data 中禁止出现上游返回的错误正文）。
     */
    private boolean looksLikeAuthError(JsonNode error) {
        String message = text(error, "message");
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("authentication") || lower.contains("invalid token")
                || lower.contains("api key");
    }

    /**
     * 拼接 {@code result.content[]} 的文本（api-spec §7.6.2 响应形态）。
     *
     * <p>非 {@code text} 类型的内容块（如 {@code image}）只保留类型占位，
     * 🔴 不把二进制/base64 塞进回灌内容（会瞬间撑爆上下文与 {@code result_max_bytes}）。
     */
    private String extractContent(JsonNode result) {
        JsonNode content = result.get("content");
        if (content == null || !content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            String type = text(block, "type");
            if ("text".equals(type)) {
                JsonNode value = block.get("text");
                if (value != null && !value.isNull()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(value.asText());
                }
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append('[').append(type == null ? "unknown" : type).append(']');
        }
        return text.toString();
    }

    private McpTransport requireTransport(McpServer server) {
        String transport = server.getTransport();
        if (McpServer.TRANSPORT_STDIO.equals(transport)) {
            // 🔴 一律拒绝（不向租户开放，api-spec §7.6.1）
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "该传输方式不被支持");
        }
        if (transport == null || transport.isBlank()) {
            transport = businessConfig.requireString(ConfigKeys.GROUP_MCP,
                    ConfigKeys.MCP_TRANSPORT_PREFERRED);
        }
        McpTransport resolved = transports.get(transport);
        if (resolved == null) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "传输方式取值必须是 streamable_http 或 sse");
        }
        return resolved;
    }

    private Map<String, Object> listParams(String cursor) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cursor", cursor);
        return params;
    }

    private Map<String, Object> callParams(String toolName, String argumentsJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        try {
            params.put("arguments", argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(argumentsJson));
        } catch (Exception e) {
            // 入参在到这里之前必须已通过 Schema 校验；仍非法说明是内部编码错误
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "工具入参不符合约定");
        }
        return params;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
