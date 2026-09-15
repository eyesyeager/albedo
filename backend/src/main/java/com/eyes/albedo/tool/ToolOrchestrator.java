package com.eyes.albedo.tool;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.audit.AuditContext;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditWriteException;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.dto.ToolExecutionResult;
import com.eyes.albedo.tool.dto.ToolProgress;
import com.eyes.albedo.tool.entity.ToolCall;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 一次工具调用的<b>全部判定与执行</b>（🔴 architecture.md §9.5.1 时序图的落地实现）。
 *
 * <p><b>REQ-TOL-002 / REQ-MCP-003 · AC-TOL-002 / AC-TOL-003 / AC-MCP-003~006 / AC-CHAT-007</b>
 *
 * <p>🔴 <b>强制校验顺序（api-spec §7.6.3，顺序不可调整）</b>：
 * <pre>
 * ① 租户上下文（由调用方以快照显式传入，缺失即拒绝执行）
 * ② 绑定 / 授权            → 30050 + 审计 tool.grant_denied（与状态流转同事务）
 * ③ SSRF 运行时兜底        → 30050 + 审计 mcp.ssrf_rejected（🔴 在 pending 阶段，否则状态机不允许 denied）
 * ④ JSON Schema 校验       → 30053，🔴 不发起任何执行
 * ⑤ 🔴 执行前授权点查      → 置 running **之前**再查一次（V1.1.4 #4；≤1 次、禁缓存）
 * ⑥ 轮次上限               → 由 chat/ChatStreamRunner 持有（它才知道"本次生成"的边界）
 * </pre>
 *
 * <p>🔴 <b>⑤ 为什么不能省（V1.1.4 #4 裁决：订正实现缺口，非新增要求）</b>：
 * ② 发生在<b>清单构造时</b>，而清单构造 → 工具执行之间横跨整轮生成
 * （最多 {@code tool.max_rounds} 轮），最坏数分钟。
 * 只信清单 = "DBA 撤授权后工具仍可被执行数分钟"，直接违反明文 AC-MCP-004。
 * 实现见 {@link ToolGrantPointCheck}（残余窗口 = 单次执行时长，已登记 AR-017）。
 *
 * <p>🔴 <b>⑤ 的复查范围（api-spec V1.1.5 G-2 裁决框 / AR-019 追认现状，注释即契约）</b>：
 * <pre>
 * ✅ 复查（授权列 / 启用列，共 4 列）：
 *      · mcp_tools.granted        / tenant_tool_grants.granted   —— 授权位
 *      · mcp_tools.status         / local_tools.status           —— 工具启用位
 *      · mcp_servers.status                                      —— MCP 服务启用位
 *      · mcp_servers.deleted_at IS NULL                          —— 🔴 只判 mcp_servers 这一张
 *        （G-1：mcp_tools / tenant_tool_grants / local_tools **无** deleted_at 列，
 *         不得为对齐旧文字给它们加列）
 * ❌ <b>不含绑定</b>：agent_capability_bindings 🔴 **不复查** ——
 *      绑定是"本轮生成期的快照"（清单构造时判定一次），生成中解绑只在
 *      <b>下一次提问</b>生效（AR-019 已登记为契约，@测试 不判缺陷）。
 *      理由：清单已发给模型，中途抽掉绑定会破坏"生成期清单自洽性"，
 *      让模型请求一个它刚刚还看得见的工具却被拒，且无法与撤授权区分。
 * 🔴 预算 ≤1 次查询（两个分支各自单条 JOIN 点查）、🔴 禁缓存、🔴 fail-closed；
 * 🔴 严禁"顺手加强"为复查绑定 —— 那既破坏上述自洽性，又必然把查询预算推到 2 次，
 *    会被 @测试 G-4 断言与 §7.1.2 查询次数表同时判缺陷。
 * 👉 紧急止血的唯一正确姿势 = 撤 granted 或置 status 停用（下一次执行即 30050），
 *    **不是**解绑 agent_capability_bindings。
 * </pre>
 *
 * <p>🔴 <b>三段式事务纪律（ADR-010，最高危）</b>：
 * <pre>
 * ① 短事务：pending / running 各自提交
 * ② 🔴 事务外：工具执行（网络调用）
 * ③ 短事务：终态 + 审计同事务提交
 * 🔴 本类整体**无 @Transactional**，且不注入任何 Repository ——
 *    从依赖层面杜绝"执行期间持有 tool_calls 行锁"，
 *    且低并发单测测不出来（由 TransactionDisciplineScanTest 静态守护）。
 *    🔴 执行前授权点查同样<b>不直接注入 Repository</b>，而是委托 {@link ToolGrantPointCheck}
 *    （它是无事务的单条 count 查询），保持本类"零 Repository"的结构性保证。
 * </pre>
 *
 * <p>🔴 <b>失败不抛异常给调用方</b>：任何失败都必须变成一个 {@code tool}(终态) 帧 +
 * 一段可回灌模型的说明（{@link ToolDispatch#feedback()}），流才能继续收敛到 {@code done}。
 */
@Slf4j
@Service
public class ToolOrchestrator {

    /**
     * 🔴 <b>{@code 30050}（安全拒绝）回灌模型的唯一措辞</b>（api-spec §7.6.4 / ADR-018 ③）。
     *
     * <p>🔴 <b>三种来源必须逐字一致</b>：不在清单内（未授权 / 未绑定）、preflight 判定服务已停用、
     * 执行前授权点查判拒、执行期竞态 —— 🔴 <b>严禁差异化</b>：措辞差异本身就是一条
     * <b>探测平台配置的信道</b>（模型/用户可据此推断"是我没授权还是服务停了"）。
     * 由 {@link ToolFeedbackDiagnosticTest} 反向断言守护。
     */
    private static final String DENIED_FEEDBACK = "该工具当前不可用（未授权或已停用），请勿重试";

    /**
     * 一次生成内的编排上下文（🔴 全部为<b>快照值</b>，异步段禁止读 ThreadLocal，§9.5.2）。
     *
     * @param tenantId       租户号
     * @param uid            用户 uid
     * @param conversationId 会话 ID
     * @param messageId      assistant 消息 ID
     * @param catalog        本次生成构造的工具清单（🔴 模型可见范围的唯一来源）
     * @param auditContext   审计上下文（由 Servlet 线程抓取后显式传入）
     * @param listener       状态流转回调（由 chat 翻译成 SSE {@code tool} 帧）
     * @param agentVersionId 🔴 本次生成绑定的 Agent 版本 ID，透传给
     *                       {@code ToolExecutionRequest} → {@code LocalToolInvocation}，
     *                       供 {@code skill_load} 回查绑定的 Skill（其余工具不使用）
     */
    public record ToolRunContext(String tenantId,
                                 long uid,
                                 long conversationId,
                                 long messageId,
                                 List<ToolDefinition> catalog,
                                 AuditContext auditContext,
                                 ToolProgressListener listener,
                                 long agentVersionId) {

        /** 兼容构造（🔴 {@code agentVersionId} 缺省为 {@code 0}，供既有调用方/单测使用）。 */
        public ToolRunContext(String tenantId, long uid, long conversationId, long messageId,
                              List<ToolDefinition> catalog, AuditContext auditContext,
                              ToolProgressListener listener) {
            this(tenantId, uid, conversationId, messageId, catalog, auditContext, listener, 0L);
        }
    }

    /**
     * 模型请求的一次工具调用。
     *
     * @param providerCallId 上游给出的 {@code tool_call.id}（幂等键的一部分）
     * @param toolKey        解析后的工具键（🔴 解析不出时传模型给的原始名，交由授权判定拒绝）
     * @param argumentsJson  模型给出的入参 JSON
     * @param round          第几轮（从 1 计）
     */
    public record ToolInvocation(String providerCallId, String toolKey, String argumentsJson,
                                 int round) {
    }

    /**
     * 编排结论（供 chat 回灌模型并继续下一轮）。
     *
     * @param toolCallId 工具调用 ID（string）
     * @param status     终态
     * @param errorCode  失败码（成功为 null）
     * @param feedback   🔴 回灌模型的 {@code role=tool} 内容（已脱敏 + 字节截断）
     * @param truncated  结果是否被字节截断
     */
    public record ToolDispatch(String toolCallId, String status, Integer errorCode,
                               String feedback, boolean truncated) {

        public boolean succeeded() {
            return ToolCall.STATUS_SUCCEEDED.equals(status);
        }

        /** 用户拒绝 / 未授权 / 确认超时（决定 {@code done.finishReason=tool_denied} 的候选）。 */
        public boolean denied() {
            return ToolCall.STATUS_DENIED.equals(status)
                    || (ToolCall.STATUS_TIMED_OUT.equals(status)
                    && errorCode != null && errorCode == ErrorCode.TOOL_DENIED);
        }

        public boolean cancelled() {
            return ToolCall.STATUS_CANCELLED.equals(status);
        }
    }

    private final ToolCatalogService catalogService;
    private final ToolAuthorizationService authorizationService;
    private final ToolGrantPointCheck grantPointCheck;
    private final ToolArgsValidator argsValidator;
    private final ToolExecutorRegistry executorRegistry;
    private final ToolCallRecorder recorder;
    private final ToolSummaryScrubber scrubber;
    private final ToolResultTruncator truncator;

    public ToolOrchestrator(ToolCatalogService catalogService,
                           ToolAuthorizationService authorizationService,
                           ToolGrantPointCheck grantPointCheck,
                           ToolArgsValidator argsValidator,
                           ToolExecutorRegistry executorRegistry,
                           ToolCallRecorder recorder,
                           ToolSummaryScrubber scrubber,
                           ToolResultTruncator truncator) {
        this.catalogService = catalogService;
        this.authorizationService = authorizationService;
        this.grantPointCheck = grantPointCheck;
        this.argsValidator = argsValidator;
        this.executorRegistry = executorRegistry;
        this.recorder = recorder;
        this.scrubber = scrubber;
        this.truncator = truncator;
    }

    /**
     * 编排一次工具调用（🔴 永不抛异常，失败以 {@link ToolDispatch} 承载）。
     */
    public ToolDispatch dispatch(ToolRunContext ctx, ToolInvocation invocation) {
        // 🔴 摘要三步顺序不可颠倒：脱敏 → 截断 → 落库/下发（api-spec §5.4.3 第 1 条）
        String argsSummary = truncator.truncateArgsSummary(
                scrubber.argsSummary(invocation.argumentsJson()));
        Optional<ToolDefinition> found = catalogService.find(ctx.catalog(), invocation.toolKey());

        // ② 绑定 / 授权：未在清单内 → denied(30050) + 审计（同一短事务）
        if (found.isEmpty()) {
            return denyUnauthorized(ctx, invocation, argsSummary);
        }
        ToolDefinition definition = found.get();
        ToolCall call = recorder.recordPending(ctx.conversationId(), ctx.messageId(),
                invocation.providerCallId(), invocation.round(), definition);
        emit(ctx, definition, call, ToolCall.STATUS_PENDING, invocation.round(), argsSummary, "",
                false, null);

        ToolExecutor.ToolExecutionRequest request = new ToolExecutor.ToolExecutionRequest(
                ctx.tenantId(), ctx.uid(), definition, invocation.argumentsJson(),
                ctx.agentVersionId());

        // ③ SSRF / 服务状态运行时兜底（🔴 仍处 pending，故 denied 是合法流转）
        try {
            executorRegistry.preflight(request);
        } catch (BusinessException e) {
            // 🔴 审计路由按 payload 里的拒绝原因（api-spec §7.6.3 第 3/4 步分工，禁止靠 message 文案判断）：
            //    GRANT_REVOKED（服务已停用/不存在）→ 本方法与状态流转同事务补写 tool.grant_denied（C1）
            //    SSRF_REJECTED               → mcp.ssrf_rejected 已由 SsrfGuard 写入，🔴 不重复写
            AuditEvent audit = e.getPayload() == ToolExecutionResult.DeniedCause.GRANT_REVOKED
                    ? authorizationService.grantDeniedEvent(ctx.tenantId(), definition.toolKey())
                    : null;
            // 🔴 措辞必须与"不在清单内"逐字一致（ADR-018 ③：30050 严禁按来源差异化）
            return terminal(ctx, definition, call, invocation.round(), argsSummary,
                    ToolCall.STATUS_DENIED, e.getCode(), DENIED_FEEDBACK, null, audit);
        }

        // ④ 入参 Schema 校验（🔴 失败不发起执行）
        try {
            argsValidator.validate(definition.toolKey(), definition.inputSchema(),
                    invocation.argumentsJson());
        } catch (BusinessException e) {
            // 🔴 ADR-018 ③：把**校验器的字段级诊断**如实回灌（它是"我们自己的校验器对参数说的话"，
            //    不含任何外部信息）—— 固定措辞让模型"知道失败"，诊断让它"知道改哪个字段"
            return terminal(ctx, definition, call, invocation.round(), argsSummary,
                    ToolCall.STATUS_FAILED, e.getCode(),
                    feedback(e.getCode(), e.getMessage()), null);
        }

        // ⑤ 🔴 执行前授权点查（api-spec §7.6.3 契约表，V1.1.4 #4；范围见类注释「⑤ 的复查范围」）：
        //    置 running **之前**再查一次，≤1 次查询、禁缓存 ——
        //    把"撤授权生效点"从"下一次生成"压到"下一次执行"。
        //    🔴 复查范围仅授权/启用列（granted、status、mcp_servers.status/deleted_at）；
        //    🔴 **不含** agent_capability_bindings（绑定 = 生成期快照，AR-019），禁止改写为复查绑定。
        if (!grantPointCheck.stillGranted(ctx.tenantId(), definition)) {
            return denyRevokedBeforeExecution(ctx, definition, call, invocation, argsSummary);
        }

        recorder.markRunning(call.getId(), argsSummary);
        emit(ctx, definition, call, ToolCall.STATUS_RUNNING, invocation.round(), argsSummary, "",
                false, null);

        // ⑦ 执行（🔴 事务外）
        ToolExecutionResult result = execute(request);
        String resultSummary = truncator.truncateResultSummary(
                scrubber.resultSummary(result.content()));

        if (ToolCall.STATUS_SUCCEEDED.equals(result.status())) {
            recorder.markSucceeded(call.getId(), resultSummary, result.truncated());
            emit(ctx, definition, call, ToolCall.STATUS_SUCCEEDED, invocation.round(), argsSummary,
                    resultSummary, result.truncated(), null);
            return new ToolDispatch(Ids.toStr(call.getId()), ToolCall.STATUS_SUCCEEDED, null,
                    result.content(), result.truncated());
        }
        // 🔴 执行期安全拒绝（api-spec V1.1.3 §7.8.1 ③）：running → denied 是**合法**流转，
        //    errorCode 恒 30050，🔴 不做任何向 failed/30052 的归一化。
        if (ToolCall.STATUS_DENIED.equals(result.status())) {
            return deniedDuringExecution(ctx, definition, call, invocation, argsSummary,
                    resultSummary, result.deniedCause());
        }
        // 失败终态：status 由执行器给出（failed / timed_out）
        // 🔴 ADR-018 ③：可回灌的码（30057 / 30053）把**执行器已放进 result.content() 的上游诊断**
        //    一并回灌（它已过 truncateForModel，与成功路径同一条链）；其余码原样固定措辞。
        return terminal(ctx, definition, call, invocation.round(), argsSummary, result.status(),
                result.errorCode(), feedback(result.errorCode(), result.content()), resultSummary);
    }

    // ===================== 各分支实现 =====================

    /**
     * 未授权 / 未绑定 / 已停用（🔴 {@code 30050} + 审计 {@code tool.grant_denied} 同事务）。
     *
     * <p>🔴 这里<b>先落 pending 再流转 denied</b>，而不是"直接插一条 denied"：
     * 前端必须能按 {@code toolCallId} 原位更新卡片，而 {@code toolCallId} 只有落库后才存在；
     * 同时 §7.11.1 的 {@code toolCallCount} 口径是"模型请求调用工具的<b>总次数</b>"，
     * 包含被拒绝的调用 —— 不落库就会漏计。
     */
    private ToolDispatch denyUnauthorized(ToolRunContext ctx, ToolInvocation invocation,
                                          String argsSummary) {
        log.warn("[SECURITY] 模型请求了不在清单内的工具，拒绝执行：toolKey={} round={}",
                invocation.toolKey(), invocation.round());
        ToolDefinition placeholder = deniedPlaceholder(invocation.toolKey());
        ToolCall call = recorder.recordPending(ctx.conversationId(), ctx.messageId(),
                invocation.providerCallId(), invocation.round(), placeholder);
        emit(ctx, placeholder, call, ToolCall.STATUS_PENDING, invocation.round(), argsSummary, "",
                false, null);
        AuditEvent audit = authorizationService.grantDeniedEvent(ctx.tenantId(),
                invocation.toolKey());
        return terminal(ctx, placeholder, call, invocation.round(), argsSummary,
                ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED,
                DENIED_FEEDBACK, null, audit);
    }

    /**
     * 🔴 <b>执行前授权点查未通过</b>（api-spec §7.6.3 契约表「失败处置」行，V1.1.4 #4）。
     *
     * <p>🔴 行仍是 pending → **pending → denied**，errorCode 恒 30050 + tool.grant_denied 审计。
     *
     * <p>🔴 审计与状态流转在<b>同一个独立短事务</b>内提交（ADR-010 / §7.14 第三行）：
     * 事件由 {@link ToolAuthorizationService#grantDeniedEvent} <b>只构造不写入</b>，
     * 交给 {@link ToolCallRecorder#markTerminal} 落库 —— 🔴 绝不能"先写审计再改状态"，
     * 那会出现"审计有、状态无"的不一致。
     *
     * <p>🔴 回灌措辞与"不在清单内"完全一致：模型不需要（也不应该）知道
     * 是撤授权、服务停用还是平台下架 —— 差异化措辞等于给模型/用户一条探测平台配置的信道。
     */
    private ToolDispatch denyRevokedBeforeExecution(ToolRunContext ctx, ToolDefinition definition,
                                                    ToolCall call, ToolInvocation invocation,
                                                    String argsSummary) {
        log.warn("[SECURITY] 执行前授权点查判定拒绝（撤授权 / 停用即刻生效）："
                        + "toolType={} toolKey={} round={} → denied + 30050",
                definition.toolType(), definition.toolKey(), invocation.round());
        AuditEvent audit = authorizationService.grantDeniedEvent(ctx.tenantId(),
                definition.toolKey());
        return terminal(ctx, definition, call, invocation.round(), argsSummary,
                ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED,
                DENIED_FEEDBACK, null, audit);
    }

    /**
     * 执行工具（🔴 事务外；执行器不抛异常，配置类异常兜底为失败结果）。
     */
    private ToolExecutionResult execute(ToolExecutor.ToolExecutionRequest request) {
        try {
            return executorRegistry.execute(request);
        } catch (BusinessException e) {
            // 例如工具类型非法（30060）：不能让它冒泡中断整条 SSE 流
            log.warn("工具执行前置配置异常：toolKey={} code={}", request.definition().toolKey(),
                    e.getCode());
            return new ToolExecutionResult(ToolCall.STATUS_FAILED, e.getCode(), "", false, 0L);
        } catch (RuntimeException e) {
            log.error("工具执行未预期异常：toolKey={}", request.definition().toolKey(), e);
            return new ToolExecutionResult(ToolCall.STATUS_FAILED, ErrorCode.TOOL_EXECUTION_FAILED,
                    "", false, 0L);
        }
    }

    /**
     * 写失败/终态 + 可选审计（🔴 审计失败 → 该次调用记 {@code 50003}，但<b>绝不中断流</b>，ADR-010）。
     */
    private ToolDispatch terminal(ToolRunContext ctx, ToolDefinition definition, ToolCall call,
                                  int round, String argsSummary, String status, Integer errorCode,
                                  String feedback, String resultSummary) {
        return terminal(ctx, definition, call, round, argsSummary, status, errorCode, feedback,
                resultSummary, null);
    }

    private ToolDispatch terminal(ToolRunContext ctx, ToolDefinition definition, ToolCall call,
                                  int round, String argsSummary, String status, Integer errorCode,
                                  String feedback, String resultSummary, AuditEvent audit) {
        Integer finalCode = errorCode;
        try {
            recorder.markTerminal(call.getId(), status, errorCode, resultSummary, audit,
                    ctx.auditContext());
        } catch (AuditWriteException e) {
            // 🔴 审计失败 → 状态与审计一起回滚了；改记 50003 再写一次（无审计），流继续收敛
            log.error("[SECURITY] 流式内安全事件审计写入失败，该次工具调用判失败：toolCallId={}",
                    call.getId(), e);
            finalCode = ErrorCode.INTERNAL_ERROR;
            safeTerminal(call.getId(), status, finalCode, resultSummary);
        } catch (BusinessException e) {
            // 状态机拒绝（例如已被 stop 收敛为 cancelled）：以库内既有终态为准，不覆盖
            log.warn("工具调用终态写入被状态机拒绝（以既有终态为准）：toolCallId={} target={} code={}",
                    call.getId(), status, e.getCode());
        }
        emit(ctx, definition, call, status, round, argsSummary,
                resultSummary == null ? "" : resultSummary, false, finalCode);
        return new ToolDispatch(Ids.toStr(call.getId()), status, finalCode, feedback, false);
    }

    private void safeTerminal(Long toolCallId, String status, Integer errorCode,
                              String resultSummary) {
        try {
            recorder.markTerminal(toolCallId, status, errorCode, resultSummary, null, null);
        } catch (RuntimeException e) {
            log.error("工具调用终态兜底写入仍失败：toolCallId={}（流结束时会由 finally 收敛）",
                    toolCallId);
        }
    }

    /**
     * 下发一帧（🔴 回调实现不得抛异常；这里再兜一层，保证工具链路不被写帧失败带崩）。
     */
    private void emit(ToolRunContext ctx, ToolDefinition definition, ToolCall call, String status,
                      int round, String argsSummary, String resultSummary, boolean truncated,
                      Integer errorCode) {
        if (ctx.listener() == null) {
            return;
        }
        try {
            ctx.listener().onProgress(new ToolProgress(Ids.toStr(call.getId()),
                    definition.toolType(), definition.toolKey(), status,
                    round, argsSummary, resultSummary, truncated, errorCode, null));
        } catch (RuntimeException e) {
            log.warn("下发工具进度帧失败（不影响工具执行）：toolCallId={} status={}",
                    call.getId(), status);
        }
    }

    /**
     * 执行器返回 {@code denied} 的<b>竞态</b>处置（🔴 api-spec V1.1.3 §7.8.1 ③ 裁决）。
     *
     * <pre>
     * 场景：确认/授权校验通过 → 行已置 running → 执行器发起调用时 DBA 刚好改库
     *      （停用 MCP 服务 / 撤销工具授权 / 把 endpoint 改成内网地址），运行时兜底判定拒绝。
     * 🔴 该竞态是**架构必然**而非实现瑕疵：§7.6.3 第 4 步的 SSRF 重校验发生在
     *    architecture.md §9.5.1 的 tool_calls → running **之后**（先置 running 再执行）。
     *
     * 🔴 处置 = running → denied（errorCode=30050），**已删除**原先"归一化为 failed + 30052"的分支：
     *   ① 语义不能失真：撤授权 = 授权拒绝、改内网地址 = SSRF 拒绝，两者都是**安全拒绝**。
     *      30052 的原义是 MCP 连接/传输/协议/上游鉴权失败 —— 把安全事件伪装成上游故障，
     *      会让 DBA 拿着 30052 去查网络，永远查不到"是我撤了授权"；
     *   ② §7.11.1 的互斥不变量按 **status** 二分（status='denied' → 只计 toolDeniedCount），
     *      新增一条**进入** denied 的路径不改变任何聚合公式；反而归一化成 failed 才会让
     *      被撤授权的调用错计进 toolFailedCount（口径失真）。
     *
     * 审计路由（零新增 action）：
     *   GRANT_REVOKED  → tool.grant_denied（本方法与状态流转**同一短事务**补写）
     *   SSRF_REJECTED  → mcp.ssrf_rejected（🔴 已由 mcp/SsrfGuard 写入，此处**不重复写**）
     * </pre>
     */
    private ToolDispatch deniedDuringExecution(ToolRunContext ctx, ToolDefinition definition,
                                               ToolCall call, ToolInvocation invocation,
                                               String argsSummary, String resultSummary,
                                               ToolExecutionResult.DeniedCause cause) {
        log.warn("[SECURITY] 工具执行期被安全拒绝（preflight 之后的改库竞态）："
                + "toolKey={} cause={} → denied + 30050", definition.toolKey(), cause);
        AuditEvent audit = cause == ToolExecutionResult.DeniedCause.GRANT_REVOKED
                ? authorizationService.grantDeniedEvent(ctx.tenantId(), definition.toolKey())
                : null;
        return terminal(ctx, definition, call, invocation.round(), argsSummary,
                ToolCall.STATUS_DENIED, ErrorCode.TOOL_DENIED,
                DENIED_FEEDBACK, resultSummary, audit);
    }

    /**
     * 回灌模型的失败说明（🔴 ADR-018 ③ / api-spec §7.6.4「诊断来源二分」）。
     *
     * <p>🔴 <b>二分判据不是"是否失败"，而是"这句诊断是谁说的"</b>：
     * <pre>
     * ✅ 可回灌（上游工具自身的业务语义 / 我们自己的入参校验器）：
     *    · 30057（result.isError=true）      → 固定措辞 + **上游错误正文**
     *    · 30053（本地 JSON Schema 校验失败）→ 固定措辞 + **校验器字段级诊断**
     *    · 30053（MCP JSON-RPC -32602）      → 固定措辞 + **上游 error.message**
     * ❌ 不可回灌（平台/传输侧诊断，可能含 endpoint / 内网地址 / 堆栈 / 凭据线索）：
     *    · 30052 / 30051 / 30056 / 30050 / 50003 → 🔴 **原样固定措辞**
     *      （🔴 30050 的三种来源——不在清单内 / 撤授权 / SSRF——措辞必须**完全一致**，
     *       差异化即给出一条探测平台配置的信道）
     * </pre>
     *
     * <p>🔴 <b>为什么必须回灌诊断</b>（BUG-MCP-001 直接成因）：执行器在 {@code isError=true}
     * 分支<b>已经</b>把上游错误正文放进了 {@code ToolExecutionResult.content}（并已过
     * {@code truncateForModel}），而编排层此前一律用固定措辞覆盖 → 这段可用诊断被<b>丢弃</b> →
     * 模型只知道"失败了"、不知道"哪个参数非法"，只能换写法反复重试（实测连续三轮、
     * 每轮都要用户再确认一次）。<b>成功</b>路径回灌的就是同一个 content ——
     * 同一条链失败路径丢内容，属实现不一致。
     *
     * <p>🔴 <b>不引入新泄露面</b>：可回灌的内容与成功结果走<b>同一条</b>处理链
     * （脱敏 → {@code tool.result_max_bytes} 字节截断）；摘要仍按
     * {@code tool.result_summary_max_chars} 单独取值（ADR-011 第 3 条两类截断严格分离不受影响）。
     *
     * @param errorCode      终态错误码
     * @param upstreamDetail 上游 / 校验器诊断（🔴 只有可回灌的码会用到它）
     */
    private String feedback(Integer errorCode, String upstreamDetail) {
        String fixed = fixedFeedback(errorCode);
        if (!relayableDiagnostic(errorCode) || upstreamDetail == null || upstreamDetail.isBlank()) {
            return fixed;
        }
        String detail = upstreamDetail.strip();
        // 🔴 同义重复保护：本地执行器在部分分支给的 content 就是平台固定措辞，
        //    拼成"工具执行返回失败，失败说明：工具执行失败"只会浪费 token 且毫无诊断价值
        if (detail.equals(fixed) || detail.equals(ErrorCode.defaultMessage(errorCode))) {
            return fixed;
        }
        return fixed + "，失败说明：" + detail;
    }

    /**
     * 该错误码的诊断是否<b>来自上游工具或校验器</b>（🔴 = 是否允许回灌，api-spec §7.6.4）。
     *
     * <p>🔴 严禁把 {@code 30052 / 30051 / 30056 / 30050 / 50003} 加进来：
     * 它们是<b>我们对基础设施</b>的诊断（连接失败 / DNS / TLS / 401 / 超时 / 安全拒绝 / 内部错误），
     * 可能含 endpoint、内网地址、异常类名。
     */
    private boolean relayableDiagnostic(Integer errorCode) {
        return errorCode != null
                && (errorCode == ErrorCode.TOOL_EXECUTION_FAILED
                || errorCode == ErrorCode.TOOL_ARGS_INVALID);
    }

    /**
     * 各错误码的<b>固定措辞</b>（🔴 不含任何上游内容；🔴 {@code 30050} 恒为同一句）。
     */
    private String fixedFeedback(Integer errorCode) {
        if (errorCode == null) {
            return "工具执行失败";
        }
        return switch (errorCode) {
            case ErrorCode.TOOL_TIMEOUT -> "工具执行超时，未获得结果";
            case ErrorCode.TOOL_RETRY_BLOCKED ->
                    "工具结果未知且不可自动重试，请勿重复调用该工具";
            case ErrorCode.MCP_UNAVAILABLE -> "工具服务当前不可用";
            case ErrorCode.TOOL_ARGS_INVALID -> "工具入参不符合约定，请修正参数后重试";
            case ErrorCode.TOOL_EXECUTION_FAILED -> "工具执行返回失败";
            case ErrorCode.INTERNAL_ERROR -> "工具调用未能完成";
            default -> "工具执行失败";
        };
    }

    /**
     * 未授权工具的占位定义（🔴 只为落库与下发帧提供最小信息）。
     */
    private ToolDefinition deniedPlaceholder(String toolKey) {
        return ToolDefinition.of(ToolDefinition.TYPE_LOCAL, toolKey, toolKey, "", null, "",
                false, 1, null, null);
    }
}
