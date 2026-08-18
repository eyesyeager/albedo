package com.eyes.albedo.tool;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.entity.ToolCall;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地 Tool 执行器（api-spec §7.7.3，REQ-TOL-002 / AC-TOL-003）。
 *
 * <p>🔴 <b>事务纪律</b>：本类<b>不是</b>事务方法，且严禁被事务方法调用（ADR-010）。
 *
 * <p>🔴 <b>超时实现口径（已裁决：api-spec §7.7.3 G8 + ADR-008 第 8 条补注）</b>：
 * <pre>
 * 约束：ADR-008 第 8 条明确「🔴 不新增线程池」——
 *       工具执行必须跑在既有 aiStreamExecutor 线程内。
 * 因此本类**不**把任务丢给别的线程池来做超时中断，而是：
 *   ① 用 FutureTask 在**当前线程**内直接 run()（不占用任何额外线程）
 *   ② 以 System.nanoTime 计时并在超时后判定为 30051 / 30056
 * ⚠️ 已裁决接受的代价（G8，AR-014）：
 *   🔴 本地实现体若**自身长时间阻塞**，超时判定会在它返回后才生效 ——
 *      即"超时会被如实上报，但不会强制打断实现体"。
 *   👉 因此 LocalToolHandler 的实现者纪律要求"短、纯、不阻塞"，
 *      且一期内置清单仅 datetime_now / calculator 两个纯计算工具（ADR-015 ③）
 *      —— 残余风险**实际为零**；MCP 工具不受此限（HttpClient 的 requestTimeout 是真超时）。
 * 📋 若出现"本地 Tool 可能长阻塞"的真实需求，必须先修订 ADR-008 第 8 条
 *    （是否允许一个**有界的**工具执行线程池），🔴 严禁实现层私起线程绕过。
 * </pre>
 *
 * <p>🔴 <b>非幂等工具结果未知 → {@code 30056} 且禁止自动重试</b>（EX-019 / AC-TOL-003）：
 * 想清楚这条的代价再改 —— 一个非幂等的"发起退款"工具超时后，
 * 我们<b>不知道</b>退款到底有没有发出去；自动重试可能造成<b>重复退款</b>。
 * 因此宁可让用户显式重新提问，也不自动重试。
 */
@Slf4j
@Component
public class LocalToolExecutor implements ToolExecutor {

    private final LocalToolRegistry registry;
    private final ToolResultTruncator truncator;

    public LocalToolExecutor(LocalToolRegistry registry, ToolResultTruncator truncator) {
        this.registry = registry;
        this.truncator = truncator;
    }

    @Override
    public String toolType() {
        return com.eyes.albedo.tool.dto.ToolDefinition.TYPE_LOCAL;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        long startedAt = System.nanoTime();
        String toolKey = request.definition().toolKey();
        int timeoutSeconds = request.definition().timeoutSeconds();

        LocalToolHandler handler;
        try {
            // 🔴 注册表有行但平台无实现 → 30060（清单构造阶段本应已拦下，这里是兜底）
            handler = registry.require(toolKey);
        } catch (BusinessException e) {
            return failure(ToolCall.STATUS_FAILED, e.getCode(),
                    "工具在平台侧没有可用实现", startedAt);
        }

        LocalToolHandler.LocalToolInvocation invocation =
                new LocalToolHandler.LocalToolInvocation(request.tenantId(), request.uid(),
                        toolKey, request.argumentsJson(), request.definition().grantConfigJson(),
                        request.definition().inputSchema(), request.agentVersionId());

        Callable<String> task = () -> handler.execute(invocation);
        FutureTask<String> future = new FutureTask<>(task);
        // 🔴 当前线程内执行：不新增线程池（ADR-008 第 8 条）
        future.run();

        try {
            String raw = future.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            long elapsedMs = elapsedMs(startedAt);
            if (elapsedMs > timeoutSeconds * 1000L) {
                // 实现体阻塞导致的超时：如实上报（见类注释的已知代价）
                return timeout(request, startedAt);
            }
            ToolResultTruncator.Truncation truncation = truncator.truncateForModel(raw);
            return new ToolExecutionResult(ToolCall.STATUS_SUCCEEDED, null,
                    truncation.content(), truncation.truncated(), elapsedMs);
        } catch (TimeoutException e) {
            return timeout(request, startedAt);
        } catch (ExecutionException e) {
            return mapExecutionFailure(toolKey, e.getCause(), startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return timeout(request, startedAt);
        }
    }

    /**
     * 超时映射（🔴 非幂等 → {@code 30056}，禁止自动重试；幂等 → {@code 30051}）。
     */
    private ToolExecutionResult timeout(ToolExecutionRequest request, long startedAt) {
        boolean idempotent = request.definition().idempotent();
        int code = idempotent ? ErrorCode.TOOL_TIMEOUT : ErrorCode.TOOL_RETRY_BLOCKED;
        log.warn("本地工具执行超时：toolKey={} idempotent={} errorCode={}",
                request.definition().toolKey(), idempotent, code);
        return failure(ToolCall.STATUS_TIMED_OUT, code,
                idempotent ? "工具执行超时" : "工具结果待确认，不可自动重试", startedAt);
    }

    /**
     * 实现体异常映射。
     *
     * <p>业务异常（{@link BusinessException}）→ 保留其已登记码；
     * 其余异常 → {@code 30057}（工具执行返回业务失败）。
     * 🔴 异常消息<b>不回灌</b>到模型：只回固定措辞，避免把内部堆栈/地址喂给模型再吐给用户。
     */
    private ToolExecutionResult mapExecutionFailure(String toolKey, Throwable cause, long startedAt) {
        if (cause instanceof BusinessException businessException) {
            log.warn("本地工具业务失败：toolKey={} code={}", toolKey, businessException.getCode());
            return failure(ToolCall.STATUS_FAILED, businessException.getCode(),
                    "工具执行失败", startedAt);
        }
        log.error("本地工具执行异常：toolKey={}", toolKey, cause);
        return failure(ToolCall.STATUS_FAILED, ErrorCode.TOOL_EXECUTION_FAILED,
                "工具执行失败", startedAt);
    }

    private ToolExecutionResult failure(String status, int errorCode, String content,
                                        long startedAt) {
        return new ToolExecutionResult(status, errorCode, content, false, elapsedMs(startedAt));
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 保留：供第三阶段记录执行起止时刻。 */
    public Instant now() {
        return Instant.now();
    }
}
