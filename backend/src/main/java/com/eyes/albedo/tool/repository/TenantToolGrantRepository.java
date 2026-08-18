package com.eyes.albedo.tool.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.tool.entity.TenantToolGrant;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 本地 Tool 租户授权仓储（{@code scope=tenant}）。
 *
 * <p>🔴 清单构造主路径 {@link #findByGrantedAndStatus(Integer, String)}（走 {@code idx_tenant_granted}）：
 * 一次取全，禁止逐个绑定单查（N+1）。
 * 🔴 一期<b>不缓存</b>：直读 MySQL，保证 DBA 取消授权后运行时立即拒绝（AC-CFG-004）。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：主键直载路径不会被追加 {@code tenant_id}
 * 条件（实测结论）；请用 {@link #findOneById(Long)}。
 */
public interface TenantToolGrantRepository extends JpaRepository<TenantToolGrant, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的授权记录（🔴 替代 {@code findById}）。
     */
    Optional<TenantToolGrant> findOneById(Long id);

    Optional<TenantToolGrant> findByToolKey(String toolKey);

    /** 🔴 清单构造主路径：一次取全本租户已授权且启用的本地 Tool。 */
    List<TenantToolGrant> findByGrantedAndStatus(Integer granted, String status);

    /**
     * 🔴 <b>绑定链递归校验专用</b>：一次取回「{@code tenant_tool_grants} + 平台
     * {@code local_tools} 当前行」（api-spec §7.3.1 G10 的 4 次查询预算）。
     *
     * <p>🔴 关联键是 {@code tool_key}（不是 id）：{@code local_tools} 是<b>单行表 + 就地递增
     * version</b>（api-spec §7.7.1 版本语义裁定），运行时一律读<b>当前行</b>。
     * 🔴 {@code left join}：平台注册行缺失是必须报出的 {@code refNotFound}。
     *
     * <p>🔴 {@code TenantToolGrant} 是租户表（discriminator 自动加 {@code tenant_id}），
     * {@code LocalTool} 是平台表（无租户维度）—— 混合关联是有意为之，不是隔离漏洞。
     *
     * @param ids 绑定的 {@code ref_id} 集合（🔴 {@code tenant_tool_grants.id}，<b>不是</b>
     *            {@code local_tools.id}，architecture.md §13.5.10 G1 裁决）
     * @return 每行 {@code [TenantToolGrant, LocalTool|null]}
     */
    @org.springframework.data.jpa.repository.Query(
            "select g, lt from TenantToolGrant g"
                    + " left join com.eyes.albedo.tool.entity.LocalTool lt on lt.toolKey = g.toolKey"
                    + " where g.id in :ids")
    List<Object[]> findBoundGrantsWithLocalTool(
            @org.springframework.data.repository.query.Param("ids") List<Long> ids);

    /**
     * 🔴 <b>每次工具执行前的授权点查</b>（api-spec §7.6.3 契约表 / architecture.md §9.5.1 #4 补注）。
     *
     * <p>判据 = §7.7.2 授权四条件中<b>与库内状态相关</b>的三条：
     * {@code tenant_tool_grants.granted=1} AND {@code tenant_tool_grants.status='enabled'} AND
     * {@code local_tools.status='enabled'}。
     * （第四条"被 agentVersion 绑定"由清单构造保证，且绑定在本次生成内不可变 ——
     * 清单一旦构造，绑定即为本次生成的快照语义；🔴 授权与启用状态才是 DBA 可随时改的部分。
     * 三表均<b>无</b> {@code deleted_at} 列，已用 MCP 核对实建结构。）
     *
     * <p>🔴 <b>必须是一条查询</b>（查询预算 ≤1 次）；🔴 禁止缓存结果
     * （缓存即回到 fail-open，破坏 AC-CFG-004「运行时以当前库内配置为准」）。
     *
     * @return 命中行数（0 = 授权已撤销 / 平台已停用，判 {@code 30050}）
     */
    @org.springframework.data.jpa.repository.Query(
            "select count(g) from TenantToolGrant g"
                    + " join com.eyes.albedo.tool.entity.LocalTool lt on lt.toolKey = g.toolKey"
                    + " where g.toolKey = :toolKey and g.granted = 1"
                    + " and g.status = 'enabled' and lt.status = 'enabled'")
    long countExecutableGrant(
            @org.springframework.data.repository.query.Param("toolKey") String toolKey);
}
