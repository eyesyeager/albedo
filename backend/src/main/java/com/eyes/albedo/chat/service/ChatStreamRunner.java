package com.eyes.albedo.chat.service;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.service.AgentService;
import com.eyes.albedo.audit.AuditActorTypes;
import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.audit.AuditSanitizer;
import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiChatRequest;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.ai.AiStreamException;
import com.eyes.albedo.chat.ai.AiStreamOutcome;
import com.eyes.albedo.chat.ai.AiToolCall;
import com.eyes.albedo.chat.ai.StreamWatchdog;
import com.eyes.albedo.chat.dto.MessageSegment;
import com.eyes.albedo.chat.dto.PreparedGeneration;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.sse.StreamSink;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.ViolationRules;
import com.eyes.albedo.conversation.service.ConversationService;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantFilter;
import com.eyes.albedo.tool.ToolCallRecorder;
import com.eyes.albedo.tool.ToolCatalogService;
import com.eyes.albedo.tool.ToolOrchestrator;
import com.eyes.albedo.tool.dto.ToolDefinition;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 流式生成执行器（<b>异步段</b>，运行在 {@code aiStreamExecutor} 线程）。
 *
 * <p>纪律（AR-002 / AC-TEN-005）：
 * <ul>
 *   <li>🔴 只使用 {@link PreparedGeneration} 里的快照值，<b>不读</b> {@code UserInfoHolder}、
 *       不读 Servlet 请求 —— 租户上下文虽由 {@code TenantAwareTaskDecorator} 恢复（供 Hibernate 用），
 *       但业务判断一律用显式参数，避免"依赖隐式上下文"的隐蔽泄露</li>
 *   <li>无论成功、失败、被停止，都必须：落库终态 → 发 {@code done} → 释放句柄与 emitter</li>
 * </ul>
 *
 * <p><b>🔴 M3：多轮工具编排（architecture.md §9.5.1 / api-spec §5.4.2）</b>
 * <pre>
 * 轮次循环（🔴 轮次计数由本类持有：只有它知道"本次生成"的边界，§5.1.3）：
 *   ① 构造工具清单（ToolCatalogService，异步段，🔴 ≤5 次批量查，api-spec §7.1.2 查询次数表）
 *   ② 组装上下文（含 Skill 注入片段）
 *   ③ 请求模型（有工具才下发 tools 字段）
 *   ④ 模型返回 tool_calls → 逐个交给 ToolOrchestrator（授权 → SSRF → Schema → 确认 → 执行）
 *   ⑤ 以 assistant(tool_calls) + role=tool 结果回灌，进入 round+1
 *   ⑥ round > tool.max_rounds → 🔴 error(30054) + done(failed)，**done 必发**
 * </pre>
 * 🔴 <b>done 必发</b>是硬约束：任何异常路径都要收敛到 {@code done}，
 * 否则前端会永久 loading（比报错严重得多）。
 *
 * <p>🔴 <b>两类截断严格分离</b>（ADR-011 第 3 条）：字节截断（{@code tool.result_max_bytes}）
 * 只作用于<b>回灌模型的结果体</b>，字符截断（{@code tool.result_summary_max_chars}）
 * 只作用于<b>摘要</b>。本类回灌用 {@code ToolDispatch.feedback()}（字节截断产物），
 * SSE 帧用摘要 —— 两者来源不同，🔴 不得互换。
 */
@Slf4j
@Component
public class ChatStreamRunner {

    /**
     * 预算耗尽时下发给用户的固定措辞（🔴 ADR-017 ④；与 EX-014 的"上游超时"对用户是同一件事）。
     *
     * <p>🔴 它是<b>用户可见文案</b>而非业务阈值，故为代码常量（与 {@code ErrorCode.defaultMessage}
     * 同类）；🔴 不得回显 deadline 数值等内部预算信息。
     */
    private static final String GENERATION_TIMEOUT_MESSAGE = "本次生成已超时，请重试";

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final ContextAssembler contextAssembler;
    private final TitleGenerator titleGenerator;
    private final AiChatClient aiChatClient;
    private final ChatCancelService cancelService;
    private final CancelRegistry cancelRegistry;
    private final StreamWatchdog watchdog;
    private final BusinessConfig businessConfig;
    private final AgentService agentService;
    private final ToolCatalogService toolCatalogService;
    private final ToolOrchestrator toolOrchestrator;
    private final ToolCallRecorder toolCallRecorder;
    /**
     * 🔴 V1.4.5（ADR-020）：{@code chat → quota} 的唯一依赖方向。
     *
     * <p>异步段只做两件事：首个"资源已消耗证据"到达时<b>结算</b>，生成结束时<b>释放</b>未结算的预占。
     */
    private final com.eyes.albedo.quota.service.QuotaService quotaService;

