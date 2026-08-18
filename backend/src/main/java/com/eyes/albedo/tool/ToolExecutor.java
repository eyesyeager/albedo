package com.eyes.albedo.tool;

import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.dto.ToolExecutionResult;

/**
 * 工具执行统一入口（本地 Tool 与 MCP Tool 同一抽象，architecture.md §5.1.1）。
 *
 * <p>🔴 <b>事务纪律（ADR-010）</b>：实现体做<b>网络 / 本地 I/O</b>，
 * 🔴 <b>严禁</b>在 {@code @Transactional} 方法内调用本接口。
 * 正确形态是三段式：短事务提交 {@code running} → <b>事务外</b>调用本接口 →
 * 短事务写终态 + 审计。该约束由 {@code TransactionDisciplineScanTest} 静态扫描守护。
 *
 * <p>🔴 <b>调用前置条件（调用方保证，本接口不重复判定）</b>，顺序见 api-spec §7.6.3：
 * <ol>
 *   <li>租户上下文存在（缺失 → {@code 30013}）</li>
 *   <li>工具在清单内（{@link ToolAuthorizationService}，否则 {@code 30050} + 审计）</li>
 *   <li>MCP：SSRF 运行时兜底（{@code SsrfGuard}，否则 {@code 30050} + 审计）</li>
 *   <li>入参通过 JSON Schema（{@link ToolArgsValidator}，否则 {@code 30053}，🔴 不执行）</li>
 *   <li>风险确认已完成（{@code ToolRiskPolicy}，第三阶段接入等待）</li>
 *   <li>轮次未超 {@code tool.max_rounds}（{@code ChatStreamRunner} 持有，第三阶段）</li>
 * </ol>
 */
public interface ToolExecutor {

    /** 支持的工具类型（{@code local} / {@code mcp}）。 */
    String toolType();

    /**
     * 执行工具（🔴 失败不抛异常，以 {@link ToolExecutionResult} 承载）。
     *
     * @param request 执行请求
     */
    ToolExecutionResult execute(ToolExecutionRequest request);

    /**
     * 调用前的<b>强制前置校验</b>（api-spec §7.6.3 第 3~4 步）。
     *
     * <p>🔴 <b>为什么必须独立于 {@link #execute}</b>：契约的校验顺序是
     * 「绑定/授权 → <b>SSRF</b> → Schema → 风险确认 → 轮次」——
     * SSRF 拒绝发生在<b>进入 {@code running} 之前</b>，其终态是 {@code denied}(30050)；
     * 而状态机只允许 {@code pending → denied}，<b>不允许 {@code running → denied}</b>
     * （api-spec §7.8.1）。若把 SSRF 校验留到 {@code execute} 里（此时行已是 {@code running}），
     * 就只能被迫写成 {@code failed}，既违反状态机也让 §7.11.1 的
     * denied/failed 互斥不变量算不平。
     *
     * <p>🔴 实现方纪律：本方法可做网络<b>解析</b>（DNS）但不得做业务调用；
     * 🔴 严禁在事务内调用（与 {@link #execute} 同）。
     *
     * @throws com.eyes.albedo.common.BusinessException 30050 SSRF 拒绝 / 服务已停用（🔴 已写审计）
     */
    default void preflight(ToolExecutionRequest request) {
        // 本地 Tool 无外部端点，无需前置校验
    }

    /**
     * 一次执行请求。
     *
     * @param tenantId      租户号（🔴 显式传入：异步段禁止依赖 ThreadLocal，architecture.md §9.5.2）
     * @param uid           调用者 uid（快照值）
     * @param definition    清单中的工具定义
     * @param argumentsJson 已通过 Schema 校验的入参
     * @param agentVersionId 本次生成绑定的 Agent 版本 ID（🔴 新增字段，见下方兼容构造说明）
     */
    record ToolExecutionRequest(String tenantId, Long uid, ToolDefinition definition,
                                String argumentsJson, long agentVersionId) {

        /**
         * 兼容构造（🔴 {@code agentVersionId} 缺省为 {@code 0}）：MCP 执行器与既有单测
         * 不需要感知 Agent 版本，只有 {@code skill_load} 这类需要"回查本次绑定"的本地
         * Tool 才要求非零值（由 {@code LocalToolExecutor} 透传给 {@code LocalToolInvocation}）。
         */
        public ToolExecutionRequest(String tenantId, Long uid, ToolDefinition definition,
                                    String argumentsJson) {
            this(tenantId, uid, definition, argumentsJson, 0L);
        }
    }
}
