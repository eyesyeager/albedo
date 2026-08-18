package com.eyes.albedo.mcp;

import java.util.List;

import com.eyes.albedo.mcp.dto.McpCallResult;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;

/**
 * MCP 客户端契约（architecture.md §5.1.1）。
 *
 * <p>🔴 <b>边界</b>（§5.1.3）：{@code tool} 包只看到「结果 / 失败分类」，
 * <b>看不到</b> endpoint、凭据、传输细节、JSON-RPC 载荷。
 * {@code McpToolExecutor} 仅委托本接口，🔴 严禁自行拼 HTTP 请求。
 *
 * <p>🔴 <b>依赖方向</b>：{@code mcp ✗→ tool}（传输层不感知编排；风险判定与确认不在 mcp）。
 *
 * <p>🔴 <b>事务纪律（ADR-010）</b>：本接口的所有方法都是<b>网络调用</b>，
 * 严禁在任何 {@code @Transactional} 方法内调用（工具执行必须切三段：
 * 短事务提交 {@code running} → <b>事务外</b>执行 → 短事务写终态 + 审计）。
 */
public interface McpClient {

    /**
     * 工具发现（{@code tools/list}，超时取 {@code sys_config: mcp.discover_timeout_seconds}）。
     *
     * <p>🔴 调用前必须已通过 {@link SsrfGuard}；本方法<b>不</b>重复做授权判定。
     * 累计工具数上限由调用方按 {@code mcp.max_tools_per_server} 判定
     * （超限 → {@code 30060} 且整批不落库）。
     *
     * @throws McpTransportException 上游失败（分类见 {@link McpFailure}）
     */
    List<McpToolDescriptor> listTools(McpServer server);

    /**
     * 工具调用（{@code tools/call}）。
     *
     * <p>超时 = {@code min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)}
     * （architecture.md §13.5.3：两者取<b>较小值</b>）。
     *
     * @param toolName      上游原名（🔴 不是 {@code toolKey}）
     * @param argumentsJson 已通过 JSON Schema 校验的入参 JSON
     * @throws McpTransportException 上游失败（{@code -32602} → {@link McpFailure#INVALID_PARAMS}；
     *                               {@code isError=true} 由返回值承载，不抛异常）
     */
    McpCallResult callTool(McpServer server, String toolName, String argumentsJson);
}
