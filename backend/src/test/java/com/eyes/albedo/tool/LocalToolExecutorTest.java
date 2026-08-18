package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.entity.ToolCall;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 本地 Tool 注册表与执行器单测（api-spec §7.7.1 / §7.7.3，AC-TOL-001 / AC-TOL-003）。
 *
 * <p>🔴 关键断言：
 * <ul>
 *   <li>注册表有行但平台无实现 → {@code 30060}（fail-closed）</li>
 *   <li>业务异常 → {@code 30057}；🔴 异常消息不回灌模型</li>
 *   <li>🔴 <b>非幂等工具结果未知 → {@code 30056}，禁止自动重试</b>（EX-019 / AC-TOL-003）</li>
 *   <li>结果超字节上限 → 截断 + {@code truncated=true}（EX-017）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalToolExecutorTest {

    @Mock
    private BusinessConfig businessConfig;

    private ToolResultTruncator truncator;

    @BeforeEach
    void setUp() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_MAX_BYTES))).thenReturn(1024 * 1024);
        truncator = new ToolResultTruncator(businessConfig);
    }

    @Test
    @DisplayName("成功执行 → succeeded + 结果回灌（errorCode=null）")
    void success() {
        LocalToolExecutor executor = executor(handler("demo_tool", invocation -> "{\"ok\":true}"));

        ToolExecutionResult result = executor.execute(request(definition("demo_tool", true, 5)));

        assertEquals(ToolCall.STATUS_SUCCEEDED, result.status());
        assertTrue(result.succeeded());
        assertEquals("{\"ok\":true}", result.content());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("🔴 注册表有行但平台无实现体 → 30060（fail-closed，不是 30057）")
    void missingHandlerIsConfigError() {
        LocalToolExecutor executor = executor();

        ToolExecutionResult result = executor.execute(request(definition("ghost_tool", true, 5)));

        assertEquals(ToolCall.STATUS_FAILED, result.status());
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, result.errorCode());
    }

    @Test
    @DisplayName("实现体抛业务异常 → 30057，且🔴 异常消息不回灌模型")
    void businessFailureMapsTo30057() {
        LocalToolExecutor executor = executor(handler("demo_tool", invocation -> {
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED,
                    "内部地址 10.0.0.5 拒绝连接");
        }));

        ToolExecutionResult result = executor.execute(request(definition("demo_tool", true, 5)));

        assertEquals(ToolCall.STATUS_FAILED, result.status());
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, result.errorCode());
        assertFalse(result.content().contains("10.0.0.5"), "🔴 不得把内部信息回灌模型");
    }

    @Test
    @DisplayName("实现体抛未预期异常 → 30057（不泄露堆栈）")
    void unexpectedFailureMapsTo30057() {
        LocalToolExecutor executor = executor(handler("demo_tool", invocation -> {
            throw new IllegalStateException("NPE-ish internal detail");
        }));

        ToolExecutionResult result = executor.execute(request(definition("demo_tool", true, 5)));

        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, result.errorCode());
        assertFalse(result.content().contains("internal detail"));
    }

    @Test
    @DisplayName("🔴 AC-TOL-003：非幂等工具超时 → 30056（禁止自动重试），幂等工具 → 30051")
    void timeoutMapsByIdempotency() {
        LocalToolExecutor slow = executor(handler("slow_tool", invocation -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }));

        ToolExecutionResult nonIdempotent = slow.execute(request(definition("slow_tool", false, 1)));
        assertEquals(ToolCall.STATUS_TIMED_OUT, nonIdempotent.status());
        assertEquals(ErrorCode.TOOL_RETRY_BLOCKED, nonIdempotent.errorCode(),
                "🔴 非幂等工具结果未知必须是 30056，绝不能自动重试");

        ToolExecutionResult idempotent = slow.execute(request(definition("slow_tool", true, 1)));
        assertEquals(ToolCall.STATUS_TIMED_OUT, idempotent.status());
        assertEquals(ErrorCode.TOOL_TIMEOUT, idempotent.errorCode());
    }

    @Test
    @DisplayName("结果超 tool.result_max_bytes → 截断 + truncated=true（EX-017）")
    void oversizeResultTruncated() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_MAX_BYTES))).thenReturn(16);
        LocalToolExecutor executor = executor(handler("demo_tool",
                invocation -> "0123456789abcdefghij"));

        ToolExecutionResult result = executor.execute(request(definition("demo_tool", true, 5)));

        assertTrue(result.truncated());
        assertTrue(result.content().startsWith("0123456789abcdef"), result.content());
    }

    @Test
    @DisplayName("🔴 实现体重复注册同一 toolKey → 启动即失败（不可运行时裁决）")
    void duplicateRegistrationFailsFast() {
        assertThrows(IllegalStateException.class, () -> new LocalToolRegistry(List.of(
                handler("dup", invocation -> "a"), handler("dup", invocation -> "b"))));
    }

    @Test
    @DisplayName("注册表查询：contains / registeredKeys / require 缺失 → 30060")
    void registryLookup() {
        LocalToolRegistry registry = new LocalToolRegistry(
                List.of(handler("demo_tool", invocation -> "ok")));

        assertTrue(registry.contains("demo_tool"));
        assertFalse(registry.contains("ghost"));
        assertEquals(java.util.Set.of("demo_tool"), registry.registeredKeys());
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class, () -> registry.require("ghost")).getCode());
    }

    @Test
    @DisplayName("🔴 一期无内置实现体：空注册表是合法的（fail-closed 由 require 承担）")
    void emptyRegistryIsValid() {
        LocalToolRegistry registry = new LocalToolRegistry(List.of());
        assertTrue(registry.registeredKeys().isEmpty());
        assertTrue(registry.find("anything").isEmpty());
    }

    @Test
    @DisplayName("执行分发器：按 toolType 分发；非法类型 → 30060")
    void executorRegistryDispatch() {
        LocalToolExecutor local = executor(handler("demo_tool", invocation -> "ok"));
        ToolExecutorRegistry registry = new ToolExecutorRegistry(List.of(local));

        assertEquals(ToolCall.STATUS_SUCCEEDED,
                registry.execute(request(definition("demo_tool", true, 5))).status());

        ToolDefinition weird = ToolDefinition.of("weird", "k", "k", "", null, "", "low",
                false, true, 5, null, null);
        BusinessException ex = assertThrows(BusinessException.class, () -> registry.execute(
                new ToolExecutor.ToolExecutionRequest("gift", 1L, weird, "{}")));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    // ===================== 辅助 =====================

    private LocalToolExecutor executor(LocalToolHandler... handlers) {
        return new LocalToolExecutor(new LocalToolRegistry(List.of(handlers)), truncator);
    }

    private LocalToolHandler handler(String toolKey,
                                     java.util.function.Function<LocalToolHandler.LocalToolInvocation,
                                             String> body) {
        return new LocalToolHandler() {
            @Override
            public String toolKey() {
                return toolKey;
            }

            @Override
            public String execute(LocalToolInvocation invocation) {
                return body.apply(invocation);
            }
        };
    }

    private ToolDefinition definition(String toolKey, boolean idempotent, int timeoutSeconds) {
        return ToolDefinition.of(ToolDefinition.TYPE_LOCAL, toolKey, toolKey, "测试工具",
                "{\"type\":\"object\"}", "", "low", false, idempotent, timeoutSeconds, null, null);
    }

    private ToolExecutor.ToolExecutionRequest request(ToolDefinition definition) {
        return new ToolExecutor.ToolExecutionRequest("gift", 10086L, definition, "{}");
    }
}
