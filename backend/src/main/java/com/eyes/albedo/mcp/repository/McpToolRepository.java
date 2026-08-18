package com.eyes.albedo.mcp.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.mcp.entity.McpTool;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP 工具仓储（{@code scope=tenant}）。
 *
 * <p>🔴 清单构造主路径是 {@link #findByGrantedAndStatus(Integer, String)}
 * （走 {@code idx_tenant_granted}）：<b>一次查出本租户全部可用工具</b>，
 * 禁止按绑定逐个单查（architecture.md §9.5.3 明令禁止 N+1）。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：主键直载路径不会被追加 {@code tenant_id}
 * 条件（实测结论）；请用 {@link #findOneById(Long)}。
 */
public interface McpToolRepository extends JpaRepository<McpTool, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的 MCP 工具（🔴 替代 {@code findById}）。
     */
    Optional<McpTool> findOneById(Long id);

    Optional<McpTool> findByToolKey(String toolKey);

    /** 🔴 清单构造主路径：一次取全本租户已授权且启用的工具。 */
    List<McpTool> findByGrantedAndStatus(Integer granted, String status);

    /** 某 MCP 服务下的全部工具（发现结果比对与按服务列举）。 */
    List<McpTool> findByMcpId(Long mcpId);

    List<McpTool> findByMcpIdAndStatus(Long mcpId, String status);

    /** 批量取指定 ID（能力绑定 → 工具定义的 IN 批量查，避免 N+1）。 */
    List<McpTool> findByIdIn(List<Long> ids);

    /**
     * 🔴 <b>绑定链递归校验专用</b>：一次取回「{@code mcp_tools} + 所属 {@code mcp_servers}」
     * （api-spec §7.3.1 G10 的 4 次查询预算，理由同
     * {@code SkillVersionRepository.findBoundSkillVersions}）。
     *
     * <p>🔴 {@code left join}：服务行缺失（DBA 删了 server 却留着 tool）是必须报出的
     * {@code refNotFound}，inner join 会让它静默消失。
     *
     * @return 每行 {@code [McpTool, McpServer|null]}
     */
    @org.springframework.data.jpa.repository.Query(
            "select t, s from McpTool t left join McpServer s on s.id = t.mcpId"
                    + " where t.id in :ids")
    List<Object[]> findBoundToolsWithServer(
            @org.springframework.data.repository.query.Param("ids") List<Long> ids);

    /**
     * 🔴 <b>每次工具执行前的授权点查</b>（api-spec §7.6.3 契约表 / architecture.md §9.5.1 #4 补注）。
     *
     * <p>判据（缺一即视为未授权）：{@code mcp_tools.granted=1} AND
     * {@code mcp_tools.status='enabled'} AND 所属 {@code mcp_servers.status='enabled'} AND
     * {@code mcp_servers.deleted_at IS NULL}。
     * （{@code mcp_tools} / {@code tenant_tool_grants} / {@code local_tools} 三表<b>无</b>
     * {@code deleted_at} 列 —— 已用 MCP 核对实建结构，故只对 {@code mcp_servers} 判软删除。）
     *
     * <p>🔴 <b>必须是一条查询</b>（查询预算 ≤1 次）：用 {@code inner join} 一次同时判定
     * 工具授权与服务状态；🔴 禁止拆成"先查 tool 再查 server"（那是 2 次），
     * 也 🔴 禁止缓存结果（缓存即回到 fail-open，破坏 AC-MCP-004「改库即生效」）。
     *
     * <p>租户维度由 Hibernate discriminator 自动追加（两张表都是租户表），
     * 因此本方法<b>结构上</b>不可能跨租户命中。
     *
     * @return 命中行数（0 = 已被撤销授权 / 已停用，判 {@code 30050}）
     */
    @org.springframework.data.jpa.repository.Query(
            "select count(t) from McpTool t join McpServer s on s.id = t.mcpId"
                    + " where t.mcpId = :mcpId and t.toolKey = :toolKey"
                    + " and t.granted = 1 and t.status = 'enabled'"
                    + " and s.status = 'enabled' and s.deletedAt is null")
    long countExecutableGrant(
            @org.springframework.data.repository.query.Param("mcpId") Long mcpId,
            @org.springframework.data.repository.query.Param("toolKey") String toolKey);
}
