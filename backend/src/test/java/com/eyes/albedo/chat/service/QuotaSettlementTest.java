package com.eyes.albedo.chat.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;

import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.dto.QuotaWindow;
import com.eyes.albedo.quota.service.QuotaService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 结算的 <b>exactly-once（进程内）</b> 与释放语义（🔴 AC-QUOTA-005~008 / K7 / K8 的单测锚点）。
 *
 * <p>🔴 <b>为什么必须单测</b>：4 类证据（正文 / 思考 / tool_calls / usage）在一次生成里
 * 常常<b>接连出现</b>，没有一次性标记就会<b>重复扣额度</b> —— 而这是本设计中最不可接受的
 * 失败模式（"重复扣用户额度"比"极端情况下漏计一次"严重得多）。
 * IT 只能观测到最终 {@code used}，无法区分"标记生效"与"恰好只触发了一次"。
 */
@ExtendWith(MockitoExtension.class)
class QuotaSettlementTest {

    private static final long MESSAGE_ID = 5001L;

    @Mock
    private QuotaService quotaService;

    private QuotaReservation tracked() {
        QuotaWindow window = new QuotaWindow("Asia/Shanghai", LocalDate.of(2026, 8, 18),
                "d20260818", Instant.parse("2026-08-17T16:00:00Z"),
                Instant.parse("2026-08-18T16:00:00Z"));
        return new QuotaReservation(true, true, "gift", 10086L, "res-1", window);
    }

    @Test
    @DisplayName("🔴 exactly-once：4 类证据接连到达也只结算 **1 次**")
    void settlesExactlyOnceAcrossAllEvidenceTypes() {
        QuotaSettlement settlement = new QuotaSettlement(quotaService, tracked(), MESSAGE_ID);

        settlement.onEvidence("delta");
        settlement.onEvidence("reasoning");
        settlement.onEvidence("toolCalls");
        settlement.onEvidence("usage");

        verify(quotaService, times(1)).settle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("🔴 已结算 → release 必须是 no-op（否则会把已计数的额度又放回去）")
    void releaseIsNoOpAfterSettlement() {
        QuotaSettlement settlement = new QuotaSettlement(quotaService, tracked(), MESSAGE_ID);

        settlement.onEvidence("delta");
        settlement.releaseIfUnsettled();

        verify(quotaService, never()).release(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("🔴 AC-QUOTA-004：未达证据边界即结束 → 释放预占（生成前失败不计数）")
    void releasesWhenNoEvidenceArrived() {
        QuotaReservation reservation = tracked();
        QuotaSettlement settlement = new QuotaSettlement(quotaService, reservation, MESSAGE_ID);

        settlement.releaseIfUnsettled();

        verify(quotaService, times(1)).release(reservation);
        verify(quotaService, never()).settle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("🔴 未启用额度（no-op 哨兵）→ 既不结算也不释放（零 Redis / 零 DB 往返）")
    void sentinelReservationIsFullyNoOp() {
        QuotaSettlement settlement = new QuotaSettlement(quotaService, QuotaReservation.none(),
                MESSAGE_ID);

        settlement.onEvidence("delta");

        verifyNoInteractions(quotaService);
    }
}
