package com.eyes.albedo.common;

/**
 * system 消息总长预算累加器（api-spec §7.5.2 ① / §7.1.2 {@code chat.system_prompt_max_chars}）。
 *
 * <p><b>职责</b>：只做"边拼边累加码点 + 超限立即返回 false"这一件事，
 * 🔴 <b>不决定</b>超限后果 —— 运行时是 fail-closed 抛 {@code 30060}，
 * 聚合校验入口是收集一条 {@code violations[]}，两者共用本类以保证口径完全一致
 * （api-spec §7.5.2 末尾要求校验入口"必须用<b>同一实现</b>累加同一预算"）。
 *
 * <p>🔴 <b>为什么落在 {@code common}（L0）而不是 {@code chat}</b>：
 * 判定的<b>归属</b>是 {@code chat/ContextAssembler}（architecture.md §5.1.3，只有它持有拼装结果），
 * 但同一份累加逻辑必须被 {@code configcheck/RuntimeConfigValidator} 复用。
 * 若把它放在 {@code chat}，聚合层就要新增 {@code configcheck → chat} 编译期依赖；
 * 若各写一份，两处口径会在改动后静默分叉 —— 而"线上 30060 与校验入口结论不一致"
 * 恰恰是 G10 裁决最想消灭的问题。本类无任何依赖，放 L0 是唯一无环落点。
 *
 * <p>🔴 <b>度量口径 = Unicode 码点</b>（{@link String#codePointCount}），
 * 不用 UTF-16 {@code length()}：否则 emoji / 生僻字被算两次，
 * 同一份文本在不同租户（用不用 emoji）表现不一致，DBA 无法解释"为什么少了几百字就超限"。
 *
 * <p>🔴 <b>性能</b>：一次 O(n) 字符累加，无分词、无正则、无 IO、无新增查询；
 * 且发生在异步段（{@code aiStreamExecutor}），按 api-spec §5.4.2 不占首字预算。
 * 🔴 调用方必须<b>先判定再追加</b>，禁止"先拼完再遍历一遍"（那会白付一次全量扫描）。
 */
public final class SystemPromptBudget {

    /** 片段之间的固定分隔符（与 {@code ContextAssembler} 的拼装一致，🔴 必须计入预算）。 */
    public static final String SECTION_SEPARATOR = "\n\n";

    private final int maxCodePoints;
    private int used;

    public SystemPromptBudget(int maxCodePoints) {
        this.maxCodePoints = maxCodePoints;
    }

    /**
     * 累加一段文本。
     *
     * @return {@code true} = 仍在预算内（调用方可以追加）；
     *         🔴 {@code false} = <b>已超限，调用方必须立即短路</b>（不得截断后继续）
     */
    public boolean accept(String text) {
        if (text == null || text.isEmpty()) {
            return !exceeded();
        }
        used += text.codePointCount(0, text.length());
        return used <= maxCodePoints;
    }

    /** 累加片段分隔符（第二段及以后必须调用，否则统计值小于实际下发长度）。 */
    public boolean acceptSeparator() {
        return accept(SECTION_SEPARATOR);
    }

    public boolean exceeded() {
        return used > maxCodePoints;
    }

    /** 已累加的码点数（🔴 可对外回显：它是长度，不是内容）。 */
    public int used() {
        return used;
    }

    public int max() {
        return maxCodePoints;
    }
}
