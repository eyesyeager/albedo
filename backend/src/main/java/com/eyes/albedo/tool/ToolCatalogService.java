package com.eyes.albedo.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentCapabilityBindingRepository;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.entity.McpTool;
import com.eyes.albedo.mcp.repository.McpServerRepository;
import com.eyes.albedo.mcp.repository.McpToolRepository;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.repository.LocalToolRepository;
import com.eyes.albedo.tool.repository.TenantToolGrantRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型可调用工具清单构造（api-spec §7.4.4 / §7.7.2，architecture.md §5.1.3 / §9.5.3）。
 *
 * <p><b>REQ-TOL-001 / REQ-TOL-002 / REQ-MCP-003 · AC-MCP-005 / AC-TOL-001 / AC-CHAT-007</b>
 *
 * <p>🔴 <b>进入清单的条件（本地 Tool 四条件，缺一不可 —— api-spec §7.7.2）</b>：
 * <ol>
 *   <li>{@code local_tools.status='enabled'}</li>
 *   <li>{@code tenant_tool_grants.granted=1 AND status='enabled'}</li>
 *   <li>被会话 {@code agentVersion} 通过 {@code agent_capability_bindings} 绑定</li>
 *   <li>{@code agent_versions.tool_policy != 'disabled'}</li>
 * </ol>
 * <b>MCP 工具三条件</b>：{@code mcp_tools.granted=1 AND status='enabled'} AND
 * 所属 {@code mcp_servers.status='enabled'} AND 被 {@code agentVersion} 绑定。
 *
 * <p>🔴 <b>性能纪律（api-spec §7.1.2 查询次数表 / architecture.md §9.5.3 / §14.2.1，
 * D-002/D-004 教训）</b>：
 * <pre>
 * 🔴 清单构造的查询预算 = **≤5 次批量查**（api-spec V1.1.4 #3 已把原"≤3 次"的表述订正为 ≤5，
 *    认定为"文档错、实现对"），允许且仅允许以下序列：
 *   ① agent_capability_bindings 一次取全（idx_tenant_version_sort）
 *   ② mcp_tools           一次 IN 批量
 *   ③ mcp_servers         一次批量（取 status / endpoint / transport / 凭据）
 *   ④ tenant_tool_grants  一次取全
 *   ⑤ local_tools         一次 IN 批量（取实现体标识 / input_schema / risk_level / timeout）
 * 🔴 ③ 与 ⑤ **不可省略**：它们依赖前一次查询的结果集（mcp_tools → mcp_servers、
 *    tenant_tool_grants → local_tools），不 join 就不可能 ≤3。
 * 🔴 **禁止改写为 join 以凑更小的数字**（V1.1.4 #3 明确不要求）：本方法在 M3 最热的
 *    首字链路上，收益 ≤1 次往返、风险是动 JPA 实体映射。
 * 🔴 **禁止 N+1**：逐个绑定单查会把 DB 往返累积到工具轮次上，挤压 300s 总封顶预算。
 * 🔴 **禁止缓存**（api-spec §7.1.2 末尾）：缓存会破坏 AC-CFG-004 / AC-MCP-004
 *    「DBA 改库取消授权后运行时立即拒绝」。
 * ⚠️ 本预算**不含** §7.6.3 的「每次工具执行前授权点查」（≤1 次/次执行，
 *    见 {@link ToolGrantPointCheck}）—— 两者按不同口径分别统计，不叠加。
 * </pre>
 *
 * <p>🔴 <b>本类不写审计</b>：清单构造只是"没把工具给模型看"，不是"拒绝了一次调用"。
 * 审计发生在<b>运行时被请求调用却不在清单内</b>时（{@link ToolAuthorizationService}）。
 *
 * <p>🔴 <b>本类不是授权判定的终点</b>（api-spec §7.6.3 / V1.1.4 #4）：清单构造是<b>一次</b>，
 * 而授权点查是<b>每次工具执行各一次</b>（{@link ToolGrantPointCheck}）——
 * 二者之间横跨整轮生成（含确认等待 120s + 多轮循环），DBA 可能在其间撤销授权。
 * 🔴 禁止以"清单已经校验过"为由删除执行前点查。
 */
