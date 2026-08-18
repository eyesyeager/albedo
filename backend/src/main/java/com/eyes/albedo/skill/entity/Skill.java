package com.eyes.albedo.skill.entity;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Skill 主体（{@code scope=tenant}，architecture.md §13.5.2）。
 *
 * <p>🔴 <b>key 唯一性归属主体表</b>（{@code uk_tenant_skill_key(tenant_id, skill_key)}），
 * 版本唯一性归属 {@link SkillVersion}。api-spec V1.1 曾把 key 唯一键标在版本表上，
 * 与「同一 Skill 多版本递增」自相矛盾，V1.1.1 已订正 —— 本实现以两表方案为基线。
 *
 * <p>{@code currentVersion} = 当前已发布版本号（{@code 0} 表示无可用版本）。
 * 一期无管理 UI，本表由 DBA 直接写库（DEC-010），运行时只读。
 */
@Getter
@Setter
@Entity
@Table(name = "skills")
public class Skill extends BaseTenantEntity {

    /** 租户内唯一（两租户可用相同 key，AC-TEN-003）。 */
    @Column(name = "skill_key", nullable = false, length = 64)
    private String skillKey;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "description", nullable = false, length = 500)
    private String description = "";

    /** enabled / disabled。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DISABLED;

    /** 当前已发布版本号，0 = 无可用版本。 */
    @Column(name = "current_version", nullable = false)
    private Integer currentVersion = 0;

    /** 乐观锁列（一期由 DBA 写库，暂不启用 {@code @Version}，避免与手工 SQL 冲突）。 */
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private java.time.Instant deletedAt;

    public static final String STATUS_ENABLED = "enabled";
    public static final String STATUS_DISABLED = "disabled";
}
