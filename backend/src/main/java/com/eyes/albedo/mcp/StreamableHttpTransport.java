package com.eyes.albedo.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.eyes.albedo.mcp.entity.McpServer;

import org.springframework.stereotype.Component;

/**
 * {@code streamable_http} 传输（🔴 <b>首选</b>，api-spec §7.6.1）。
 *
 * <p>形态：<b>单端点 POST</b> + 响应可以是裸 JSON 或单条 SSE 帧
 * （{@code Accept: application/json, text/event-stream}）。
 *
 * <p>为什么首选它：连接管理最简单（无会话端点、无长驻连接），
 * 与单体的同步线程模型契合（{@code aiStreamExecutor} 内一次 {@code send} 即完成），
 * 🔴 也天然满足"单次调用超时受 {@code mcp.call_timeout_seconds} 约束"。
 *
 * <p>🔴 <b>本传输不受 ADR-016 影响</b>（api-spec G9′）：<b>仍不发送</b> {@code initialize}、
 * <b>仍不维护</b> {@code Mcp-Session-Id}；上游强制要求握手或会话头 → {@code protocol_incompatible}。
 * 📋 二期再议。
 */
@Component
public class StreamableHttpTransport extends AbstractMcpTransport {

    public StreamableHttpTransport(HttpClient httpClient) {
        super(httpClient);
    }

    @Override
    public String transport() {
        return McpServer.TRANSPORT_STREAMABLE_HTTP;
    }

    @Override
    public String exchange(McpServer server, Map<String, String> authHeaders,
                           McpRpcRequest request, Duration timeout) {
        // 🔴 以原域名发起（保留 TLS 主机名校验与 SNI，ADR-009 ②）
        URI uri = URI.create(server.getEndpoint().trim());
        // 单请求传输：整个预算就是这一次 POST（deadline 预算制在此退化为原语义）
        String body = postForBody(uri, request.json(), authHeaders,
                MEDIA_JSON + ", " + MEDIA_SSE, timeout);
        return extractJsonPayload(body);
    }
}
