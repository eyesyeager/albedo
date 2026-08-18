package com.eyes.albedo.tool;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code tool_calls} 状态流转落库（🔴 <b>ADR-010 的三段式事务纪律在此落地</b>）。
 *
 * <p><b>🔴 事务纪律（本类是最容易踩雷的地方，务必读完）</b>：
 * <pre>
 * 工具执行必须切成三段，🔴 中间那段 **必须在事务外**：
 *   ① 短事务：markRunning(...) 提交 status=running
 *   ② 🔴 事务外：执行工具（LocalToolHandler / McpClient 网络调用）
 *   ③ 短事务：markSucceeded/markFailed(...) 写终态 + 审计（同事务）
 *
 * 为什么不能把 ② 包进事务（ADR-010 的四条硬理由）：
 *   1. 一次生成可达 300s，长事务会迅速耗尽远程 MySQL 的连接池；
 *   2. 🔴 **致命自锁**：confirm 接口要 SELECT … FOR UPDATE 同一行，
 *      若生成线程在等待/执行期间持有该行锁，confirm 会锁等待超时，
 *      而生成线程又在等 confirm —— 双方互等，高风险确认功能直接死锁；
 *   3. SSE 分片已发出，事务回滚无法"撤回"用户已看到的内容；
 *   4. 审计必须不可篡改，放进可能回滚的长事务与"只写不改"冲突。
 *
 * 🔴 因此本类的每个方法都是 REQUIRES_NEW 的**独立短事务**，
 *    且方法体内 **禁止出现** McpClient / HttpClient / SseWriter / LocalToolHandler 调用。
 *    该约束由 TransactionDisciplineScanTest 静态扫描守护。
 * </pre>
 *
 * <p>🔴 <b>审计与状态流转同事务</b>（api-spec §7.14）：流式内的安全事件
 * （授权被拒 / SSRF 拒绝 / 确认允许拒绝超时）必须与 {@code tool_calls} 状态在<b>同一短事务</b>提交；
 * 审计失败 → 该次工具调用失败（{@code errorCode=50003}），但<b>绝不中断 SSE 流</b>。
 */
@Slf4j
@Service
public class ToolCallRecorder {

    private final ToolCallRepository toolCallRepository;
    private final AuditService auditService;

    public ToolCallRecorder(ToolCallRepository toolCallRepository, AuditService auditService) {
        this.toolCallRepository = toolCallRepository;
        this.auditService = auditService;
    }

