package com.eyes.albedo.tool;

import java.util.Set;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 风险等级 × Agent {@code toolPolicy} 的确认矩阵（api-spec §7.7.3 / PRD §8.7）。
 *
 * <table border="1">
 *   <caption>确认矩阵（🔴 唯一实现，前后端不得各写一遍）</caption>
 *   <tr><th>{@code tool_policy}</th><th>{@code low}</th><th>{@code medium}</th><th>{@code high}</th></tr>
 *   <tr><td>{@code disabled}</td><td>不进清单</td><td>不进清单</td><td>不进清单</td></tr>
 *   <tr><td>{@code auto}</td><td>自动执行</td><td>自动执行</td><td>🔴 <b>每次必须确认</b></td></tr>
 *   <tr><td>{@code confirm}</td><td>自动执行</td><td>🔴 必须确认</td><td>🔴 <b>每次必须确认</b></td></tr>
 * </table>
 *
 * <p>🔴 <b>两条不可协商的规则</b>：
 * <ol>
 *   <li>{@code high} 的确认要求<b>不可被任何策略降级</b>（PRD §8.7）——
 *       包括 {@code tool_policy=auto}。租户也不能自行下调 {@code risk_level}
 *       （该列在平台表 {@code local_tools} / 由平台规则给出的 {@code mcp_tools}，租户无写权限）</li>
 *   <li>"每次"= <b>逐次确认</b>：同一会话、同一工具的历史同意<b>不得</b>沿用到下一次调用。
 *       因此本类是<b>无状态</b>的纯函数 —— 🔴 它<b>不持有</b>任何"已确认过"的记忆，
 *       从实现形态上杜绝"沿用历史同意"这类退化。</li>
 * </ol>
 *
 * <p>⚠️ 未知风险等级一律按 {@code high} 处理（fail-closed）：
 * 上游发现结果或 DBA 写库可能给出非法值，此时"当作高风险"是唯一安全的默认。
 */
@Slf4j
@Component
public class ToolRiskPolicy {

    public static final String RISK_LOW = "low";
    public static final String RISK_MEDIUM = "medium";
    public static final String RISK_HIGH = "high";

    public static final String POLICY_DISABLED = "disabled";
    public static final String POLICY_AUTO = "auto";
    public static final String POLICY_CONFIRM = "confirm";

    public static final Set<String> ALL_RISK_LEVELS = Set.of(RISK_LOW, RISK_MEDIUM, RISK_HIGH);
    public static final Set<String> ALL_POLICIES = Set.of(POLICY_DISABLED, POLICY_AUTO,
            POLICY_CONFIRM);

    /**
     * 判定结论。
     *
     * @param inCatalog            是否进入模型可调用清单
     * @param requiresConfirmation 是否需要用户逐次确认
     */
    public record Decision(boolean inCatalog, boolean requiresConfirmation) {

        public static Decision notInCatalog() {
            return new Decision(false, false);
        }

        public static Decision auto() {
            return new Decision(true, false);
        }

        public static Decision confirm() {
            return new Decision(true, true);
        }
    }

    /**
     * 按矩阵判定。
     *
     * @param riskLevel  {@code low} / {@code medium} / {@code high}（未知按 {@code high}）
     * @param toolPolicy {@code agent_versions.tool_policy}
     */
    public Decision decide(String riskLevel, String toolPolicy) {
        String risk = normalizeRisk(riskLevel);
        String policy = normalizePolicy(toolPolicy);

        if (POLICY_DISABLED.equals(policy)) {
            return Decision.notInCatalog();
        }
        if (RISK_HIGH.equals(risk)) {
            // 🔴 high 每次必须确认，任何策略都不能降级
            return Decision.confirm();
        }
        if (RISK_MEDIUM.equals(risk) && POLICY_CONFIRM.equals(policy)) {
            return Decision.confirm();
        }
        return Decision.auto();
    }

    /**
     * 按 Agent 版本判定。
     */
    public Decision decide(String riskLevel, AgentVersion agentVersion) {
        return decide(riskLevel, agentVersion == null ? POLICY_DISABLED
                : agentVersion.getToolPolicy());
    }

    /**
     * 工具是否可进入清单（{@code tool_policy != 'disabled'}，四条件之一）。
     */
    public boolean toolsEnabled(AgentVersion agentVersion) {
        return agentVersion != null
                && !POLICY_DISABLED.equals(normalizePolicy(agentVersion.getToolPolicy()));
    }

    /**
     * 校验注册值合法性（供配置校验与清单构造使用）。
     *
     * @throws BusinessException 30060 风险等级或策略取值非法
     */
    public void requireValid(String riskLevel, String toolPolicy) {
        if (riskLevel == null || !ALL_RISK_LEVELS.contains(riskLevel)) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "风险等级取值必须是 low / medium / high");
        }
        if (toolPolicy == null || !ALL_POLICIES.contains(toolPolicy)) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具策略取值必须是 disabled / auto / confirm");
        }
    }

    /**
     * 🔴 未知风险等级一律按 {@code high}（fail-closed）。
     */
    public String normalizeRisk(String riskLevel) {
        if (riskLevel == null || !ALL_RISK_LEVELS.contains(riskLevel)) {
            if (riskLevel != null) {
                log.warn("非法风险等级，按 high 处理：riskLevel={}", riskLevel);
            }
            return RISK_HIGH;
        }
        return riskLevel;
    }

    /**
     * 🔴 未知 {@code tool_policy} 一律按 {@code disabled}（fail-closed：宁可不给工具，也不越权）。
     */
    public String normalizePolicy(String toolPolicy) {
        if (toolPolicy == null || !ALL_POLICIES.contains(toolPolicy)) {
            if (toolPolicy != null) {
                log.warn("非法工具策略，按 disabled 处理：toolPolicy={}", toolPolicy);
            }
            return POLICY_DISABLED;
        }
        return toolPolicy;
    }
}
