package com.eyes.albedo.chat.dto;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.quota.dto.QuotaReservation;

/**
 * 一次生成任务的<b>不可变准备结果</b>（同步阶段产出，异步阶段只读）。
 *
 * <p>🔴 这是租户隔离在异步链路的关键约定（RISK-001 / AC-TEN-005）：
 * 所有身份与上下文信息在<b>进入线程池之前</b>就固化为参数，
 * 异步段<b>不得</b>再读取 {@code UserInfoHolder} 或 Servlet 请求。
 *
 * @param tenantId           租户号（快照值）
 * @param uid                用户 uid（快照值）
 * @param conversationId     会话 ID
 * @param userMessageId      本轮用户消息 ID（重新生成时为原用户消息 ID）
 * @param assistantMessageId 本次 assistant 消息（尝试）ID
 * @param runtime            Agent 版本快照（会话创建时绑定的版本）
 * @param firstUserContent   首条用户消息内容（用于生成标题）
 * @param needTitle          本轮成功后是否需要生成标题
 * @param reservation        🔴 日额度预占凭据（V1.4.5 / ADR-020）：由准入第 4 步产出、
 *                           异步段在首个"资源已消耗证据"帧 flush 之后结算，未结算则在
 *                           {@code finally} 释放。🔴 未启用额度时为 no-op 哨兵而<b>不是</b>
 *                           {@code null}（避免结算/释放路径处处判空、漏一处即 NPE 或漏释放）
 */
public record PreparedGeneration(String tenantId,
                                 long uid,
                                 long conversationId,
                                 long userMessageId,
                                 long assistantMessageId,
                                 AgentRuntime runtime,
                                 String firstUserContent,
                                 boolean needTitle,
                                 QuotaReservation reservation) {

    /**
     * 同步阶段的构造（🔴 {@code ChatService} 只负责建消息，<b>不感知额度</b> ——
     * {@code conversation}/{@code chat} 与 {@code quota} 的职责边界，见 §5.1.2）。
     */
    public PreparedGeneration(String tenantId, long uid, long conversationId, long userMessageId,
                              long assistantMessageId, AgentRuntime runtime,
                              String firstUserContent, boolean needTitle) {
        this(tenantId, uid, conversationId, userMessageId, assistantMessageId, runtime,
                firstUserContent, needTitle, QuotaReservation.none());
    }

    /**
     * 附上准入阶段取得的预占凭据（🔴 §9.6.1 的 {@code openStream(prepared.withReservation(...))}）。
     */
    public PreparedGeneration withReservation(QuotaReservation quotaReservation) {
        return new PreparedGeneration(tenantId, uid, conversationId, userMessageId,
                assistantMessageId, runtime, firstUserContent, needTitle, quotaReservation);
    }
}
