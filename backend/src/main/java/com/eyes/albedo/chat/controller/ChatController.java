package com.eyes.albedo.chat.controller;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.eyes.albedo.chat.dto.PreparedGeneration;
import com.eyes.albedo.chat.dto.SendMessageRequest;
import com.eyes.albedo.chat.dto.StopResultDTO;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.service.CancelRegistry;
import com.eyes.albedo.chat.service.ChatService;
import com.eyes.albedo.chat.service.ChatStreamRunner;
import com.eyes.albedo.chat.service.GenerationAdmission;
import com.eyes.albedo.chat.service.IdempotencyService;
import com.eyes.albedo.chat.sse.SseWriter;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式对话接口（api-spec.md §4.6）。
 *
 * <p>响应形态的两段式约定：
 * <ul>
 *   <li><b>建流之前</b>失败 → HTTP 200 + {@code application/json} 标准 {@code Result}</li>
 *   <li><b>建流之后</b>失败 → SSE {@code error} 事件（数字业务码）+ {@code done}</li>
 * </ul>
 * 因此本 Controller 的所有前置校验都在<b>同步阶段</b>完成，进入线程池后不再产生 JSON 响应。
 *
 * <p>首字延迟保障（AR-004）：连接建立后<b>立即写 {@code meta}</b> 并附
 * {@code X-Accel-Buffering: no} 响应头，规避 Nginx/代理缓冲。
 */
