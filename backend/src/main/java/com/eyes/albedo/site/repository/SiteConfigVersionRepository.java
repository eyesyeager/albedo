package com.eyes.albedo.site.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.site.entity.SiteConfigVersion;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 站点配置版本仓储（tenant scope，Hibernate 自动追加 {@code tenant_id}）。
 *
 * <p>🔴 全部使用 JPQL / 派生查询，禁止 {@code nativeQuery}（原生 SQL 不会被追加租户条件）。
 */
public interface SiteConfigVersionRepository extends JpaRepository<SiteConfigVersion, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的配置版本。
     *
     * <p>🔴 不要用 {@code findById}：主键直载路径不会被 Hibernate 追加 {@code tenant_id} 条件
     * （实测结论），会读到其他租户的站点配置内容。
     */
    Optional<SiteConfigVersion> findOneById(Long id);

    Optional<SiteConfigVersion> findByVersion(Long version);

    Optional<SiteConfigVersion> findByVersionAndStatus(Long version, String status);

    List<SiteConfigVersion> findByStatus(String status);

    Page<SiteConfigVersion> findAllByOrderByVersionDesc(Pageable pageable);

    /**
     * 当前租户内的最大版本号；无记录返回 {@code null}。
     */
    @Query("SELECT MAX(v.version) FROM SiteConfigVersion v")
    Long findMaxVersion();

    /**
     * 最新草稿（同一租户同时最多保留一份工作草稿，取最大版本号那条）。
     */
    @Query("SELECT v FROM SiteConfigVersion v WHERE v.status = :status ORDER BY v.version DESC")
    List<SiteConfigVersion> findByStatusOrderByVersionDesc(@Param("status") String status, Pageable pageable);
}
