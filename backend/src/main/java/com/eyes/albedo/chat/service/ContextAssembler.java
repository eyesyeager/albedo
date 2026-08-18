package com.eyes.albedo.chat.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.repository.MessageRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.SystemPromptBudget;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.common.ViolationRules;
import com.eyes.albedo.skill.SkillInjectionService;
import com.eyes.albedo.skill.dto.SkillInjectionFragment;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上下文组装（REQ-CHAT-004，策略 {@code summary_then_window}）。
 *
 * <p><b>组装顺序</b>（🔴 <b>单一前导 system 不变量</b>，ADR-019 ① / api-spec §7.5.2）：
 * <pre>
 *   唯一那条 system 消息（🔴 至多 1 条且必须位于 index 0）
 *     ① 租户段：agent_versions.system_prompt → Skill instruction → Skill output_constraint
 *     ② 历史摘要块（若有更早内容且摘要已缓存）
 *     ③ 平台工具调用纪律段（仅当本次确实下发 tools，🔴 恒为最后一块）
 *   然后才是最近消息窗口（user/assistant）→ 本轮工具链（由 ChatStreamRunner 追加）
 * </pre>
 * 🔴 <b>为什么必须合并成一条</b>：真实上游（混元 OpenAI 兼容接口）对 {@code messages} 的
 * {@code system} 形态有硬约束 —— 多于 1 条或不在 {@code index 0} 即 {@code status=400}
 * 「system 角色必须位于列表的最开始」，本次生成在<b>进入工具调用之前</b>就失败
 * （BUG-MCP-004；摘要块此前作为第 2/3 条 system 注入，属同源既有隐患，一并订正）。
 * 🔴 <b>物理合并 ≠ 预算合并</b>：{@code 30060} 的判定对象恒为 ①（租户段），
 * 与最终 system 消息的物理长度无关（②③ 不计入，见 ADR-019 ③）。
 *
 * <p><b>🔴 总输入长度由三段各自有界的预算共同封顶</b>（V1.1.9）：
 * <pre>
 *   system 提示   ≤ chat.system_prompt_max_chars     （超限 30060，禁止截断）
 *   更早内容摘要   ≤ chat.context_summary_max_chars   （确定性生成，天然有界）
 *   最近消息窗口   ≤ chat.context_max_chars           （从最新往回填，超预算即停）
 * </pre>
 * 🔴 <b>为什么窗口必须有长度预算、光有条数不够</b>：
 * {@code chat.message_max_chars}（20000）× {@code chat.context_max_messages}（20）
 * = 40 万字符，远超任何模型的上下文窗口。缺预算时，一个聊过长内容的会话会让上游
 * 拒绝<b>整个请求</b>，而窗口每轮都会重新纳入同样的超长历史 —— 表现为
 * <b>该会话永久不可用</b>（重试多少次都失败，用户只能新建会话）。这是多轮场景下
 * 最严重的失败模式，且完全不可自愈。
 *
 * <p>关键设计取舍：
 * <ul>
 *   <li>🔴 <b>装配只做一次分页查询 + 一次计数</b>，禁止 N+1（性能红线）</li>
 *   <li>🔴 <b>窗口从最新往回填充</b>而非"取 N 条再截断"：越近的轮次对当前回答越关键，
 *       预算耗尽时必须先保住最近的对话</li>
 *   <li>🔴 <b>最新一条无条件保留</b>：它是本轮用户提问，丢了模型根本不知道要回答什么</li>
 *   <li>🔴 <b>窗口不得以 assistant 开头</b>：那是截断产生的"无问题的回答"，是纯噪声</li>
 *   <li>摘要是<b>异步产物</b>：本轮只使用「已缓存的摘要」，没有就退化为确定性滑动窗口，
 *       🔴 绝不为了摘要而阻断/延迟本轮回答（REQ-CHAT-004 明确要求）</li>
 *   <li>摘要生成失败 → 缓存里就是没有摘要 → 自动是滑动窗口，无需额外降级分支</li>
 *   <li>只纳入已定稿消息（{@code sent/completed/stopped}）：
 *       把 {@code failed}/{@code streaming} 内容塞进上下文会让模型基于半成品推理</li>
 * </ul>
 */
@Slf4j
@Service
public class ContextAssembler {

    private static final Duration SUMMARY_TTL = Duration.ofHours(12);

