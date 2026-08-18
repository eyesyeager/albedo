package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.entity.ToolCall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 风险矩阵与状态机单测（api-spec §7.7.3 / §7.8.1）。
 */
class ToolRiskPolicyTest {

    private final ToolRiskPolicy policy = new ToolRiskPolicy();

    @Test
    @DisplayName("🔴 §7.7.3 矩阵逐格：tool_policy=disabled → 任何风险等级都不进清单")
    void disabledPolicyBlocksAll() {
        for (String risk : new String[]{"low", "medium", "high"}) {
            ToolRiskPolicy.Decision decision = policy.decide(risk, ToolRiskPolicy.POLICY_DISABLED);
            assertFalse(decision.inCatalog(), "risk=" + risk);
        }
    }

    @Test
    @DisplayName("🔴 §7.7.3 矩阵逐格：auto → low/medium 自动执行，high 仍必须确认（不可降级）")
    void autoPolicyMatrix() {
        assertFalse(policy.decide("low", ToolRiskPolicy.POLICY_AUTO).requiresConfirmation());
        assertFalse(policy.decide("medium", ToolRiskPolicy.POLICY_AUTO).requiresConfirmation());
        assertTrue(policy.decide("high", ToolRiskPolicy.POLICY_AUTO).requiresConfirmation(),
                "🔴 high 的确认要求不可被任何策略降级（PRD §8.7）");
    }

    @Test
    @DisplayName("🔴 §7.7.3 矩阵逐格：confirm → low 自动、medium 必须确认、high 必须确认")
    void confirmPolicyMatrix() {
        assertFalse(policy.decide("low", ToolRiskPolicy.POLICY_CONFIRM).requiresConfirmation());
        assertTrue(policy.decide("medium", ToolRiskPolicy.POLICY_CONFIRM).requiresConfirmation());
        assertTrue(policy.decide("high", ToolRiskPolicy.POLICY_CONFIRM).requiresConfirmation());
    }

    @Test
    @DisplayName("🔴 未知风险等级按 high（fail-closed）；未知策略按 disabled（宁可不给工具）")
    void unknownValuesFailClosed() {
        assertTrue(policy.decide("unknown", ToolRiskPolicy.POLICY_AUTO).requiresConfirmation());
        assertTrue(policy.decide(null, ToolRiskPolicy.POLICY_AUTO).requiresConfirmation());
        assertFalse(policy.decide("low", "unknown-policy").inCatalog());
        assertFalse(policy.decide("low", (String) null).inCatalog());
        assertEquals(ToolRiskPolicy.RISK_HIGH, policy.normalizeRisk("weird"));
        assertEquals(ToolRiskPolicy.POLICY_DISABLED, policy.normalizePolicy("weird"));
    }

    @Test
    @DisplayName("toolsEnabled：tool_policy=disabled 或非法 → false")
    void toolsEnabledCheck() {
        assertFalse(policy.toolsEnabled(null));
        assertFalse(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_DISABLED)));
        assertTrue(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_AUTO)));
        assertTrue(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_CONFIRM)));
    }

    @Test
    @DisplayName("非法注册值 → 30060")
    void invalidRegistrationRejected() {
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> policy.requireValid("weird", ToolRiskPolicy.POLICY_AUTO)).getCode());
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID,
                assertThrows(BusinessException.class,
                        () -> policy.requireValid("low", "weird")).getCode());
    }

    @Test
    @DisplayName("🔴 §7.8.1 状态机：合法流转全通过，终态不可再迁移")
    void stateMachineTransitions() {
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_PENDING,
                ToolCall.STATUS_AWAITING_CONFIRMATION));
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_PENDING, ToolCall.STATUS_RUNNING));
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_AWAITING_CONFIRMATION,
                ToolCall.STATUS_TIMED_OUT));
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_RUNNING,
                ToolCall.STATUS_SUCCEEDED));

        // 🔴 终态不可覆盖（否则后到的 timeout 会把 succeeded 改掉，破坏 30055 冲突判定）
        for (String terminal : ToolCall.TERMINAL_STATUSES) {
            assertFalse(ToolStateMachine.canTransition(terminal, ToolCall.STATUS_RUNNING),
                    terminal + " 必须是终态");
            assertTrue(ToolStateMachine.terminal(terminal));
        }
        // 跳跃流转非法
        assertFalse(ToolStateMachine.canTransition(ToolCall.STATUS_PENDING,
                ToolCall.STATUS_SUCCEEDED));
        assertFalse(ToolStateMachine.canTransition(ToolCall.STATUS_AWAITING_CONFIRMATION,
                ToolCall.STATUS_SUCCEEDED));
    }

    @Test
    @DisplayName("🔴 timed_out 的两种语义由 errorCode 区分（30050 确认超时 vs 30051/30056 执行超时）")
    void timedOutSemantics() {
        assertTrue(ToolStateMachine.confirmTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_DENIED));
        assertFalse(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_DENIED));

        assertTrue(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_TIMEOUT));
        assertTrue(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_RETRY_BLOCKED));
        assertFalse(ToolStateMachine.confirmTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_TIMEOUT));
    }

    @Test
    @DisplayName("非法流转 → 30060（绝不静默覆盖终态）")
    void illegalTransitionThrows() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> ToolStateMachine.requireTransition(ToolCall.STATUS_SUCCEEDED,
                        ToolCall.STATUS_RUNNING));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
    }

    private AgentVersion agentVersion(String toolPolicy) {
        AgentVersion version = new AgentVersion();
        version.setToolPolicy(toolPolicy);
        return version;
    }
}
