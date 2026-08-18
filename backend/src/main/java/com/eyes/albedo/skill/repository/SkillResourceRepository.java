package com.eyes.albedo.skill.repository;

import com.eyes.albedo.skill.entity.SkillResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Skill 资源文件仓储。
 *
 * <p>🔴 租户隔离由 {@code BaseTenantEntity} + Hibernate discriminator 自动追加
 * {@code tenant_id = ?}，禁止在此使用原生 SQL（{@code nativeQuery=true}）。
 *
 * <p>🔴 性能红线（§14.2）：装配 Skill 注入片段只做一次<b>批量</b>查询
 * （{@link #findBySkillVersionIdInOrderBySkillVersionIdAscResourceOrderAsc}），
 * 禁止按单个版本逐条查询（N+1）。
 *
 * <p>{@link #findBySkillVersionIdAndResourcePath} 是 {@code skill_exec} 单次按需取脚本
 * 用（模型显式指定 {@code skillKey+resourcePath}，天然是单行点查，不构成 N+1）。
 */
@Repository
public interface SkillResourceRepository extends JpaRepository<SkillResource, Long> {

    List<SkillResource> findBySkillVersionId(Long skillVersionId);

    List<SkillResource> findBySkillVersionIdInOrderBySkillVersionIdAscResourceOrderAsc(List<Long> versionIds);

    Optional<SkillResource> findBySkillVersionIdAndResourcePath(Long skillVersionId, String resourcePath);
}
