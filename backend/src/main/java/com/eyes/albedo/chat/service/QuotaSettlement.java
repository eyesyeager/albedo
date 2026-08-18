package com.eyes.albedo.chat.service;

import java.util.concurrent.atomic.AtomicBoolean;

import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.service.QuotaService;

import lombok.extern.slf4j.Slf4j;

/**
 * 一次生成的额度结算<b>一次性标记</b>（🔴 exactly-once 的<b>进程内</b>那一道，§9.6.2）。
 *
 * <p>🔴 <b>为什么需要它</b>：结算触发点是"首个<b>资源已消耗证据</b>"，而证据有 4 类且可能在
 * 同一次生成里<b>接连出现</b>（首个正文分片 → 本轮 tool_calls → token usage …）。
 * 没有一次性标记，一次生成会被扣多次额度 —— 而<b>重复扣用户额度</b>是本设计中
 * 最不可接受的失败模式（比"极端情况下漏计一次"严重得多）。
 *
 * <p>结算触发点集合（api-spec §7.15.4，🔴 <b>不得增删</b>）：
 * <pre>
 * ⓐ 上游产出首个 assistant 正文分片（delta.text 非空）
 * ⓑ 上游产出首个思考分片（delta.reasoning 非空）—— 它同样是已消耗的模型输出
 * ⓒ 本轮上游返回了 tool_calls（模型已完成一次推理并请求调用工具）
 * ⓓ 上游返回了可归属本次尝试的 token usage（totalTokens > 0）
 * </pre>
 *
 * <p>🔴 <b>时序纪律：先 flush 该 SSE 帧，再落账</b> —— ⓐ/ⓑ 正是首字锚点（§9.5.3），
 * 把 DB 往返放在其前会直接把结算耗时算进首字 P95。
 *
 * <p>🔴 <b>非线程安全的使用约定</b>：本对象只在<b>单条生成线程</b>上使用
 * （与 {@code buffer} / {@code SegmentAccumulator} 相同）；用 {@link AtomicBoolean} 只是为了
 * 让"标记 → 判断"这一步本身是原子的，不代表可以跨线程共享。
 */
@Slf4j
final class QuotaSettlement {

    private final QuotaService quotaService;
    private final QuotaReservation reservation;
    private final long assistantMessageId;
    private final AtomicBoolean settled = new AtomicBoolean(false);

    QuotaSettlement(QuotaService quotaService, QuotaReservation reservation,
                    long assistantMessageId) {
        this.quotaService = quotaService;
        this.reservation = reservation;
        this.assistantMessageId = assistantMessageId;
    }

    /**
     * 首个"资源已消耗证据"到达时结算（🔴 同一次生成只会真正执行一次）。
     *
     * @param evidence 证据类型（仅用于日志排障：delta / reasoning / toolCalls / usage）
     */
    void onEvidence(String evidence) {
        if (!reservation.tracked()) {
            return;
        }
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        log.info("[QUOTA] 命中资源已消耗证据，结算本次生成尝试：messageId={} evidence={}",
                assistantMessageId, evidence);
        // 🔴 内部永不抛异常：结算失败绝不影响本次生成（不中断流、不改 done、不改 finishReason）
        quotaService.settle(reservation);
    }

    /**
     * 生成结束时的释放（🔴 {@code run()} 的 finally 无条件调用）。
     *
     * <p>🔴 已结算 → <b>什么都不做</b>（release 对已结算的预占必须是 no-op）；
     * 未结算 → 释放预占，{@code remaining} 立即恢复（AC-QUOTA-004：生成前失败不计数）。
     */
    void releaseIfUnsettled() {
        if (!reservation.tracked() || settled.get()) {
            return;
        }
        quotaService.release(reservation);
    }
}