@Slf4j
@Service
public class ToolCatalogService {

    /** {@code violations[].objectType}（api-spec §7.6.5 固定字面量）。 */
    private static final String OBJECT_TYPE_MCP_TOOL = "mcpTool";
    private static final String OBJECT_TYPE_LOCAL_TOOL = "localTool";

    private final AgentCapabilityBindingRepository bindingRepository;
    private final McpToolRepository mcpToolRepository;
    private final McpServerRepository mcpServerRepository;
    private final TenantToolGrantRepository grantRepository;
    private final LocalToolRepository localToolRepository;
    private final LocalToolRegistry localToolRegistry;
    private final ToolRiskPolicy riskPolicy;
    private final ToolArgsValidator argsValidator;
    private final com.eyes.albedo.sysconfig.BusinessConfig businessConfig;

    public ToolCatalogService(AgentCapabilityBindingRepository bindingRepository,
                             McpToolRepository mcpToolRepository,
                             McpServerRepository mcpServerRepository,
                             TenantToolGrantRepository grantRepository,
                             LocalToolRepository localToolRepository,
                             LocalToolRegistry localToolRegistry,
                             ToolRiskPolicy riskPolicy,
                             ToolArgsValidator argsValidator,
                             com.eyes.albedo.sysconfig.BusinessConfig businessConfig) {
        this.bindingRepository = bindingRepository;
        this.mcpToolRepository = mcpToolRepository;
        this.mcpServerRepository = mcpServerRepository;
        this.grantRepository = grantRepository;
        this.localToolRepository = localToolRepository;
        this.localToolRegistry = localToolRegistry;
        this.riskPolicy = riskPolicy;
        this.argsValidator = argsValidator;
        this.businessConfig = businessConfig;
    }

    /**
     * 构造模型可见工具清单。
     *
     * <p>🔴 非法配置（Schema 不可编译 / 超时越界 / 无实现体）→ {@code 30060}，
     * 在<b>进入模型之前</b>失败（AC-CFG-004 / ADR-014 第 4 条）。
     *
     * @param agentVersion 会话绑定的 Agent 版本（🔴 快照，不是"当前最新"）
     */
    @Transactional(readOnly = true)
    public List<ToolDefinition> buildCatalog(AgentVersion agentVersion) {
        if (!riskPolicy.toolsEnabled(agentVersion)) {
            // tool_policy=disabled → 一个工具都不给模型看（四条件之四）
            return List.of();
        }

        // ① 绑定表一次取全
        List<AgentCapabilityBinding> bindings = bindingRepository
                .findByAgentVersionIdOrderBySortOrderAscRefIdAsc(agentVersion.getId());
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<Long> mcpToolIds = new ArrayList<>();
        List<Long> localGrantIds = new ArrayList<>();
        for (AgentCapabilityBinding binding : bindings) {
            if (AgentCapabilityBinding.TYPE_MCP_TOOL.equals(binding.getCapabilityType())) {
                mcpToolIds.add(binding.getRefId());
            } else if (AgentCapabilityBinding.TYPE_LOCAL_TOOL.equals(binding.getCapabilityType())) {
                localGrantIds.add(binding.getRefId());
            }
        }

        List<ToolDefinition> catalog = new ArrayList<>();
        catalog.addAll(mcpTools(mcpToolIds, agentVersion));
        catalog.addAll(localTools(localGrantIds, agentVersion));
        log.debug("工具清单构造完成：agentVersionId={} size={}", agentVersion.getId(), catalog.size());
        return catalog;
    }

