package com.eyes.albedo.mcp.dto;

/**
 * 连接测试结果（api-spec §7.4.2 的 {@code data}）。
 *
 * <p>🔴 <b>禁止出现</b>：凭据、endpoint、解析出的 IP、完整响应体、上游错误正文
 * —— 本记录<b>只做分类</b>（api-spec §7.4.2 末两条）。
 *
 * @param mcpId         MCP ID（对外 string）
 * @param transport     实际使用的传输
 * @param result        8 个固定字面量之一（{@code McpCheckResult.literal()}）
 * @param healthy       是否健康（等价于 {@code result=="success"}）
 * @param latencyMs     耗时毫秒（SSRF 拒绝时为 0，因为未发起连接）
 * @param toolCount     发现的工具数
 * @param checkedAt     检测时间（ISO-8601 UTC）
 * @param auditEventId  🔴 32 位小写 UUID hex（无连字符），原样返回、禁止截断
 */
public record McpConnectionTestResult(String mcpId,
                                      String transport,
                                      String result,
                                      boolean healthy,
                                      long latencyMs,
                                      int toolCount,
                                      String checkedAt,
                                      String auditEventId) {
}
