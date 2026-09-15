package com.eyes.albedo.tool;

import com.eyes.albedo.agent.entity.AgentVersion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Agent {@code tool_policy} 的工具启用判定（api-spec §7.7.3）。
 *
 * <p>🔴 工具确认功能已删除（原「风险等级 × 确认矩阵」不再存在）：本类退化为
 * <b>「是否启用工具清单」的二元判断</b> —— {@code tool_policy != 'disabled'} 即启用。
 *
 * <p>🔴 取值兼容：{@code disabled} / {@code auto} / {@code confirm} 三个字面量<b>保留</b>
 * （存量 {@code agent_versions.tool_policy} 数据不受影响），但删除确认后
 * {@code confirm} 与 {@code auto} 语义等价（均自动执行，无逐次确认）。
 *
 * <p>🔴 未知 {@code tool_policy} 一律按 {@code disabled}（fail-closed：宁可不给工具，也不越权）。
 */
@Slf4j
@Component
public class ToolRiskPolicy {

    public static final String POLICY_DISABLED = "disabled";
    public static final String POLICY_AUTO = "auto";
    public static final String POLICY_CONFIRM = "confirm";

    /**
     * 工具是否可进入清单（{@code tool_policy != 'disabled'}）。
     */
    public boolean toolsEnabled(AgentVersion agentVersion) {
        return agentVersion != null
                && !POLICY_DISABLED.equals(normalizePolicy(agentVersion.getToolPolicy()));
    }

    /**
     * 🔴 未知 {@code tool_policy} 一律按 {@code disabled}（fail-closed：宁可不给工具，也不越权）。
     */
    public String normalizePolicy(String toolPolicy) {
        if (toolPolicy == null
                || !(POLICY_DISABLED.equals(toolPolicy)
                    || POLICY_AUTO.equals(toolPolicy)
                    || POLICY_CONFIRM.equals(toolPolicy))) {
            if (toolPolicy != null) {
                log.warn("非法工具策略，按 disabled 处理：toolPolicy={}", toolPolicy);
            }
            return POLICY_DISABLED;
        }
        return toolPolicy;
    }
}
