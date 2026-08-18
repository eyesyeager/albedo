package com.eyes.albedo.tool;

import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.McpClient;
import com.eyes.albedo.mcp.McpFailure;
import com.eyes.albedo.mcp.McpTransportException;
import com.eyes.albedo.mcp.SsrfGuard;
import com.eyes.albedo.mcp.dto.McpCallResult;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.repository.McpServerRepository;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.entity.ToolCall;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 工具执行器（api-spec §7.6.3 / §7.6.4，REQ-MCP-003 / AC-MCP-004）。
 *
 * <p>🔴 <b>本类只做三件事</b>（architecture.md §5.1.3：{@code McpToolExecutor} 仅委托
 * {@code mcp/McpClient}，不自行拼 HTTP）：
 * <ol>
 *   <li>取当前库内的 {@code mcp_servers} 行（🔴 <b>每次都重新读</b>，不缓存 ——
 *       AC-MCP-004 要求 DBA 改库即生效）</li>
 *   <li>🔴 <b>每次调用前</b>做 SSRF 运行时兜底（api-spec §7.6.3 第 4 步，
 *       拒绝 → {@code 30050} + 强制审计 {@code mcp.ssrf_rejected}）</li>
 *   <li>委托 {@link McpClient#callTool}，把 {@link McpFailure} 映射为已登记错误码</li>
 * </ol>
 *
 * <p>🔴 <b>事务纪律</b>：本类做网络 I/O，🔴 严禁在事务方法内调用（ADR-010）。
 *
 * <p>🔴 <b>上游输出按不可信内容处理</b>（PRD §8.6 / api-spec §7.6.4）：
 * 结果只作为 {@code role=tool} 消息回灌，不得改写系统提示、不得提升工具权限、
 * 不得触发未授权工具；回灌前先脱敏再截断。
 *
 * <p>🔴 <b>V1.4.2（ADR-018 ③）失败诊断的二分</b>：
 * <pre>
 * ✅ 可回灌：result.isError=true 的正文（30057）、JSON-RPC -32602 的 error.message（30053）
 *            —— 它们是"上游**对参数/业务**说的话"，与成功结果同一条 truncateForModel 链
 * ❌ 不可回灌：连接失败 / DNS / TLS / 3xx / 401 / 协议不兼容 / 超时 / 安全拒绝
 *            —— 它们是"我们**对基础设施**的诊断"，可能含 endpoint、内网地址、凭据线索
 * </pre>
 *
 * <p>🔴 <b>MCP 工具一律按非幂等处理</b>：MCP 协议没有"幂等性"声明，
 * 因此结果未知（超时）时按 {@code 30056} 收敛，<b>禁止自动重试</b>（AC-TOL-003）。
 * 想清楚代价再改：一个 {@code create_ticket} 超时后自动重试，会产生两张工单。
 */
@Slf4j
@Component
public class McpToolExecutor implements ToolExecutor {

    private final McpClient mcpClient;
    private final McpServerRepository mcpServerRepository;
    private final SsrfGuard ssrfGuard;
    private final ToolResultTruncator truncator;

    public McpToolExecutor(McpClient mcpClient,
                          McpServerRepository mcpServerRepository,
                          SsrfGuard ssrfGuard,
                          ToolResultTruncator truncator) {
        this.mcpClient = mcpClient;
        this.mcpServerRepository = mcpServerRepository;
        this.ssrfGuard = ssrfGuard;
        this.truncator = truncator;
    }

    @Override
    public String toolType() {
        return com.eyes.albedo.tool.dto.ToolDefinition.TYPE_MCP;
    }

    /**
     * 调用前置校验（api-spec §7.6.3 第 3~4 步：服务状态 + <b>SSRF 运行时兜底</b>）。
     *
     * <p>🔴 必须在 {@code tool_calls} 仍为 {@code pending} 时调用：SSRF 拒绝的终态是
     * {@code denied}(30050)，而状态机不允许 {@code running → denied}（详见
     * {@link ToolExecutor#preflight}）。
     *
     * <p>🔴 每次调用前都重新读库 + 重新解析域名（AC-MCP-004：DBA 改库即生效，ADR-009）。
     *
     * @throws BusinessException 30050 服务已停用 / SSRF 拒绝（后者已写 {@code mcp.ssrf_rejected} 审计）
     */
    @Override
    public void preflight(ToolExecutionRequest request) {
        McpServer server = requireEnabledServer(request);
        // 🔴 流式内 → 独立短事务审计，绝不中断 SSE 流
        ssrfGuard.requireAllowed(server, SsrfGuard.AuditMode.NEW_TRANSACTION);
    }

    /**
     * 取当前库内且已启用的 MCP 服务（🔴 不缓存 endpoint 与凭据，architecture.md §12.1.1）。
     *
     * <p>🔴 <b>拒绝时把「原因」放进异常 payload</b>（{@link ToolExecutionResult.DeniedCause}）：
     * 服务被停用属<b>授权/停用类</b>拒绝，必须写审计 {@code tool.grant_denied}（api-spec §7.6.3
     * 第 3 步 / §8.3 C1「未授权/未绑定/<b>已停用</b> → 30050 + 审计」）；
     * 而 SSRF 拒绝的审计已由 {@code SsrfGuard} 写入，编排层<b>不得重复写</b>。
     * 编排层只能通过 payload 区分两者 —— 🔴 严禁靠比较异常 message 文案来判断（改一个字就静默失效）。
     *
     * @throws BusinessException 30050 服务不存在或已被停用（payload = {@code GRANT_REVOKED}）
     * @throws BusinessException 30060 工具配置缺少 MCP 引用
     */
    private McpServer requireEnabledServer(ToolExecutionRequest request) {
        Long mcpId = request.definition().mcpId();
        if (mcpId == null) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具配置缺少 MCP 服务引用");
        }
        McpServer server = mcpServerRepository.findOneById(mcpId).orElse(null);
        if (server == null || !McpServer.STATUS_ENABLED.equals(server.getStatus())) {
            log.warn("[SECURITY] MCP 服务不存在或已停用，拒绝调用：mcpId={}", mcpId);
            throw new BusinessException(ErrorCode.TOOL_DENIED, "工具调用被拒绝",
                    ToolExecutionResult.DeniedCause.GRANT_REVOKED);
        }
        return server;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        return execute(request, null);
    }

    /**
     * 执行（可显式传入审计上下文；异步段读不到 Servlet 请求）。
     */
    public ToolExecutionResult execute(ToolExecutionRequest request, AuditContext auditContext) {
        long startedAt = System.nanoTime();
        Long mcpId = request.definition().mcpId();
        if (mcpId == null) {
            return failure(ToolCall.STATUS_FAILED, ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具配置缺少 MCP 服务引用", startedAt);
        }

        // ① 🔴 每次都重新读当前库内配置（不缓存 endpoint 与凭据，architecture.md §12.1.1）
        McpServer server = mcpServerRepository.findOneById(mcpId).orElse(null);
        if (server == null || !McpServer.STATUS_ENABLED.equals(server.getStatus())) {
            // 🔴 执行期竞态（api-spec V1.1.3 §7.8.1 ③）：preflight 通过后 DBA 停用服务 / 撤销授权。
            //    收敛为 denied + 30050（**安全拒绝**语义），🔴 严禁归一化为 failed + 30052 ——
            //    那会把安全事件伪装成上游故障，把 DBA 导向"查网络"的错误方向。
            //    审计 tool.grant_denied 由编排层与状态流转同事务补写（DeniedCause.GRANT_REVOKED）。
            log.warn("[SECURITY] MCP 服务在执行期已被停用或不存在，按安全拒绝收敛：mcpId={}", mcpId);
            return denied(ToolExecutionResult.DeniedCause.GRANT_REVOKED, startedAt);
        }

        // ② 🔴 SSRF 运行时兜底（流式内 → 独立短事务审计，绝不中断 SSE 流）
        try {
            ssrfGuard.requireAllowed(server, SsrfGuard.AuditMode.NEW_TRANSACTION);
        } catch (BusinessException e) {
            // 🔴 审计 mcp.ssrf_rejected 已由 SsrfGuard 写入，编排层不得重复写
            return denied(ToolExecutionResult.DeniedCause.SSRF_REJECTED, startedAt);
        }

        // ③ 委托 McpClient（🔴 事务外）
        try {
            McpCallResult result = mcpClient.callTool(server,
                    request.definition().toolName(), request.argumentsJson());
            ToolResultTruncator.Truncation truncation = truncator.truncateForModel(result.content());
            if (result.isError()) {
                // 上游业务失败 → 30057（非超时、非鉴权、非参数错误）
                return new ToolExecutionResult(ToolCall.STATUS_FAILED,
                        ErrorCode.TOOL_EXECUTION_FAILED, truncation.content(),
                        truncation.truncated(), elapsedMs(startedAt));
            }
            return new ToolExecutionResult(ToolCall.STATUS_SUCCEEDED, null,
                    truncation.content(), truncation.truncated(), elapsedMs(startedAt));
        } catch (McpTransportException e) {
            return mapTransportFailure(e, startedAt);
        } catch (BusinessException e) {
            // 凭据密文非法 / 传输配置非法 → 30060（配置问题，不是上游问题）
            return failure(ToolCall.STATUS_FAILED, e.getCode(), "工具执行失败", startedAt);
        }
    }

    /**
     * {@link McpFailure} → {@code tool_calls.status} + {@code errorCode}（api-spec §7.6.4）。
     *
     * <p>🔴 超时改判 {@code 30056}：MCP 工具一律按非幂等处理，结果未知禁止自动重试。
     *
     * <p>🔴 <b>V1.4.2（ADR-018 ③）{@code INVALID_PARAMS} 分支回灌上游参数诊断</b>：
     * {@code -32602} 的 {@code error.message} 是"上游<b>对参数</b>说的话"（业务语义），
     * 经 {@code truncateForModel}（脱敏 → 字节截断）后作为 {@code content} 交给编排层回灌 ——
     * 与<b>成功</b>路径完全同一条处理链，🔴 不引入任何新的泄露面。
     * <p>🔴 其余分支<b>保持固定措辞</b>：<b>传输 / 平台侧</b>诊断（连接失败 / DNS / TLS / 3xx /
     * 401 / 协议不兼容 / 超时）可能含 endpoint、内网地址、凭据线索，🔴 一律不喂给模型。
     */
    private ToolExecutionResult mapTransportFailure(McpTransportException e, long startedAt) {
        McpFailure failure = e.failure();
        if (failure == McpFailure.TIMEOUT) {
            log.warn("MCP 工具调用超时，按非幂等收敛为 30056（禁止自动重试）");
            return failure(ToolCall.STATUS_TIMED_OUT, ErrorCode.TOOL_RETRY_BLOCKED,
                    "工具结果待确认，不可自动重试", startedAt);
        }
        // 🔴 只记分类与错误码：upstreamParamHint 严禁进入日志 / 审计（McpTransportException 类注释）
        log.warn("MCP 工具调用失败：failure={} errorCode={}", failure, failure.errorCode());
        if (failure == McpFailure.INVALID_PARAMS && e.upstreamParamHint() != null) {
            ToolResultTruncator.Truncation truncation =
                    truncator.truncateForModel(e.upstreamParamHint());
            return new ToolExecutionResult(ToolCall.STATUS_FAILED, failure.errorCode(),
                    truncation.content(), truncation.truncated(), elapsedMs(startedAt));
        }
        // 🔴 回灌内容只给固定措辞：**传输 / 平台侧**诊断不喂给模型（api-spec §7.6.4 二分裁决）
        return failure(ToolCall.STATUS_FAILED, failure.errorCode(),
                ErrorCode.defaultMessage(failure.errorCode()), startedAt);
    }

    private ToolExecutionResult failure(String status, int errorCode, String content,
                                        long startedAt) {
        return new ToolExecutionResult(status, errorCode, content, false, elapsedMs(startedAt));
    }

    /**
     * 执行期<b>安全拒绝</b>（🔴 恒为 {@code denied} + {@code 30050}，api-spec §7.8.1 ③）。
     *
     * <p>回灌模型的措辞与"不在清单内"一致：模型不需要（也不应该）知道是撤授权还是地址非法。
     */
    private ToolExecutionResult denied(ToolExecutionResult.DeniedCause cause, long startedAt) {
        return new ToolExecutionResult(ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED,
                "工具调用被拒绝", false, elapsedMs(startedAt), cause);
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
