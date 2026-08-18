package com.eyes.albedo.skill.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.skill.entity.Skill;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Skill 主体仓储（{@code scope=tenant}，Hibernate discriminator 自动追加 {@code tenant_id}）。
 *
 * <p>🔴 禁止原生 SQL：会静默丢失租户条件（{@code TenantIsolationScanTest} 静态扫描守护）。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>（实测结论，ConfigValidateIT 守护）：
 * {@code findById} 是<b>主键直载</b>（{@code EntityManager.find}），Hibernate 6 在该路径上
 * <b>不会</b>追加 {@code tenant_id} 条件，因此能读到<b>其他租户</b>的行 —— 直接违反 AC-TEN-004。
 * 租户内按 ID 查找必须走派生查询 {@link #findOneById(Long)}（JPQL 路径会被追加租户条件）。
 */
public interface SkillRepository extends JpaRepository<Skill, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的 Skill（🔴 替代 {@code findById}，见类注释）。
     */
    Optional<Skill> findOneById(Long id);

    Optional<Skill> findBySkillKey(String skillKey);

    /** 按状态列举（走 {@code idx_tenant_status}）。 */
    List<Skill> findByStatus(String status);
}