    /**
     * MCP 工具（🔴 一次 IN 批量查 + 一次服务状态批量查，无 N+1）。
     */
    private List<ToolDefinition> mcpTools(List<Long> mcpToolIds, AgentVersion agentVersion) {
        if (mcpToolIds.isEmpty()) {
            return List.of();
        }
        List<McpTool> tools = mcpToolRepository.findByIdIn(mcpToolIds);
        if (tools.isEmpty()) {
            return List.of();
        }
        // 服务状态：一次取全本租户 enabled 的 MCP（清单构造只关心 enabled）
        Map<Long, McpServer> enabledServers = new LinkedHashMap<>();
        for (McpServer server : mcpServerRepository.findByStatus(McpServer.STATUS_ENABLED)) {
            enabledServers.put(server.getId(), server);
        }

        List<ToolDefinition> definitions = new ArrayList<>();
        for (McpTool tool : tools) {
            // 🔴 三条件：已授权 + 启用 + 所属服务启用
            if (!tool.grantedAndEnabled()) {
                continue;
            }
            McpServer server = enabledServers.get(tool.getMcpId());
            if (server == null) {
                continue;
            }
            // Schema 自身非法 → 30060（在进模型之前失败）
            argsValidator.requireUsableSchema(tool.getToolKey(), tool.getInputSchema(), false);

            String risk = riskPolicy.normalizeRisk(tool.getRiskLevel());
            ToolDefinition definition = ToolDefinition.of(ToolDefinition.TYPE_MCP,
                    tool.getToolKey(),
                    tool.getToolName(), tool.getDescription(), tool.getInputSchema(),
                    tool.getInputSchemaDigest(), risk,
                    riskPolicy.decide(risk, agentVersion).requiresConfirmation(),
                    // MCP 工具的幂等性上游未声明：🔴 一律按**非幂等**处理（结果未知不自动重试，AC-TOL-003）
                    false,
                    effectiveMcpTimeout(server), server.getId(), null);
            // 🔴 归一化后 >64 字符 → 30060 rule=functionNameTooLong（拒绝不截断，api-spec §7.6.5）
            ToolFunctionNames.requireWithinLength(definition.toolKey(), definition.functionName(),
                    OBJECT_TYPE_MCP_TOOL, tool.getId());
            definitions.add(definition);
        }
        return definitions;
    }

    /**
     * 本地 Tool（🔴 授权表一次取全 + 平台注册表一次 IN 批量查，无 N+1）。
     */
    private List<ToolDefinition> localTools(List<Long> grantIds, AgentVersion agentVersion) {
        if (grantIds.isEmpty()) {
            return List.of();
        }
        // 一次取全本租户"已授权且启用"的授权行，再按绑定的 grantId 过滤
        Map<Long, TenantToolGrant> granted = new LinkedHashMap<>();
        for (TenantToolGrant grant : grantRepository.findByGrantedAndStatus(1,
                TenantToolGrant.STATUS_ENABLED)) {
            granted.put(grant.getId(), grant);
        }
        List<TenantToolGrant> bound = new ArrayList<>();
        List<String> toolKeys = new ArrayList<>();
        for (Long grantId : grantIds) {
            TenantToolGrant grant = granted.get(grantId);
            if (grant != null) {
                bound.add(grant);
                toolKeys.add(grant.getToolKey());
            }
        }
        if (bound.isEmpty()) {
            return List.of();
        }
        Map<String, LocalTool> registry = new LinkedHashMap<>();
        for (LocalTool tool : localToolRepository.findByToolKeyIn(toolKeys)) {
            registry.put(tool.getToolKey(), tool);
        }

        int maxTimeout = businessConfig.requireInt(com.eyes.albedo.sysconfig.ConfigKeys.GROUP_TOOL,
                com.eyes.albedo.sysconfig.ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS);
        int defaultTimeout = businessConfig.requireInt(
                com.eyes.albedo.sysconfig.ConfigKeys.GROUP_TOOL,
                com.eyes.albedo.sysconfig.ConfigKeys.TOOL_DEFAULT_TIMEOUT_SECONDS);

        List<ToolDefinition> definitions = new ArrayList<>();
        for (TenantToolGrant grant : bound) {
            LocalTool tool = registry.get(grant.getToolKey());
            if (tool == null || !LocalTool.STATUS_ENABLED.equals(tool.getStatus())) {
                // 平台侧未启用或注册行缺失 → 不进清单（四条件之一）
                continue;
            }
            // 🔴 注册表有行但平台无实现体 → 30060（在清单构造阶段就失败，ADR-014 第 4 条同理）
            localToolRegistry.require(tool.getToolKey());
            // 🔴 运行时一律读 local_tools **当前行**（api-spec §7.7.1 版本语义裁定 ③）
            argsValidator.requireUsableSchema(tool.getToolKey(), tool.getInputSchema(), true);

            int timeout = tool.getTimeoutSeconds() == null ? defaultTimeout : tool.getTimeoutSeconds();
            if (timeout < 1 || timeout > maxTimeout) {
                throw new com.eyes.albedo.common.BusinessException(
                        com.eyes.albedo.common.ErrorCode.RUNTIME_CONFIG_INVALID,
                        "超时秒数必须在 1~" + maxTimeout + " 之间");
            }
            String risk = riskPolicy.normalizeRisk(tool.getRiskLevel());
            ToolDefinition definition = ToolDefinition.of(ToolDefinition.TYPE_LOCAL,
                    tool.getToolKey(),
                    tool.getName(), tool.getDescription(), tool.getInputSchema(),
                    com.eyes.albedo.mcp.McpSchemaDigest.of(null), risk,
                    riskPolicy.decide(risk, agentVersion).requiresConfirmation(),
                    tool.idempotentTool(), timeout, null, grant.getConfig());
            // 🔴 本地 tool_key 受 ^[a-z][a-z0-9_]{1,63}$ 约束，天然合规；仍统一校验以防注册表被改库绕过
            ToolFunctionNames.requireWithinLength(definition.toolKey(), definition.functionName(),
                    OBJECT_TYPE_LOCAL_TOOL, tool.getId());
            definitions.add(definition);
        }
        return definitions;
    }