    /**
     * 落库 {@code status=pending}（模型请求调用，待校验）。
     *
     * <p>🔴 幂等：同一 {@code (message_id, provider_call_id)} 重复投递<b>不重复建行</b>
     * （走 {@code uk_tenant_msg_call}）—— 上游重发与幂等回放都会命中。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall recordPending(Long conversationId, Long messageId, String providerCallId,
                                  int round, ToolDefinition definition) {
        Optional<ToolCall> existing = toolCallRepository
                .findByMessageIdAndProviderCallId(messageId, providerCallId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ToolCall call = new ToolCall();
        call.setConversationId(conversationId);
        call.setMessageId(messageId);
        call.setProviderCallId(providerCallId);
        call.setRound(round);
        call.setToolType(definition.toolType());
        call.setToolKey(definition.toolKey());
        call.setToolNameSnapshot(definition.toolName() == null ? "" : definition.toolName());
        call.setMcpId(definition.mcpId());
        call.setSchemaDigest(definition.schemaDigest() == null ? "" : definition.schemaDigest());
        call.setRiskLevel(definition.riskLevel());
        call.setRequiresConfirmation(definition.requiresConfirmation() ? 1 : 0);
        call.setStatus(ToolCall.STATUS_PENDING);
        return toolCallRepository.save(call);
    }

    /**
     * {@code pending → awaiting_confirmation}（需用户逐次确认）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall markAwaitingConfirmation(Long toolCallId, String argsSummary) {
        ToolCall call = lock(toolCallId);
        ToolStateMachine.requireTransition(call.getStatus(), ToolCall.STATUS_AWAITING_CONFIRMATION);
        call.setStatus(ToolCall.STATUS_AWAITING_CONFIRMATION);
        call.setRequiresConfirmation(1);
        call.setArgsSummary(argsSummary == null ? "" : argsSummary);
        return toolCallRepository.save(call);
    }

    /**
     * {@code → running}（① 段：提交后<b>立即返回</b>，工具执行在事务外进行）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall markRunning(Long toolCallId, String argsSummary) {
        ToolCall call = lock(toolCallId);
        ToolStateMachine.requireTransition(call.getStatus(), ToolCall.STATUS_RUNNING);
        call.setStatus(ToolCall.STATUS_RUNNING);
        call.setArgsSummary(argsSummary == null ? "" : argsSummary);
        call.setStartedAt(Instant.now());
        return toolCallRepository.save(call);
    }

    /**
     * {@code running → succeeded}（③ 段：写终态；含结果被截断的情形）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall markSucceeded(Long toolCallId, String resultSummary, boolean truncated) {
        ToolCall call = lock(toolCallId);
        ToolStateMachine.requireTransition(call.getStatus(), ToolCall.STATUS_SUCCEEDED);
        call.setStatus(ToolCall.STATUS_SUCCEEDED);
        call.setResultSummary(resultSummary == null ? "" : resultSummary);
        call.setTruncated(truncated ? 1 : 0);
        call.setErrorCode(null);
        finish(call);
        return toolCallRepository.save(call);
    }

    /**
     * {@code → failed / timed_out / denied / cancelled}（③ 段：写失败终态 + 可选审计）。
     *
     * <p>🔴 {@code auditEvent} 非空时与状态流转<b>同一事务</b>提交（api-spec §7.14）。
     *
     * @param status     目标终态
     * @param errorCode  🔴 必须是已登记数字码（{@code 30050}~{@code 30057} / {@code 50003}）
     * @param auditEvent 需与状态流转原子提交的审计事件（可为 null）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolCall markTerminal(Long toolCallId, String status, Integer errorCode,
                                 String resultSummary, AuditEvent auditEvent,
                                 AuditContext auditContext) {
        ToolCall call = lock(toolCallId);
        ToolStateMachine.requireTransition(call.getStatus(), status);
        call.setStatus(status);
        call.setErrorCode(errorCode);
        if (resultSummary != null) {
            call.setResultSummary(resultSummary);
        }
        finish(call);
        ToolCall saved = toolCallRepository.save(call);
        if (auditEvent != null) {
            // 🔴 ADR-010：审计必须与状态流转在**同一短事务**内提交。
            //    AuditService.record(...) 是 REQUIRED → 加入本方法的 REQUIRES_NEW 事务；
            //    🔴 绝不能用 recordInNewTransaction（那会开第二个事务，出现
            //    "状态回滚了但审计留下了"或反之的不一致）。
            //    显式传 auditContext 的原因：异步生成线程读不到 Servlet 请求，
            //    actorId 必须由调用方用快照 uid 填好（§9.5.2 第 3 条）。
            if (auditContext == null) {
                auditService.record(auditEvent);
            } else {
                auditService.record(auditEvent, auditContext);
            }
        }
        return saved;
    }

    /**
     * 流结束时的兜底收敛（architecture.md §9.5.4 不变量 3）。
     *
     * <p>🔴 <b>为什么必须有</b>：客户端断连 / 生成异常 / JVM 退出前，
     * 处于 {@code pending} / {@code awaiting_confirmation} / {@code running} 的行会永久悬挂，
     * 使 {@code GET /conversations/{id}/tool-calls} 出现"永远在执行中"的僵尸卡片。
     * 由 {@code ChatStreamRunner} 的 {@code finally} 调用（M3 第三阶段接线）。
     *
     * @return 被收敛的行数
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int cancelPendingByMessage(Long messageId) {
        List<ToolCall> calls = toolCallRepository.findByMessageId(messageId);
        int cancelled = 0;
        for (ToolCall call : calls) {
            if (ToolStateMachine.terminal(call.getStatus())) {
                continue;
            }
            call.setStatus(ToolCall.STATUS_CANCELLED);
            finish(call);
            toolCallRepository.save(call);
            cancelled++;
        }
        if (cancelled > 0) {
            log.info("流结束时收敛非终态工具调用：messageId={} cancelled={}", messageId, cancelled);
        }
        return cancelled;
    }

    /**
     * 行锁读取（🔴 状态机的唯一裁决点）。
     */
    private ToolCall lock(Long toolCallId) {
        return toolCallRepository.findByIdForUpdate(toolCallId)
                .orElseThrow(BusinessException::notFound);
    }

    private void finish(ToolCall call) {
        Instant now = Instant.now();
        call.setFinishedAt(now);
        if (call.getStartedAt() != null) {
            call.setDurationMs((int) Math.max(0,
                    now.toEpochMilli() - call.getStartedAt().toEpochMilli()));
        }
    }
}
