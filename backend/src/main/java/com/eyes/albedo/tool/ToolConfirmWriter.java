package com.eyes.albedo.tool;

import java.time.Instant;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认决定的<b>状态机裁决与落库</b>（api-spec §7.8.2 冲突矩阵，🔴 ADR-008 第 5/6 条）。
 *
 * <p>🔴 <b>为什么独立成一个 Bean</b>：确认接口必须"行锁内流转 + 审计同事务 → <b>提交后</b>才唤醒生成线程"。
 * 若把事务方法写在 {@code ToolConfirmService} 里由其自身调用，Spring 代理不介入自调用，
 * {@code @Transactional} 会<b>静默失效</b>（与 {@code McpDiscoveryWriter} 同一教训）。
 *
 * <p>🔴 <b>事务边界（ADR-010，最高危）</b>：
 * <ul>
 *   <li>本类是 {@code REQUIRES_NEW} 的<b>短事务</b>：行锁只在方法内持有，返回即释放</li>
 *   <li>🔴 方法体内<b>禁止</b>：网络调用、SSE 写出、{@code CompletableFuture} 唤醒、Redis 写入
 *       —— 唤醒必须发生在<b>事务提交之后</b>（否则生成线程可能读到未提交状态），
 *       由 {@code ToolConfirmService} 在本方法返回后执行</li>
 * </ul>
 *
 * <p>🔴 <b>幂等与冲突（api-spec V1.1.3 §7.8.2 矩阵逐行实现）</b>：
 * <table border="1">
 *   <caption>当前状态（🔴 <b>判定基准是 {@code decision} 列</b>）× 提交决定</caption>
 *   <tr><th>当前 status（errorCode / decision）</th><th>allow</th><th>deny</th></tr>
 *   <tr><td>{@code awaiting_confirmation}（{@code NULL}）</td><td>→ {@code running}</td>
 *       <td>→ {@code denied}(30050)</td></tr>
 *   <tr><td>{@code running}/{@code succeeded}/{@code failed}（{@code allow}）</td>
 *       <td>回放 {@code replayed=true}</td><td>🔴 {@code 30055}</td></tr>
 *   <tr><td>{@code timed_out}(30051/30056 执行超时，{@code allow})</td><td>回放</td>
 *       <td>🔴 {@code 30055}</td></tr>
 *   <tr><td>{@code denied}(30050，{@code deny} —— <b>用户拒绝</b>)</td><td>🔴 {@code 30055}</td>
 *       <td>回放</td></tr>
 *   <tr><td>🔴 {@code denied}(30050，{@code allow} —— <b>执行期竞态被拒</b>，§7.8.1 ③)</td>
 *       <td>回放（用户原决定即 allow，<b>不是</b>冲突）</td><td>🔴 {@code 30055}</td></tr>
 *   <tr><td>🔴 {@code denied}(30050，{@code NULL} —— 未授权/未绑定/SSRF，<b>从未要求确认</b>)</td>
 *       <td>{@code 10004}</td><td>{@code 10004}</td></tr>
 *   <tr><td>{@code timed_out}(30050 确认超时 = 已按拒绝收敛，{@code NULL})</td>
 *       <td>🔴 {@code 30055}</td><td>回放</td></tr>
 *   <tr><td>{@code cancelled}</td><td>回放</td><td>回放</td></tr>
 *   <tr><td>{@code pending}（尚未要求确认）</td><td>{@code 10004}</td><td>{@code 10004}</td></tr>
 * </table>
 *
 * <p><b>🔴 审计三分（api-spec V1.1.3 §7.8.2 ④⑤ / §7.14 不变量 6「审计只记新事实」）</b>：
 * <pre>
 * 首次决定（replayed=false） → tool.confirm_allowed / tool.confirm_denied，返回 32 位 eventId
 * 幂等回放（replayed=true）  → 🔴 **不写** audit_logs，auditEventId 恒为 null，仅记 WARN
 * 决定冲突（30055）          → tool.confirm_conflict，🔴 同一 toolCallId **至多一条**（行锁内点查去重）
 * </pre>
 */
@Slf4j
@Service
public class ToolConfirmWriter {

