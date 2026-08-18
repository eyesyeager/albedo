package com.eyes.albedo.agent.repository;

import java.util.List;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Agent 能力绑定仓储（{@code scope=tenant}）。
 *
 * <p>🔴 <b>性能纪律（architecture.md §9.5.3）</b>：清单构造必须
 * <b>一次取全</b>某 Agent 版本的所有绑定（{@link #findByAgentVersionIdOrderBySortOrderAscRefIdAsc}
 * 走 {@code idx_tenant_version_sort}），再按 {@code capability_type} 分组后对
 * {@code mcp_tools} / {@code tenant_tool_grants} 各做<b>一次 IN 批量查</b>。
 * 🔴 禁止逐个绑定单查（N+1 会把 DB 往返算进首字预算）。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：主键直载路径不会被追加 {@code tenant_id}
 * 条件（M2-min 实测结论）。本仓储不暴露按 ID 单查的入口。
 */
public interface AgentCapabilityBindingRepository extends JpaRepository<AgentCapabilityBinding, Long> {

    /**
     * 一次取全某 Agent 版本的全部绑定，按 api-spec §7.5.2 的注入顺序
     * （{@code sort_order ASC, ref_id ASC}）返回。
     */
    List<AgentCapabilityBinding> findByAgentVersionIdOrderBySortOrderAscRefIdAsc(Long agentVersionId);

    /**
     * 按能力类型取绑定（同样按注入顺序）。
     */
    List<AgentCapabilityBinding> findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
            Long agentVersionId, String capabilityType);
}
