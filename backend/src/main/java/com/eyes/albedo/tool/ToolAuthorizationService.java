package com.eyes.albedo.tool;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.dto.ToolDefinition;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 运行时工具授权二次校验（api-spec §7.4.4 末尾 / §7.6.3 第 2~3 步）。
 *
 * <p><b>REQ-TOL-002 / REQ-MCP-003 · AC-MCP-005 / AC-TOL-002 / AC-AUD-003</b>
 *
 * <p>🔴 <b>为什么已经有"清单级隔离"还要这一道</b>（api-spec §7.4.4 末尾原话：
 * "运行时校验是<b>第二道</b>兜底"）：
 * <ol>
 *   <li>模型输出按<b>不可信内容</b>处理 —— 它完全可能"幻觉"出一个没给它的工具名，
 *       或复述历史对话里出现过的工具名；</li>
 *   <li>清单在生成<b>开始时</b>构造，而 DBA 可能在生成<b>进行中</b>取消授权
 *       （AC-MCP-004 要求改库即生效）；</li>
 *   <li>🔴 Skill 指令正文可能提到某个工具 —— 但<b>指令文本永远不能提权</b>
 *       （api-spec §7.5.2 第 2 条）。</li>
 * </ol>
 *
 * <p>🔴 <b>拒绝的统一处置</b>：{@code 30050} + 审计 {@code tool.grant_denied}，
 * {@code tool_calls.status=denied}。审计走<b>独立短事务</b>（流式内，ADR-010）：
 * 🔴 审计失败只使该次工具调用失败，<b>绝不中断 SSE 流</b>。
 */
@Slf4j
@Service
public class ToolAuthorizationService {

    private final AuditService auditService;

    public ToolAuthorizationService(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 校验模型请求的工具是否在清单内。
     *
     * <p>🔴 顺序对齐 api-spec §7.6.3：租户上下文 → <b>绑定/授权</b> → SSRF → Schema →
     * 风险确认 → 轮次。本方法负责第 2~3 步。
     *
     * @param tenantId    租户号（显式传入：异步段禁止依赖 ThreadLocal）
     * @param catalog     本次生成构造出的清单
     * @param toolKey     模型请求的工具键
     * @param auditContext 审计上下文（异步段由生成线程显式抓取）
     * @throws BusinessException 30050 未授权 / 未绑定 / 已停用（🔴 已写审计）
     */
    public ToolDefinition requireAuthorized(String tenantId, List<ToolDefinition> catalog,
                                            String toolKey, AuditContext auditContext) {
        Optional<ToolDefinition> definition = catalog.stream()
                .filter(item -> item.toolKey().equals(toolKey))
                .findFirst();
        if (definition.isPresent()) {
            return definition.get();
        }
        writeGrantDenied(tenantId, toolKey, auditContext);
        // 🔴 消息不回显"该工具是否存在"（避免模型/用户借错误差异探测平台能力面）
        throw new BusinessException(ErrorCode.TOOL_DENIED,
                ErrorCode.defaultMessage(ErrorCode.TOOL_DENIED));
    }

    /**
     * 构造"工具授权被拒"审计事件（🔴 <b>只构造不写入</b>）。
     *
     * <p><b>为什么需要这个入口</b>（ADR-010）：api-spec §7.14 要求流式内的安全事件
     * 与 {@code tool_calls} 状态流转在<b>同一短事务</b>提交。因此编排层
     * （{@code ToolOrchestrator}）会把本事件<b>作为参数</b>交给
     * {@code ToolCallRecorder.markTerminal(...)}，由后者在同一事务里落库 ——
     * 而不是在这里先写审计、再由别处写状态（那会出现"审计有、状态无"的不一致）。
     *
     * <p>{@link #requireAuthorized} 仍保留"检查 + 立即写审计"的语义，供非编排调用方使用。
     */
    public AuditEvent grantDeniedEvent(String tenantId, String toolKey) {
        return AuditEvent.tenant(tenantId, AuditActions.TOOL_GRANT_DENIED,
                AuditResults.DENIED, "tool", toolKey,
                "工具未授权 / 未绑定 / 已停用", ErrorCode.TOOL_DENIED);
    }

    /**
     * 写"工具授权被拒"审计（🔴 独立短事务；失败不上抛，绝不中断 SSE 流）。
     *
     * @return 审计事件 ID；写入失败返回空串（调用方据此把 errorCode 记为 50003 亦可）
     */
    public String writeGrantDenied(String tenantId, String toolKey, AuditContext auditContext) {
        AuditEvent event = grantDeniedEvent(tenantId, toolKey);
        try {
            return auditContext == null
                    ? auditService.recordInNewTransaction(event)
                    : auditService.recordInNewTransaction(event, auditContext);
        } catch (RuntimeException e) {
            // 🔴 ADR-010：流式内审计失败 → 该次工具调用失败，但流必须继续收敛
            log.error("[SECURITY] 工具授权拒绝事件审计写入失败：toolKey={}", toolKey, e);
            return "";
        }
    }
}