    /**
     * 摘要回看的<b>查询安全上界</b>（倍数，作用于窗口条数）。
     *
     * <p>🔴 这不是"摘要覆盖范围"的业务参数，而是防止超长会话一次性拉回几千行的
     * <b>查询保护</b>，故为代码常量而非 {@code sys_config} 键。
     * 覆盖范围的保证来自选材策略本身：<b>紧邻窗口优先 + 开场锚点无条件纳入</b>，
     * 两端都不会因为这个上界而丢失。
     */
    private static final int SUMMARY_LOOKBACK_FACTOR = 4;

    /** 中间内容被略去时的显式标记（🔴 不能让模型误以为摘要是连续的）。 */
    private static final String SUMMARY_GAP_MARKER = "……（中间若干轮已省略）";

    private static final String SUMMARY_PREFIX =
            "以下是本次对话更早内容的摘要（可能不完整），供你保持连贯：\n";

    /**
     * 平台纪律段唯一支持的占位符（🔴 ADR-018 ② / api-spec §7.5.2 ④）。
     *
     * <p>它是<b>模板语法</b>而非业务参数，故为代码常量；文案本身在
     * {@code sys_config: chat.tool_usage_guideline}。
     */
    private static final String CURRENT_TIME_PLACEHOLDER = "{{currentTime}}";

    private final MessageRepository messageRepository;
    private final BusinessConfig businessConfig;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final SkillInjectionService skillInjectionService;

    public ContextAssembler(MessageRepository messageRepository,
                            BusinessConfig businessConfig,
                            StringRedisTemplate redis,
                            TenantCacheKeys cacheKeys,
                            SkillInjectionService skillInjectionService) {
        this.messageRepository = messageRepository;
        this.businessConfig = businessConfig;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.skillInjectionService = skillInjectionService;
    }

    /**
     * 组装上下文（M1 无 Skill 场景，保留以免既有调用方与测试全部返工）。
     */
    @Transactional(readOnly = true)
    public List<AiMessage> assemble(String tenantId, long conversationId, AgentRuntime runtime) {
        return assemble(tenantId, conversationId, runtime, null);
    }

    /**
     * 组装上下文（M3：注入会话绑定 {@code agentVersion} 的 Skill 片段）。
     *
     * <p>🔴 <b>单一前导 system 不变量</b>（ADR-019 ① / api-spec §7.5.2，不可调整）：
     * 整个上下文里 {@code role=system} <b>至多 1 条且必须位于 index 0</b>；
     * 多段内容一律作为<b>同一条消息内部的块</b>拼接，块顺序固定为
     * <pre>
     *   ① 租户段
     *      ⓐ agent_versions.system_prompt
     *      ⓑ 已绑定 Skill 版本的 instruction（binding.sort_order ASC, ref_id ASC）
     *      ⓒ 已绑定 Skill 版本的 output_constraint（同序，🔴 整体追加于 ① 的末尾）
     *   ② 历史摘要块（SUMMARY_PREFIX + 已缓存摘要）
     *   ③ 平台工具调用纪律段（🔴 恒为最后一块）
     *   然后才是最近消息窗口（沿用 M1 的 summary_then_window）
     * </pre>
     * ⓑⓒ 是<b>两段独立追加</b>：若逐 Skill 交错拼接，后一个 Skill 的指令会盖过前一个的输出要求。
     * 🔴 块之间沿用 {@link SystemPromptBudget#SECTION_SEPARATOR}，
     * <b>禁止</b>另造「【平台纪律】」这类可见分隔文案（那属文案且须入库）。
     *
     * <p>🔴 <b>上下文压缩不得裁掉系统提示</b>（ADR-011 / §9.2）：本方法把系统提示（含 Skill 片段）
     * 作为<b>第一条消息无条件保留</b>，压缩只作用于"更早的对话内容"（摘要）与窗口。
     * 🔴 <b>未完成工具链也不得被裁</b>：本轮的 {@code assistant(tool_calls)} + {@code tool} 结果
     * 由 {@code ChatStreamRunner} 在本方法返回的列表<b>之后追加</b>，永不进入窗口裁剪逻辑。
     *
     * @param tenantId       租户号（异步线程内取自上下文快照，不读 Servlet 请求）
     * @param conversationId 会话 ID
     * @param runtime        Agent 运行时快照（提供系统提示与上下文策略）
     * @param agentVersion   会话绑定的 Agent 版本实体（🔴 快照；为 null 表示不注入 Skill）
     * @return 发往上游的消息序列
     */
    @Transactional(readOnly = true)
    public List<AiMessage> assemble(String tenantId, long conversationId, AgentRuntime runtime,
                                    AgentVersion agentVersion) {
        return assemble(tenantId, conversationId, runtime, agentVersion, false);
    }

