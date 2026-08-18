package com.eyes.albedo.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.dto.ToolExecutionResult;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具执行分发器（🔴 本地 Tool 与 MCP Tool 的<b>统一执行入口</b>）。
 *
 * <p>为什么需要它：编排层（第三阶段的 {@code ToolOrchestrator} / {@code ChatStreamRunner}）
 * 不应该出现 {@code if (local) … else if (mcp) …} —— 那样每加一种工具类型就要改编排，
 * 而编排是最难测的一段。这里按 {@link ToolExecutor#toolType()} 静态分发，
 * 使编排层只面对"一个执行入口 + 一种结果形态"。
 *
 * <p>🔴 <b>事务纪律（ADR-010）</b>：本类做 I/O 分发，🔴 严禁在 {@code @Transactional} 方法内调用。
 * 三段式：短事务 {@code running} → <b>事务外</b>调用本类 → 短事务写终态 + 审计。
 */
@Slf4j
@Component
public class ToolExecutorRegistry {

    private final Map<String, ToolExecutor> executors = new LinkedHashMap<>();

    public ToolExecutorRegistry(List<ToolExecutor> executorBeans) {
        for (ToolExecutor executor : executorBeans) {
            ToolExecutor existing = executors.put(executor.toolType(), executor);
            if (existing != null) {
                throw new IllegalStateException("工具执行器重复注册：toolType=" + executor.toolType());
            }
        }
        log.info("工具执行器注册完成：types={}", executors.keySet());
    }

    /**
     * 按工具类型执行。
     *
     * @throws BusinessException 30060 工具类型非法（配置问题，不是执行问题）
     */
    public ToolExecutionResult execute(ToolExecutor.ToolExecutionRequest request) {
        return require(request).execute(request);
    }

    /**
     * 按工具类型做调用前置校验（api-spec §7.6.3 第 3~4 步，见 {@link ToolExecutor#preflight}）。
     *
     * @throws BusinessException 30050 SSRF 拒绝 / 服务已停用；30060 工具类型非法
     */
    public void preflight(ToolExecutor.ToolExecutionRequest request) {
        require(request).preflight(request);
    }

    private ToolExecutor require(ToolExecutor.ToolExecutionRequest request) {
        String toolType = request.definition().toolType();
        ToolExecutor executor = executors.get(toolType);
        if (executor == null) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具类型取值必须是 local 或 mcp");
        }
        return executor;
    }
}
