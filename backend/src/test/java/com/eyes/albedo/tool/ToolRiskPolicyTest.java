package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.entity.ToolCall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具启用判定与状态机单测（api-spec §7.7.3 / §7.8.1）。
 */
class ToolRiskPolicyTest {

    private final ToolRiskPolicy policy = new ToolRiskPolicy();

    @Test
    @DisplayName("toolsEnabled：tool_policy=disabled 或非法 → false")
    void toolsEnabledCheck() {
        assertFalse(policy.toolsEnabled(null));
        assertFalse(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_DISABLED)));
        assertTrue(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_AUTO)));
        assertTrue(policy.toolsEnabled(agentVersion(ToolRiskPolicy.POLICY_CONFIRM)));
    }

    @Test
    @DisplayName("normalizePolicy：未知取值 fail-closed 到 disabled；合法取值原样保留")
    void normalizePolicyCheck() {
        assertEquals(ToolRiskPolicy.POLICY_DISABLED, policy.normalizePolicy("weird"));
        assertEquals(ToolRiskPolicy.POLICY_DISABLED, policy.normalizePolicy(null));
        assertEquals(ToolRiskPolicy.POLICY_AUTO, policy.normalizePolicy(ToolRiskPolicy.POLICY_AUTO));
        assertEquals(ToolRiskPolicy.POLICY_CONFIRM,
                policy.normalizePolicy(ToolRiskPolicy.POLICY_CONFIRM));
        assertEquals(ToolRiskPolicy.POLICY_DISABLED,
                policy.normalizePolicy(ToolRiskPolicy.POLICY_DISABLED));
    }

    @Test
    @DisplayName("🔴 §7.8.1 状态机：合法流转全通过，终态不可再迁移")
    void stateMachineTransitions() {
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_PENDING, ToolCall.STATUS_RUNNING));
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_RUNNING,
                ToolCall.STATUS_SUCCEEDED));
        // 🔴 V1.1.3 ③：执行期竞态 running → denied 合法
        assertTrue(ToolStateMachine.canTransition(ToolCall.STATUS_RUNNING, ToolCall.STATUS_DENIED));

        // 🔴 终态不可覆盖
        for (String terminal : ToolCall.TERMINAL_STATUSES) {
            assertFalse(ToolStateMachine.canTransition(terminal, ToolCall.STATUS_RUNNING),
                    terminal + " 必须是终态");
            assertTrue(ToolStateMachine.terminal(terminal));
        }
        // 跳跃流转非法
        assertFalse(ToolStateMachine.canTransition(ToolCall.STATUS_PENDING,
                ToolCall.STATUS_SUCCEEDED));
    }

    @Test
    @DisplayName("🔴 timed_out 仅表示执行超时（30051/30056）")
    void timedOutSemantics() {
        assertTrue(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_TIMEOUT));
        assertTrue(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_RETRY_BLOCKED));
        assertFalse(ToolStateMachine.executionTimeout(ToolCall.STATUS_TIMED_OUT,
                ErrorCode.TOOL_DENIED));
    }

    private AgentVersion agentVersion(String toolPolicy) {
        AgentVersion version = new AgentVersion();
        version.setToolPolicy(toolPolicy);
        return version;
    }
}
