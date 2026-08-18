package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成预算快照单测（🔴 ADR-017 的 L2/L3 算术，architecture.md §9.5.4 不变量 4）。
 *
 * <p>为什么值得单测：这段算术决定"业务收敛是否早于传输层死亡"——
 * 一个符号写错（忘记减 grace、或 {@code exhausted} 的比较方向反了）
 * 就会让 BUG-MCP-002 原样复发，而端到端用例只在<b>极端配置</b>下才能暴露它。
 *
 * <p>🔴 构造手法：把起点推到过去（不 sleep），并<b>额外留 500ms 余量</b>——
 * {@code remainingSeconds()} 是<b>向下取整</b>的，若不留余量，用例会随执行时机在
 * {@code R} / {@code R−1} 之间抖动（这不是实现缺陷，而是"绝不高估剩余"的刻意取舍）。
 */
class GenerationDeadlineTest {

    /** 构造一个"剩余恰为 {@code remainingSeconds} 秒"的预算快照。 */
    private static GenerationDeadline withRemaining(long deadlineSeconds, long graceSeconds,
                                                    long remainingSeconds) {
        long consumedNanos = Duration.ofSeconds(deadlineSeconds - remainingSeconds).toNanos()
                - Duration.ofMillis(500).toNanos();
        return new GenerationDeadline(System.nanoTime() - consumedNanos, deadlineSeconds,
                graceSeconds);
    }

    @Test
    @DisplayName("剩余预算 = deadline − 已消耗；可用预算再扣一份宽限")
    void remainingAndUsable() {
        GenerationDeadline deadline = withRemaining(300, 15, 200);

        assertEquals(200, deadline.remainingSeconds());
        assertEquals(185, deadline.usableSeconds(), "🔴 可用 = remaining − grace");
        assertEquals(99, deadline.elapsedSeconds(),
                "已消耗同样向下取整（构造留了 500ms 余量，故为 99 而非 100）");
        assertFalse(deadline.exhausted());
    }

    @Test
    @DisplayName("🔴 remaining ≤ grace → exhausted（此时禁止开启任何新工作）")
    void exhaustedWhenRemainingWithinGrace() {
        assertTrue(withRemaining(300, 15, 15).exhausted(), "remaining=grace → 已耗尽");
        assertTrue(withRemaining(300, 15, 10).exhausted(), "remaining<grace → 已耗尽");
        assertTrue(withRemaining(300, 15, -100).exhausted(), "🔴 剩余为负也必须判耗尽");
        assertFalse(withRemaining(300, 15, 16).exhausted(), "remaining=grace+1 → 仍可继续");
    }

    @Test
    @DisplayName("🔴 工具执行准入：可用预算 < 该步骤自身超时 → 不允许开始（宁可少跑一次）")
    void admissionByStepTimeout() {
        GenerationDeadline deadline = withRemaining(100, 15, 40); // usable = 25

        assertTrue(deadline.allows(25), "恰好够 → 允许");
        assertTrue(deadline.allows(10));
        assertFalse(deadline.allows(26), "🔴 差一秒也不允许：跑到一半被传输层掐断更糟");
        assertFalse(deadline.allows(120));
    }

    @Test
    @DisplayName("🔴 子步骤有效预算 = min(自身上限, remaining − grace)，下界 1 秒")
    void budgetForTakesMinimum() {
        assertEquals(60, withRemaining(300, 15, 290).budgetFor(60),
                "预算充裕 → 用自身上限（Agent 单轮超时）");
        assertEquals(25, withRemaining(100, 15, 40).budgetFor(60),
                "🔴 预算紧张 → 被收紧为可用预算");
        assertEquals(1, withRemaining(20, 15, 1).budgetFor(60),
                "🔴 下界 1 秒：返回 0 会被上游当成\"无超时\"，比收紧更危险");
    }

    @Test
    @DisplayName("start(...) 以 nanoTime 计时（🔴 不受系统时钟回拨影响）")
    void startUsesMonotonicClock() {
        GenerationDeadline deadline = GenerationDeadline.start(300, 15);

        assertEquals(300, deadline.deadlineSeconds());
        assertEquals(15, deadline.graceSeconds());
        assertEquals(0, deadline.elapsedSeconds(), "刚创建：已消耗 0 秒");
        assertEquals(299, deadline.remainingSeconds(),
                "🔴 向下取整：刚创建即 299（保守 1 秒，绝不高估剩余）");
        assertFalse(deadline.exhausted());
    }
}
