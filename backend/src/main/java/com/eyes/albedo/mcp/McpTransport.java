package com.eyes.albedo.mcp;

import java.time.Duration;
import java.util.Map;

import com.eyes.albedo.mcp.entity.McpServer;

/**
 * MCP 传输抽象（api-spec §7.6.1）。
 *
 * <p>| 传输 | 取舍 |
 * <ul>
 *   <li>{@code streamable_http} —— 🔴 <b>首选</b>（{@code sys_config: mcp.transport_preferred}）：
 *       单端点 POST + 可选流式响应，连接管理简单，与单体同步线程模型契合</li>
 *   <li>{@code sse} —— MCP <b>HTTP+SSE</b> 传输（仅当上游不支持首选传输时使用）。
 *       🔴 <b>V1.4.0（ADR-016 / api-spec G6′）起在<b>单次 exchange 内自适应</b>两种形态</b>：
 *       ① <b>同步应答形态</b>（POST 响应体内直接返回 JSON-RPC 结果）
 *       ② <b>2024-11-05 异步推送形态</b>（POST 回 {@code 202} 空体，结果由本次 exchange 持有的
 *       GET 事件流推送）。🔴 <b>不新增传输枚举值</b>，判据 = 首个 POST 的响应体形态；
 *       🔴 单次调用受<b>同一个</b> deadline 预算约束，禁止长驻连接、禁止跨调用复用 session</li>
 *   <li>{@code stdio} —— 🔴 <b>一律拒绝</b>（不向租户开放）：{@code transport='stdio'} → {@code 30060}</li>
 * </ul>
 */
public interface McpTransport {

    /** 本传输支持的 {@code mcp_servers.transport} 取值。 */
    String transport();

    /**
     * 发送一次 JSON-RPC 请求并返回响应报文文本（🔴 已剥离 SSE 帧）。
     *
     * @param server      MCP 配置（🔴 endpoint 与凭据不得外泄）
     * @param authHeaders 由 {@link McpCredentialResolver} 现场构造的鉴权头
     * @param request     JSON-RPC 2.0 请求（🔴 含 {@code id}：{@code sse} 异步形态要靠它在流上匹配结果）
     * @param timeout     本次 exchange 的<b>总预算</b>（🔴 deadline 预算制，各子请求取 remaining）
     * @throws McpTransportException 已分类的上游失败
     */
    String exchange(McpServer server, Map<String, String> authHeaders, McpRpcRequest request,
                    Duration timeout);
}
