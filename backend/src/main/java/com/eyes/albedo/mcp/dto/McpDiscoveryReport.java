package com.eyes.albedo.mcp.dto;

import java.util.List;

/**
 * 工具发现结果（api-spec §7.4.3 的 {@code data}）。
 *
 * @param mcpId              MCP ID（对外 string，ADR-004）
 * @param discoveredAt       发现时间（ISO-8601 UTC）
 * @param newCount           新增工具数（🔴 一律 {@code granted=false} + {@code disabled}）
 * @param unchangedCount     未变化工具数
 * @param schemaChangedCount Schema 变化工具数（🔴 已授权者自动降级）
 * @param removedCount       上游已移除工具数（保留历史行并置 {@code disabled}）
 * @param tools              逐工具比对结果
 */
public record McpDiscoveryReport(String mcpId,
                                 String discoveredAt,
                                 int newCount,
                                 int unchangedCount,
                                 int schemaChangedCount,
                                 int removedCount,
                                 List<DiscoveredTool> tools) {

    /**
     * 单个工具的比对结果。
     *
     * <p>🔴 {@code inputSchemaDigest} 只回摘要，<b>不回</b>完整 Schema
     * （Schema 可能含上游内部字段命名，属实现细节）。
     *
     * @param toolKey           {@code {mcpKey}:{name}}
     * @param name              上游原名
     * @param description       说明
     * @param inputSchemaDigest {@code sha256:} 前缀 + 16 hex
     * @param granted           是否已授权
     * @param status            enabled / disabled
     * @param changeType        new / unchanged / schema_changed / removed
     */
    public record DiscoveredTool(String toolKey,
                                 String name,
                                 String description,
                                 String inputSchemaDigest,
                                 boolean granted,
                                 String status,
                                 String changeType) {
    }
}
