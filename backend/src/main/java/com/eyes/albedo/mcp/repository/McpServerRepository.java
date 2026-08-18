package com.eyes.albedo.mcp.repository;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.mcp.entity.McpServer;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * MCP 服务仓储（{@code scope=tenant}）。
 *
 * <p>🔴 清单构造只取 {@code status='enabled'}（走 {@code idx_tenant_status}）。
 * 🔴 禁止缓存查询结果（architecture.md §12.1.1）：endpoint 与凭据密文连 L2 都禁入。
 *
 * <p>🔴 <b>禁止用 {@code findById} 做租户内查找</b>：主键直载路径不会被追加 {@code tenant_id}
 * 条件（实测结论），会读到其他租户的 MCP 配置（含 endpoint 与凭据密文）；
 * 请用 {@link #findOneById(Long)}。
 */
public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    /**
     * 按 ID 查找<b>当前租户</b>的 MCP 配置（🔴 替代 {@code findById}）。
     */
    Optional<McpServer> findOneById(Long id);

    Optional<McpServer> findByMcpKey(String mcpKey);

    List<McpServer> findByStatus(String status);
}
