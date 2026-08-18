package com.eyes.albedo.mcp;

/**
 * MCP 上游失败异常（🔴 <b>只携带分类，不携带上游细节</b>）。
 *
 * <p>为什么不直接抛 {@code BusinessException}：
 * <ul>
 *   <li>连接测试需要的是<b>诊断分类</b>（8 个字面量），不是错误码；</li>
 *   <li>运行时调用需要的是<b>错误码</b>（{@code 30051/30052/30053/30057}）；</li>
 *   <li>把两者统一到 {@link McpFailure} 后，调用方各取所需，避免"同一故障两处结论不同"。</li>
 * </ul>
 *
 * <p>🔴 <b>消息纪律</b>：{@link #getMessage()} 只允许出现<b>分类名</b>与固定措辞，
 * 严禁包含 endpoint / IP / 端口 / 凭据 / 上游响应正文 / 堆栈
 * （api-spec §7.3.1、§7.4.2、architecture.md §11.1.2 禁记清单）。
 * 上游细节只允许进入<b>本地日志的 DEBUG 级</b>，且经 {@code LogScrubber} 脱敏。
 *
 * <p>🔴 <b>唯一例外：{@link #upstreamParamHint()}</b>（ADR-018 ③ / 落点表 6，V1.4.2 新增）——
 * 仅 {@link McpFailure#INVALID_PARAMS}（JSON-RPC {@code -32602}）时承载上游的
 * <b>参数诊断</b>（{@code error.message}）。它的流向被严格限定：
 * <pre>
 * ✅ 允许：回灌模型的 role=tool 内容 / resultSummary（经脱敏 + 字节截断）
 * ❌ 禁止：getMessage()、日志、审计、对外响应
 * </pre>
 * 🔴 为什么可以放它进来：{@code -32602} 的 {@code error.message} 是"上游<b>对参数</b>说的话"
 * （业务语义），与"我们对基础设施的诊断"（连接失败 / TLS / 401 / 超时）性质不同 ——
 * 后者可能含 endpoint、内网地址、凭据线索，🔴 一律不得回灌（api-spec §7.6.4 二分裁决）。
 */
public class McpTransportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final McpFailure failure;

    /**
     * 🔴 <b>仅</b> {@link McpFailure#INVALID_PARAMS} 时非空的上游参数诊断
     * （🔴 <b>不</b>进入 {@link #getMessage()}）。
     */
    private final String upstreamParamHint;

    public McpTransportException(McpFailure failure) {
        this(failure, (String) null);
    }

    /**
     * 带上游参数诊断（🔴 非 {@code INVALID_PARAMS} 时该值会被<b>丢弃</b>，防止误用）。
     */
    public McpTransportException(McpFailure failure, String upstreamParamHint) {
        // 🔴 固定措辞：不回显任何上游信息
        super("MCP 上游不可用：" + failure.name());
        this.failure = failure;
        this.upstreamParamHint = sanitizedHint(failure, upstreamParamHint);
    }

    /**
     * 带原因链（🔴 {@code cause} 仅供本地日志，绝不进对外响应 / 审计 / SSE）。
     */
    public McpTransportException(McpFailure failure, Throwable cause) {
        super("MCP 上游不可用：" + failure.name(), cause);
        this.failure = failure;
        this.upstreamParamHint = null;
    }

    public McpFailure failure() {
        return failure;
    }

    public int errorCode() {
        return failure.errorCode();
    }

    /**
     * 上游参数诊断（🔴 可能为 {@code null}；🔴 <b>只能</b>用于回灌模型 / 摘要，
     * 严禁写日志、审计或异常消息）。
     */
    public String upstreamParamHint() {
        return upstreamParamHint;
    }

    /**
     * 🔴 <b>结构性防误用</b>：只有 {@code INVALID_PARAMS} 允许携带上游文本。
     *
     * <p>把这条规则放在构造函数里（而不是靠调用方自觉），是为了让"给 30052 塞上游正文"
     * 这类回归<b>在编译产物层面不可能发生</b>。
     */
    private static String sanitizedHint(McpFailure failure, String hint) {
        if (failure != McpFailure.INVALID_PARAMS || hint == null || hint.isBlank()) {
            return null;
        }
        return hint.strip();
    }
}
