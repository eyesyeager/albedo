package com.eyes.albedo.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.mcp.dto.McpDiscoveryReport;
import com.eyes.albedo.mcp.dto.McpToolDescriptor;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.entity.McpTool;
import com.eyes.albedo.mcp.repository.McpToolRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发现结果的比对与落库（🔴 <b>独立 Bean 的意义在于让 {@code @Transactional} 真正生效</b>）。
 *
 * <p>⚠️ 若把本类的 {@link #persist} 方法直接写在 {@link McpDiscoveryService} 里，
 * 由 {@code discover()} 内部自调用 —— Spring 的代理不会介入自调用，
 * {@code @Transactional} <b>静默失效</b>，"降级审计与授权变更同事务"这条硬约束
 * （ADR-010 / EX-024）会在没有任何报错的情况下被破坏。这是本类拆分的唯一理由。
 *
 * <p>🔴 <b>事务边界</b>：本类方法是<b>短事务</b>，内部<b>不得</b>出现任何网络调用
 * （SSRF 解析与 {@code tools/list} 都在 {@link McpDiscoveryService} 的事务外完成）。
 */
@Slf4j
@Service
public class McpDiscoveryWriter {

    /** 上游 description 的落库列宽（{@code mcp_tools.description VARCHAR(500)}）。 */
    private static final int DESCRIPTION_MAX = 500;

    private final McpToolRepository mcpToolRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public McpDiscoveryWriter(McpToolRepository mcpToolRepository,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.mcpToolRepository = mcpToolRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 比对上游工具与 {@code mcp_tools} 现有行并落库。
     *
     * <p>🔴 规则见 {@link McpDiscoveryService} 类注释（新工具默认禁用 / schema_changed 降级 /
     * removed 保留历史行 / dryRun 不落库）。
     *
     * @param dryRun {@code true} 时只比对不落库、不写审计
     */
    @Transactional
    public McpDiscoveryReport persist(McpServer server, List<McpToolDescriptor> upstream,
                                     boolean dryRun) {
        Instant now = Instant.now();
        Map<String, McpTool> existing = new LinkedHashMap<>();
        for (McpTool tool : mcpToolRepository.findByMcpId(server.getId())) {
            existing.put(tool.getToolKey(), tool);
        }

        List<McpDiscoveryReport.DiscoveredTool> reported = new ArrayList<>();
        List<McpTool> toSave = new ArrayList<>();
        int newCount = 0;
        int unchangedCount = 0;
        int schemaChangedCount = 0;

        for (McpToolDescriptor descriptor : upstream) {
            String toolKey = server.getMcpKey() + ":" + descriptor.name();
            String digest = McpSchemaDigest.of(descriptor.inputSchema());
            McpTool row = existing.remove(toolKey);

            if (row == null) {
                toSave.add(createRow(server, descriptor, toolKey, digest, now));
                newCount++;
                reported.add(report(toolKey, descriptor, digest, toSave.get(toSave.size() - 1)));
                continue;
            }

            String previousDigest = row.getInputSchemaDigest();
            boolean schemaChanged = !digest.equals(previousDigest);
            row.setToolName(descriptor.name());
            row.setDescription(truncateDescription(descriptor.description()));
            row.setInputSchema(schemaJson(descriptor));
            row.setInputSchemaDigest(digest);
            row.setDiscoveredAt(now);
            row.setRemovedAt(null);

            if (schemaChanged) {
                row.setChangeType(McpTool.CHANGE_SCHEMA_CHANGED);
                schemaChangedCount++;
                if (row.grantedAndEnabled()) {
                    // 🔴 自动降级：封堵"先以无害 Schema 拿授权，再偷换参数"的提权路径
                    row.setGranted(0);
                    row.setStatus(McpTool.STATUS_DISABLED);
                    if (!dryRun) {
                        writeGrantRevokedAudit(server, row, AuditActions.REASON_SCHEMA_CHANGED,
                                previousDigest, digest);
                    }
                }
            } else {
                row.setChangeType(McpTool.CHANGE_UNCHANGED);
                unchangedCount++;
            }
            toSave.add(row);
            reported.add(report(toolKey, descriptor, digest, row));
        }

        // 剩余 existing = 上游已移除：🔴 保留历史行并置 disabled，不物理删除
        int removedCount = 0;
        for (McpTool removed : existing.values()) {
            // 🔴 先取原授权状态：置 0 之后就无法判断"原本是否已授权"了
            boolean wasGranted = removed.grantedAndEnabled();
            String previousDigest = removed.getInputSchemaDigest();
            removed.setChangeType(McpTool.CHANGE_REMOVED);
            removed.setStatus(McpTool.STATUS_DISABLED);
            removed.setGranted(0);
            removed.setRemovedAt(now);
            if (wasGranted && !dryRun) {
                // 🔴 G3：原本已授权的工具被上游移除 → 同样是"系统撤销授权"，reason=toolRemoved
                writeGrantRevokedAudit(server, removed, AuditActions.REASON_TOOL_REMOVED,
                        previousDigest, AuditActions.DIGEST_REMOVED);
            }
            toSave.add(removed);
            removedCount++;
            reported.add(new McpDiscoveryReport.DiscoveredTool(removed.getToolKey(),
                    removed.getToolName(), removed.getDescription(),
                    McpDiscoveryService.DIGEST_PREFIX + removed.getInputSchemaDigest(),
                    false, McpTool.STATUS_DISABLED,
                    McpTool.CHANGE_REMOVED));
        }

        if (!dryRun) {
            mcpToolRepository.saveAll(toSave);
        }
        return new McpDiscoveryReport(String.valueOf(server.getId()), TimeFormat.iso(now),
                newCount, unchangedCount, schemaChangedCount, removedCount, reported);
    }

    private McpTool createRow(McpServer server, McpToolDescriptor descriptor, String toolKey,
                              String digest, Instant now) {
        McpTool created = new McpTool();
        created.setMcpId(server.getId());
        created.setToolName(descriptor.name());
        created.setToolKey(toolKey);
        created.setDescription(truncateDescription(descriptor.description()));
        created.setInputSchema(schemaJson(descriptor));
        created.setInputSchemaDigest(digest);
        // 🔴 新发现工具默认禁用（AC-MCP-005 的核心断言）
        created.setGranted(0);
        created.setStatus(McpTool.STATUS_DISABLED);
        created.setChangeType(McpTool.CHANGE_NEW);
        created.setDiscoveredAt(now);
        return created;
    }

    /**
     * 系统发起的授权撤销审计（api-spec §7.4.3 / §7.14，🔴 G3 已裁决用独立 action）。
     *
     * <p>🔴 {@code action=mcp.tool_grant_revoked}、{@code actorType=system}、
     * {@code objectType=mcpTool}、{@code result=success}（撤销动作本身执行成功），
     * {@code reason} ∈ {@code schemaChanged} | {@code toolRemoved}。
     * <b>不再复用 {@code tool.grant_denied}</b>：后者语义是"模型请求调用一个不可用工具"，
     * 复用会让真实越权事件被系统例行降级淹没（对照表见 {@code AuditActions}）。
     *
     * <p>🔴 {@code beforeDigest}/{@code afterDigest} 只放 Schema 摘要，不放 Schema 正文；
     * {@code removed} 场景 after 记 {@code "removed"}。
     * 🔴 与 {@code discover} <b>同事务</b>：审计失败 → 整批回滚 + {@code 50003}（EX-024）。
     */
    private void writeGrantRevokedAudit(McpServer server, McpTool row, String reason,
                                        String beforeDigest, String afterDigest) {
        AuditContext systemContext = AuditContext.system(auditService.currentContext().requestId());
        auditService.record(AuditEvent.tenant(server.getTenantId(),
                                AuditActions.MCP_TOOL_GRANT_REVOKED, AuditResults.SUCCESS,
                                "mcpTool", String.valueOf(row.getId()), reason, null)
                        .withDigests(beforeDigest == null ? "" : beforeDigest, afterDigest),
                systemContext);
    }

    private McpDiscoveryReport.DiscoveredTool report(String toolKey, McpToolDescriptor descriptor,
                                                     String digest, McpTool row) {
        return new McpDiscoveryReport.DiscoveredTool(toolKey, descriptor.name(),
                row.getDescription(), McpDiscoveryService.DIGEST_PREFIX + digest,
                Integer.valueOf(1).equals(row.getGranted()),
                row.getStatus(), row.getChangeType());
    }

    private String truncateDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.length() <= DESCRIPTION_MAX
                ? description : description.substring(0, DESCRIPTION_MAX);
    }

    private String schemaJson(McpToolDescriptor descriptor) {
        if (!descriptor.hasSchema()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(descriptor.inputSchema());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "入参 Schema 必须是合法 JSON");
        }
    }
}
