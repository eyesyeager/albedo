package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.repository.MessageRepository;
import com.eyes.albedo.skill.SkillInjectionService;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 多轮对话上下文策略单测（REQ-CHAT-004 / api-spec §5.2 · V1.1.9）。
 *
 * <p>🔴 锁定的核心不变量：
 * <ul>
 *   <li><b>窗口总长有界</b> —— 没有长度预算时 20×20000=40 万字符会击穿上游窗口，
 *       且窗口每轮都重新纳入同样的超长历史 → <b>该会话永久不可用</b>，用户无法自愈</li>
 *   <li><b>最新一条无条件保留</b> —— 丢了本轮提问，模型不知道要回答什么</li>
 *   <li><b>窗口不以 assistant 开头</b> —— 那是截断伪影（"没有问题的回答"）</li>
 *   <li><b>摘要判定用实际纳入条数</b> —— 只比条数会让"条数没超但内容超长"的会话
 *       被静默截断且拿不到摘要，那恰恰是最需要摘要的场景</li>
 *   <li><b>摘要两端锚定</b> —— 紧邻窗口优先（连续性）+ 开场锚点（任务设定）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAssemblerTest {

    private static final long CONVERSATION_ID = 900L;
    private static final String TENANT = "gift";
    private static final String SUMMARY_KEY = "albedo:gift:chat:summary:900";

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private BusinessConfig businessConfig;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private TenantCacheKeys cacheKeys;
    @Mock
    private SkillInjectionService skillInjectionService;
    @Mock
    private ValueOperations<String, String> valueOps;

    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ContextAssembler(messageRepository, businessConfig, redis, cacheKeys,
                skillInjectionService);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(cacheKeys.chatSummary(TENANT, CONVERSATION_ID)).thenReturn(SUMMARY_KEY);
        // 默认配置：条数 20、窗口 100 码点、摘要 800/单条 120、system 预算充足
        config(ConfigKeys.CONTEXT_MAX_MESSAGES, 20);
        config(ConfigKeys.CONTEXT_MAX_CHARS, 100);
        config(ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS, 800);
        config(ConfigKeys.CONTEXT_SUMMARY_ITEM_MAX_CHARS, 120);
        config(ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, 100000);
        when(businessConfig.requireBoolean(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_SUMMARY_ENABLED)).thenReturn(true);
    }

    private void config(String key, int value) {
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, key)).thenReturn(value);
    }

    // ============================ 构造工具 ============================

    private Message msg(long id, String role, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(role);
        message.setContent(content);
        message.setStatus(Message.STATUS_COMPLETED);
        message.setCreatedAt(Instant.parse("2026-08-16T00:00:00Z").plusSeconds(id));
        return message;
    }

    private Message user(long id, String content) {
        return msg(id, Message.ROLE_USER, content);
    }

    private Message assistant(long id, String content) {
        return msg(id, Message.ROLE_ASSISTANT, content);
    }

    private String repeat(char c, int times) {
        return String.valueOf(c).repeat(times);
    }

    /** 铺设「最近窗口查询」的返回值（参数按时间正序给出，内部转倒序）。 */
    private void givenRecent(List<Message> chronological, long total) {
        List<Message> desc = new ArrayList<>(chronological);
        java.util.Collections.reverse(desc);
        when(messageRepository.findRecentForContext(eq(CONVERSATION_ID), any(Pageable.class)))
                .thenReturn(desc);
        when(messageRepository.countForContext(CONVERSATION_ID)).thenReturn(total);
    }

    private AgentRuntime runtime() {
        return new AgentRuntime(1L, 2L, "你是礼遇顾问", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096,
                AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 60,
                AgentVersion.TOOL_POLICY_DISABLED);
    }

    private List<AiMessage> assemble() {
        return assembler.assemble(TENANT, CONVERSATION_ID, runtime());
    }

    /** 取出非 system 的窗口部分 */
    private List<AiMessage> windowOf(List<AiMessage> context) {
        return context.stream().filter(m -> !AiMessage.ROLE_SYSTEM.equals(m.role())).toList();
    }

    // ============================ 窗口长度预算 ============================

    @Nested
    @DisplayName("窗口长度预算")
    class WindowBudget {

        @Test
        @DisplayName("🔴 超长历史按预算收敛，且优先保住最近的轮次")
        void longHistoryIsBoundedKeepingRecent() {
            givenRecent(List.of(
                    user(1, repeat('A', 40)),
                    assistant(2, repeat('B', 40)),
                    user(3, repeat('C', 40)),
                    assistant(4, repeat('D', 40)),
                    user(5, repeat('E', 10))), 5);

            List<AiMessage> window = windowOf(assemble());

            // 预算 100：E(10) + D(40) + C(40) = 90；再加 B 会到 130 → 停
            assertEquals(3, window.size());
            assertEquals(repeat('C', 40), window.get(0).content(), "最旧保留的应是 C");
            assertEquals(repeat('D', 40), window.get(1).content());
            assertEquals(repeat('E', 10), window.get(2).content(), "🔴 最近一轮必须在");
        }

        @Test
        @DisplayName("🔴 最新一条即便自身超预算也必须保留（否则模型不知道要回答什么）")
        void newestMessageIsKeptEvenIfOverBudget() {
            givenRecent(List.of(
                    user(1, repeat('A', 40)),
                    user(2, repeat('Z', 500))), 2);

            List<AiMessage> window = windowOf(assemble());

            assertEquals(1, window.size());
            assertEquals(repeat('Z', 500), window.get(0).content());
        }

        @Test
        @DisplayName("历史短于预算时全部纳入，不做任何裁剪")
        void shortHistoryFullyIncluded() {
            givenRecent(List.of(user(1, "你好"), assistant(2, "你好呀"), user(3, "在吗")), 3);

            assertEquals(3, windowOf(assemble()).size());
        }

        @Test
        @DisplayName("空 assistant 不占预算、不进窗口（一条空回答没有信息量）")
        void blankAssistantExcluded() {
            givenRecent(List.of(user(1, "问题"), assistant(2, "   "), user(3, "再问")), 3);

            List<AiMessage> window = windowOf(assemble());

            assertEquals(2, window.size());
            assertTrue(window.stream().allMatch(m -> AiMessage.ROLE_USER.equals(m.role())));
        }
    }

    // ============================ 配对完整性 ============================

    @Nested
    @DisplayName("配对完整性")
    class Pairing {

        @Test
        @DisplayName("🔴 截断后窗口以 assistant 开头 → 丢弃该孤立回答")
        void leadingOrphanAssistantDropped() {
            config(ConfigKeys.CONTEXT_MAX_CHARS, 60);
            givenRecent(List.of(
                    user(1, repeat('A', 40)),
                    assistant(2, repeat('B', 40)),
                    user(3, repeat('C', 10))), 3);

            List<AiMessage> window = windowOf(assemble());

            // 预算 60：C(10) + B(40) = 50 纳入，A 超预算 → 反转后头部是 B（孤立 assistant）
            assertEquals(1, window.size(), "孤立的 B 必须被丢弃");
            assertEquals(AiMessage.ROLE_USER, window.get(0).role());
            assertEquals(repeat('C', 10), window.get(0).content());
        }

        @Test
        @DisplayName("窗口正常以 user 开头时不做任何丢弃")
        void normalWindowUntouched() {
            givenRecent(List.of(user(1, "问"), assistant(2, "答"), user(3, "再问")), 3);

            List<AiMessage> window = windowOf(assemble());

            assertEquals(3, window.size());
            assertEquals(AiMessage.ROLE_USER, window.get(0).role());
        }

        @Test
        @DisplayName("🔴 整窗无 user 时不得清空（fail-safe 优先于结构洁癖）")
        void allAssistantWindowNotCleared() {
            givenRecent(List.of(assistant(1, "甲"), assistant(2, "乙")), 2);

            assertEquals(2, windowOf(assemble()).size());
        }
    }

    // ============================ 摘要触发判定 ============================

    @Nested
    @DisplayName("摘要触发判定")
    class SummaryTrigger {

        @Test
        @DisplayName("🔴 条数未超但被长度预算裁过 → 仍须附摘要（旧实现在此静默丢内容）")
        void summaryAttachedWhenTruncatedByCharsOnly() {
            // 总数 5 远小于 context_max_messages(20)，但内容超长导致窗口只装下一部分
            givenRecent(List.of(
                    user(1, repeat('A', 60)),
                    assistant(2, repeat('B', 60)),
                    user(3, repeat('C', 60))), 3);
            when(valueOps.get(SUMMARY_KEY)).thenReturn("用户：早先的问题");

            List<AiMessage> context = assemble();

            assertTrue(context.stream().anyMatch(m -> AiMessage.ROLE_SYSTEM.equals(m.role())
                            && m.content().contains("早先的问题")),
                    "🔴 长度裁剪同样意味着有更早内容，必须附摘要");
        }

        @Test
        @DisplayName("🔴 摘要作为**唯一那条 system 内的块**注入（ADR-019：绝不新起第 2 条 system）")
        void summaryIsBlockOfSingleSystemMessage() {
            givenRecent(List.of(
                    user(1, repeat('A', 60)),
                    assistant(2, repeat('B', 60)),
                    user(3, repeat('C', 60))), 3);
            when(valueOps.get(SUMMARY_KEY)).thenReturn("用户：早先的问题");

            List<AiMessage> context = assemble();

            assertEquals(1, context.stream()
                            .filter(m -> AiMessage.ROLE_SYSTEM.equals(m.role())).count(),
                    "🔴 摘要一旦成为第 2 条 system，长会话在摘要 TTL 内每轮都被上游 400");
            assertEquals(AiMessage.ROLE_SYSTEM, context.get(0).role());
            String system = context.get(0).content();
            assertTrue(system.startsWith("你是礼遇顾问"), "🔴 租户段仍为前缀：" + system);
            assertTrue(system.contains("早先的问题"), "🔴 摘要块必须在同一条 system 内：" + system);
        }

        @Test
        @DisplayName("全部历史都在窗口内 → 不附摘要（没有更早内容）")
        void noSummaryWhenNothingElided() {
            givenRecent(List.of(user(1, "问"), assistant(2, "答")), 2);
            when(valueOps.get(SUMMARY_KEY)).thenReturn("不该出现的摘要");

            List<AiMessage> context = assemble();

            assertFalse(context.stream().anyMatch(m -> m.content().contains("不该出现的摘要")));
        }

        @Test
        @DisplayName("策略为 window（非 summary_then_window）时不附摘要")
        void noSummaryUnderWindowStrategy() {
            givenRecent(List.of(user(1, repeat('A', 200)), user(2, repeat('B', 200))), 9);
            when(valueOps.get(SUMMARY_KEY)).thenReturn("摘要内容");

            AgentRuntime windowOnly = new AgentRuntime(1L, 2L, "提示", "hunyuan", "m",
                    new BigDecimal("0.7"), 4096, AgentVersion.CONTEXT_STRATEGY_WINDOW, 60,
                    AgentVersion.TOOL_POLICY_DISABLED);
            List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, windowOnly);

            assertFalse(context.stream().anyMatch(m -> m.content().contains("摘要内容")));
        }

        @Test
        @DisplayName("摘要缺失时静默退化为滑动窗口（🔴 绝不阻断本轮）")
        void missingSummaryDegradesToWindow() {
            givenRecent(List.of(user(1, repeat('A', 60)), user(2, repeat('B', 60))), 8);
            when(valueOps.get(SUMMARY_KEY)).thenReturn(null);

            List<AiMessage> context = assemble();

            assertFalse(windowOf(context).isEmpty(), "窗口照常可用");
        }

        @Test
        @DisplayName("Redis 读取异常时退化为滑动窗口，不抛出")
        void redisFailureDegrades() {
            givenRecent(List.of(user(1, repeat('A', 60)), user(2, repeat('B', 60))), 8);
            when(valueOps.get(SUMMARY_KEY)).thenThrow(
                    new org.springframework.dao.QueryTimeoutException("redis 超时"));

            assertFalse(windowOf(assemble()).isEmpty());
        }
    }

    // ============================ 系统提示 ============================

    @Nested
    @DisplayName("系统提示")
    class SystemPrompt {

        @Test
        @DisplayName("🔴 系统提示恒为第一条，且不受窗口长度预算影响（ADR-011）")
        void systemPromptAlwaysFirstAndNeverTrimmed() {
            config(ConfigKeys.CONTEXT_MAX_CHARS, 5);
            givenRecent(List.of(user(1, repeat('A', 100))), 1);

            List<AiMessage> context = assemble();

            assertEquals(AiMessage.ROLE_SYSTEM, context.get(0).role());
            assertEquals("你是礼遇顾问", context.get(0).content(), "系统提示不得被裁");
        }
    }

    // ============================ 摘要选材 ============================

    @Nested
    @DisplayName("摘要选材（两端锚定）")
    class SummarySelection {

        /** 让窗口只装下最新一条，其余进入「更早」区间 */
        private void givenWindowOfOne(List<Message> earlierChronological, long total) {
            config(ConfigKeys.CONTEXT_MAX_CHARS, 1);
            givenRecent(List.of(user(99, "最新问题")), total);
            List<Message> desc = new ArrayList<>(earlierChronological);
            java.util.Collections.reverse(desc);
            when(messageRepository.findEarlierForSummary(eq(CONVERSATION_ID), any(Instant.class),
                    anyLong(), any(Pageable.class))).thenReturn(desc);
        }

        private String capturedSummary() {
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(valueOps).set(eq(SUMMARY_KEY), captor.capture(), any(java.time.Duration.class));
            return captor.getValue();
        }

        @Test
        @DisplayName("🔴 预算紧张时优先保留紧邻窗口的内容（旧实现从最旧开始拼，恰好相反）")
        void windowAdjacentContentWins() {
            config(ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS, 30);
            givenWindowOfOne(List.of(
                    user(1, "最古老的话题"),
                    user(2, "中间的话题"),
                    user(3, "紧邻窗口的话题")), 4);
            // 开场锚点：id=1 那条
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "最古老的话题")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);
            String summary = capturedSummary();

            assertTrue(summary.contains("紧邻窗口的话题"), "🔴 紧邻窗口的内容必须入摘要");
        }

        @Test
        @DisplayName("🔴 开场锚点无条件纳入（任务设定几乎总在开场）")
        void openingAnchorAlwaysIncluded() {
            config(ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS, 40);
            givenWindowOfOne(List.of(
                    user(1, "请全程用 TypeScript"),
                    user(2, "中间话题"),
                    user(3, "紧邻话题")), 4);
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "请全程用 TypeScript")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);
            String summary = capturedSummary();

            assertTrue(summary.contains("请全程用 TypeScript"), "🔴 开场约束不得被挤掉");
            assertTrue(summary.indexOf("请全程用 TypeScript") < summary.length(),
                    "锚点应位于摘要开头（时间正序）");
            assertTrue(summary.startsWith("用户：请全程用 TypeScript"));
        }

        @Test
        @DisplayName("🔴 中间内容被略去时插入显式标记（不让模型误以为摘要连续）")
        void gapMarkerWhenContentElided() {
            config(ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS, 30);
            givenWindowOfOne(List.of(
                    user(1, "开场"),
                    user(2, repeat('中', 50)),
                    user(3, "紧邻")), 4);
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "开场")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);

            assertTrue(capturedSummary().contains("省略"), "应有省略标记");
        }

        @Test
        @DisplayName("更早内容全部纳入时不加省略标记（否则是误导）")
        void noGapMarkerWhenComplete() {
            givenWindowOfOne(List.of(user(1, "开场"), user(2, "紧邻")), 3);
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "开场")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);

            assertFalse(capturedSummary().contains("省略"));
        }

        @Test
        @DisplayName("🔴 单条超长按码点截断并补省略号（避免半句话被当完整陈述）")
        void longItemTruncatedWithEllipsis() {
            config(ConfigKeys.CONTEXT_SUMMARY_ITEM_MAX_CHARS, 5);
            givenWindowOfOne(List.of(user(1, "开场"), user(2, "这是一段很长的内容需要被截断")), 3);
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "开场")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);
            String summary = capturedSummary();

            assertTrue(summary.contains("这是一段很…"), "应截断到 5 个码点并补省略号：" + summary);
        }

        @Test
        @DisplayName("摘要中的角色前缀保留（模型需据此区分谁说的）")
        void rolePrefixPreserved() {
            givenWindowOfOne(List.of(user(1, "问题"), assistant(2, "回答")), 3);
            when(messageRepository.findFirstUserMessage(eq(CONVERSATION_ID), any(Pageable.class)))
                    .thenReturn(List.of(user(1, "问题")));

            assembler.refreshSummary(TENANT, CONVERSATION_ID);
            String summary = capturedSummary();

            assertTrue(summary.contains("用户：问题"));
            assertTrue(summary.contains("助手：回答"));
        }

        @Test
        @DisplayName("没有更早内容时不写摘要（省一次 Redis 写）")
        void noWriteWhenNoEarlierContent() {
            givenRecent(List.of(user(1, "问"), assistant(2, "答")), 2);

            assembler.refreshSummary(TENANT, CONVERSATION_ID);

            verify(valueOps, never()).set(any(), any(), any(java.time.Duration.class));
        }

        @Test
        @DisplayName("摘要开关关闭时直接返回，不查库不写缓存")
        void disabledSummarySkipsEverything() {
            when(businessConfig.requireBoolean(ConfigKeys.GROUP_CHAT,
                    ConfigKeys.CONTEXT_SUMMARY_ENABLED)).thenReturn(false);

            assembler.refreshSummary(TENANT, CONVERSATION_ID);

            verify(messageRepository, never()).findRecentForContext(anyLong(), any(Pageable.class));
            verify(valueOps, never()).set(any(), any(), any(java.time.Duration.class));
        }
    }
}