@Slf4j
@RestController
public class ChatController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    /** 关闭代理缓冲（Nginx 识别）——SSE 首字延迟的常见杀手。 */
    private static final String HEADER_ACCEL_BUFFERING = "X-Accel-Buffering";
    /** 幂等回放只写少量已落库内容，短超时足够。 */
    private static final long REPLAY_TIMEOUT_MILLIS = 30_000L;

    private final ChatService chatService;
    private final ChatStreamRunner streamRunner;
    private final IdempotencyService idempotencyService;
    private final GenerationAdmission admission;
    private final QuotaService quotaService;
    private final Executor aiStreamExecutor;
    private final com.eyes.albedo.tool.ToolConfirmRegistry toolConfirmRegistry;
    private final BusinessConfig businessConfig;
    private final CancelRegistry cancelRegistry;

    public ChatController(ChatService chatService,
                          ChatStreamRunner streamRunner,
                          IdempotencyService idempotencyService,
                          GenerationAdmission admission,
                          QuotaService quotaService,
                          @Qualifier(AsyncConfig.AI_STREAM_EXECUTOR) Executor aiStreamExecutor,
                          com.eyes.albedo.tool.ToolConfirmRegistry toolConfirmRegistry,
                          BusinessConfig businessConfig,
                          CancelRegistry cancelRegistry) {
        this.chatService = chatService;
        this.streamRunner = streamRunner;
        this.idempotencyService = idempotencyService;
        this.admission = admission;
        this.quotaService = quotaService;
        this.aiStreamExecutor = aiStreamExecutor;
        this.toolConfirmRegistry = toolConfirmRegistry;
        this.businessConfig = businessConfig;
        this.cancelRegistry = cancelRegistry;
    }

    /**
     * 发送消息并建立流式响应。
     *
     * @param conversationId 已有会话 ID，或特殊值 {@code new}（原子建会话 + 保存首条消息）
     */
    @Permission(PermissionEnum.USER)
    @PostMapping(value = "/api/v1/conversations/{conversationId}/messages")
    public ResponseEntity<SseEmitter> send(@PathVariable String conversationId,
                                          @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
                                          String idempotencyKey,
                                          @RequestBody(required = false) SendMessageRequest request) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        long uid = currentUid();

        // 🔴 建流之前的校验失败一律以异常抛出：GlobalExceptionHandler 会返回
        //    「HTTP 200 + application/json 的 Result」，与 api-spec §4.6.1 的两段式约定一致。
        //    注意返回类型必须是具体的 ResponseEntity<SseEmitter>，否则 Spring 不会启用
        //    ResponseBodyEmitterReturnValueHandler（用 ResponseEntity<?> 会退化为消息转换器并报错）。
        String key = idempotencyService.requireKey(idempotencyKey);
        String content = chatService.normalizeContent(request == null ? null : request.content());
        String agentId = request == null ? null : request.agentId();

        // 幂等命中 → 回放原结果，绝不重复创建会话/消息（EX-013 / AC-CON-002）
        // 🔴 ADR-020：回放**不计 QPM、不占日额度、不重复结算**（AC-QUOTA-007）——
        //    因此这一步必须在 admission.admit(...) **之前**返回。
        Optional<ResponseEntity<SseEmitter>> replay = tryReplay(uid, key);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 🔴 准入五步的 ①~④（顺序见 GenerationAdmission / §9.6.1）
        QuotaReservation reservation = admission.admit(tenantId, uid);
        // 🔴 ⑤ 建消息 + 建流：本步任何异常都必须释放预占（AC-QUOTA-004"生成前失败不计数"）
        return withReservation(reservation, () -> {
            PreparedGeneration prepared =
                    ChatService.NEW_CONVERSATION.equalsIgnoreCase(conversationId)
                            ? chatService.prepareNewConversation(uid, agentId, content, key)
                            : chatService.prepareExistingConversation(uid, conversationId, content, key);
            return openStream(prepared.withReservation(reservation));
        });
    }

    /**
     * 停止生成。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/api/v1/messages/{messageId}/stop")
    public Result<StopResultDTO> stop(@PathVariable String messageId) {
        return Result.success(chatService.stop(currentUid(), messageId));
    }

    /**
     * 重新生成（新建尝试，旧尝试保留）。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/api/v1/messages/{messageId}/regenerate")
    public ResponseEntity<SseEmitter> regenerate(@PathVariable String messageId,
                                                 @RequestHeader(value = IDEMPOTENCY_HEADER,
                                                         required = false) String idempotencyKey) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        long uid = currentUid();
        String key = idempotencyService.requireKey(idempotencyKey);

        Optional<ResponseEntity<SseEmitter>> replay = tryReplay(uid, key);
        if (replay.isPresent()) {
            return replay.get();
        }
        QuotaReservation reservation = admission.admit(tenantId, uid);
        return withReservation(reservation, () -> openStream(
                chatService.prepareRegenerate(uid, messageId, key).withReservation(reservation)));
    }

    /**
     * 🔴 <b>准入第 ⑤ 步的预占保护</b>（api-spec §7.15.3 步骤 5 / AC-QUOTA-004）。
     *
     * <p>建消息与建流可能因 {@code 10004} / {@code 30040} / {@code 30031} / {@code 50003} 等
     * 任意原因失败；此时<b>模型尚未被调用</b>，因此 🔴 <b>必须释放预占</b>，
     * 否则用户会为一次"根本没开始生成"的尝试白扣一次额度上的 {@code remaining}
     * （直到预占自然过期，最坏 = 生成预算 + 宽限 + 余量）。
     *
     * <p>🔴 <b>只释放预占、不回退 QPM</b>：AC-QUOTA-004 明文"对应请求已经通过频率准入时，
     * 其 QPM 次数不回退"；实现 {@code DECR} 会让"被限流期间重试不延长封禁窗口"失效。
     *
     * <p>🔴 <b>成功路径不释放</b>：预占的生命周期自此交给异步段
     * （{@code ChatStreamRunner} 在证据点结算、在 finally 释放未结算的预占）。
     */
    private ResponseEntity<SseEmitter> withReservation(QuotaReservation reservation,
                                                       Supplier<ResponseEntity<SseEmitter>> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            quotaService.release(reservation);
            throw e;
        }
    }

    // ===================== 内部实现 =====================

    /**
     * 建立 SSE 流：先 flush {@code meta}，再把生成任务交给专用线程池。
     *
     * <p>🔴 <b>连接寿命 = 业务预算 + 收尾宽限</b>（ADR-017 ①，V1.4.2 订正）：
     * <pre>
     * SseEmitter timeout = (chat.generation_deadline_seconds + chat.deadline_grace_seconds) × 1000
     * </pre>
     * 🔴 <b>严禁</b>再用 {@code runtime.requestTimeoutSeconds()} —— 那是 <b>单轮模型调用</b>的预算
     * （{@code AiChatRequest → AiChatClient.overallGuard} 逐轮生效），不含工具执行与确认等待。
     * 把它当整流寿命正是 BUG-MCP-002 的根因：{@code requestTimeoutSeconds=60s} 而
     * {@code tool.confirm_wait_seconds=120s} 时，连接必然在第一次确认等待期间超时，
     * 生成线程之后写的 {@code done} 被静默丢弃（"done 必发"在物理上不可能成立）。
     *
     * <p>🔴 层间硬序：业务侧在 {@code remaining ≤ grace} 时主动收敛，因此传输层比业务
     * <b>多活 grace 秒</b>，{@code error} + {@code done} 一定写得出去。
     */
    private ResponseEntity<SseEmitter> openStream(PreparedGeneration prepared) {
        // 🔴 预算全部来自 sys_config（反硬编码红线：requireLong 缺键直接失败，无代码默认值兜底）
        long deadlineSeconds = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS);
        long graceSeconds = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS);
        SseEmitter emitter = new SseEmitter((deadlineSeconds + graceSeconds) * 1000L);
        SseWriter writer = new SseWriter(emitter);
        // 🔴 首字之前立即写出 meta（SseEmitter 会在响应初始化时 flush 该缓冲）
        writer.meta(Ids.toStr(prepared.conversationId()),
                Ids.toStr(prepared.assistantMessageId()),
                prepared.runtime().version(),
                Ids.toStr(prepared.userMessageId()));

        long assistantId = prepared.assistantMessageId();
        emitter.onTimeout(() -> onTransportTimeout(assistantId, deadlineSeconds, graceSeconds));
        emitter.onError(e -> {
            log.debug("SSE 连接异常结束：messageId={}", assistantId);
            wakeConfirmWaiters(assistantId, "error");
        });
        // 🔴 ADR-008 第 9 条：客户端断连（关页面 / 切网）必须立即把确认等待唤醒为 cancelled，
        //    否则生成线程会白等满 tool.confirm_wait_seconds（默认 120s），
        //    最坏 64 个线程被占住 → CallerRunsPolicy 回压到 Tomcat 线程（AR-008）。
        //    正常结束时 onCompletion 也会触发，此时已无等待者，调用是幂等的空操作。
        emitter.onCompletion(() -> wakeConfirmWaiters(assistantId, "completion"));

        // TenantAwareTaskDecorator 会复制租户上下文快照；业务判断仍只用 prepared 中的显式值
        aiStreamExecutor.execute(() -> streamRunner.run(prepared, writer));
        return sseResponse(emitter);
    }

    /**
     * 🔴 <b>传输层超时的收敛语义</b>（ADR-017 ⑥ / §9.5.4 不变量 5）。
     *
     * <p>🔴 <b>它是"不应发生"的路径</b>：业务侧在 {@code remaining ≤ grace} 时就该主动收敛，
     * 因此走到这里意味着"预算不等式被打破，或存在未按 {@code remaining} 收敛的阻塞点"——
     * 故日志级别为 <b>WARN</b> 并带 {@code [DEADLINE]} 前缀，供运维直接告警。
     *
     * <p>动作固定三步：
     * <ol>
     *   <li>唤醒确认等待为 {@code cancelled}（关闭上游流<b>不会</b>唤醒挂在确认上的线程）</li>
     *   <li>{@link CancelRegistry#close(long)} 关上游流，让生成线程尽快退出（释放
     *       {@code aiStreamExecutor} 线程，AR-008）</li>
     *   <li>WARN {@code [DEADLINE]}</li>
     * </ol>
     *
     * <p>🔴 <b>刻意不写 Redis 取消标记</b>（{@code chat:cancel:{messageId}}）：那会把终态污染成
     * {@code stopped}，掩盖"超时"这一事实 —— 语义上这不是用户停止。
     * 🔴 该路径下 {@code done} 能否抵达客户端<b>不可保证</b>（物理断连豁免，§5.1 第 3 条），
     * 但<b>落库终态必须完成</b>（由 {@code ChatStreamRunner} 的 finally 保证）。
     */
    private void onTransportTimeout(long assistantMessageId, long deadlineSeconds,
                                    long graceSeconds) {
        wakeConfirmWaiters(assistantMessageId, "timeout");
        boolean closed = cancelRegistry.close(assistantMessageId);
        log.warn("[DEADLINE] 🔴 SSE 传输层超时先于业务收敛到达（本不应发生）：messageId={} "
                        + "sseTimeoutSeconds={} deadlineSeconds={} graceSeconds={} upstreamClosed={}；"
                        + "请核对预算不等式与是否存在未按 remaining 收敛的阻塞点"
                        + "（ADR-017 ⑤⑥ / AR-022）",
                assistantMessageId, deadlineSeconds + graceSeconds, deadlineSeconds, graceSeconds,
                closed);
    }

    /**
     * 唤醒该消息上所有确认等待（🔴 幂等：无等待者时为空操作）。
     */
    private void wakeConfirmWaiters(long assistantMessageId, String cause) {
        int woken = toolConfirmRegistry.cancelByMessage(assistantMessageId);
        if (woken > 0) {
            log.info("客户端连接结束（{}），已唤醒确认等待为 cancelled：messageId={} count={}",
                    cause, assistantMessageId, woken);
        }
    }

    private ResponseEntity<SseEmitter> sseResponse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=utf-8"))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header(HEADER_ACCEL_BUFFERING, "no")
                .header(HttpHeaders.CONNECTION, "keep-alive")
                .body(emitter);
    }

    /**
     * 幂等回放：命中已完成的 assistant 消息则以流回放；仍在生成中则抛 30020 让前端稍后重试。
     */
    private Optional<ResponseEntity<SseEmitter>> tryReplay(long uid, String key) {
        Optional<Message> recorded = chatService.findByIdempotencyKey(uid, key);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        Optional<Message> assistant = chatService.resolveReplayAssistant(recorded.get());
        if (assistant.isEmpty() || !assistant.get().terminal()) {
            // 前一次请求仍在执行（或 assistant 尚未创建）：让前端重试，
            // 🔴 绝不放行第二次执行 —— 那会重复创建消息并重复消耗模型额度
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "相同请求正在处理中，请稍后重试");
        }
        Message target = assistant.get();
        SseEmitter emitter = new SseEmitter(REPLAY_TIMEOUT_MILLIS);
        SseWriter writer = new SseWriter(emitter);
        writer.meta(Ids.toStr(target.getConversationId()), Ids.toStr(target.getId()),
                target.getAgentVersion() == null ? 0L : target.getAgentVersion(),
                Ids.toStr(recorded.get().getId()));
        aiStreamExecutor.execute(() -> streamRunner.replay(writer,
                Ids.toStr(target.getConversationId()), Ids.toStr(recorded.get().getId()), target));
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
