package com.eyes.albedo.tool;

import java.util.Optional;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.conversation.service.ConversationService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tool.dto.ToolConfirmRequest;
import com.eyes.albedo.tool.dto.ToolConfirmResultDTO;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 高风险工具确认接口的业务编排（api-spec §7.8.2，ADR-008）。
 *
 * <p><b>职责切分（🔴 事务纪律，ADR-010）</b>：
 * <pre>
 * ① 本类（🔴 <b>无事务</b>）：入参校验 → 归属校验 → 委托 ② → <b>提交之后</b>唤醒生成线程
 * ② ToolConfirmWriter（REQUIRES_NEW 短事务）：行锁 + 状态机 + 审计（同事务）
 * </pre>
 * 🔴 唤醒（{@code CompletableFuture.complete} + Redis 信号）<b>必须在事务外</b>：
 * 否则生成线程可能在事务提交前被唤醒，读到<b>旧状态</b>（`awaiting_confirmation`），
 * 然后按"超时"继续走 —— 这是一个只在高并发出现的诡异 bug。
 *
 * <p>🔴 <b>归属校验（architecture.md §9.5.2 第 4 条）</b>：confirm 跑在<b>另一个 Servlet 线程</b>，
 * 与生成线程不共享上下文。因此必须<b>独立</b>校验"{@code messageId} 所属会话的
 * {@code tenant_id + uid}"：
 * <ul>
 *   <li>租户维度由 Hibernate discriminator 自动加在 {@code tool_calls} 查询上（跨租户 → 查不到 → {@code 10004}）</li>
 *   <li>用户维度经 {@code ConversationService.require(uid, conversationId)}（非本人 → {@code 10004}）</li>
 * </ul>
 * 🔴 <b>绝不信任</b> {@code toolCallId} 自带的任何身份信息。
 *
 * <p><b>🔴 事务边界例外（api-spec §7.8.2 ⑤ / §7.14 不变量 7，V1.1.4 #5 已追认为正式契约）</b>：
 * <pre>
 * 例外只有一条，且 🔴 **仅授予「30055 决定冲突」这一条路径**：
 *   ToolConfirmWriter.conflict(...) 用 **独立短事务**（recordInNewTransaction）写
 *   audit action=tool.confirm_conflict，🔴 顺序必须是「**先提交审计、再抛 30055**」。
 *
 * 为什么该路径必须破例（否则契约在物理上自我否定）：
 *   本路径**必然**以抛 BusinessException(30055) 收尾 → 外层 REQUIRES_NEW 事务整体回滚 →
 *   若审计走同事务（REQUIRED），那条"必须留痕"的冲突审计会随回滚一并消失
 *   （@后端 实测：断言 1 条实得 **0 条**）。
 *
 * 为什么破例**不违反** §7.14 第一行的"审计与业务同事务"：
 *   ① 🔴 本路径**零业务写入** —— 不改动 tool_calls 任何列（已是终态，改动即篡改历史
 *      并破坏 §7.11.1 聚合），因此不存在需要与审计原子绑定的业务动作；
 *   ② §7.14 第一行的立意是"不得执行了业务却没审计"，这里没有业务可执行。
 *
 * 🔴 两条不可放宽的配套约束（改动前必须回读 api-spec §7.14 不变量 7）：
 *   ⓐ 去重点查（同一 toolCallId 至多一条冲突审计）🔴 仍在 **行锁内**执行
 *      —— 行锁把并发的相反提交串行化，结构上不可能写出两条；
 *   ⓑ 🔴 严禁以"反正会抛异常"为由把其它（有业务写入的）路径也改成独立事务，
 *      那会重新打开"执行了但没审计"的口子。新增此类路径必须先回写 §7.14 再实现。
 * </pre>
 */
@Slf4j
@Service
public class ToolConfirmService {

    /** {@code reason} 长度上限（api-spec §7.8.2 字段表）。 */
    private static final int REASON_MAX_CHARS = 200;

    private final ToolCallRepository toolCallRepository;
    private final ToolConfirmWriter confirmWriter;
    private final ToolConfirmRegistry confirmRegistry;
    private final ConversationService conversationService;

    public ToolConfirmService(ToolCallRepository toolCallRepository,
                             ToolConfirmWriter confirmWriter,
                             ToolConfirmRegistry confirmRegistry,
                             ConversationService conversationService) {
        this.toolCallRepository = toolCallRepository;
        this.confirmWriter = confirmWriter;
        this.confirmRegistry = confirmRegistry;
        this.conversationService = conversationService;
    }

    /**
     * 提交确认决定。
     *
     * @throws BusinessException 10001 {@code decision} 非法 / {@code reason} 超长；
     *                           10004 消息或工具调用不存在 / 非本人 / 跨租户 / 无待确认调用；
     *                           30055 与既有决定相反；50003 审计写入失败
     */
    public ToolConfirmResultDTO confirm(long uid, String messageIdRaw, String toolCallIdRaw,
                                        ToolConfirmRequest request) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        ToolConfirmDecision decision = ToolConfirmDecision.ofUserInput(
                request == null ? null : trimmed(request.decision()));
        if (decision == null) {
            throw BusinessException.validation("decision 取值必须是 allow 或 deny");
        }
        String reason = request == null ? null : request.reason();
        if (reason != null && reason.codePointCount(0, reason.length()) > REASON_MAX_CHARS) {
            throw BusinessException.validation("reason 不能超过 " + REASON_MAX_CHARS + " 个字符");
        }

        long messageId = Ids.parse(messageIdRaw);
        long toolCallId = Ids.parse(toolCallIdRaw);
        // 🔴 归属校验：租户维度靠 discriminator，用户维度靠会话归属
        ToolCall call = requireOwnedToolCall(uid, messageId, toolCallId);

        ToolConfirmWriter.ConfirmOutcome outcome = confirmWriter.apply(tenantId,
                messageId, call.getId(), decision, reason, uid);

        // 🔴 事务已提交 → 现在才唤醒生成线程（本机 Future + Redis 跨实例兜底信号）
        confirmRegistry.publishSignal(tenantId, toolCallId, decision);
        boolean localHit = confirmRegistry.complete(toolCallId, decision);
        log.info("工具确认已受理：toolCallId={} decision={} status={} replayed={} localHit={}",
                toolCallId, decision, outcome.status(), outcome.replayed(), localHit);

        return new ToolConfirmResultDTO(Ids.toStr(outcome.toolCallId()),
                Ids.toStr(outcome.messageId()), decision.literal(), outcome.status(),
                TimeFormat.iso(outcome.decidedAt()), outcome.replayed(), outcome.auditEventId());
    }

    /**
     * 取工具调用并校验归属（🔴 任何不匹配统一 {@code 10004}，不泄露存在性）。
     */
    private ToolCall requireOwnedToolCall(long uid, long messageId, long toolCallId) {
        Optional<ToolCall> found = toolCallRepository.findById(toolCallId);
        if (found.isEmpty() || found.get().getMessageId() == null
                || found.get().getMessageId() != messageId) {
            throw BusinessException.notFound();
        }
        ToolCall call = found.get();
        // 🔴 会话归属 = 用户维度校验（会话表带 uid；非本人/已删除 → 10004）
        conversationService.require(uid, String.valueOf(call.getConversationId()));
        return call;
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
