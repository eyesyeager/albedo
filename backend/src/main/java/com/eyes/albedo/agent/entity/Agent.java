package com.eyes.albedo.agent.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent 主体（{@code scope=tenant}，可编辑）。
 *
 * <p>与 {@link AgentVersion} 的关系：本表是「可编辑主体 + 当前发布指针」，
 * 运行时行为一律取自 {@code currentVersion} 指向的<b>不可变快照</b>，因此改主体不影响历史会话。
 *
 * <p>唯一键 {@code uk_tenant_agent_key(tenant_id, agent_key)} → 两个租户可用相同 key（AC-TEN-003）。
 * {@code version} 为 JPA 乐观锁，并发发布 → {@code 30020}。
 */
@Getter
@Setter
@Entity
@Table(name = "agents")
public class Agent extends BaseTenantEntity {

    @Column(name = "agent_key", nullable = false, length = 64)
    private String agentKey;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "description", nullable = false, length = 300)
    private String description = "";

    @Column(name = "avatar_url", nullable = false, length = 2048)
    private String avatarUrl = "";

    /** enabled / disabled / deleted。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    @Column(name = "is_default", nullable = false, columnDefinition = "tinyint")
    private Integer isDefault = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 当前已发布版本号，0 = 未发布（未发布不得对终端用户展示）。 */
    @Column(name = "current_version", nullable = false)
    private Long currentVersion = 0L;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_DELETED = "deleted";

    /**
     * 是否可供终端用户使用：已启用且有已发布版本。
     */
    public boolean runnable() {
        return STATUS_ENABLED.equals(status) && currentVersion != null && currentVersion > 0
                && deletedAt == null;
    }

    public boolean defaultAgent() {
        return isDefault != null && isDefault == 1;
    }
}
