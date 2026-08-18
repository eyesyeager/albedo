package com.eyes.albedo.mcp;

import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.dto.McpDiscoveryReport;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MCP 工具发现编排（api-spec §7.4.3，REQ-MCP-002 / AC-MCP-002 / AC-MCP-005）。
 *
 * <p><b>🔴 五条发现规则（api-spec §7.4.3）</b>：
 * <ol>
 *   <li>{@code changeType} ∈ {@code new / unchanged / schema_changed / removed}</li>
 *   <li>🔴 {@code new} 一律 {@code granted=false} + {@code status=disabled}
 *       —— <b>新发现工具默认禁用</b>。为什么这条最关键：发现是"上游说它有什么"，
 *       授权是"我们允许用什么"；若发现即授权，上游只要新增一个 {@code delete_all}
 *       就直接获得了调用权</li>
 *   <li>🔴 {@code schema_changed} 的<b>已授权</b>工具自动降级 + 审计
 *       —— 封堵"先以无害 Schema 拿授权，再偷换参数"</li>
 *   <li>{@code removed} 保留历史行置 {@code disabled}，🔴 不物理删除
 *       （保住 {@code tool_calls} 可追溯语义）</li>
 *   <li>工具数超 {@code mcp.max_tools_per_server} → {@code 30060} 且🔴 <b>整批不落库</b></li>
 * </ol>
 *
 * <p>🔴 <b>事务纪律（ADR-010）</b>：本类<b>不是</b>事务方法 —— 它含 DNS 解析与 HTTP 调用。
 * 落库与审计收敛在 {@link McpDiscoveryWriter#persist} 的<b>短事务</b>内
 * （非流式路径：审计与业务同事务，审计失败即整体失败，EX-024）。
 */
@Slf4j
@Service
public class McpDiscoveryService {

    /** 摘要的对外前缀（api-spec §7.4.3 示例 {@code sha256:8c1f…}）。 */
    public static final String DIGEST_PREFIX = "sha256:";

    private final McpClient mcpClient;
    private final McpDiscoveryWriter discoveryWriter;
    private final SsrfGuard ssrfGuard;
    private final BusinessConfig businessConfig;

    public McpDiscoveryService(McpClient mcpClient,
                              McpDiscoveryWriter discoveryWriter,
                              SsrfGuard ssrfGuard,
                              BusinessConfig businessConfig) {
        this.mcpClient = mcpClient;
        this.discoveryWriter = discoveryWriter;
        this.ssrfGuard = ssrfGuard;
        this.businessConfig = businessConfig;
    }

    /**
     * 执行发现。
     *
     * @param dryRun {@code true} 时只返回比对结果不落库
     * @throws BusinessException 30050（SSRF 拒绝）、30052（连接 / 协议 / 鉴权失败）、
     *                           30060（配置非法 / 工具数超限）
     */
    public McpDiscoveryReport discover(McpServer server, boolean dryRun) {
        // ① SSRF 校验（🔴 拒绝则不发起任何连接；非流式 → 审计与业务同事务）
        ssrfGuard.requireAllowed(server, SsrfGuard.AuditMode.SAME_TRANSACTION);

        // ② tools/list（🔴 必须在事务外）
        List<McpToolDescriptor> upstream;
        try {
            upstream = mcpClient.listTools(server);
        } catch (McpTransportException e) {
            // 🔴 只记分类，不记 endpoint / 上游正文
            log.warn("MCP 工具发现失败：mcpId={} failure={}", server.getId(), e.failure());
            throw new BusinessException(ErrorCode.MCP_UNAVAILABLE,
                    ErrorCode.defaultMessage(ErrorCode.MCP_UNAVAILABLE));
        }

        // ③ 数量上限（🔴 超限整批不落库）
        int maxTools = businessConfig.requireInt(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_MAX_TOOLS_PER_SERVER);
        if (upstream.size() > maxTools) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "工具数超过单服务上限 " + maxTools);
        }

        // ④ 比对与落库（短事务，审计同事务）
        return discoveryWriter.persist(server, upstream, dryRun);
    }
}