    /**
     * 组装上下文（🔴 V1.4.2 / ADR-018 ②：按"本次是否下发工具"决定是否注入<b>平台纪律段</b>；
     * 🔴 V1.4.4 / ADR-019：纪律段与摘要块<b>合并进唯一那条 system 消息</b>，纪律段恒为末块）。
     *
     * @param toolsOffered 本次生成的工具清单是否非空（由 {@code ChatStreamRunner} 传入）
     */
    @Transactional(readOnly = true)
    public List<AiMessage> assemble(String tenantId, long conversationId, AgentRuntime runtime,
                                    AgentVersion agentVersion, boolean toolsOffered) {
        ContextWindow window = resolveWindow(conversationId);

        List<AiMessage> context = new ArrayList<>(window.messages().size() + 1);
        // ① 租户段：🔴 预算判定（30060）只吃这一段，且必须在拼接 ②③ 之前完成
        //    —— 顺序倒过来就等于把平台段计入了 chat.system_prompt_max_chars
        String tenantSection = buildSystemPrompt(tenantId, runtime, agentVersion);
        // ② 历史摘要块（🔴 ADR-019：由"另一条 system 消息"改为同一条消息内的块）
        String summarySection = summarySection(tenantId, conversationId, runtime, window);
        // ③ 平台纪律段：🔴 恒为末块，且**不过** SystemPromptBudget
        String guidelineSection = toolUsageGuideline(toolsOffered);

        // 🔴 单一前导 system 消息（ADR-019 ①）：三块拼成一条，块间用既有 SECTION_SEPARATOR
        StringBuilder system = new StringBuilder();
        appendBlock(system, tenantSection);
        appendBlock(system, summarySection);
        appendBlock(system, guidelineSection);
        String systemMessage = system.toString();
        // 🔴 整段为空则不 add（保持"无 system 也合法"的既有行为）
        if (!systemMessage.isBlank()) {
            context.add(AiMessage.system(systemMessage));
        }
        if (!guidelineSection.isEmpty()) {
            // 🔴 只记长度不记正文；三块分列是合并后**唯一**的排障入口（AR-021 ③）
            log.debug("已注入平台工具调用纪律段（合并为唯一 system 消息的末块，不计入 system_prompt 预算）："
                            + "tenantCodePoints={} summaryCodePoints={} guidelineCodePoints={}",
                    codePoints(tenantSection), codePoints(summarySection),
                    codePoints(guidelineSection));
        }

        context.addAll(window.messages());
        if (window.truncatedByChars()) {
            log.info("上下文窗口按长度预算收敛：conversationId={} included={}/{} chars={}/{}",
                    conversationId, window.includedCount(), window.totalCount(),
                    window.usedChars(), windowMaxChars());
        }
        return context;
    }

    /**
     * 追加一个 system 块（🔴 空块不追加、不留空分隔符）。
     *
     * <p>🔴 这里<b>不做任何预算判定</b>：租户段的 {@code 30060} 已在
     * {@link #buildSystemPrompt} 内完成，摘要块与平台纪律段按 ADR-019 ③ 不计入该预算。
     */
    private void appendBlock(StringBuilder system, String block) {
        if (block == null || block.isEmpty()) {
            return;
        }
        if (system.length() > 0) {
            system.append(SystemPromptBudget.SECTION_SEPARATOR);
        }
        system.append(block);
    }

    /**
     * 历史摘要块（🔴 判定与降级行为与 M1 逐字一致，只改"承载形态"）。
     *
     * <p>🔴 判定依据是「实际纳入条数」而非 {@code context_max_messages}：
     * 窗口可能因<b>长度预算</b>提前收尾（条数没超但内容超长），
     * 旧实现用 {@code total > recent.size()} 判定，那种会话会被静默截断且拿不到摘要。
     *
     * <p>🔴 摘要暂缺 → debug 日志 + 退化为确定性滑动窗口，<b>绝不阻断本轮</b>。
     */
    private String summarySection(String tenantId, long conversationId, AgentRuntime runtime,
                                  ContextWindow window) {
        boolean summaryStrategy = AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW
                .equals(runtime.contextStrategy());
        if (!(summaryStrategy && window.hasEarlier() && summaryEnabled())) {
            return "";
        }
        Optional<String> summary = readSummary(tenantId, conversationId);
        if (summary.isEmpty()) {
            log.debug("更早内容存在但摘要暂缺，本轮按滑动窗口运行："
                            + "conversationId={} included={} total={}",
                    conversationId, window.includedCount(), window.totalCount());
            return "";
        }
        return SUMMARY_PREFIX + summary.get();
    }

