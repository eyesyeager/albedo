package com.eyes.albedo.mcp;

import java.time.Instant;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.repository.McpServerRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 连接测试结果落库 + 强制审计（🔴 独立 Bean 以保证 {@code @Transactional} 真正生效）。
 *
 * <p>⚠️ 与 {@link McpDiscoveryWriter} 同一理由：若把本方法写在 {@link McpConnectionTester} 内部
 * 由其自调用，Spring 代理不介入，{@code @Transactional} 会<b>静默失效</b>，
 * "审计与业务同事务"（ADR-010 / EX-024）在没有任何报错的情况下被破坏。
 *
 * <p>🔴 <b>短事务纪律</b>：本类内<b>不得</b>出现任何网络调用（DNS / HTTP 都在事务外完成）。
 */
@Service
public class McpConnectionTestWriter {

    private final McpServerRepository mcpServerRepository;
    private final AuditService auditService;

    public McpConnectionTestWriter(McpServerRepository mcpServerRepository,
                                   AuditService auditService) {
        this.mcpServerRepository = mcpServerRepository;
        this.auditService = auditService;
    }

    /**
     * 更新 {@code mcp_servers.last_check_*} 并写 {@code mcp.connection_test} 审计。
     *
     * <p>🔴 <b>同一事务</b>：审计写入失败 → 整体回滚 → 对外 {@code 50003}
     * （EX-024「不得执行后伪报未审计」）。
     *
     * @return {@code auditEventId}（32 位小写 hex，🔴 原样返回、禁止截断）
     */
    @Transactional
    public String persistOutcome(McpServer server, McpCheckResult outcome, int toolCount) {
        server.setLastCheckStatus(outcome.checkStatus());
        server.setLastCheckResult(outcome.literal());
        server.setLastCheckedAt(Instant.now());
        mcpServerRepository.save(server);

        // 🔴 reason 只放分类字面量与工具数：不含 endpoint / IP / 凭据 / 上游正文
        return auditService.record(AuditEvent.tenant(server.getTenantId(),
                AuditActions.MCP_CONNECTION_TEST,
                outcome.healthy() ? AuditResults.SUCCESS : AuditResults.FAILED,
                "mcpServer", String.valueOf(server.getId()),
                "result=" + outcome.literal() + ", toolCount=" + toolCount,
                outcome == McpCheckResult.SSRF_REJECTED ? ErrorCode.TOOL_DENIED : null));
    }
}
