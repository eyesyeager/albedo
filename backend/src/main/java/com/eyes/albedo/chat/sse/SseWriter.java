package com.eyes.albedo.chat.sse;

import java.io.IOException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件写出器（api-spec.md §5 契约的唯一实现）。
 *
 * <p>事件顺序：{@code meta} → ({@code delta} | {@code tool})* → ({@code error})? → {@code done}。
 * <ul>
 *   <li>{@code meta} 在首个模型分片<b>之前立即写出并 flush</b>：对抗 Nginx/代理缓冲，保障首字 P95 ≤5s（AR-004）</li>
 *   <li>除物理断连外 {@code done} <b>必发</b>；{@code error} 之后仍要发 {@code done}</li>
 *   <li>🔴 {@code error.code} 必须是<b>数字业务码</b>，禁止字符串码（如 "AUTH_FAILED"）</li>
 * </ul>
 *
 * <p>写失败（客户端已断开）不向上抛异常：此时生成已无接收方，但<b>已生成内容仍需落库</b>，
 * 因此只标记连接失效，由调用方继续完成持久化（EX-015）。
 */
@Slf4j
public class SseWriter implements StreamSink {

    public static final String EVENT_META = "meta";
    public static final String EVENT_DELTA = "delta";
    public static final String EVENT_TOOL = "tool";
    public static final String EVENT_ERROR = "error";
    public static final String EVENT_DONE = "done";

    private final SseEmitter emitter;
    private boolean broken;

    public SseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    /**
     * 连接是否已失效（客户端断开）。
     */
    @Override
    public boolean broken() {
        return broken;
    }

    /**
     * {@code meta} 事件：真实会话 ID、本次 assistant 消息 ID、Agent 版本、用户消息 ID。
     */
    @Override
    public void meta(String conversationId, String messageId, long agentVersion, String userMessageId) {
        send(EVENT_META, new SseEvents.Meta(conversationId, messageId, agentVersion, userMessageId));
    }

    /**
     * {@code delta} 事件：增量正文分片（前端顺序拼接，非全量）。
     */
    @Override
    public void delta(String text) {
        send(EVENT_DELTA, SseEvents.Delta.text(text));
    }

    /**
     * {@code delta} 事件的思考过程通道（推理型模型的思维链增量）。
     *
     * <p>🔴 <b>复用 {@code delta} 事件名，不新增事件名</b> —— api-spec §5.4.1 第 6 条把
     * 事件名集合冻结为 {@code meta|delta|tool|error|done}，新增 {@code event: reasoning}
     * 会让所有既有前端的 {@code default: ignore} 分支静默丢弃整条通道，属破坏性变更。
     *
     * <p>🔴 思考内容走 {@code reasoning} 字段、{@code text} 恒空串：既让新前端能单独渲染折叠面板，
     * 又让只读 {@code text} 的旧前端拿到空串（不污染正文、不参与落库与标题生成）。
     */
    @Override
    public void reasoning(String reasoning) {
        send(EVENT_DELTA, SseEvents.Delta.reasoning(reasoning));
    }

    /**
     * {@code tool} 事件：工具调用状态帧（api-spec §5.2 / §5.4.2）。
     *
     * <p>🔴 <b>每次状态流转都必须下发一帧，禁止跳帧</b>；{@code toolCallId} 稳定不变，
     * 前端按它<b>原位更新</b>同一张工具卡片。
     *
     * <p>🔴 首字 P95 口径（§5.4.2）：{@code tool} 帧<b>也是用户可见帧</b> ——
     * "首轮即工具调用"的生成里，第一个 {@code tool} 帧就是用户感知到的首字。
     */
    @Override
    public void tool(com.eyes.albedo.tool.dto.ToolProgress progress) {
        send(EVENT_TOOL, SseEvents.Tool.from(progress));
    }

    /**
     * {@code error} 事件：数字业务码 + 可展示语义（🔴 字段集合恒三项，见 {@link SseEvents.Error}）。
     *
     * <p>🔴 <b>只写 code + message</b>（{@code retryAfterSeconds} 一期恒 null）：
     * 🔴 严禁把 {@code BusinessException.getPayload()}（含 {@code violations[]}）塞进本事件 ——
     * api-spec §5.2 末条 / §8.3 A4 明令终端用户路径不得下发字段级明细。
     */
    @Override
    public void error(int code, String message) {
        send(EVENT_ERROR, SseEvents.Error.of(code, message));
    }

    /**
     * {@code done} 事件：最终态。
     *
     * @param title 首轮成功后生成的标题；无则为 null（契约允许 null，但字段必须存在）
     */
    @Override
    public void done(String finishReason, String messageId, String status, String title) {
        send(EVENT_DONE, new SseEvents.Done(finishReason, messageId, status, title));
    }

    /**
     * 心跳注释帧（无分片超过 heartbeat 秒时发送，前端忽略）。
     */
    @Override
    public void ping() {
        if (broken) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | RuntimeException e) {
            markBroken(e);
        }
    }

    private void send(String event, Object data) {
        if (broken) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            markBroken(e);
        }
    }

    private void markBroken(Exception e) {
        if (broken) {
            return;
        }
        broken = true;
        // 客户端断开是正常场景（关闭页面 / 切网），不记 ERROR 以免污染告警
        log.debug("SSE 连接已失效，停止写出但继续持久化：cause={}", e.getClass().getSimpleName());
        // 🔴 ADR-008 第 9 条：把失效**主动**告知 SseEmitter，触发 onCompletion 回调 ——
        //    ChatController 在回调里唤醒确认等待为 cancelled。
        //    不做这一步的后果：生成线程挂在确认等待上（它没在读流，关流不会唤醒它），
        //    用户关掉页面后仍白等满 tool.confirm_wait_seconds（默认 120s），
        //    最坏 64 个线程被这样占住 → CallerRunsPolicy 回压到 Tomcat 线程（AR-008）。
        //    ⚠️ 这里用 complete() 而不是 completeWithError()：后者会触发一次**错误分派**，
        //    Spring 试图把 JSON Result 写进 text/event-stream 响应，产生
        //    "No converter for Result with preset Content-Type" 的噪声 WARN（客户端早就没了，写也没人收）。
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 已完成 / 已出错的 emitter 会拒绝二次收尾，属正常竞态
            log.trace("SSE emitter 二次收尾被忽略");
        }
    }

    /**
     * 结束响应（必须调用，否则 Servlet 异步上下文不会释放）。
     */
    @Override
    public void complete() {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("完成 SSE 响应时忽略异常：{}", e.getClass().getSimpleName());
        }
    }
}