    /** 审计 {@code object_type}（api-spec §7.8.2 ⑤ / §7.14 固定字面量）。 */
    private static final String OBJECT_TYPE_TOOL_CALL = "toolCall";

    private final ToolCallRepository toolCallRepository;
    private final AuditService auditService;

    public ToolConfirmWriter(ToolCallRepository toolCallRepository, AuditService auditService) {
        this.toolCallRepository = toolCallRepository;
        this.auditService = auditService;
    }

    /**
     * 一次确认提交的结论。
     *
     * @param toolCallId   工具调用 ID
     * @param messageId    所属 assistant 消息 ID
     * @param decision     用户提交的决定
     * @param status       流转后的 {@code tool_calls.status}
     * @param decidedAt    决定时间（回放时为原决定时间）
     * @param replayed     是否为重复提交的回放（api-spec §7.8.2 {@code data.replayed}）
     * @param auditEventId 审计事件 ID（32 位 hex，🔴 原样返回禁止截断）
     */
    public record ConfirmOutcome(long toolCallId,
                                 long messageId,
                                 ToolConfirmDecision decision,
                                 String status,
                                 Instant decidedAt,
                                 boolean replayed,
                                 String auditEventId) {
    }

    /**
     * 行锁内裁决并落库（🔴 审计与状态流转<b>同一事务</b>，api-spec §7.14）。
     *
     * @throws BusinessException 10004 工具调用不存在 / 不属该消息 / 无待确认调用；
     *                           30055 与既有决定相反；50003 审计写入失败（EX-024）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmOutcome apply(String tenantId, long messageId, long toolCallId,
                                ToolConfirmDecision decision, String reason, long uid) {
        // 🔴 行锁 = 状态机唯一裁决点（三方竞态 confirm / 等待超时 / 停止生成，AR-010）
        ToolCall call = toolCallRepository.findByIdForUpdate(toolCallId)
                .orElseThrow(BusinessException::notFound);
        if (call.getMessageId() == null || call.getMessageId() != messageId) {
            // 🔴 toolCallId 必须隶属该 messageId，否则按不存在处理（不泄露存在性）
            throw BusinessException.notFound();
        }

        String status = call.getStatus();
        if (ToolCall.STATUS_AWAITING_CONFIRMATION.equals(status)) {
            return applyFirstDecision(call, decision, reason, uid);
        }
        if (ToolCall.STATUS_PENDING.equals(status)) {
            // 尚未要求确认（低风险自动执行 / 还没走到风险判定）→ 无待确认的工具调用
            throw BusinessException.notFound();
        }
        return replayOrConflict(call, decision, reason);
    }

    /**
     * {@code awaiting_confirmation} 上的首次决定。
     */
    private ConfirmOutcome applyFirstDecision(ToolCall call, ToolConfirmDecision decision,
                                              String reason, long uid) {
        Instant now = Instant.now();
        String targetStatus = decision == ToolConfirmDecision.ALLOW
                ? ToolCall.STATUS_RUNNING : ToolCall.STATUS_DENIED;
        ToolStateMachine.requireTransition(call.getStatus(), targetStatus);

        call.setStatus(targetStatus);
        call.setDecision(decision.literal());
        call.setDecidedByUid(uid);
        call.setDecidedAt(now);
        if (decision == ToolConfirmDecision.ALLOW) {
            // 🔴 由 confirm 接口把状态推进到 running：生成线程被唤醒后不再重复流转
            //    （running → running 是非法流转，会被状态机拒绝）
            call.setStartedAt(now);
        } else {
            call.setErrorCode(ErrorCode.TOOL_DENIED);
            call.setFinishedAt(now);
            if (call.getStartedAt() != null) {
                call.setDurationMs((int) Math.max(0,
                        now.toEpochMilli() - call.getStartedAt().toEpochMilli()));
            }
        }
        ToolCall saved = toolCallRepository.saveAndFlush(call);
        String eventId = writeFirstDecisionAudit(saved, decision, reason);
        return new ConfirmOutcome(saved.getId(), saved.getMessageId(), decision, saved.getStatus(),
                now, false, eventId);
    }

