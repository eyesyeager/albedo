package com.eyes.albedo.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.entity.McpTool;
import com.eyes.albedo.mcp.repository.McpToolRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 工具逐项授权（api-spec §7.4.4，REQ-MCP-002 / AC-MCP-002 / AC-MCP-005）。
 *
 * <p>🔴 <b>一期是"数据契约"，没有 HTTP 接口</b>：授权由 DBA/开发人员直接写库（DEC-010），
 * {@code PUT …/grant} 与授权 UI 均 Deferred 至二期（api-spec §6 / §7.4.4）。
 * 因此本类<b>刻意不挂 Controller</b> —— 提供它是为了：
 * <ol>
 *   <li>让"授权/取消授权"这件事有<b>唯一实现</b>（含审计），避免二期各写一遍；</li>
 *   <li>让发现流程的<b>自动降级</b>与人工授权走同一状态语义；</li>
 *   <li>让集成测试可以用"服务级 API"而不是散落的 SQL 去构造授权态。</li>
 * </ol>
 * ⚠️ 若哪天出现了 {@code /admin/mcp/{id}/tools/{toolKey}/grant} 接口，
 * 🔴 必须<b>先</b>回写 api-spec §7.4.4 再实现（当前契约明确写着"无 HTTP 接口"）。
 *
 * <p>🔴 <b>进入模型可调用清单的条件（缺一不可）</b>：
 * {@code mcp_tools.granted=1 AND status='enabled'} AND 所属
 * {@code mcp_servers.status='enabled'} AND 被会话 {@code agentVersion} 绑定
 * —— 前两条在本类与 {@link #listGrantedToolKeys} 落实，第三条属 {@code tool} 包。
 */
@Slf4j
@Service
public class McpToolGrantService {

    private final McpToolRepository mcpToolRepository;
    private final AuditService auditService;

    public McpToolGrantService(McpToolRepository mcpToolRepository, AuditService auditService) {
        this.mcpToolRepository = mcpToolRepository;
        this.auditService = auditService;
    }

    /**
     * 当前租户已授权且启用的 MCP 工具键（🔴 一次查询取全，走 {@code idx_tenant_granted}）。
     *
     * <p>🔴 <b>不缓存</b>（api-spec §7.1.2 末尾）：缓存会破坏 AC-MCP-004
     * 「改库为取消授权后运行时仍须拒绝」。
     */
    @Transactional(readOnly = true)
    public List<McpTool> listGrantedTools() {
        return mcpToolRepository.findByGrantedAndStatus(1, McpTool.STATUS_ENABLED);
    }

    /**
     * 某 MCP 服务下已授权且启用的工具键（供 api-spec §7.4.1 的 {@code allowedToolKeys}）。
     */
    @Transactional(readOnly = true)
    public List<String> listGrantedToolKeys(Long mcpId) {
        return mcpToolRepository.findByMcpIdAndStatus(mcpId, McpTool.STATUS_ENABLED).stream()
                .filter(McpTool::grantedAndEnabled)
                .map(McpTool::getToolKey)
                .sorted()
                .toList();
    }

    /**
     * 授权单个工具（🔴 短事务：授权变更 + 审计同事务）。
     *
     * <p>⚠️ 契约缺口（已回报 @架构师）：api-spec §7.14 的 action 枚举没有"授权被授予"这一项。
     * 因为一期没有授权 HTTP 接口，该事件在一期不会由用户触发，故本方法<b>不写审计</b>
     * （🔴 不自造 action）；二期开放授权接口时必须先补 action 再补审计。
     *
     * @throws BusinessException 10004 工具不存在或跨租户（不泄露存在性）
     */
    @Transactional
    public McpTool grant(String toolKey, Long operatorUid) {
        McpTool tool = require(toolKey);
        tool.setGranted(1);
        tool.setStatus(McpTool.STATUS_ENABLED);
        tool.setGrantedBy(operatorUid);
        tool.setGrantedAt(Instant.now());
        return mcpToolRepository.save(tool);
    }

    /**
     * 取消授权（🔴 短事务：授权变更 + 审计同事务）。
     *
     * <p>写审计 {@code tool.grant_denied}（reason 标注人工撤销），与 schema_changed
     * 自动降级共用该 action —— 理由同 {@link McpDiscoveryWriter}（契约暂无专用 action）。
     *
     * @throws BusinessException 10004 工具不存在或跨租户
     */
    @Transactional
    public McpTool revoke(String toolKey, String reason) {
        McpTool tool = require(toolKey);
        tool.setGranted(0);
        tool.setStatus(McpTool.STATUS_DISABLED);
        auditService.record(AuditEvent.tenant(tool.getTenantId(),
                AuditActions.TOOL_GRANT_DENIED, AuditResults.DENIED,
                "mcpTool", String.valueOf(tool.getId()),
                reason == null || reason.isBlank() ? "人工撤销授权" : reason,
                ErrorCode.TOOL_DENIED));
        return mcpToolRepository.save(tool);
    }

    private McpTool require(String toolKey) {
        Optional<McpTool> tool = mcpToolRepository.findByToolKey(toolKey);
        return tool.orElseThrow(BusinessException::notFound);
    }
}