    /**
     * MCP 工具的有效超时 = {@code min(mcp.call_timeout_seconds, mcp_servers.timeout_seconds)}
     * （architecture.md §13.5.3）。
     */
    private int effectiveMcpTimeout(McpServer server) {
        int configured = businessConfig.requireInt(com.eyes.albedo.sysconfig.ConfigKeys.GROUP_MCP,
                com.eyes.albedo.sysconfig.ConfigKeys.MCP_CALL_TIMEOUT_SECONDS);
        Integer perServer = server.getTimeoutSeconds();
        return perServer == null || perServer <= 0 ? configured : Math.min(configured, perServer);
    }

    /**
     * 按 {@code toolKey} 取清单中的定义（运行时二次鉴权用）。
     */
    public Optional<ToolDefinition> find(List<ToolDefinition> catalog, String toolKey) {
        return catalog.stream().filter(definition -> definition.toolKey().equals(toolKey))
                .findFirst();
    }

    /**
     * 构造「模型函数名 → 工具定义」索引（下发 {@code tools} 字段与解析 {@code tool_calls} 共用）。
     *
     * <p>🔴 <b>碰撞即 {@code 30060} {@code rule=functionNameCollision}</b>（api-spec §7.6.5）：
     * 归一化把非法字符替换为下划线，因此 {@code crm:lookup} 与 {@code crm_lookup} 会撞成同一函数名。
     * 若放过碰撞，模型返回该函数名时我们<b>无法确定它想调哪个工具</b> ——
     * 猜错等于<b>执行了用户没批准的工具</b>。故 fail-closed：让配置在<b>进入模型之前</b>暴露。
     *
     * <p>🔴 <b>为什么归属本类</b>（architecture.md §5.1.3）：只有它看得到<b>本次生成的完整清单</b>，
     * 碰撞判定必须在全清单范围内做；单个工具自身永远发现不了碰撞。
     *
     * @throws com.eyes.albedo.common.BusinessException 30060 函数名碰撞
     */
    public java.util.Map<String, ToolDefinition> functionIndex(List<ToolDefinition> catalog) {
        Map<String, ToolDefinition> index = new LinkedHashMap<>();
        for (ToolDefinition definition : catalog) {
            ToolDefinition previous = index.put(definition.functionName(), definition);
            if (previous != null) {
                log.warn("[SECURITY] 工具函数名归一化后冲突，本次生成在进入模型之前失败："
                                + "functionName={} type={}", definition.functionName(),
                        definition.toolType());
                throw ToolFunctionNames.collision(
                        definition.mcp() ? OBJECT_TYPE_MCP_TOOL : OBJECT_TYPE_LOCAL_TOOL,
                        definition.mcpId());
            }
        }
        return index;
    }
}
