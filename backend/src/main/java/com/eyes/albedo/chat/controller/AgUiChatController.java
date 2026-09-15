package com.eyes.albedo.chat.controller;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.eyes.albedo.chat.dto.PreparedGeneration;
import com.eyes.albedo.chat.dto.RunAgentInput;
import com.eyes.albedo.chat.dto.StopResultDTO;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.service.CancelRegistry;
import com.eyes.albedo.chat.service.ChatService;
import com.eyes.albedo.chat.service.ChatStreamRunner;
import com.eyes.albedo.chat.service.GenerationAdmission;
import com.eyes.albedo.chat.service.IdempotencyService;
import com.eyes.albedo.chat.sse.AgUiStreamSink;
import com.eyes.albedo.chat.sse.AgUiWriter;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.config.AsyncConfig;
import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.service.QuotaService;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AG-UI 协议流式对话端点。
 *
 * <p>🔴 这是「彻底替换为 AG-UI 协议」的新端点，请求体为标准 {@link RunAgentInput}，
 * 响应为标准 AG-UI 事件流（{@code RUN_STARTED} → 文本/推理/工具事件 → {@code RUN_FINISHED}/{@code RUN_ERROR}）。
 *
 * <p><b>映射约定</b>：
 * <ul>
 *   <li>{@code threadId} → 本项目的 conversationId（{@code "new"} 表示原子新建会话）</li>
 *   <li>{@code runId} → 幂等键（与旧 {@code Idempotency-Key} 等价）</li>
 *   <li>{@code messages[]} → 取最后一条 {@code role=user} 消息的 {@code content} 作为本次输入</li>
 *   <li>{@code agentId} → 经 {@code forwardedProps.agentId} 透传（{@code "new"} 场景使用）</li>
 * </ul>
 *
 * <p>历史会话回显仍走 REST（契约决策：AG-UI 只管流式增量），工具确认交互见后续 interrupt/resume。
 */
@Slf4j
@RestController
public class AgUiChatController {

    private static final String HEADER_ACCEL_BUFFERING = "X-Accel-Buffering";

    private final ChatService chatService;
    private final ChatStreamRunner streamRunner;
    private final IdempotencyService idempotencyService;
    private final GenerationAdmission admission;
    private final QuotaService quotaService;
    private final Executor aiStreamExecutor;
    private final BusinessConfig businessConfig;
    private final CancelRegistry cancelRegistry;

    public AgUiChatController(ChatService chatService,
                              ChatStreamRunner streamRunner,
                              IdempotencyService idempotencyService,
                              GenerationAdmission admission,
                              QuotaService quotaService,
                              @Qualifier(AsyncConfig.AI_STREAM_EXECUTOR) Executor aiStreamExecutor,
                              BusinessConfig businessConfig,
                              CancelRegistry cancelRegistry) {
        this.chatService = chatService;
        this.streamRunner = streamRunner;
        this.idempotencyService = idempotencyService;
        this.admission = admission;
        this.quotaService = quotaService;
        this.aiStreamExecutor = aiStreamExecutor;
        this.businessConfig = businessConfig;
        this.cancelRegistry = cancelRegistry;
    }

