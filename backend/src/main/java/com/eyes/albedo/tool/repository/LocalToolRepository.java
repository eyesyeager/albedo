package com.eyes.albedo.tool.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.tool.entity.LocalTool;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 平台本地 Tool 注册表仓储（🔴 {@code scope=platform}，无 Hibernate 租户条件）。
 *
 * <p>🔴 因为是平台表，本仓储的任何查询都<b>不受 discriminator 保护</b>：
 * 结果对所有租户可见是<b>有意</b>的（平台注册表租户只读），
 * 但"某租户能否使用"必须再经 {@code tenant_tool_grants} 判定（四条件之一）。
 */
public interface LocalToolRepository extends JpaRepository<LocalTool, Long> {

    Optional<LocalTool> findByToolKey(String toolKey);

    /** 清单构造过滤（走 {@code idx_status}）。 */
    List<LocalTool> findByStatus(String status);

    /** 批量取（授权表 → 注册元数据的 IN 批量查，避免 N+1）。 */
    List<LocalTool> findByToolKeyIn(List<String> toolKeys);
}
