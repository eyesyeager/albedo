package com.eyes.albedo.agent.repository;

import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentVersion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Agent 版本仓储（tenant scope）。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：{@code findById} 是主键直载
 * （{@code EntityManager.find}），Hibernate 6 在该路径上<b>不会</b>追加 {@code tenant_id} 条件
 * （实测结论，ConfigValidateIT 守护），会读到其他租户的版本快照（含 systemPrompt）；
 * 请用 {@link #findOneById(Long)}。
 */
public interface AgentVersionRepository extends JpaRepository<AgentVersion, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的 Agent 版本（🔴 替代 {@code findById}）。
     */
    Optional<AgentVersion> findOneById(Long id);

    Optional<AgentVersion> findByAgentIdAndVersion(Long agentId, Long version);

    /**
     * 指定 Agent 的最大版本号；无记录返回 {@code null}。
     */
    @Query("SELECT MAX(v.version) FROM AgentVersion v WHERE v.agentId = :agentId")
    Long findMaxVersion(@Param("agentId") Long agentId);
}
