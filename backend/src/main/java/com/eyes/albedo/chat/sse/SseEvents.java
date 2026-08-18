package com.eyes.albedo.chat.sse;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SSE 事件负载（api-spec.md §5.2）。
 *
 * <p>全部标注 {@code @JsonInclude(ALWAYS)}：全局 {@code non_null} 策略会省略 {@code title: null}，
 * 而契约明确规定 {@code done.title} 类型为 {@code string | null}——字段必须存在，值才可以为 null。
 */
public final class SseEvents {

    private SseEvents() {
    }

    /**
     * {@code meta}：在首个模型分片之前立即 flush。
     *
     * @param conversationId 真实会话 ID（{@code new} 场景为新建 ID）
     * @param messageId      本次 assistant 消息（尝试）ID
     * @param agentVersion   本次生成使用的 Agent 版本
     * @param userMessageId  本次保存的用户消息 ID（regenerate 为原用户消息 ID）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Meta(String conversationId, String messageId, long agentVersion, String userMessageId) {
    }

    /**
     * {@code delta}：文本增量分片（前端顺序拼接）。
     *
     * <p>🔴 <b>{@code reasoning} 与 {@code text} 互斥承载，不得混用同一个字段</b>（api-spec §5.2）：
     * 推理型模型（如 {@code hunyuan-a13b}）会先输出思维链再输出正文，二者语义完全不同 ——
     * 正文是答案、思维链是过程。若把思维链塞进 {@code text}，会造成三重破坏：
     * ① 只读 {@code text} 的 M1 前端把思考过程当答案渲染（§5.4.1 第 4 条兼容性被破坏）；
     * ② 思考过程被拼进 {@code messages.content} 落库，污染历史与"复制回答"；
     * ③ 会话标题由首轮正文生成，将取到思考片段。
     *
     * <p>因此约定：<b>正文帧</b> {@code text=正文, reasoning=null}；
     * <b>思考帧</b> {@code text="", reasoning=思维链增量}。
     * 🔴 M1 前端读 {@code text} 恒得空串、忽略未知字段 {@code reasoning}，行为与升级前完全一致。
     *
     * <p>🔴 思考内容<b>只在本次流式期间可见、不落库</b>（一期裁定，0 DDL）：
     * {@code messages} 表无对应列，刷新页面后不再展示，历史会话亦不回显。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Delta(String text, String reasoning) {

        /** 正文分片（{@code reasoning} 恒 null）。 */
        public static Delta text(String text) {
            return new Delta(text, null);
        }

