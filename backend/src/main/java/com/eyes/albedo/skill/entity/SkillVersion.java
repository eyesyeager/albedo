package com.eyes.albedo.skill.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Skill 不可变版本（{@code scope=tenant}，architecture.md §13.5.2 / api-spec §7.5.1）。
 *
 * <p>🔴 <b>{@code status='published'} 后行不可变</b>：{@code instruction} /
 * {@code variablesSchema} / {@code outputConstraint} / {@code version} 均声明
 * {@code updatable = false}，仅允许 {@code published → archived} 的状态迁移。
 * 为什么（AC-SKL-002）：会话绑定的是<b>精确 Agent 版本</b>，而 Agent 版本引用<b>精确 Skill 版本</b>；
 * 若已发布版本可被改写，历史会话的行为会静默漂移，"版本快照"就名存实亡。
 *
 * <p>🔴 {@code instruction} 是<b>内部资产</b>：禁止出现在任何对外响应、SSE 事件、埋点、审计与日志中；
 * 审计/埋点只记 {@code skillVersionId} 与 sha256 前 16 位 digest（api-spec §7.5.2 第 4 条）。
 *
 * <p>长度与数量上限取 {@code sys_config}：{@code skill.instruction_max_chars} /
 * {@code skill.max_variables}，越界 → {@code 30060}（🔴 代码中禁止出现 50000 / 50 字面量）。
 */
@Getter
@Setter
@Entity
@Table(name = "skill_versions")
public class SkillVersion extends BaseTenantEntity {

    /** 指向 {@code skills.id}；🔴 必须同租户，跨租户引用 → {@code 30060}。 */
    @Column(name = "skill_id", nullable = false, updatable = false)
    private Long skillId;

    /** 租户内单 Skill 递增。 */
    @Column(name = "version", nullable = false, updatable = false)
    private Integer version;

    /** 指令正文（🔴 内部资产，永不外泄）。 */
    @Column(name = "instruction", nullable = false, updatable = false, columnDefinition = "longtext")
    private String instruction;

    /** {@code [{name,required,description,defaultValue}]}。 */
    @Column(name = "variables_schema", updatable = false, columnDefinition = "json")
    private String variablesSchema;

    @Column(name = "output_constraint", updatable = false, columnDefinition = "text")
    private String outputConstraint;

    /** draft / published / archived（🔴 唯一允许的迁移是 published → archived）。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    @Column(name = "published_by", updatable = false)
    private Long publishedBy;

    @Column(name = "published_at", updatable = false)
    private Instant publishedAt;

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    /** {@code output_constraint} 长度上限（api-spec §7.5.1：0~5000）。 */
    public static final int OUTPUT_CONSTRAINT_MAX_CHARS = 5000;
}
