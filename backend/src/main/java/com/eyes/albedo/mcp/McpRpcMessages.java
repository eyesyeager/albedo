package com.eyes.albedo.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON-RPC 2.0 报文的<b>唯一构造与解析入口</b>（ADR-016 实施落点 #2，🔴 纯静态工具、无状态、无 IO）。
 *
 * <p>🔴 <b>为什么必须收敛到一处</b>：{@link McpJsonRpcClient}（业务语义层）与
 * {@link SseTransport}（传输层，异步形态要自己发 {@code initialize} 并在流上匹配 id）
 * 都需要"构造请求 / 判定 jsonrpc 合法性 / 取 id"。两处各写一遍的必然结果是
 * <b>id 生成规则或合法性判据分叉</b> —— 一旦分叉，异步形态会出现"客户端按 A 规则等 id、
 * 报文按 B 规则生成"的静默挂起（表现为莫名 {@code 30051}，极难排查）。
 *
 * <p>🔴 <b>协议常量不是业务参数</b>：{@link #JSON_RPC_VERSION} / {@link #PROTOCOL_VERSION}
 * 由 MCP 规范固定，改动它等于换协议，因此以常量固化而<b>不</b>入 {@code sys_config}
 * （反硬编码红线针对的是"业务阈值/开关"，见 architecture.md §7.3）。
 */
public final class McpRpcMessages {

    /** JSON-RPC 版本（协议固定值）。 */
    public static final String JSON_RPC_VERSION = "2.0";

    /**
     * 握手声明的 MCP 协议版本（🔴 <b>2024-11-05</b>）。
     *
     * <p>取该版本而非更新版本的理由：本 {@code initialize} 只在 {@code sse}（HTTP+SSE）传输上发送，
     * 而 HTTP+SSE 正是 2024-11-05 定义的传输；声明更高版本会让严格实现的上游
     * 以"版本不支持"拒绝握手（ADR-016 实测形态：上游自身回的也是 {@code 2024-11-05}）。
     */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    /** 握手方法名。 */
    public static final String METHOD_INITIALIZE = "initialize";
    /** 握手完成通知（🔴 无 id，不等结果）。 */
    public static final String METHOD_INITIALIZED = "notifications/initialized";

    private static final String CLIENT_NAME = "albedo";
    private static final String CLIENT_VERSION = "1.0.0";

    private McpRpcMessages() {
    }

    /** 新的请求 id（32 位小写 hex，与审计 eventId 同形态）。 */
    public static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 构造一条带 id 的 JSON-RPC 请求。
     */
    public static McpRpcRequest request(ObjectMapper objectMapper, String method,
                                       Map<String, Object> params) {
        String id = newId();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", JSON_RPC_VERSION);
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return new McpRpcRequest(id, method, write(objectMapper, request));
    }

    /**
     * 构造 {@code initialize} 请求（🔴 在 {@code sse} 传输上<b>兼作形态探测</b>，ADR-016 ②③）。
     */
    public static McpRpcRequest initialize(ObjectMapper objectMapper) {
        Map<String, Object> clientInfo = new LinkedHashMap<>();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        // 🔴 声明空能力：本系统只消费 tools，不订阅 resources / prompts / sampling
        params.put("capabilities", new LinkedHashMap<String, Object>());
        params.put("clientInfo", clientInfo);
        return request(objectMapper, METHOD_INITIALIZE, params);
    }

    /**
     * 构造 {@code notifications/initialized} 通知报文（🔴 <b>无 id</b>：通知不会有响应）。
     */
    public static String initializedNotification(ObjectMapper objectMapper) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", JSON_RPC_VERSION);
        notification.put("method", METHOD_INITIALIZED);
        notification.put("params", new LinkedHashMap<String, Object>());
        return write(objectMapper, notification);
    }

    /**
     * 宽容解析：非 JSON 一律返回 {@code null}（🔴 <b>不抛异常</b>）。
     *
     * <p>为什么必须宽容：SSE 流上混入非 JSON 行（心跳注释、上游调试输出）是常态，
     * 逐帧解析时抛异常会把"该丢弃的噪声"升级为"整次调用失败"。
     */
    public static JsonNode parseQuietly(ObjectMapper objectMapper, String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否为合法 JSON-RPC 2.0 报文对象。 */
    public static boolean isJsonRpc(JsonNode node) {
        return node != null && node.isObject()
                && JSON_RPC_VERSION.equals(text(node, "jsonrpc"));
    }

    /** 取报文 id（无 id 的通知返回 {@code null}）。 */
    public static String idOf(JsonNode node) {
        return node == null ? null : text(node, "id");
    }

    /** 报文是否携带 JSON-RPC {@code error}。 */
    public static boolean hasError(JsonNode node) {
        if (node == null) {
            return false;
        }
        JsonNode error = node.get("error");
        return error != null && !error.isNull();
    }

    /** 字段文本值（缺失 / null 返回 {@code null}）。 */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String write(ObjectMapper objectMapper, Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 入参全部由本类构造，走到这里说明是内部编码错误
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无法构造 MCP 请求");
        }
    }
}
