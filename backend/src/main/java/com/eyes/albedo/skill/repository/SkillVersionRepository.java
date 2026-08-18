package com.eyes.albedo.skill.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.skill.entity.SkillVersion;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Skill 版本仓储（{@code scope=tenant}）。
 *
 * <p>运行时消费口径（api-spec §7.5.2）：只读取<b>会话绑定 agentVersion 所引用的那个版本</b>，
 * 🔴 不得"取最新版"，否则新增 Skill 版本会改变旧会话行为（违反 AC-SKL-002）。
 *
 * <p>🔴 一期<b>不缓存</b>：Skill 版本快照直读 MySQL（api-spec §7.1.2 末尾），
 * 以保证 DBA 改库后立即生效；缓存键已在 architecture.md §12.2 预留但一期禁用。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：主键直载路径不会被追加 {@code tenant_id}
 * 条件（实测结论），会读到其他租户的行；请用 {@link #findOneById(Long)}。
 */
public interface SkillVersionRepository extends JpaRepository<SkillVersion, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的 Skill 版本（🔴 替代 {@code findById}）。
     */
    Optional<SkillVersion> findOneById(Long id);

    /** 精确版本引用（走 {@code uk_tenant_skill_version}）。 */
    Optional<SkillVersion> findBySkillIdAndVersion(Long skillId, Integer version);

    /** 某 Skill 指定状态的版本（走 {@code idx_tenant_skill_status}）。 */
    List<SkillVersion> findBySkillIdAndStatus(Long skillId, String status);

    /** 某 Skill 的全部版本，版本号倒序。 */
    List<SkillVersion> findBySkillIdOrderByVersionDesc(Long skillId);

    /**
     * 🔴 <b>绑定链递归校验专用</b>：一次取回「{@code Skill} 主体 + 被绑定的精确版本」
     * （api-spec §7.3.1 G10「三类被引用对象各一次 {@code IN} 批量查，≤4 次查询」）。
     *
     * <p>🔴 <b>为什么必须是 JOIN 而不是两次查询</b>：G10 把递归校验的查询预算钉在 4 次
     * （绑定表 1 次 + 三类被引用对象各 1 次）。Skill 侧需要<b>两张表</b>
     * （{@code skills} 判归属与 key，{@code skill_versions} 判状态与正文），
     * 拆成两次查询就会超预算；用 {@code left join … on} 一次取回两个实体即可满足。
     * 🔴 用 {@code left join}（而非 inner）：精确版本<b>不存在</b>本身就是必须报出的
     * {@code refNotFound} 违规，inner join 会让这条记录直接消失（表现为"少校验了一个绑定"）。
     *
     * <p>🔴 两张表均为租户表，Hibernate discriminator 会自动追加 {@code tenant_id}，
     * 因此跨租户 {@code ref_id} 表现为<b>查不到</b> → 按 {@code crossTenantReference} 报出。
     *
     * @param skillIds 绑定的 {@code ref_id} 集合（{@code skills.id}）
     * @param versions 绑定的 {@code ref_version} 集合（🔴 精确版本，禁止"最新"语义）
     * @return 每行 {@code [Skill, SkillVersion|null]}；调用方按 {@code (skillId, version)} 配对过滤
     */
    @org.springframework.data.jpa.repository.Query(
            "select s, sv from Skill s left join SkillVersion sv"
                    + " on sv.skillId = s.id and sv.version in :versions"
                    + " where s.id in :skillIds")
    List<Object[]> findBoundSkillVersions(
            @org.springframework.data.repository.query.Param("skillIds") List<Long> skillIds,
            @org.springframework.data.repository.query.Param("versions") List<Integer> versions);
}
