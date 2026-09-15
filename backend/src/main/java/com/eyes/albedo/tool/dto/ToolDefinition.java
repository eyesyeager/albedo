package com.eyes.albedo.tool.dto;

import com.eyes.albedo.tool.ToolFunctionNames;

/**
 * 模型可见的工具定义（{@code ToolCatalogService} 的输出）。
 *
 * <p>🔴 <b>只有进入本清单的工具才对模型可见</b>（清单级隔离是第一道防线，
 * 运行时二次鉴权是第二道兜底，api-spec §7.4.4 末尾）。
 *
 * <p>🔴 本记录<b>不含</b> MCP {@code endpoint} 与凭据：
 * {@code tool} 包只看到"结果 / 错误码"，看不到传输细节（architecture.md §5.1.3）。
 *
 * <p><b>🔴 {@code toolKey} 与 {@code functionName} 是两个不同的标识符</b>（api-spec §7.6.5）：
 * <pre>
 * toolKey      = 契约标识符。🔴 SSE tool.toolKey、tool_calls.tool_key、§7.9.1 查询、
 *                审计 object_id **一律记它**
 * functionName = **内部**标识符，只用于下发给上游模型的 tools[].function.name。
 *                🔴 严禁泄漏到任何对外字段（否则前端与 @测试 会出现两套工具标识）
 * </pre>
 *
 * @param toolType             {@code local} / {@code mcp}
 * @param toolKey              工具标识（租户内唯一；MCP 为 {@code {mcpKey}:{toolName}}）
 * @param functionName         🔴 归一化后的模型函数名（{@link ToolFunctionNames#normalize}）；
 *                             长度与碰撞由 {@code ToolCatalogService} 在<b>全清单范围</b>判定
 * @param toolName             上游/注册原名（MCP 调用时用它，不是 toolKey）
 * @param description          说明（会下发给模型）
 * @param inputSchema          JSON Schema draft 2020-12
 * @param schemaDigest         Schema 摘要（落 {@code tool_calls.schema_digest} 供追溯）
 * @param idempotent           是否幂等（🔴 非幂等 + 结果未知 → {@code 30056} 禁止自动重试）
 * @param timeoutSeconds       本工具的执行超时
 * @param mcpId                MCP 工具所属服务 ID（本地 Tool 为 null）
 * @param grantConfigJson      {@code tenant_tool_grants.config}（本地 Tool 专用，可为空）
 */
public record ToolDefinition(String toolType,
                             String toolKey,
                             String functionName,
                             String toolName,
                             String description,
                             String inputSchema,
                             String schemaDigest,
                             boolean idempotent,
                             int timeoutSeconds,
                             Long mcpId,
                             String grantConfigJson) {

    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_MCP = "mcp";

    /**
     * 工厂：{@code functionName} 由 {@code toolKey} <b>归一化派生</b>，
     * 🔴 调用方不得自行传入（防止两处口径分叉）。
     */
    public static ToolDefinition of(String toolType, String toolKey, String toolName,
                                    String description, String inputSchema, String schemaDigest,
                                    boolean idempotent, int timeoutSeconds, Long mcpId,
                                    String grantConfigJson) {
        return new ToolDefinition(toolType, toolKey, ToolFunctionNames.normalize(toolKey), toolName,
                description, inputSchema, schemaDigest, idempotent,
                timeoutSeconds, mcpId, grantConfigJson);
    }

    public boolean local() {
        return TYPE_LOCAL.equals(toolType);
    }

    public boolean mcp() {
        return TYPE_MCP.equals(toolType);
    }
}
