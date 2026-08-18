package com.eyes.albedo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * system 提示预算累加器（api-spec V1.1.3 §7.5.2 ① 裁决）。
 *
 * <p>🔴 本用例守护两条容易写错的口径：
 * <ol>
 *   <li><b>码点</b>而非 UTF-16 {@code length()}：emoji / 生僻字必须只算<b>一个</b>字符，
 *       否则同一份文本"用了 emoji 的租户"会莫名超限</li>
 *   <li>片段分隔符<b>计入</b>预算：不计会让统计值小于实际下发长度（低报）</li>
 * </ol>
 */
class SystemPromptBudgetTest {

    @Test
    @DisplayName("🔴 度量口径 = Unicode 码点（emoji 只算 1 个，不是 UTF-16 的 2）")
    void countsCodePointsNotUtf16Length() {
        // "😀" 的 UTF-16 length = 2，码点数 = 1
        String emoji = "😀";
        assertEquals(2, emoji.length(), "前提：该字符的 UTF-16 长度确实是 2");

        SystemPromptBudget budget = new SystemPromptBudget(1);
        assertTrue(budget.accept(emoji), "🔴 按码点计只占 1，必须放行");
        assertEquals(1, budget.used());
        assertFalse(budget.exceeded());
    }

    @Test
    @DisplayName("恰好等于上限 → 放行；再多一个码点 → 超限")
    void boundaryIsInclusive() {
        SystemPromptBudget budget = new SystemPromptBudget(3);
        assertTrue(budget.accept("abc"));
        assertFalse(budget.exceeded());
        assertFalse(budget.accept("d"));
        assertTrue(budget.exceeded());
        assertEquals(4, budget.used());
    }

    @Test
    @DisplayName("🔴 分隔符必须计入预算（否则统计值低于实际下发长度）")
    void separatorCountsTowardBudget() {
        SystemPromptBudget budget = new SystemPromptBudget(4);
        assertTrue(budget.accept("ab"));
        // "\n\n" 占 2 → 累计 4，仍在预算内
        assertTrue(budget.acceptSeparator());
        assertEquals(4, budget.used());
        assertFalse(budget.accept("c"), "累计 5 > 4，必须超限");
    }

    @Test
    @DisplayName("null / 空串不改变累计值，且不把已超限状态改回来")
    void blankSectionsAreNoop() {
        SystemPromptBudget budget = new SystemPromptBudget(2);
        assertTrue(budget.accept(null));
        assertTrue(budget.accept(""));
        assertEquals(0, budget.used());

        assertFalse(budget.accept("abc"));
        assertFalse(budget.accept(null), "🔴 已超限后即便传空串也必须继续返回 false（fail-closed）");
        assertTrue(budget.exceeded());
    }

    @Test
    @DisplayName("payload 形状与 api-spec §7.3.1 失败响应一致（violations[] 带 rule）")
    void payloadShapeMatchesContract() {
        Object payload = ViolationRules.payload("agentVersion", "5", "systemPrompt",
                ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED, "已超出系统提示长度上限");
        String rendered = String.valueOf(payload);
        assertTrue(rendered.contains("violations"), rendered);
        assertTrue(rendered.contains(ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED), rendered);
        assertTrue(rendered.contains("valid=false"), rendered);
    }
}