    /**
     * AG-UI 运行入口：接收 {@link RunAgentInput}，返回 AG-UI 事件流。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/api/v1/agui/run")
    public ResponseEntity<SseEmitter> run(@RequestBody RunAgentInput input) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        long uid = currentUid();

        String runId = idempotencyService.requireKey(input.runId());
        String content = extractUserContent(input);
        String agentId = extractAgentId(input);
        String regenerateMessageId = extractRegenerateMessageId(input);

        // 幂等命中 → 回放原结果（AG-UI 事件流形态）
        Optional<ResponseEntity<SseEmitter>> replay = tryReplay(uid, runId);
        if (replay.isPresent()) {
            return replay.get();
        }

        QuotaReservation reservation = admission.admit(tenantId, uid);
        return withReservation(reservation, () -> {
            PreparedGeneration prepared;
            if (regenerateMessageId != null) {
                // 🔴 regenerate：从 messageId 反查会话，不重复保存用户消息
                prepared = chatService.prepareRegenerate(uid, regenerateMessageId, runId);
            } else {
                String threadId = input.threadId() == null || input.threadId().isBlank()
                        ? ChatService.NEW_CONVERSATION : input.threadId();
                prepared = ChatService.NEW_CONVERSATION.equalsIgnoreCase(threadId)
                        ? chatService.prepareNewConversation(uid, agentId, content, runId)
                        : chatService.prepareExistingConversation(uid, threadId, content, runId);
            }
            return openStream(prepared.withReservation(reservation), input);
        });
    }

    /**
     * 停止生成（沿用旧端点，AG-UI 无独立 stop 语义）。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/api/v1/agui/messages/{messageId}/stop")
    public Result<StopResultDTO> stop(@PathVariable String messageId) {
        return Result.success(chatService.stop(currentUid(), messageId));
    }

    // ===================== 内部实现 =====================

    private String extractUserContent(RunAgentInput input) {
        if (input.messages() == null || input.messages().isEmpty()) {
            return "";
        }
        // 🔴 取最后一条 role=user 的消息；content 可能是 string 或 content 片段数组
        for (int i = input.messages().size() - 1; i >= 0; i--) {
            Map<String, Object> message = input.messages().get(i);
            if ("user".equals(message.get("role"))) {
                Object content = message.get("content");
                return content == null ? "" : content.toString();
            }
        }
        return "";
    }

    private String extractAgentId(RunAgentInput input) {
        if (input.forwardedProps() == null) {
            return null;
        }
        Object agentId = input.forwardedProps().get("agentId");
        return agentId == null ? null : agentId.toString();
    }

    private String extractRegenerateMessageId(RunAgentInput input) {
        if (input.forwardedProps() == null) {
            return null;
        }
        Object messageId = input.forwardedProps().get("regenerateMessageId");
        return messageId == null || messageId.toString().isBlank() ? null : messageId.toString();
    }

    private ResponseEntity<SseEmitter> withReservation(QuotaReservation reservation,
                                                       Supplier<ResponseEntity<SseEmitter>> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            quotaService.release(reservation);
            throw e;
        }
    }

    /**
     * 建立 AG-UI 事件流：先 flush {@code RUN_STARTED}，再把生成任务交给专用线程池。
     *
     * <p>🔴 连接寿命沿用旧契约：{@code (generation_deadline + deadline_grace) × 1000}。
     */
    private ResponseEntity<SseEmitter> openStream(PreparedGeneration prepared, RunAgentInput input) {
        long deadlineSeconds = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS);
        long graceSeconds = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS);
        SseEmitter emitter = new SseEmitter((deadlineSeconds + graceSeconds) * 1000L);

        String threadId = Ids.toStr(prepared.conversationId());
        String runId = Ids.toStr(prepared.assistantMessageId());
        AgUiWriter agUiWriter = new AgUiWriter(emitter, threadId, runId);
        // 首字之前立即写出 RUN_STARTED（与旧 meta 同构：对抗代理缓冲，保障首字 P95）
        agUiWriter.runStarted(Map.of(
                "conversationId", threadId,
                "userMessageId", Ids.toStr(prepared.userMessageId()),
                "agentVersion", prepared.runtime().version()));

        long assistantId = prepared.assistantMessageId();
        emitter.onTimeout(() -> onTransportTimeout(assistantId, deadlineSeconds, graceSeconds));
        emitter.onError(e -> {
            log.debug("AG-UI SSE 连接异常结束：messageId={}", assistantId);
        });

        AgUiStreamSink sink = new AgUiStreamSink(agUiWriter);
        aiStreamExecutor.execute(() -> streamRunner.run(prepared, sink));
        return sseResponse(emitter);
    }

    private void onTransportTimeout(long assistantMessageId, long deadlineSeconds, long graceSeconds) {
        boolean closed = cancelRegistry.close(assistantMessageId);
        log.warn("[DEADLINE] 🔴 AG-UI SSE 传输层超时先于业务收敛到达（本不应发生）：messageId={} "
                        + "sseTimeoutSeconds={} deadlineSeconds={} graceSeconds={} upstreamClosed={}",
                assistantMessageId, deadlineSeconds + graceSeconds, deadlineSeconds, graceSeconds, closed);
    }

    private ResponseEntity<SseEmitter> sseResponse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=utf-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header(HEADER_ACCEL_BUFFERING, "no")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .body(emitter);
    }

    private Optional<ResponseEntity<SseEmitter>> tryReplay(long uid, String key) {
        Optional<Message> recorded = chatService.findByIdempotencyKey(uid, key);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        Optional<Message> assistant = chatService.resolveReplayAssistant(recorded.get());
        if (assistant.isEmpty() || !assistant.get().terminal()) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "相同请求正在处理中，请稍后重试");
        }
        Message target = assistant.get();
        SseEmitter emitter = new SseEmitter(30_000L);
        AgUiWriter writer = new AgUiWriter(emitter, Ids.toStr(target.getConversationId()),
                Ids.toStr(target.getId()));
        writer.runStarted(Map.of(
                "conversationId", Ids.toStr(target.getConversationId()),
                "userMessageId", Ids.toStr(recorded.get().getId()),
                "agentVersion", target.getAgentVersion() == null ? 0L : target.getAgentVersion()));
        aiStreamExecutor.execute(() -> {
            try {
                if (target.getContent() != null && !target.getContent().isEmpty()) {
                    writer.textDelta(Ids.toStr(target.getId()), target.getContent());
                }
                writer.custom("completion", Map.of(
                        "finishReason", target.getFinishReason() == null ? Message.FINISH_STOP
                                : target.getFinishReason(),
                        "messageId", Ids.toStr(target.getId()),
                        "status", target.getStatus(),
                        "title", ""));
                writer.runFinished();
            } finally {
                writer.complete();
            }
        });
        return Optional.of(sseResponse(emitter));
    }

    private long currentUid() {
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            throw BusinessException.permissionDenied("无法确定当前身份");
        }
        return uid;
    }
}