        /** 思考过程分片（🔴 {@code text} 恒空串，绝不占用正文通道）。 */
        public static Delta reasoning(String reasoning) {
            return new Delta("", reasoning);
        }
    }

    /**
     * {@code error}：🔴 code 必须是数字业务码，message 不得含内部地址/堆栈/密钥。
     *
     * <p>🔴 <b>字段集合恒为三项</b>（api-spec §5.2 末条 V1.1.4 #2 / §8.3 A4）：
     * {@code code} + {@code message} + {@code retryAfterSeconds}。
     * 🔴 即便 {@code code=30060}（运行时配置非法）也<b>禁止</b>携带
     * {@code violations} / {@code objectType} / {@code objectId} / {@code rule} / {@code checkedObjects}
     * —— SSE 的接收者是<b>终端用户</b>，字段级明细属内部配置拓扑，下发即泄露；
     * 字段级明细的唯一出口是 §7.3.1 的管理端校验接口，诊断信息走 ERROR 日志 + {@code requestId} 反查。
     *
     * <p>🔴 {@code retryAfterSeconds} 一期<b>恒为 {@code null}</b>（§7.12 / §8.3 H1：
     * 限流发生在建流之前，流内无任何限流点，故 {@code 10005} 在 SSE 内不可达）。
     * 字段仍必须存在：§5.4.1「字段只增不改不删」，删掉会破坏前端兼容契约。
     * 🔴 <b>禁止</b>为了让它"有值"而在流内新增限流点（属新增业务约束，二期议题）。
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Error(int code, String message, Integer retryAfterSeconds) {

        /** 一期唯一构造路径：{@code retryAfterSeconds} 恒 {@code null}（流内 10005 不可达）。 */
        public static Error of(int code, String message) {
            return new Error(code, message, null);
        }
    }

    /**
     * {@code tool}：工具调用状态帧（api-spec §5.2，M3）。
     *
     * <p>🔴 <b>字段齐备是硬约束</b>（§8.2 核对项）：13 个字段一个不能少（V1.2.2 起含
     * {@code confirmExpiresInSeconds}），允许为 {@code null} 的字段（{@code errorCode} /
     * {@code retryAfterSeconds} / {@code confirmExpiresInSeconds}）
     * 也<b>必须存在</b>（故整类 {@code JsonInclude.ALWAYS}）。
     *
     * <p>🔴 <b>{@code summary} 是 V1.0 已声明的兼容字段，永久保留</b>（§5.4.1 第 2 条）：
     * 即便被 {@code argsSummary} / {@code resultSummary} 取代也必须继续下发，
     * 否则只读 {@code summary} 的 M1 前端会显示空白。
     * 取值口径：非终态（{@code pending}/{@code awaiting_confirmation}/{@code running}）= {@code argsSummary}，
     * 终态 = {@code resultSummary}。
     *
     * <p>🔴 <b>禁含</b>：完整入参明文、完整结果正文、消息正文、{@code systemPrompt}、
     * Skill 正文、MCP {@code endpoint}、凭据/Token。
     *
     * @param toolCallId        工具调用 ID（🔴 稳定不变，前端按它原位更新卡片）
     * @param toolType          {@code local} | {@code mcp}
     * @param toolKey           工具标识
     * @param riskLevel         {@code low} | {@code medium} | {@code high}
     * @param status            状态机取值（snake_case）
     * @param round             第几轮（从 1 起）
     * @param summary           🔴 兼容字段（见上）
     * @param argsSummary       入参脱敏摘要
     * @param resultSummary     结果脱敏摘要
     * @param truncated         结果是否被字节截断（EX-017）
     * @param errorCode         终态失败时的数字业务码，否则 {@code null}
     * @param retryAfterSeconds 限流拒绝时的剩余等待秒数，否则 {@code null}
     * @param confirmExpiresInSeconds 🔴 <b>V1.2.2 新增（ADR-017 ③ⓑ）</b>：本次确认的<b>实际</b>
     *                          剩余等待秒数，🔴 仅 {@code status=awaiting_confirmation} 帧非
     *                          {@code null}，其余状态恒 {@code null}。<br>
     *                          🔴 前端消费规则：<b>优先用本字段</b>，{@code null} / 缺失时才回退
     *                          {@code sys_config: tool.confirm_wait_seconds}（旧前端零破坏）。<br>
     *                          🔴 存在意义：确认等待被生成总预算收紧为
     *                          {@code min(tool.confirm_wait_seconds, remaining − grace)}，
     *                          前端若继续按 {@code sys_config} 显示倒计时会<b>骗人</b>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Tool(String toolCallId,
                       String toolType,
                       String toolKey,
                       String riskLevel,
                       String status,
                       int round,
                       String summary,
                       String argsSummary,
                       String resultSummary,
                       boolean truncated,
                       Integer errorCode,
                       Integer retryAfterSeconds,
                       Integer confirmExpiresInSeconds) {

        /**
         * 由工具侧中立进度对象翻译而来（🔴 {@code tool} 包不得依赖 {@code chat}，故翻译发生在这里）。
         */
        public static Tool from(com.eyes.albedo.tool.dto.ToolProgress progress) {
            String summary = progress.beforeResult()
                    ? progress.argsSummary() : progress.resultSummary();
            return new Tool(progress.toolCallId(), progress.toolType(), progress.toolKey(),
                    progress.riskLevel(), progress.status(), progress.round(), summary,
                    progress.argsSummary(), progress.resultSummary(), progress.truncated(),
                    progress.errorCode(), progress.retryAfterSeconds(),
                    progress.confirmExpiresInSeconds());
        }
    }

    /**
     * {@code done}：最终态，除物理断连外必发。
     *
     * @param finishReason stop / length / stopped / failed / timeout
     * @param messageId    assistant 消息 ID
     * @param status       completed / stopped / failed
     * @param title        首轮成功后生成的标题，否则 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Done(String finishReason, String messageId, String status, String title) {
    }
}