    // ================================ 窗口构建 ================================

    /**
     * 解析最近消息窗口（🔴 一次分页查询 + 一次计数，装配与摘要共用同一实现）。
     *
     * <p>共用的意义：窗口边界一旦在两处各写一遍，摘要就会与窗口出现重叠或缺口 ——
     * 而这正是旧实现用下标算术定位边界时踩过的坑。
     */
    private ContextWindow resolveWindow(long conversationId) {
        int windowSize = businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_MAX_MESSAGES);
        int maxChars = windowMaxChars();

        // 倒序取最近 windowSize 条（最新在前）
        List<Message> recentDesc = messageRepository.findRecentForContext(
                conversationId, PageRequest.of(0, windowSize));
        long total = messageRepository.countForContext(conversationId);

        List<AiMessage> ordered = new ArrayList<>(recentDesc.size());
        List<Message> included = new ArrayList<>(recentDesc.size());
        int used = 0;
        boolean truncatedByChars = false;

        for (Message message : recentDesc) {
            String content = message.getContent() == null ? "" : message.getContent();
            boolean user = Message.ROLE_USER.equals(message.getRole());
            boolean assistant = Message.ROLE_ASSISTANT.equals(message.getRole());
            if (!user && !assistant) {
                continue;
            }
            // 空 assistant 不回灌：一条空回答对模型没有信息量，只占预算
            if (assistant && content.isBlank()) {
                continue;
            }
            int cost = codePoints(content);
            // 🔴 最新一条无条件保留：它是本轮用户提问，丢了模型不知道要回答什么。
            //    即便它自身超预算也必须留 —— 宁可让上游按它的规则截断，
            //    也不能发出一个"没有问题"的请求。
            if (!ordered.isEmpty() && used + cost > maxChars) {
                truncatedByChars = true;
                break;
            }
            ordered.add(user ? AiMessage.user(content) : AiMessage.assistant(content));
            included.add(message);
            used += cost;
        }

        Collections.reverse(ordered);
        Collections.reverse(included);
        int droppedHead = dropLeadingOrphanAssistants(ordered);
        for (int i = 0; i < droppedHead; i++) {
            used -= codePoints(included.remove(0).getContent());
        }

