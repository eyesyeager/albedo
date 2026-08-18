package com.eyes.albedo.mcp;

/**
 * 一条待发送的 JSON-RPC 2.0 请求（ADR-016 实施落点 #1 的载体类型，无任何逻辑）。
 *
 * <p>🔴 <b>为什么传输层必须拿到 {@code id} 而不能只拿报文文本</b>：
 * {@code sse} 传输的 <b>2024-11-05 异步推送形态</b>下，POST 只回 {@code 202} 空体，
 * 真正的结果由同一次 exchange 内持有的 GET 事件流推送 —— 流上会混入
 * {@code notifications/*} / {@code logging} / {@code ping} / <b>id 不匹配</b>的帧，
 * 传输层只能靠 {@code id} 判定"哪一帧才是本次请求的结果"（ADR-016 ④ 结果匹配纪律）。
 * 若沿用"只传报文文本"，传输层就得自己再解析一遍 JSON 取 id，
 * 等于把 JSON-RPC 语义拆到两处实现（迟早分叉）。
 *
 * @param id     JSON-RPC 请求 id（🔴 通知类报文无 id，见 {@link McpRpcMessages}，不走本类型）
 * @param method JSON-RPC 方法名（仅用于日志与诊断，🔴 不参与结果匹配）
 * @param json   完整请求体文本（已序列化，传输层原样发送）
 */
public record McpRpcRequest(String id, String method, String json) {
}