    /**
     * 非 {@code awaiting_confirmation} 状态：回放或冲突（🔴 矩阵见类注释）。
     *
     * <p>🔴 <b>判定基准 = {@code tool_calls.decision} 列</b>（api-spec V1.1.3 §7.8.2）：
     * 自 V1.1.3 起 {@code denied} 有<b>三种来源</b>（用户拒绝 / 用户已允许但执行期竞态被拒 /
     * 从未要求确认的授权拒绝），只看 {@code status} 会把"用户重试自己的 allow"误判成 {@code 30055}。
     */
    private ConfirmOutcome replayOrConflict(ToolCall call, ToolConfirmDecision decision,
                                            String reason) {
        if (ToolCall.STATUS_CANCELLED.equals(call.getStatus())) {
            // 生成已终止：任何决定都不再生效，但按契约回放 code=0（前端据此刷新卡片）
            return replay(call, decision);
        }
        Boolean effectiveAllow = effectiveAllow(call);
        if (effectiveAllow == null) {
            // 🔴 decision=NULL 的 denied（未授权/未绑定/SSRF，**从未要求确认**）→ 10004
            //    （§7.8.2 矩阵：无待确认的工具调用；🔴 不泄露"这条调用为什么被拒"）
            throw BusinessException.notFound();
        }
        if ((decision == ToolConfirmDecision.ALLOW) != effectiveAllow) {
            // 🔴 相反决定 → 30055；以服务端既有决定为准，前端不得重试
            return conflict(call, decision, reason, effectiveAllow);
        }
        return replay(call, decision);
    }

    /**
     * 既有<b>用户决定</b>等价于"允许过"还是"拒绝过"。
     *
     * <p>🔴 <b>以 {@code decision} 列为基准</b>（api-spec V1.1.3 §7.8.2 矩阵）：
     * <table border="1">
     *   <caption>三类 denied 的区分</caption>
     *   <tr><th>status / errorCode / decision</th><th>本方法返回</th><th>再提交 allow</th></tr>
     *   <tr><td>{@code denied} / 30050 / {@code deny}（用户拒绝）</td><td>{@code false}</td>
     *       <td>{@code 30055}</td></tr>
     *   <tr><td>🔴 {@code denied} / 30050 / {@code allow}（执行期竞态被拒，§7.8.1 ③）</td>
     *       <td>{@code true}</td><td>回放（用户原决定即 allow，<b>不是</b>冲突）</td></tr>
     *   <tr><td>🔴 {@code denied} / 30050 / {@code NULL}（未授权/SSRF，从未要求确认）</td>
     *       <td>{@code null}</td><td>{@code 10004}</td></tr>
     *   <tr><td>{@code timed_out} / 30050 / {@code NULL}（确认超时 = 已按拒绝收敛）</td>
     *       <td>{@code false}</td><td>{@code 30055}</td></tr>
     *   <tr><td>{@code timed_out} / 30051·30056 / {@code allow}（执行超时 = 已执行过）</td>
     *       <td>{@code true}</td><td>回放</td></tr>
     * </table>
     *
     * @return {@code true}=允许过；{@code false}=拒绝过；🔴 {@code null}=<b>从未要求确认</b>
     */
    private Boolean effectiveAllow(ToolCall call) {
        String decision = call.getDecision();
        if (ToolCall.DECISION_ALLOW.equals(decision)) {
            return Boolean.TRUE;
        }
        if (ToolCall.DECISION_DENY.equals(decision)) {
            return Boolean.FALSE;
        }
        // decision 为 NULL：只有"确认等待超时"算作已按拒绝收敛（§7.8.1 ④），其余属从未要求确认
        if (ToolStateMachine.confirmTimeout(call.getStatus(), call.getErrorCode())) {
            return Boolean.FALSE;
        }
        if (ToolCall.STATUS_DENIED.equals(call.getStatus())) {
            return null;
        }
        // running / succeeded / failed / timed_out(执行超时) 且 decision=NULL：
        // 属"低风险自动执行"路径 —— 从未要求确认，但已经执行过，语义等价于允许过（§7.8.2 矩阵第 2 行）
        return Boolean.TRUE;
    }