    public ChatStreamRunner(ChatService chatService,
                            ConversationService conversationService,
                            ContextAssembler contextAssembler,
                            TitleGenerator titleGenerator,
                            AiChatClient aiChatClient,
                            ChatCancelService cancelService,
                            CancelRegistry cancelRegistry,
                            StreamWatchdog watchdog,
                            BusinessConfig businessConfig,
                            AgentService agentService,
                            ToolCatalogService toolCatalogService,
                            ToolOrchestrator toolOrchestrator,
                            ToolCallRecorder toolCallRecorder,
                            com.eyes.albedo.quota.service.QuotaService quotaService) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.contextAssembler = contextAssembler;
        this.titleGenerator = titleGenerator;
        this.aiChatClient = aiChatClient;
        this.cancelService = cancelService;
        this.cancelRegistry = cancelRegistry;
        this.watchdog = watchdog;
        this.businessConfig = businessConfig;
        this.agentService = agentService;
        this.toolCatalogService = toolCatalogService;
        this.toolOrchestrator = toolOrchestrator;
        this.toolCallRecorder = toolCallRecorder;
        this.quotaService = quotaService;
    }

    /**
     * 执行一次生成（含多轮工具编排）。
     */
    public void run(PreparedGeneration prepared, StreamSink writer) {
        long assistantId = prepared.assistantMessageId();
        log.info("[ChatStream] 开始流式生成 assistantId={} conversationId={} agentId={} version={} tenant={} uid={}",
                assistantId, prepared.conversationId(), prepared.runtime().agentId(), prepared.runtime().version(),
                MDC.get(TenantFilter.MDC_TENANT_ID), MDC.get(TenantFilter.MDC_UID));
        StringBuilder buffer = new StringBuilder();
        // 🔴 思考过程独立累积：与 buffer 分开，落库时写入 messages.reasoning 列。
        //    仍然**绝不进 roundText**（不回灌模型上下文），也不参与标题生成。
        StringBuilder reasoningBuffer = new StringBuilder();
        // 🔴 按轮段落累积：只为还原「思考 → 工具 → 思考 → 正文」的真实时序（排版投影）。
        //    权威内容仍是 buffer / reasoningBuffer，本累积器出任何问题都不得影响它们。
        SegmentAccumulator segments = new SegmentAccumulator();
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        ScheduledFuture<?> heartbeat = startHeartbeat(writer, lastActivity);
        // 🔴 ADR-020 ②：额度结算的进程内一次性标记（4 类证据接连出现时只扣一次）
        QuotaSettlement settlement = new QuotaSettlement(quotaService, prepared.reservation(),
                assistantId);

        try {
            chatService.markStreaming(assistantId);
            generate(prepared, writer, buffer, reasoningBuffer, segments, lastActivity, settlement);
        } catch (AiStreamException e) {
            // 上游故障/超时：已接收内容必须保留（EX-015），并以数字业务码传达
            failGracefully(prepared, writer, buffer.toString(), reasoningBuffer.toString(),
                    segments, e.getCode(), e.getMessage(), e.getFinishReason());
        } catch (BusinessException e) {
            // 🔴 30060（运行时非法配置）必须留一条可反查的 ERROR 日志（api-spec §7.3.1 / §8.3 G-5）：
            //    响应体只给 code + message，诊断信息只能走日志
            logRuntimeConfigInvalid(prepared, e);
            failGracefully(prepared, writer, buffer.toString(), reasoningBuffer.toString(),
                    segments, e.getCode(), e.getMessage(), Message.FINISH_FAILED);
        } catch (RuntimeException e) {
            log.error("生成过程未预期异常：messageId={}", assistantId, e);
            failGracefully(prepared, writer, buffer.toString(), reasoningBuffer.toString(),
                    segments, ErrorCode.INTERNAL_ERROR,
                    ErrorCode.defaultMessage(ErrorCode.INTERNAL_ERROR), Message.FINISH_FAILED);
        } finally {
            watchdog.cancel(heartbeat);
            cancelRegistry.unregister(assistantId);
            // 🔴 §9.5.4 不变量 3：流结束时把残留的非终态工具调用统一收敛为 cancelled
            //    （否则查询接口会出现"永远在执行中"的僵尸卡片）
            toolCallRecorder.cancelPendingByMessage(assistantId);
            cancelService.clear(prepared.tenantId(), assistantId);
            // 🔴 ADR-020 ②：未达"资源已消耗证据"即结束 → 释放预占，remaining 立即恢复
            //    （已结算则为 no-op）。放在 writer.complete() 之前或之后都不影响正确性，
            //    但必须在 finally 里 —— 任何异常路径都不允许把预占留成孤儿。
            settlement.releaseIfUnsettled();
            writer.complete();
        }
    }

    // ===================== 多轮编排主干 =====================

    /**
     * 🔴 <b>运行时 {@code 30060} 的诊断日志</b>（api-spec §7.3.1 载荷形状裁定 / §8.3 G-5，
     * V1.1.4 #2 的<b>唯一新增要求</b>）。
     *
     * <p>🔴 <b>为什么必须有这条日志</b>：终端用户路径（SSE {@code error} 事件）的 {@code 30060}
     * 🔴 <b>只允许 {@code code + message}</b> —— {@code violations[]} 含内部对象 ID 与配置拓扑，
     * 下发即把租户配置结构泄露给任意登录用户。因此"哪儿有问题"这条信息只能落在<b>服务端日志</b>：
     * DBA 凭用户给的 {@code requestId} 反查，字段级出口仍唯一保留在
     * {@code POST /admin/config/validate}（管理端）。
     *
     * <p>🔴 记录内容 = {@code requestId + tenantId + agentVersion + rule + objectType:objectId}；
     * 🔴 全部过 {@link AuditSanitizer#reason(String)} 脱敏（与审计同一套禁记键名 + 值级模式，
     * 作为契约里 "LogScrubber 或<b>等价脱敏</b>" 的等价实现）——
     * 防止有人把 endpoint / 凭据片段塞进 {@code message} 后经日志外泄。
     *
     * <p>🔴 其它错误码不在此打 ERROR：它们各自已有归属日志，重复打点会淹没真正需要人介入的事件。
     */
    private void logRuntimeConfigInvalid(PreparedGeneration prepared, BusinessException e) {
        if (e.getCode() != ErrorCode.RUNTIME_CONFIG_INVALID) {
            return;
        }
        String diagnostic = ViolationRules.diagnostic(e.getPayload());
        log.error("[CONFIG] 运行时配置非法，本次生成在进入模型/工具执行前失败（响应仅回 code+message，"
                        + "字段级明细禁止下发）：requestId={} tenantId={} agentId={} agentVersion={} "
                        + "code={} {} message={}",
                MDC.get("requestId"), prepared.tenantId(), prepared.runtime().agentId(),
                prepared.runtime().version(), e.getCode(),
                AuditSanitizer.reason(diagnostic), AuditSanitizer.reason(e.getMessage()));
    }

    private void generate(PreparedGeneration prepared, StreamSink writer, StringBuilder buffer,
                          StringBuilder reasoningBuffer, SegmentAccumulator segments,
                          AtomicLong lastActivity, QuotaSettlement settlement) {
        long assistantId = prepared.assistantMessageId();
        // 🔴 ADR-017 L2：**入口算一次**业务预算，全程只取 remaining（禁止各步骤各拿一份完整超时）。
        //    🔴 只在异步段创建，值全部来自 sys_config（反硬编码红线）。
        GenerationDeadline deadline = GenerationDeadline.start(
                businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                        ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS),
                businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                        ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS));
        // 🔴 工具清单与 Skill 注入都在**异步段**完成：绝不挪到 Controller，
        //    否则 DB 往返会被算进"建立连接 → 首字"路径（§9.5.3）
        AgentVersion agentVersion = agentService.requireVersion(prepared.runtime().agentId(),
                prepared.runtime().version());
        List<ToolDefinition> catalog = toolCatalogService.buildCatalog(agentVersion);
        Map<String, ToolDefinition> functionIndex = toolCatalogService.functionIndex(catalog);

        List<AiMessage> context = new ArrayList<>(contextAssembler.assemble(prepared.tenantId(),
                prepared.conversationId(), prepared.runtime(), agentVersion, !catalog.isEmpty()));
        int maxRounds = businessConfig.requireInt(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_ROUNDS);
        ToolOrchestrator.ToolRunContext toolContext =
                toolContext(prepared, catalog, writer, agentVersion.getId());

        int round = 0;
        boolean deniedInLastRound = false;
        while (true) {
            // 🔴 §9.5.4 不变量 4 ④：进入新一轮之前 remaining ≤ grace → 直接按超时收敛，
            //    绝不再请求模型（否则这一轮必然跑到传输层死亡之后）
            if (deadline.exhausted()) {
                convergeOnDeadline(prepared, writer, buffer, reasoningBuffer, segments, deadline,
                        round + 1, "进入新一轮模型调用前预算耗尽");
                return;
            }
            StringBuilder roundText = new StringBuilder();
            AiStreamOutcome outcome = aiChatClient.stream(
                    buildRequest(prepared, context, catalog, deadline),
                    delta -> {
                        roundText.append(delta);
                        segments.appendText(delta);
                        onDelta(writer, buffer, lastActivity, delta);
                        // 🔴 结算证据 ⓐ：首个正文分片。**帧已 flush 之后**才落账
                        //    （ⓐ/ⓑ 正是首字锚点，把 DB 往返放在其前会算进首字 P95，§9.5.3）
                        if (!delta.isEmpty()) {
                            settlement.onEvidence("delta");
                        }
                    },
                    // 🔴 思考过程**不进 roundText**：那会把思维链回灌给模型（污染上下文并浪费 token）。
                    //    也**不进 buffer**：buffer 是正文，会被标题生成取用（api-spec §5.2 delta.reasoning）。
                    //    它只进 reasoningBuffer（落库到 messages.reasoning）与 segments（排版投影）。
                    reasoning -> {
                        segments.appendReasoning(reasoning);
                        onReasoning(writer, reasoningBuffer, lastActivity, reasoning);
                        // 🔴 结算证据 ⓑ：首个思考分片同样是**已消耗**的模型输出
                        if (!reasoning.isEmpty()) {
                            settlement.onEvidence("reasoning");
                        }
                    },
                    handle -> registerCancelHandle(assistantId, handle),
                    () -> cancelService.isCancelled(prepared.tenantId(), assistantId),
                    businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                            ConfigKeys.CANCEL_CHECK_INTERVAL_CHUNKS));

            // 🔴 本轮流式一结束就立刻定格段落：必须在任何分支之前，
            //    否则 return 路径会漏掉最后一段，历史回显就少一块思考
            segments.sealRound();

            // 🔴 结算证据 ⓒ/ⓓ（api-spec §7.15.4，@后端 不得增删该集合）：
            //    ⓒ 本轮返回 tool_calls —— 模型已完成一次推理并请求调用工具；
            //    ⓓ 上游返回了可归属本次尝试的 token usage（totalTokens > 0）。
            //    🔴 放在 sealRound 之后、任何 return 之前：否则"只返回 tool_calls 就断流"
            //       这条路径会白得一次生成（AC-QUOTA-005）。
            if (outcome.requestsTools()) {
                settlement.onEvidence("toolCalls");
            }
            if (hasAttributableUsage(outcome)) {
                settlement.onEvidence("usage");
            }

            if (outcome.cancelled() || cancelService.isCancelled(prepared.tenantId(), assistantId)) {
                completeSuccessfully(prepared, writer, buffer.toString(),
                        reasoningBuffer.toString(), segments, outcome);
                return;
            }
            if (!outcome.requestsTools()) {
                if (deniedInLastRound && buffer.length() == 0) {
                    // 🔴 §5.4.2 末表 / §7.8.1 ⑤：工具被拒且模型在无工具结果下**也没产出任何内容**
                    //    → 以 error(30050) + done(tool_denied) 收敛。
                    //    为什么要这条分支：否则用户会收到一个"成功但完全空白"的回答，
                    //    比明确告知"工具调用被拒绝"糟糕得多。
                    failGracefully(prepared, writer, "", reasoningBuffer.toString(), segments,
                            ErrorCode.TOOL_DENIED,
                            ErrorCode.defaultMessage(ErrorCode.TOOL_DENIED),
                            Message.FINISH_TOOL_DENIED);
                    return;
                }
                completeSuccessfully(prepared, writer, buffer.toString(),
                        reasoningBuffer.toString(), segments, outcome);
                return;
            }

            round++;
            if (round > maxRounds) {
                // 🔴 超限：不再下发新的 tool 帧，直接 error(30054) + done(failed)（api-spec §5.4.2 表末行）
                log.warn("工具调用轮次超过上限，本次生成以 30054 收敛：messageId={} maxRounds={}",
                        assistantId, maxRounds);
                failGracefully(prepared, writer, buffer.toString(), reasoningBuffer.toString(),
                        segments, ErrorCode.TOOL_LOOP_LIMIT_EXCEEDED,
                        ErrorCode.defaultMessage(ErrorCode.TOOL_LOOP_LIMIT_EXCEEDED),
                        Message.FINISH_FAILED);
                return;
            }

            // 🔴 先回灌"模型请求调用工具"的 assistant 消息，再逐个回灌 tool 结果（顺序不可颠倒）
            context.add(AiMessage.assistantToolCalls(roundText.toString(), outcome.toolCalls()));
            boolean cancelledDuringTools = false;
            boolean anyDenied = false;
            for (AiToolCall call : outcome.toolCalls()) {
                // 🔴 §9.5.4 不变量 4 ③ 工具执行准入：remaining − grace < 本工具有效超时
                //    → **不发起**本次执行，直接按超时收敛。
                //    宁可少跑一次工具，也不允许"跑到一半被传输层掐断"；
                //    本轮已下发的帧由 run() 的 finally 统一收敛为 cancelled（不变量 3）。
                ToolDefinition definition = functionIndex.get(call.name());
                if (definition != null && !deadline.allows(definition.timeoutSeconds())) {
                    convergeOnDeadline(prepared, writer, buffer, reasoningBuffer, segments, deadline,
                            round, "工具执行准入不通过（剩余预算不足以在宽限内跑完 toolKey="
                                    + definition.toolKey() + " timeoutSeconds="
                                    + definition.timeoutSeconds() + "）");
                    return;
                }
                ToolOrchestrator.ToolDispatch dispatch = toolOrchestrator.dispatch(toolContext,
                        toolInvocation(call, functionIndex, round));
                lastActivity.set(System.currentTimeMillis());
                context.add(AiMessage.tool(call.id(), dispatch.feedback()));
                anyDenied = anyDenied || dispatch.denied();
                if (dispatch.cancelled()) {
                    cancelledDuringTools = true;
                }
            }
            if (cancelledDuringTools
                    || cancelService.isCancelled(prepared.tenantId(), assistantId)) {
                // 用户在工具阶段停止：按 stopped 收敛（已生成内容保留）
                completeSuccessfully(prepared, writer, buffer.toString(), reasoningBuffer.toString(),
                        segments,
                        new AiStreamOutcome(Message.FINISH_STOPPED, outcome.usage(), true));
                return;
            }
            deniedInLastRound = anyDenied;
            if (anyDenied) {
                log.info("本轮存在被拒绝的工具调用，继续让模型在无工具结果下作答：messageId={}",
                        assistantId);
            }
        }
    }

    /**
     * 是否存在<b>可归属本次尝试</b>的 token 用量（🔴 结算证据 ⓓ，api-spec §7.15.4）。
     *
     * <p>🔴 判据是 {@code totalTokens > 0}：上游未返回用量时 {@code usage} 整个为 {@code null}
     * （🔴 我们从不编造数字），而 {@code totalTokens=0} 说明上游明确表示"没消耗"，
     * 那不构成"资源已消耗"的证据。
     */
    private boolean hasAttributableUsage(AiStreamOutcome outcome) {
        return outcome.usage() != null
                && outcome.usage().totalTokens() != null
                && outcome.usage().totalTokens() > 0;
    }

    /**
     * 工具编排上下文（🔴 全部快照值 + 两个回调，见 {@code ToolOrchestrator.ToolRunContext}）。
     *
     * @param agentVersionId 🔴 本次改造新增：透传给 {@code skill_load} 用来回查绑定 Skill
     */
    private ToolOrchestrator.ToolRunContext toolContext(PreparedGeneration prepared,
                                                        List<ToolDefinition> catalog,
                                                        StreamSink writer,
                                                        long agentVersionId) {
        // 🔴 异步段读不到 Servlet 请求：审计上下文用快照 uid 显式构造（§9.5.2 第 3 条 / AR-013）
        AuditContext auditContext = new AuditContext(MDC.get("requestId"),
                AuditActorTypes.END_USER, prepared.uid(), "", "");
        return new ToolOrchestrator.ToolRunContext(prepared.tenantId(), prepared.uid(),
                prepared.conversationId(), prepared.assistantMessageId(), catalog, auditContext,
                // 🔴 tool → chat 的唯一出口：把中立进度翻译成 SSE tool 帧
                writer::tool,
                agentVersionId);
    }

    /**
     * 模型给的函数名 → 工具定义（🔴 <b>必须查表</b>，api-spec §7.6.5 回映射规则）。
     *
     * <p>🔴 <b>严禁字符串还原</b>（例如 {@code name.replace("_", ":")}）：归一化<b>不可逆</b> ——
     * {@code a_b} 无法判断原文是 {@code a:b} 还是 {@code a_b}，猜错等于
     * <b>执行了用户没批准的工具</b>。因此本次生成内持有的
     * {@code Map<functionName, 定义>}（由 {@code ToolCatalogService.functionIndex} 构造）
     * 是唯一合法的反查途径；映射表随本次生成结束即释放，🔴 不进任何缓存层（§12.1）。
     *
     * <p>解析不到时<b>原样传入模型给的名字</b>，交由 {@code ToolOrchestrator} 的授权判定以
     * {@code 30050} 拒绝并写 {@code tool.grant_denied} 审计 —— 🔴 绝不"猜一个最像的工具"。
     *
     * <p>🔴 传给编排层的是 <b>{@code toolKey}</b>（对外契约标识符），不是 {@code functionName}：
     * SSE {@code tool.toolKey}、{@code tool_calls.tool_key}、§7.9.1 查询与审计一律记原始 {@code toolKey}。
     */
    private ToolOrchestrator.ToolInvocation toolInvocation(AiToolCall call,
                                                           Map<String, ToolDefinition> functionIndex,
                                                           int round) {
        ToolDefinition definition = functionIndex.get(call.name());
        if (definition == null) {
            log.warn("[SECURITY] 模型返回的函数名不在本次生成的映射表内，按未授权处理："
                    + "functionName={} round={}", call.name(), round);
        }
        String toolKey = definition == null ? call.name() : definition.toolKey();
        return new ToolOrchestrator.ToolInvocation(call.id(), toolKey, call.argumentsJson(), round);
    }

    // ===================== 分片与收敛 =====================

    private void onDelta(StreamSink writer, StringBuilder buffer, AtomicLong lastActivity, String delta) {
        buffer.append(delta);
        lastActivity.set(System.currentTimeMillis());
        // 客户端断开后 writer 自动静默；内容仍继续累积以便落库（EX-015）
        writer.delta(delta);
    }

    /**
     * 思考过程分片：下发 + 独立累积。
     *
     * <p>🔴 <b>刻意不写正文 buffer</b>：{@code buffer} 是落库正文与首轮标题的来源，
     * 思维链进去会污染历史记录、"复制回答"与会话标题（api-spec §5.2 {@code delta.reasoning}）。
     * <p>🔴 <b>但必须刷新 {@code lastActivity}</b>：否则纯推理阶段（可能持续数秒无正文）
     * 会被心跳逻辑判为"长时间无分片"而误发 ping 甚至触发上层空闲判定。
     */
    private void onReasoning(StreamSink writer, StringBuilder reasoningBuffer,
                             AtomicLong lastActivity, String reasoning) {
        reasoningBuffer.append(reasoning);
        lastActivity.set(System.currentTimeMillis());
        writer.reasoning(reasoning);
    }

    private void registerCancelHandle(long assistantId, Closeable handle) {
        cancelRegistry.register(assistantId, handle);
    }

    /**
     * 按轮次累积「思考 / 正文」段落，用于还原时间线。
     *
     * <p>🔴 <b>轮次编号必须与 {@code tool_calls.round} 同源</b>：本累积器从 1 起，
     * 每轮流式结束调用一次 {@link #sealRound()}；而编排主干在同一位置之后才 {@code round++}
     * 并以该值派发工具。于是「段落 N」与「工具 N」天然对齐，
     * 时间线即 {@code 段落(1) → 工具(1) → 段落(2) → 工具(2) → … → 段落(N)}。
     *
     * <p>🔴 <b>非线程安全</b>：与 {@code buffer} 一样只在单条生成线程上访问。
     */
    private static final class SegmentAccumulator {

        private final List<MessageSegment> sealed = new ArrayList<>();
        private final StringBuilder reasoning = new StringBuilder();
        private final StringBuilder text = new StringBuilder();
        private int round = 1;

        void appendReasoning(String delta) {
            reasoning.append(delta);
        }

        void appendText(String delta) {
            text.append(delta);
        }

        /** 定格本轮段落并进入下一轮（🔴 空轮也必须递增轮号，否则与工具轮号错位）。 */
        void sealRound() {
            if (reasoning.length() > 0 || text.length() > 0) {
                sealed.add(new MessageSegment(round, reasoning.toString(), text.toString()));
            }
            reasoning.setLength(0);
            text.setLength(0);
            round++;
        }

        /**
         * 取快照。
         *
         * <p>🔴 <b>会把尚未定格的部分一并封口</b>：上游异常 / 用户停止时当前轮是从
         * {@code stream()} 内部抛出的，来不及走到 {@link #sealRound()} ——
         * 只有在这里补封才不会丢掉最后一段思考。
         */
        List<MessageSegment> snapshot() {
            List<MessageSegment> all = new ArrayList<>(sealed);
            if (reasoning.length() > 0 || text.length() > 0) {
                all.add(new MessageSegment(round, reasoning.toString(), text.toString()));
            }
            return all;
        }
    }

    private void completeSuccessfully(PreparedGeneration prepared, StreamSink writer,
                                      String content, String reasoning,
                                      SegmentAccumulator segments, AiStreamOutcome outcome) {
        long assistantId = prepared.assistantMessageId();
        boolean cancelled = outcome.cancelled()
                || cancelService.isCancelled(prepared.tenantId(), assistantId);

        if (cancelled) {
            chatService.appendStoppedContent(assistantId, content, reasoning, segments.snapshot(),
                    prepared.runtime().model(), outcome.usage());
            chatService.touchConversation(prepared.conversationId());
            writer.done(Message.FINISH_STOPPED, Ids.toStr(assistantId), Message.STATUS_STOPPED, null);
            return;
        }

        String finishReason = outcome.finishReason() == null || outcome.finishReason().isBlank()
                ? Message.FINISH_STOP : outcome.finishReason();
        if ("tool_calls".equals(finishReason)) {
            // 上游把"本轮以工具调用结束"也写进 finish_reason；对终端用户无意义，归一化为 stop
            finishReason = Message.FINISH_STOP;
        }
        chatService.finish(assistantId, content, reasoning, segments.snapshot(),
                Message.STATUS_COMPLETED, finishReason,
                outcome.usage(), prepared.runtime().model(), null);
        chatService.touchConversation(prepared.conversationId());

        String title = applyTitle(prepared);
        refreshSummaryQuietly(prepared);
        writer.done(finishReason, Ids.toStr(assistantId), Message.STATUS_COMPLETED, title);
    }

    private void failGracefully(PreparedGeneration prepared, StreamSink writer, String content,
                                String reasoning, SegmentAccumulator segments,
                                int code, String message, String finishReason) {
        long assistantId = prepared.assistantMessageId();
        boolean cancelled = cancelService.isCancelled(prepared.tenantId(), assistantId);
        if (cancelled) {
            // 用户主动停止导致的读取中断：语义是 stopped，不是失败
            chatService.appendStoppedContent(assistantId, content, reasoning, segments.snapshot(),
                    prepared.runtime().model(), null);
            chatService.touchConversation(prepared.conversationId());
            writer.done(Message.FINISH_STOPPED, Ids.toStr(assistantId), Message.STATUS_STOPPED, null);
            return;
        }
        chatService.finish(assistantId, content, reasoning, segments.snapshot(),
                Message.STATUS_FAILED, finishReason, null,
                prepared.runtime().model(), code);
        chatService.touchConversation(prepared.conversationId());
        writer.error(code, message);
        writer.done(finishReason, Ids.toStr(assistantId), Message.STATUS_FAILED, null);
    }

    private String applyTitle(PreparedGeneration prepared) {
        if (!prepared.needTitle()) {
            return null;
        }
        String candidate = titleGenerator.generate(prepared.firstUserContent());
        if (candidate.isEmpty()) {
            return null;
        }
        // 用户手动改名后返回 empty（applyAutoTitle 内部判定），此时不下发 title
        Optional<String> applied = conversationService.applyAutoTitle(prepared.conversationId(), candidate);
        return applied.orElse(null);
    }

    /**
     * 异步刷新历史摘要：失败只告警，🔴 绝不影响本轮已完成的回答。
     *
     * <p>🔴 不再前置 {@code needsSummary()}：它与 {@code refreshSummary()} 内部用的是
     * <b>同一套</b>窗口解析，前置判断等于把「一次分页查询 + 一次计数」白做两遍。
     * {@code refreshSummary} 自身在无更早内容时直接返回。
     */
    private void refreshSummaryQuietly(PreparedGeneration prepared) {
        try {
            contextAssembler.refreshSummary(prepared.tenantId(), prepared.conversationId());
        } catch (RuntimeException e) {
            log.warn("刷新会话摘要失败，下轮将退化为滑动窗口：conversationId={}", prepared.conversationId());
        }
    }

    private AiChatRequest buildRequest(PreparedGeneration prepared, List<AiMessage> context,
                                      List<ToolDefinition> catalog, GenerationDeadline deadline) {
        // 🔴 §9.5.4 不变量 4 ①：每轮模型调用的有效超时 = min(agent.requestTimeoutSeconds,
        //    remaining − grace)。🔴 Agent 的 requestTimeoutSeconds 语义**收窄固化**为
        //    "单轮模型流式调用上限"，它绝不能再出现在任何"整条流"的计算里（ADR-017 ②）。
        int agentLimit = prepared.runtime().requestTimeoutSeconds();
        int effectiveTimeout = (int) deadline.budgetFor(agentLimit);
        if (effectiveTimeout < agentLimit) {
            log.info("[DEADLINE] 本轮模型超时被生成预算收紧：agentLimit={}s effective={}s "
                            + "remaining={}s grace={}s",
                    agentLimit, effectiveTimeout, deadline.remainingSeconds(),
                    deadline.graceSeconds());
        }
        return new AiChatRequest(
                prepared.runtime().model(),
                context,
                prepared.runtime().temperature(),
                prepared.runtime().maxOutputTokens(),
                effectiveTimeout,
                businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.FIRST_TOKEN_TIMEOUT_SECONDS),
                catalog);
    }

    /**
     * 🔴 <b>生成预算耗尽的统一收敛</b>（ADR-017 ④，🔴 <b>零新错误码</b>）。
     *
     * <pre>
     * error(code=50002, message="本次生成已超时，请重试") + done(finishReason=timeout, status=failed)
     * </pre>
     *
     * <p>🔴 <b>为什么复用 {@code 50002} 而不登记新码</b>：M1 起"整体超时 / 首字超时"就是
     * {@code AiStreamException.timeout → 50002 + finishReason=timeout}（EX-014），
     * 前端与 @测试 的展示与断言口径已固化；"预算耗尽"与"上游不回"对用户是<b>同一件事</b>
     * （本次生成没能在时限内完成、可重试），前端动作完全一致。
     *
     * <p>🔴 <b>代价与缓解</b>：{@code 50002} 的告警计数会混入"预算耗尽"，因此本路径的日志
     * <b>强制</b>带 {@code [DEADLINE]} 前缀并记 deadline / elapsed / remaining / round / 原因，
     * 供运维区分（ADR-017 ④ 的明文要求）。
     *
     * <p>🔴 已生成的正文与 reasoning <b>必须落库保留</b>（EX-015）——
     * 由 {@link #failGracefully} 承担；{@code tool_calls} 的非终态由 {@code run()} 的
     * finally 统一收敛为 {@code cancelled}（不变量 3）。
     */
    private void convergeOnDeadline(PreparedGeneration prepared, StreamSink writer,
                                    StringBuilder buffer, StringBuilder reasoningBuffer,
                                    SegmentAccumulator segments, GenerationDeadline deadline,
                                    int round, String cause) {
        log.warn("[DEADLINE] 🔴 单次生成预算耗尽，按超时收敛（error 50002 + done timeout）："
                        + "messageId={} deadlineSeconds={} graceSeconds={} elapsedSeconds={} "
                        + "remainingSeconds={} round={} cause={}",
                prepared.assistantMessageId(), deadline.deadlineSeconds(), deadline.graceSeconds(),
                deadline.elapsedSeconds(), deadline.remainingSeconds(), round, cause);
        failGracefully(prepared, writer, buffer.toString(), reasoningBuffer.toString(), segments,
                ErrorCode.UPSTREAM_UNAVAILABLE, GENERATION_TIMEOUT_MESSAGE, Message.FINISH_TIMEOUT);
    }

    /**
     * 心跳：无分片超过 {@code chat.stream_heartbeat_seconds} 时发送注释帧，
     * 防止中间代理因空闲而断开长连接（api-spec §5.1）。
     *
     * <p>🔴 <b>等待用户确认期间心跳不得停</b>（§5.1 末条）：确认最长
     * {@code tool.confirm_wait_seconds}（默认 120s）远超代理空闲阈值。
     * 本心跳跑在 {@link StreamWatchdog} 的独立调度线程上，
     * 因此生成线程挂在确认等待时它<b>照常发 {@code : ping}</b>。
     */
    private ScheduledFuture<?> startHeartbeat(StreamSink writer, AtomicLong lastActivity) {
        long heartbeatSeconds = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.STREAM_HEARTBEAT_SECONDS);
        return watchdog.scheduleHeartbeat(() -> {
            if (System.currentTimeMillis() - lastActivity.get() >= heartbeatSeconds * 1000L) {
                writer.ping();
            }
        }, heartbeatSeconds);
    }

    /**
     * 用于幂等回放：把既有 assistant 消息作为一次性流回放给客户端（EX-013）。
     */
    public void replay(StreamSink writer, String conversationId, String userMessageId, Message assistant) {
        try {
            if (assistant.getContent() != null && !assistant.getContent().isEmpty()) {
                writer.delta(assistant.getContent());
            }
            String finishReason = assistant.getFinishReason() == null || assistant.getFinishReason().isBlank()
                    ? Message.FINISH_STOP : assistant.getFinishReason();
            writer.done(finishReason, Ids.toStr(assistant.getId()), assistant.getStatus(), null);
            log.info("幂等回放已完成：messageId={} conversationId={} userMessageId={}",
                    assistant.getId(), conversationId, userMessageId);
        } finally {
            writer.complete();
        }
    }
}
