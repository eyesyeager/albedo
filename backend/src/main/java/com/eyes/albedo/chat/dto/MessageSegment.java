package com.eyes.albedo.chat.dto;

/**
 * 一轮生成中产出的「思考 / 正文」段落（{@code messages.segments} 的元素）。
 *
 * <p>🔴 <b>{@code round} 的语义</b>：本段落之后紧跟 {@code tool_calls.round = round} 的工具调用。
 * 即时间线为 {@code 段落(1) → 工具(1) → 段落(2) → 工具(2) → … → 段落(N)}，
 * 最后一段之后没有工具（模型已给出最终答案）。
 * 🔴 该编号必须与 {@code tool_calls.round} 同源，否则历史回显的因果顺序会错位。
 *
 * <p>🔴 <b>本记录是渲染投影</b>：{@code reasoning} / {@code text} 的拼接结果
 * 必须与 {@code messages.reasoning} / {@code messages.content} 一致，
 * 但后两者才是权威（上下文回灌、标题、复制只认它们）。
 *
 * @param round     轮次（1 起）
 * @param reasoning 本轮思考过程（无则空串）
 * @param text      本轮正文（无则空串）
 */
public record MessageSegment(int round, String reasoning, String text) {

    /** 两者皆空的段落无需下发（例如仅产生工具调用、既无思考也无正文的一轮）。 */
    public boolean blank() {
        return (reasoning == null || reasoning.isEmpty()) && (text == null || text.isEmpty());
    }
}