    /**
     * 幂等回放（🔴 <b>不写审计</b>，api-spec V1.1.3 §7.8.2 ④ / §7.14 不变量 6）。
     *
     * <p>🔴 <b>为什么去掉回放审计</b>（本轮返工点，原实现写同 action + {@code replayed:} 前缀）：
     * <ol>
     *   <li>审计承载的是<b>安全事实</b>（"用户在某时刻做出了某决定"）。回放<b>不产生新的安全事实</b>
     *       —— 服务端状态一个字节都没变，写进去的是"客户端又问了一次"，那是<b>访问日志</b>的职责；</li>
     *   <li>🔴 会被前端重试<b>放大成审计洪水</b>（实测可达）：confirm 接口
     *       <b>不使用</b> {@code Idempotency-Key}（§1.4），多标签页 / 网络抖动重试 / 用户连点确认按钮
     *       都会重复提交，且回放返回 {@code code=0} —— 前端认为"成功"而不做抑制。
     *       一次高风险确认可能落几十条同 action 审计行，把真正的越权与拒绝事件淹没
     *       （同 §7.7.1「low 风险纯函数成功执行不写审计」的抗噪原则）；</li>
     *   <li>可追溯性无损：首次决定的审计行 + {@code tool_calls} 的
     *       {@code decision}/{@code decided_by_uid}/{@code decided_at} 已完整回答
     *       "谁在何时决定了什么"，回放不增加任何信息量。</li>
     * </ol>
     *
     * <p>🔴 配套契约：{@code auditEventId} 恒为 <b>null</b> —— 禁止编造新 ID，
     * 也<b>不</b>回查首次审计行（{@code tool_calls} 无 {@code audit_event_id} 列，
     * 为回放多做一次 {@code audit_logs} 查询属无谓开销）。WARN 日志保留。
     */
    private ConfirmOutcome replay(ToolCall call, ToolConfirmDecision decision) {
        log.warn("确认决定回放（服务端状态未变化，🔴 按 §7.14 不变量 6 不写审计）："
                        + "toolCallId={} status={} submitted={}",
                call.getId(), call.getStatus(), decision);
        return new ConfirmOutcome(call.getId(), call.getMessageId(), decision, call.getStatus(),
                call.getDecidedAt() == null ? Instant.now() : call.getDecidedAt(), true, null);
    }

