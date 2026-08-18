package com.eyes.albedo.mcp.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 上游 {@code tools/list} 返回的单个工具定义（api-spec §7.6.2）。
 *
 * <p>{@code toolKey} 不在此计算：它需要 {@code mcpKey}（{@code {mcpKey}:{name}}），
 * 属于本地拼装，由 {@code McpDiscoveryService} 负责 —— 保持本记录是"上游原样数据"。
 *
 * @param name        上游原名
 * @param description 说明（可空）
 * @param inputSchema JSON Schema draft 2020-12（可空；上游不给时按空对象处理）
 */
public record McpToolDescriptor(String name, String description, JsonNode inputSchema) {

    public McpToolDescriptor {
        description = description == null ? "" : description;
    }

    public boolean hasSchema() {
        return inputSchema != null && !inputSchema.isNull();
    }
}