        Message oldest = included.isEmpty() ? null : included.get(0);
        return new ContextWindow(List.copyOf(ordered), included.size(), total,
                oldest == null ? null : oldest.getCreatedAt(),
                oldest == null ? null : oldest.getId(),
                used, truncatedByChars);
    }

    /**
     * 丢弃窗口头部的孤立 {@code assistant}，返回丢弃条数。
     *
     * <p>🔴 <b>为什么必须丢</b>：真实会话永远从 {@code user} 开始，窗口以 {@code assistant}
     * 开头只可能是<b>截断产生的伪影</b> —— 模型会看到一个"没有问题的回答"，
     * 既无信息量又干扰它对话轮结构的理解（部分 OpenAI 兼容实现甚至直接报错）。
     *
     * <p>🔴 <b>但整窗无 user 时不丢</b>：那会清空窗口、只剩 system，
     * 比留着孤立 assistant 糟糕得多（fail-safe 优先于洁癖）。
     */
    private int dropLeadingOrphanAssistants(List<AiMessage> ordered) {
        int firstUser = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (AiMessage.ROLE_USER.equals(ordered.get(i).role())) {
                firstUser = i;
                break;
            }
        }
        if (firstUser <= 0) {
            return 0;
        }
        ordered.subList(0, firstUser).clear();
        return firstUser;
    }

    private int windowMaxChars() {
        return businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_CHARS);
    }

    /**
     * 最近消息窗口的解析结果。
     *
     * @param messages         已按时间正序排列的上游消息
     * @param includedCount    实际纳入窗口的消息条数
     * @param totalCount       会话内可用于上下文的消息总数
     * @param oldestIncludedAt 窗口中最旧一条的创建时间（摘要游标；窗口为空时 null）
     * @param oldestIncludedId 窗口中最旧一条的 ID（摘要游标；窗口为空时 null）
     * @param usedChars        窗口已用码点数
     * @param truncatedByChars 是否因长度预算提前收尾（区别于因条数上限收尾）
     */
    private record ContextWindow(List<AiMessage> messages,
                                 int includedCount,
                                 long totalCount,
                                 Instant oldestIncludedAt,
                                 Long oldestIncludedId,
                                 int usedChars,
                                 boolean truncatedByChars) {

        /** 是否存在窗口之外的更早内容（→ 需要摘要）。 */
        boolean hasEarlier() {
            return totalCount > includedCount;
        }
    }

    // ================================ 系统提示 ================================

    /**
     * 拼装 system 消息（🔴 顺序见 {@link #assemble(String, long, AgentRuntime, AgentVersion)} 注释）。
     *
     * <p>🔴 Skill 引用链非法（跨租户 / 精确版本缺失 / 未声明变量 / 缺必填值）→ {@code 30060}，
     * 由 {@code SkillInjectionService} 抛出并<b>在进入模型之前</b>失败（AC-CFG-004）。
     * 这里<b>不做 try-catch 降级</b>：静默丢掉 Skill 会让 AI 行为与配置不符且无人察觉，
     * 比明确失败危险得多。
     *
     * <p><b>🔴 system 总长预算（api-spec §7.5.2 ① 裁决，V1.1.3 起本期实现）</b>：
     * <pre>
     * 判定对象 = system_prompt + 全部绑定 Skill 的 instruction + output_constraint
     *            （🔴 按**变量替换后**的实际长度计，否则可用长变量值绕过）
     * 度量口径 = Unicode 码点（SystemPromptBudget，非 UTF-16 length）
     * 阈值     = sys_config: chat.system_prompt_max_chars（🔴 代码中无该数字）
     * 超限行为 = 🔴 整体失败 30060 rule=systemPromptBudgetExceeded，**禁止任何截断**
     * </pre>
     * 🔴 <b>为什么明确否决"只截断最低优先级片段"</b>：注入顺序
     * （systemPrompt → instruction → output_constraint）本身就是<b>语义依赖链</b>，
     * 截掉尾部 {@code output_constraint} 会让模型以"没有输出约束"的形态运行 —— 那是
     * <b>静默降级</b>，排障时表现为"AI 偶尔不守格式"却查不出原因。配置超限是<b>配置问题</b>，
     * 必须让 DBA 看到，不能由运行时替它做减法。
     *
     * <p>🔴 <b>为什么不影响首字</b>：本方法<b>边拼边累加、超限处立即短路</b>
     * （不是"先拼完再遍历一遍"），且整体发生在异步段，按 §5.4.2 不占首字预算。
     */
    private String buildSystemPrompt(String tenantId, AgentRuntime runtime,
                                     AgentVersion agentVersion) {
        // 🔴 阈值来自 sys_config；requireInt 在缺键时直接失败（反硬编码红线，无代码默认值兜底）
        SystemPromptBudget budget = new SystemPromptBudget(businessConfig.requireInt(
                ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS));
        StringBuilder prompt = new StringBuilder();
        appendSection(prompt, runtime.systemPrompt(), budget, agentVersion);
        if (agentVersion == null) {
            return prompt.toString();
        }
        SkillInjectionFragment.Injection injection = skillInjectionService.resolveInstructions(
                agentVersion, SkillRuntimeContext.ofTenant(tenantId));
        if (injection.isEmpty()) {
            return prompt.toString();
        }
        for (String instruction : injection.instructions()) {
            appendSection(prompt, instruction, budget, agentVersion);
        }
        for (String constraint : injection.outputConstraints()) {
            appendSection(prompt, constraint, budget, agentVersion);
        }
        // 🔴 只记标识与 digest，绝不记正文（Skill 正文与 systemPrompt 都是内部资产）
        log.debug("已注入 Skill 片段：conversationAgentVersionId={} refs={} systemPromptCodePoints={}",
                agentVersion.getId(), injection.auditRefs(), budget.used());
        return prompt.toString();
    }

    /**
     * 追加一段并<b>先行</b>累加预算（🔴 超限即抛，绝不追加、绝不截断）。
     */
    private void appendSection(StringBuilder prompt, String section, SystemPromptBudget budget,
                               AgentVersion agentVersion) {
        if (section == null || section.isBlank()) {
            return;
        }
        boolean needSeparator = prompt.length() > 0;
        if (needSeparator && !budget.acceptSeparator()) {
            throw budgetExceeded(agentVersion, budget);
        }
        if (!budget.accept(section)) {
            throw budgetExceeded(agentVersion, budget);
        }
        if (needSeparator) {
            prompt.append(SystemPromptBudget.SECTION_SEPARATOR);
        }
        prompt.append(section);
    }

    /**
     * 构造 {@code 30060 rule=systemPromptBudgetExceeded}。
     *
     * <p>🔴 {@code message} 只回"已超出系统提示长度上限"，<b>禁回</b>正文片段
     * （systemPrompt / Skill 指令均为内部资产，api-spec §7.5.2 第 4 条）；
     * 长度数字本身可回 —— 它是长度不是内容，且 DBA 需要它才能判断该压多少。
     */
    private BusinessException budgetExceeded(AgentVersion agentVersion, SystemPromptBudget budget) {
        Long versionId = agentVersion == null ? null : agentVersion.getId();
        log.warn("🔴 system 提示总长超出预算，本次生成在进入模型之前失败："
                        + "agentVersionId={} max={}（sys_config[{}.{}]）；"
                        + "🔴 未做任何截断（api-spec §7.5.2 ⑤ 明确否决静默降级）",
                versionId, budget.max(), ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS);
        return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                "已超出系统提示长度上限（上限 " + budget.max() + " 字符），请减少绑定的 Skill 或压缩指令",
                ViolationRules.payload("agentVersion",
                        versionId == null ? null : String.valueOf(versionId),
                        "systemPrompt", ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED,
                        "已超出系统提示长度上限"));
    }

    // ================================ 平台工具调用纪律段 ================================

    /**
     * 🔴 <b>平台级工具调用纪律段</b>（ADR-018 ② / ADR-019 / api-spec §7.5.2 ⑥）。
     *
     * <p><b>为什么需要它</b>（BUG-MCP-001 实测）：function-calling 下模型会"热心补全"外部工具的
     * 可选参数，并凭训练期知识把"最近"推算成 2024 年时间戳（当前 2026 年）→ 外部判参数非法 →
     * 换个写法再试 → 每次都要用户再确认一次。🔴 上游 schema <b>一律原样透传</b>
     * （ADR-018 ① 已否决"补 enum / 改 type / 重写 description"），因此唯一"零猜测"的干预点
     * 就是一段<b>与具体工具无关</b>的通用纪律 + 一个<b>服务器当前时间</b>。
     *
     * <p>🔴 四条硬纪律（缺一即缺陷）：
     * <ul>
     *   <li>🔴 <b>仅当本次生成确实下发了 tools 时注入</b>：无工具的生成（含
     *       {@code tool_policy=disabled}）零影响、不占任何预算</li>
     *   <li>🔴 <b>合并进唯一那条 system 消息并恒为末块</b>（V1.4.4 订正，ADR-019 ②）：
     *       原「独立的第二条 system 消息」形态被真实上游否证（{@code status=400}
     *       「system 角色必须位于列表的最开始」，BUG-MCP-004）。<b>末块</b>而非首块的理由：
     *       ⓐ 后置指令在与租户 {@code systemPrompt} 冲突时更占优势（fail-safe 方向）；
     *       ⓑ 租户段仍是前缀，既有行为零漂移；ⓒ 固定后缀可被 {@code endsWith} 机械断言。
     *       🔴 排障"这句话是谁说的"由「恒为末块 + 逐字等于 sys_config 文案」与
     *       {@code assemble} 内分列三块码点数的日志承担（AR-021 ③ / AR-023 ④）</li>
     *   <li>🔴 <b>不经过 {@link SystemPromptBudget}</b>（连 {@code acceptSeparator} 也不调用）：
     *       该预算的判定对象按 §7.5.2 恒为
     *       "system_prompt + Skill instruction + output_constraint"（<b>租户配置</b>），
     *       平台段不在其列。🔴 <b>物理合并 ≠ 预算合并</b>：本方法必须在租户段预算判定
     *       <b>完成之后</b>才被调用，顺序倒过来就等于计入</li>
     *   <li>🔴 唯一占位符 {@code {{currentTime}}}；🔴 文案本身取自
     *       {@code sys_config: chat.tool_usage_guideline}，代码中<b>不得出现该文案字面量</b></li>
     * </ul>
     *
     * <p>🔴 它是<b>引导</b>不是<b>保证</b>：不得以"纪律里写了"为由削弱任何 Schema 校验、
     * 风险确认或审计（api-spec §7.5.2 ⑥ 语义边界）。
     *
     * <p>🔴 缺键 / 空白 → {@code BusinessConfig.requireString} 抛 {@code 50003}（反硬编码红线：
     * 业务参数禁止代码默认值兜底）。它已在 {@code StartupChecker.REQUIRED_CONFIG} 内，
     * 正常情况下启动阶段就会被拦下。
     *
     * <p>🔴 <b>禁止缓存本方法返回值</b>（更禁止缓存合并后的 system 文本）：
     * {@code {{currentTime}}} 是运行时值，缓存会同时造成时间冻结与跨租户串味；
     * 配置侧缓存的是<b>含占位符的模板</b>，不受影响。
     *
     * @param toolsOffered 本次生成是否确实下发了 tools
     * @return 替换占位符后的纪律段文本；🔴 不注入时返回<b>空串</b>（不是 null）
     */
    private String toolUsageGuideline(boolean toolsOffered) {
        if (!toolsOffered) {
            return "";
        }
        String template = businessConfig.requireString(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE);
        // 🔴 无占位符则不注入时间（api-spec §7.5.2 ④）；有则替换为服务器当前时间 ISO-8601
        String guideline = template.contains(CURRENT_TIME_PLACEHOLDER)
                ? template.replace(CURRENT_TIME_PLACEHOLDER, TimeFormat.iso(Instant.now()))
                : template;
        return guideline.isBlank() ? "" : guideline;
    }

    // ================================ 更早内容摘要 ================================

    /**
     * 是否存在需要摘要的更早内容。
     *
     * <p>🔴 判定必须与 {@link #assemble} 用<b>同一个</b>窗口实现：若这里只比条数
     * （{@code countForContext > context_max_messages}），那些「条数没超但内容超长」
     * 的会话就永远不会触发摘要 —— 它们恰恰是最需要摘要的（窗口刚被长度预算裁过）。
     */
    @Transactional(readOnly = true)
    public boolean needsSummary(long conversationId) {
        if (!summaryEnabled()) {
            return false;
        }
        return resolveWindow(conversationId).hasEarlier();
    }

    /**
     * 生成「确定性摘要」：把窗口之外的更早消息按角色截断拼接。
     *
     * <p>为什么不用模型做摘要：模型摘要会引入额外的上游调用、额外的失败面与额外的时延，
     * 而 M1 的产品要求是「更早内容摘要，摘要失败使用确定性滑动窗口」。
     * 这里用<b>确定性算法</b>直接满足要求：结果稳定可复现、零失败率、零额外成本。
     * 若后续要接模型摘要，只需替换本方法实现，调用方与降级路径都不用改。
     *
     * <p><b>🔴 V1.1.9 选材策略：两端锚定</b>（修复旧实现的两个方向性缺陷）
     * <pre>
     * ① 紧邻窗口的更早内容优先（{@code findEarlierForSummary} 倒序返回）
     *    ——旧实现从**最旧**开始拼，800 字符预算很快耗尽，
     *      结果把「紧邻窗口、对连续性最关键」的那几轮全丢了。
     * ② 开场首条用户消息**无条件预留预算**
     *    ——任务设定（"用 TypeScript"/"预算 5000 元"）几乎总在开场；
     *      旧实现的回看上界（windowSize×4）会让长会话彻底看不到开场。
     * ③ 中间被略去时插入显式标记，🔴 不让模型误以为摘要是连续的
     * </pre>
     */
    @Transactional(readOnly = true)
    public void refreshSummary(String tenantId, long conversationId) {
        if (!summaryEnabled()) {
            return;
        }
        ContextWindow window = resolveWindow(conversationId);
        if (!window.hasEarlier() || window.oldestIncludedId() == null) {
            return;
        }
        int summaryMaxChars = businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS);
        int itemMaxChars = businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_SUMMARY_ITEM_MAX_CHARS);
        int windowSize = businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_MAX_MESSAGES);

        // 🔴 以「窗口中最旧那条」为游标，天然不会与窗口重叠、也不会漏
        List<Message> earlierDesc = messageRepository.findEarlierForSummary(
                conversationId, window.oldestIncludedAt(), window.oldestIncludedId(),
                PageRequest.of(0, Math.max(1, windowSize * SUMMARY_LOOKBACK_FACTOR)));
        if (earlierDesc.isEmpty()) {
            return;
        }

        // ② 开场锚点先占预算，确保它不会被紧邻内容挤掉
        Optional<Message> anchor = openingMessage(conversationId, window.oldestIncludedId());
        String anchorLine = anchor.map(message -> renderLine(message, itemMaxChars))
                .filter(line -> !line.isEmpty())
                .orElse(null);
        int anchorCost = anchorLine == null ? 0 : codePoints(anchorLine);
        int recentBudget = summaryMaxChars - anchorCost;

        // ① 紧邻窗口优先，往更早填
        List<String> recentLines = new ArrayList<>();
        int used = 0;
        for (Message message : earlierDesc) {
            if (anchor.isPresent() && anchor.get().getId().equals(message.getId())) {
                continue;
            }
            String line = renderLine(message, itemMaxChars);
            if (line.isEmpty()) {
                continue;
            }
            int cost = codePoints(line);
            if (used + cost > recentBudget) {
                break;
            }
            recentLines.add(line);
            used += cost;
        }
        Collections.reverse(recentLines);

        String summary = renderSummary(anchorLine, recentLines, window);
        if (!summary.isEmpty()) {
            writeSummary(tenantId, conversationId, summary);
        }
    }

    /**
     * 会话开场消息（首条用户消息）。
     *
     * <p>🔴 只有当它确实落在窗口之外时才作为锚点：否则会与窗口内容重复，
     * 白占摘要预算（短会话里首条消息往往还在窗口里）。
     */
    private Optional<Message> openingMessage(long conversationId, long oldestIncludedId) {
        List<Message> first = messageRepository.findFirstUserMessage(
                conversationId, PageRequest.of(0, 1));
        if (first.isEmpty()) {
            return Optional.empty();
        }
        Message opening = first.get(0);
        return opening.getId() != null && opening.getId() < oldestIncludedId
                ? Optional.of(opening) : Optional.empty();
    }

    /**
     * 渲染摘要正文（锚点 → 省略标记 → 紧邻窗口的若干轮，整体时间正序）。
     */
    private String renderSummary(String anchorLine, List<String> recentLines,
                                 ContextWindow window) {
        long earlierAvailable = window.totalCount() - window.includedCount();
        int rendered = recentLines.size() + (anchorLine == null ? 0 : 1);

        StringBuilder sb = new StringBuilder();
        if (anchorLine != null) {
            sb.append(anchorLine).append('\n');
        }
        // 🔴 只在真的有内容被略去时才加标记，否则是误导
        if (rendered < earlierAvailable) {
            sb.append(SUMMARY_GAP_MARKER).append('\n');
        }
        for (String line : recentLines) {
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 单条消息 → 摘要行（🔴 截断时补省略号，避免模型把半句话当完整陈述）。
     */
    private String renderLine(Message message, int itemMaxChars) {
        String raw = message.getContent() == null ? "" : message.getContent();
        String text = raw.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return "";
        }
        String prefix = Message.ROLE_USER.equals(message.getRole()) ? "用户：" : "助手：";
        int count = codePoints(text);
        if (count <= itemMaxChars) {
            return prefix + text;
        }
        return prefix + truncate(text, itemMaxChars) + "…";
    }

    private boolean summaryEnabled() {
        return businessConfig.requireBoolean(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_SUMMARY_ENABLED);
    }

    private Optional<String> readSummary(String tenantId, long conversationId) {
        try {
            return Optional.ofNullable(
                    redis.opsForValue().get(cacheKeys.chatSummary(tenantId, conversationId)));
        } catch (RuntimeException e) {
            // 读不到摘要 → 自动退化为滑动窗口，不影响本轮
            log.warn("读取会话摘要失败，本轮使用滑动窗口：conversationId={}", conversationId);
            return Optional.empty();
        }
    }

    private void writeSummary(String tenantId, long conversationId, String summary) {
        try {
            redis.opsForValue().set(cacheKeys.chatSummary(tenantId, conversationId), summary, SUMMARY_TTL);
        } catch (RuntimeException e) {
            log.warn("写入会话摘要失败（下轮继续使用滑动窗口）：conversationId={}", conversationId);
        }
    }

    private static int codePoints(String text) {
        return text == null || text.isEmpty() ? 0 : text.codePointCount(0, text.length());
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int count = text.codePointCount(0, text.length());
        if (count <= maxChars) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, maxChars);
        return text.substring(0, endIndex);
    }
}
