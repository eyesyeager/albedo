package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.dto.ToolProgress;
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
 * 🔴 <b>失败回灌的「诊断来源二分」单测</b>（ADR-018 ③ / api-spec §7.6.4 裁决框）。
 *
 * <p>为什么必须单测而不是只做端到端：二分的判据是"这句诊断<b>是谁说的</b>"——
 * 可回灌（{@code 30057} / {@code 30053}）与不可回灌（{@code 30052} / {@code 30051} /
 * {@code 30056} / {@code 30050} / {@code 50003}）各有 4~5 条分支，
 * 端到端很难同时覆盖全部；而一旦有人"顺手"把 {@code 30052} 也拼上上游正文，
 * 就会把 endpoint / 内网地址 / 凭据线索喂给模型（🔴 安全边界，不是体验问题）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolFeedbackDiagnosticTest {

    private static final String TENANT = "fbt";
    private static final long CONVERSATION_ID = 7001L;
    private static final long MESSAGE_ID = 7002L;
    private static final long TOOL_CALL_ID = 7003L;
    private static final String TOOL_KEY = "mock:web_search";

    /** 🔴 平台侧诊断的关键字：出现在回灌内容里即视为泄露（api-spec §7.6.4 反向断言）。 */
    private static final List<String> FORBIDDEN_IN_FEEDBACK = List.of(
            "http://", "https://", "127.0.0.1", "169.254", "endpoint", "Exception",
            "McpTransportException", "Bearer");

    @Mock
    private ToolCatalogService catalogService;
    @Mock
    private ToolAuthorizationService authorizationService;
    @Mock
    private ToolGrantPointCheck grantPointCheck;
    @Mock
    private ToolArgsValidator argsValidator;
    @Mock
    private ToolExecutorRegistry executorRegistry;
    @Mock
    private ToolCallRecorder recorder;
    @Mock
    private ToolSummaryScrubber scrubber;
    @Mock
    private ToolResultTruncator truncator;

    private ToolOrchestrator orchestrator;
    private final List<ToolProgress> frames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orchestrator = new ToolOrchestrator(catalogService, authorizationService, grantPointCheck,
                argsValidator, executorRegistry, recorder, scrubber, truncator);
        frames.clear();
        when(scrubber.argsSummary(anyString())).thenAnswer(i -> i.getArgument(0));
        when(scrubber.resultSummary(anyString())).thenAnswer(i -> i.getArgument(0));
        when(truncator.truncateArgsSummary(anyString())).thenAnswer(i -> i.getArgument(0));
        when(truncator.truncateResultSummary(anyString())).thenAnswer(i -> i.getArgument(0));
        when(recorder.recordPending(anyLong(), anyLong(), anyString(), org.mockito.ArgumentMatchers
                .anyInt(), any())).thenReturn(toolCall());
        when(grantPointCheck.stillGranted(anyString(), any())).thenReturn(true);
    }

    // ===================== ✅ 可回灌：上游 / 校验器对参数说的话 =====================

    @Test
    @DisplayName("🔴 30057（上游 isError=true）→ 回灌 = 固定措辞 + **上游错误正文**（BUG-MCP-001 主修）")
    void executionFailureRelaysUpstreamBody() {
        String upstream = "Mode 参数非法，只允许 0/1/2";
        stubExecute(new ToolExecutionResult(ToolCall.STATUS_FAILED,
                ErrorCode.TOOL_EXECUTION_FAILED, upstream, false, 12L));

        ToolOrchestrator.ToolDispatch dispatch = dispatch(definition());

        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, dispatch.errorCode());
        assertTrue(dispatch.feedback().contains("工具执行返回失败"), "固定措辞必须保留");
        assertTrue(dispatch.feedback().contains(upstream),
                "🔴 上游诊断必须如实回灌，否则模型只知道\"失败了\"、只能换写法瞎试：" + dispatch.feedback());
    }

    @Test
    @DisplayName("🔴 30053（本地 Schema 校验失败）→ 回灌 = 固定措辞 + **校验器字段级诊断**")
    void schemaValidationRelaysValidatorDiagnostic() {
        String diagnostic = "字段 FromTime 类型应为 number";
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.TOOL_ARGS_INVALID, diagnostic))
                .when(argsValidator).validate(anyString(), any(), anyString());

        ToolOrchestrator.ToolDispatch dispatch = dispatch(definition());

        assertEquals(ErrorCode.TOOL_ARGS_INVALID, dispatch.errorCode());
        assertEquals(ToolCall.STATUS_FAILED, dispatch.status());
        assertTrue(dispatch.feedback().contains("工具入参不符合约定"), "固定措辞必须保留");
        assertTrue(dispatch.feedback().contains(diagnostic),
                "🔴 模型必须知道\"改哪个字段\"，否则只能整体换写法：" + dispatch.feedback());
        verify(executorRegistry, never()).execute(any());
    }

    @Test
    @DisplayName("🔴 30053（MCP -32602 的上游 error.message，由执行器放进 content）→ 同样回灌")
    void mcpInvalidParamsRelaysUpstreamMessage() {
        String upstream = "Invalid params: Mode must be one of 0,1,2";
        stubExecute(new ToolExecutionResult(ToolCall.STATUS_FAILED, ErrorCode.TOOL_ARGS_INVALID,
                upstream, false, 8L));

        ToolOrchestrator.ToolDispatch dispatch = dispatch(definition());

        assertTrue(dispatch.feedback().contains(upstream), dispatch.feedback());
    }

    // ===================== ❌ 不可回灌：平台 / 传输侧诊断 =====================

    @Test
    @DisplayName("🔴 30052 / 30051 / 30056 / 50003 → 回灌**只有固定措辞**（反向断言：无 endpoint 等泄露）")
    void platformDiagnosticsAreNeverRelayed() {
        // 🔴 故意把"看起来很有用"的平台诊断塞进 content：编排层必须原样丢弃
        String leaky = "connect failed to https://127.0.0.1:9011/mcp (McpTransportException)";
        List<Integer> platformCodes = List.of(ErrorCode.MCP_UNAVAILABLE, ErrorCode.TOOL_TIMEOUT,
                ErrorCode.TOOL_RETRY_BLOCKED, ErrorCode.INTERNAL_ERROR);

        for (Integer code : platformCodes) {
            String status = code == ErrorCode.TOOL_TIMEOUT || code == ErrorCode.TOOL_RETRY_BLOCKED
                    ? ToolCall.STATUS_TIMED_OUT : ToolCall.STATUS_FAILED;
            stubExecute(new ToolExecutionResult(status, code, leaky, false, 5L));

            ToolOrchestrator.ToolDispatch dispatch = dispatch(definition());

            assertEquals(code, dispatch.errorCode());
            for (String forbidden : FORBIDDEN_IN_FEEDBACK) {
                assertFalse(dispatch.feedback().contains(forbidden),
                        "🔴 平台/传输侧诊断禁止喂给模型（含 " + forbidden + "）：" + dispatch.feedback());
            }
            assertFalse(dispatch.feedback().contains(leaky), "回灌必须是固定措辞：" + dispatch.feedback());
        }
    }

    @Test
    @DisplayName("🔴 30050 的三种来源措辞**完全一致**（差异化 = 探测平台配置的信道）")
    void deniedWordingIsIdenticalAcrossSources() {
        // ① 不在清单内（未授权 / 未绑定）
        when(catalogService.find(any(), anyString())).thenReturn(Optional.empty());
        String notInCatalog = dispatch(null).feedback();

        // ② preflight 判定服务已停用 / 撤授权
        when(catalogService.find(any(), anyString())).thenReturn(Optional.of(definition()));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.TOOL_DENIED, "工具调用被拒绝",
                        ToolExecutionResult.DeniedCause.GRANT_REVOKED))
                .when(executorRegistry).preflight(any());
        String revokedAtPreflight = dispatch(definition()).feedback();

        // ③ 执行前授权点查判拒
        org.mockito.Mockito.doNothing().when(executorRegistry).preflight(any());
        when(grantPointCheck.stillGranted(anyString(), any())).thenReturn(false);
        String revokedAtPointCheck = dispatch(definition()).feedback();

        assertEquals(notInCatalog, revokedAtPreflight,
                "🔴 三种来源的措辞必须逐字相同（否则模型/用户可据措辞差异推断平台配置）");
        assertEquals(notInCatalog, revokedAtPointCheck, "🔴 同上");
        for (String forbidden : FORBIDDEN_IN_FEEDBACK) {
            assertFalse(notInCatalog.contains(forbidden), notInCatalog);
        }
    }

    // ===================== 辅助 =====================

    private ToolOrchestrator.ToolDispatch dispatch(ToolDefinition definition) {
        if (definition != null) {
            when(catalogService.find(any(), anyString())).thenReturn(Optional.of(definition));
        }
        when(authorizationService.grantDeniedEvent(anyString(), anyString())).thenReturn(null);
        ToolOrchestrator.ToolRunContext ctx = new ToolOrchestrator.ToolRunContext(TENANT, 10086L,
                CONVERSATION_ID, MESSAGE_ID,
                definition == null ? List.of() : List.of(definition), null, frames::add);
        return orchestrator.dispatch(ctx,
                new ToolOrchestrator.ToolInvocation("call-1", TOOL_KEY, "{\"Query\":\"x\"}", 1));
    }

    private void stubExecute(ToolExecutionResult result) {
        when(executorRegistry.execute(any())).thenReturn(result);
    }

    private ToolDefinition definition() {
        return ToolDefinition.of(ToolDefinition.TYPE_MCP, TOOL_KEY, "web_search", "联网搜索",
                "{\"type\":\"object\"}", "digest",
                false, 30, 1L, null);
    }

    private ToolCall toolCall() {
        ToolCall call = new ToolCall();
        call.setId(TOOL_CALL_ID);
        call.setStatus(ToolCall.STATUS_PENDING);
        return call;
    }
}
