package com.eyes.albedo.skill.entity;

import com.eyes.albedo.tenant.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Skill 资源文件（按精确版本存储，与不可变 {@code instruction} 解耦）。
 *
 * <p>一个 {@code SkillVersion} 可挂载多个资源文件（SKILL.md 中引用的
 * {@code assets/}、{@code references/}、{@code promo/} 等目录下的素材）。
 * 资源在运行时由 {@code SkillInjectionService} 读取并作为 instruction 的补充素材注入，
 * 使 Agent 在执行时能拿到 SKILL.md 引用的文件正文，修复此前"只注入 SKILL.md、
 * 资源文件内容缺失"的接入缺口。
 *
 * <p>🔴 关联 {@code skill_versions.id}（精确版本，遵循不可变版本纪律，
 * 不污染 {@code instruction} 字段）。{@code tenant_id} 由框架自动维护（BaseTenantEntity）。
 *
 * <p>🔴 <b>{@code executable} 字段（配套 {@code skill_exec} 工具新增）</b>：仅是<b>声明式标记</b>，
 * 与 {@code local_tools} 的"禁止登记可执行代码"纪律不冲突 —— 本表存的仍是<b>数据</b>
 * （随 {@code skill_version_id} 不可变快照），{@code skill_exec} 只是"读取这份数据并跑一次
 * 平台白名单解释器"，代码来源始终是平台/租户预置的 Skill 版本快照，<b>绝不接受模型/用户
 * 运行时传入的任意代码</b>。仅当 {@code executable=1} 且 {@code contentType} 落在
 * {@code SkillExecHandler} 的语言白名单内时才可被执行；其余情况 {@code skill_exec} 一律拒绝。
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "skill_resources")
public class SkillResource extends BaseTenantEntity {

    @Column(name = "skill_version_id", nullable = false)
    private Long skillVersionId;

    @Column(name = "resource_path", nullable = false, length = 255)
    private String resourcePath;

    @Column(name = "content_type", length = 32)
    private String contentType = "markdown";

    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "resource_order", nullable = false)
    private Integer resourceOrder = 0;

    /** 0 = 不可执行（默认，绝大多数资源仅是参考素材）；1 = 可被 {@code skill_exec} 执行。 */
    @Column(name = "executable", nullable = false, columnDefinition = "tinyint")
    private Integer executable = 0;

    public boolean isExecutable() {
        return Integer.valueOf(1).equals(executable);
    }
}