    /**
     * 决定冲突（🔴 {@code 30055} + 审计 {@code tool.confirm_conflict}，api-spec V1.1.3 §7.8.2 ⑤）。
     *
     * <p>🔴 <b>为什么必须留痕</b>："用户先 allow 又 deny（或反之）"是<b>安全相关行为</b>，
     * 且恰好发生在<b>高风险工具</b>这条最需要留痕的链路上 —— 可能是多标签页/前端缺陷，
     * 也可能是有人试图<b>翻转一个已生效的高风险决定</b>（已 allow 并执行成功后再提交 deny，
     * 制造"我没批准过"的抗辩）。不留痕等于放弃举证能力。
     *
     * <p><b>🔴 审计必须用「独立短事务」写（本轮实测踩到的坑，务必读完再改）</b>：
     * <pre>
     * 本方法最终**抛出** 30055 → 外层 REQUIRES_NEW 事务**整体回滚**。
     * 若审计用 AuditService.record(...)（REQUIRED，加入外层事务），
     * 🔴 那条冲突审计会**随回滚一起消失** —— 表现为"接口如约返回 30055，但 audit_logs 里什么都没有"，
     *    而这恰恰是本裁决要消灭的"不留痕"。实测即如此（用例断言 1 条实得 0 条）。
     * 👉 因此用 recordInNewTransaction(...)：内层事务**立即提交**，不受外层回滚影响。
     *
     * 🔴 这**不违反** ADR-010 / §7.14 的"审计与业务同事务"：
     *    那条纪律保护的是"业务状态变了却没审计"（或反之）的**原子性**；
     *    而本路径 🔴 **不改动 tool_calls 任何列**（状态已是终态，改动即篡改历史并破坏 §7.11.1 聚合），
     *    根本没有需要与审计保持原子的业务写入 —— 唯一的副作用就是这条审计本身。
     * </pre>
     *
     * <p>🔴 <b>防刷：同一 {@code toolCallId} 至多一条</b> —— 点查去重在<b>行锁内</b>完成
     * （{@code idx_object} 支撑），且写入也在锁内，因此并发的相反提交会被行锁串行化，
     * 结构上不可能写出两条。已存在则跳过写入但<b>仍返回 30055</b>。
     *
     * @throws BusinessException 30055 恒抛（本方法只在冲突分支被调用）；
     *                           50003 审计写入失败（EX-024：不得"拒绝了却没留痕"）
     */
    private ConfirmOutcome conflict(ToolCall call, ToolConfirmDecision decision, String reason,
                                    boolean effectiveAllow) {
        String objectId = String.valueOf(call.getId());
        log.warn("[SECURITY] 确认决定与服务端既有决定冲突：toolCallId={} status={} errorCode={}"
                        + " existingDecision={} submitted={}",
                call.getId(), call.getStatus(), call.getErrorCode(), call.getDecision(), decision);

        if (!auditService.alreadyRecorded(call.getTenantId(), AuditActions.TOOL_CONFIRM_CONFLICT,
                OBJECT_TYPE_TOOL_CALL, objectId)) {
            String before = effectiveAllow
                    ? ToolCall.DECISION_ALLOW : ToolCall.DECISION_DENY;
            AuditEvent event = AuditEvent.tenant(call.getTenantId(),
                            AuditActions.TOOL_CONFIRM_CONFLICT, AuditResults.DENIED,
                            OBJECT_TYPE_TOOL_CALL, objectId,
                            reason == null || reason.isBlank()
                                    ? AuditActions.REASON_CONFLICTING_DECISION : reason,
                            ErrorCode.TOOL_CONFIRM_CONFLICT)
                    // 🔴 既有决定 / 被拒绝的提交值均为枚举字面量（allow|deny），非敏感值，可原样记
                    .withDigests(before, decision.literal());
            try {
                auditService.recordInNewTransaction(event);
            } catch (RuntimeException e) {
                // 🔴 留不下痕就不能只回 30055（那等于"拒绝了但无从举证"）→ 升级为 50003
                log.error("[SECURITY] 决定冲突审计写入失败：toolCallId={}", call.getId(), e);
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        ErrorCode.defaultMessage(ErrorCode.INTERNAL_ERROR));
            }
        } else {
            log.warn("同一 toolCallId 的冲突审计已存在，跳过写入但仍返回 30055（防审计洪水）："
                    + "toolCallId={}", call.getId());
        }
        throw new BusinessException(ErrorCode.TOOL_CONFIRM_CONFLICT,
                ErrorCode.defaultMessage(ErrorCode.TOOL_CONFIRM_CONFLICT));
    }

    /**
     * 写<b>首次决定</b>的审计（🔴 与状态流转同一事务；失败 → 整体回滚 + {@code 50003}，EX-024）。
     *
     * <p>🔴 {@code allow} → {@code tool.confirm_allowed}（{@code result=success}）；
     * {@code deny} → {@code tool.confirm_denied}（{@code result=denied}）。
     * 🔴 <b>仅首次决定写入</b>：回放不写（见 {@link #replay}），冲突另走
     * {@code tool.confirm_conflict}（见 {@link #conflict}）。
     */
    private String writeFirstDecisionAudit(ToolCall call, ToolConfirmDecision decision,
                                           String reason) {
        boolean allow = decision == ToolConfirmDecision.ALLOW;
        String action = allow ? AuditActions.TOOL_CONFIRM_ALLOWED : AuditActions.TOOL_CONFIRM_DENIED;
        String result = allow ? AuditResults.SUCCESS : AuditResults.DENIED;
        String auditReason = reason == null || reason.isBlank()
                ? decision.literal() : reason;
        AuditEvent event = AuditEvent.tenant(call.getTenantId(), action, result,
                OBJECT_TYPE_TOOL_CALL, String.valueOf(call.getId()), auditReason,
                allow ? null : ErrorCode.TOOL_DENIED);
        // 🔴 record(...) 是 REQUIRED：加入本短事务（不是 REQUIRES_NEW），保证原子性
        return auditService.record(event);
    }
}
