package com.eyes.albedo.agent.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.entity.Agent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Agent 仓储（tenant scope，Hibernate 自动追加 {@code tenant_id}）。
 *
 * <p>排序基线（api-spec §4.4.1）：{@code isDefault DESC, sortOrder ASC, id ASC} —— 末位 id 保证稳定。
 */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByAgentKey(String agentKey);

    Optional<Agent> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 可供终端用户使用的 Agent：已启用 + 有已发布版本 + 未删除。
     */
    @Query("SELECT a FROM Agent a WHERE a.status = :status AND a.currentVersion > 0 AND a.deletedAt IS NULL "
            + "ORDER BY a.isDefault DESC, a.sortOrder ASC, a.id ASC")
    List<Agent> findRunnable(@Param("status") String status);

    /**
     * 管理视图：全部未删除 Agent（M2 管理端使用，稳定排序）。
     */
    @Query("SELECT a FROM Agent a WHERE a.deletedAt IS NULL "
            + "ORDER BY a.isDefault DESC, a.sortOrder ASC, a.id ASC")
    List<Agent> findAllManaged();

    /**
     * 当前默认 Agent（每租户最多一个）。
     */
    @Query("SELECT a FROM Agent a WHERE a.isDefault = 1 AND a.deletedAt IS NULL")
    List<Agent> findDefaults();
}
