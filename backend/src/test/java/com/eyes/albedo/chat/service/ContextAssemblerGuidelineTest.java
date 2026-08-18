package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.chat.ai.AiMessage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.repository.MessageRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.SkillInjectionService;
import com.eyes.albedo.skill.dto.SkillInjectionFragment;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tool.ToolRiskPolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 🔴 <b>平台工具调用纪律段的注入口径</b>单测（ADR-018 ② / ADR-019 / api-spec §7.5.2 ⑥）。
 *
 * <p>四条被守护的硬约束（缺一即 BUG-MCP-001 的修复不成立或引发连带破坏）：
 * <ul>
 *   <li>🔴 <b>仅当本次生成确实下发 tools 时注入</b>：无工具的生成零影响</li>
 *   <li>🔴 <b>合并为唯一那条 system 消息的末块</b>（V1.4.4 订正，ADR-019 ②）：
 *       原「独立的第二条 system 消息」被真实上游否证（{@code status=400}，BUG-MCP-004）；
 *       租户段仍是<b>前缀</b>（既有行为零漂移），纪律段为<b>后缀</b>（可 {@code endsWith} 机械断言）</li>
 *   <li>🔴 <b>不计入 {@code chat.system_prompt_max_chars}</b>：否则本次变更会把
 *       <b>既有满配租户</b>直接打成 {@code 30060}（不可接受的连带破坏）——
 *       🔴 <b>物理合并 ≠ 预算合并</b>，判定对象恒为租户段</li>
 *   <li>🔴 {@code {{currentTime}}} 替换为服务器当前时间：直接消灭"模型按训练期知识
 *       把『最近』推算成 2024 年时间戳"这一实测失败模式</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextAssemblerGuidelineTest {

    private static final long CONVERSATION_ID = 901L;
    private static final String TENANT = "gift";
    /** 🔴 文案取自 {@code sys_config}（本测试自造一份桩文案，生产文案见 api-spec §7.1.2）。 */
    private static final String GUIDELINE_TEMPLATE =
            "当前服务器时间：{{currentTime}}。调用工具时请遵守以下纪律：只传必填参数。";

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
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_MESSAGES))
                .thenReturn(20);
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_CHARS))
                .thenReturn(1000);
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS)).thenReturn(100000);
        when(businessConfig.requireBoolean(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CONTEXT_SUMMARY_ENABLED)).thenReturn(false);
        when(businessConfig.requireString(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE)).thenReturn(GUIDELINE_TEMPLATE);
        when(messageRepository.findRecentForContext(eq(CONVERSATION_ID), any(Pageable.class)))
                .thenReturn(List.of(user(1L, "帮我联网搜索最近关于 DeepSeek 的新闻")));
        when(messageRepository.countForContext(CONVERSATION_ID)).thenReturn(1L);
        when(skillInjectionService.resolveInstructions(any(), any()))
                .thenReturn(SkillInjectionFragment.Injection.empty());
    }

    @Test
    @DisplayName("🔴 本次下发了 tools → 合并为**唯一** system 消息的**末块**，且 {{currentTime}} 已替换")
    void guidelineMergedAsTrailingBlockOfSingleSystem() {
        List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, runtime(),
                agentVersion(), true);

        List<AiMessage> systems = context.stream()
                .filter(message -> AiMessage.ROLE_SYSTEM.equals(message.role())).toList();
        assertEquals(1, systems.size(),
                "🔴 单一前导 system 不变量（ADR-019 ①）：system 全局至多 1 条");
        assertEquals(0, context.indexOf(systems.get(0)),
                "🔴 唯一那条 system 必须位于 index 0（否则上游 400）");
        String system = systems.get(0).content();
        assertTrue(system.startsWith("你是礼遇顾问"),
                "🔴 租户段必须仍是**前缀**（既有行为零漂移）：" + system);
        assertTrue(system.contains("只传必填参数"), system);
        assertFalse(system.contains("{{currentTime}}"),
                "🔴 占位符必须已替换，否则模型会把它当字面量：" + system);
        assertTrue(system.contains(today()),
                "🔴 必须是**当前**时间（消灭\"按训练期知识推算时间戳\"的失败模式）：" + system);
        // 🔴 纪律段恒为末块：可被机械断言，验收不依赖人工阅读（ADR-019 ② ⓒ）
        String guideline = GUIDELINE_TEMPLATE.substring(0,
                GUIDELINE_TEMPLATE.indexOf("{{currentTime}}"));
        assertTrue(system.endsWith("调用工具时请遵守以下纪律：只传必填参数。"),
                "🔴 system 内容必须以替换后的纪律段结尾（恒为末块）：" + system);
        assertTrue(system.contains(guideline), "纪律段前半段亦须完整保留：" + system);
    }

    @Test
    @DisplayName("🔴 本次未下发 tools → 完全不注入（无工具的生成零影响、不占任何预算）")
    void noGuidelineWhenNoTools() {
        List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, runtime(),
                agentVersion(), false);

        assertEquals(1, context.stream()
                        .filter(message -> AiMessage.ROLE_SYSTEM.equals(message.role())).count(),
                "🔴 只应有租户段一条 system");
        assertFalse(context.toString().contains("只传必填参数"), "🔴 不得注入纪律段");
    }

    @Test
    @DisplayName("🔴 旧签名（4 参）默认不注入：既有调用方与历史行为零变化")
    void legacySignatureDoesNotInject() {
        List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, runtime(),
                agentVersion());

        assertFalse(context.toString().contains("只传必填参数"));
    }

    @Test
    @DisplayName("🔴🔴 满配 system_prompt + 纪律段仍**不** 30060（证明纪律段不计入租户预算）")
    void guidelineDoesNotConsumeSystemPromptBudget() {
        int budget = 200;
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS)).thenReturn(budget);
        // 🔴 租户 system 恰好用满预算：若纪律段被计入，本次必然 30060
        String fullPrompt = "满".repeat(budget);
        AgentRuntime full = new AgentRuntime(1L, 2L, fullPrompt, "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096,
                AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 60,
                ToolRiskPolicy.POLICY_AUTO);

        List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, full,
                agentVersion(), true);

        // 🔴 判据不变：满配租户段 + 纪律段仍不 30060（物理合并 ≠ 预算合并）
        assertEquals(1, context.stream()
                .filter(message -> AiMessage.ROLE_SYSTEM.equals(message.role())).count());
        String system = context.get(0).content();
        assertTrue(system.startsWith(fullPrompt), "🔴 租户段完整保留（禁止任何截断）");
        assertTrue(system.contains("只传必填参数"), "🔴 纪律段照常注入");
        assertTrue(system.codePointCount(0, system.length()) > budget,
                "🔴 最终 system 物理长度**可以**超过 system_prompt_max_chars（ADR-019 ③，有意为之）");
        // 反向对照：租户段自己再多一个字符就必须 30060（证明预算判定本身没被削弱）
        AgentRuntime overflow = new AgentRuntime(1L, 2L, fullPrompt + "超", "hunyuan",
                "hunyuan-a13b", new BigDecimal("0.7"), 4096,
                AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 60,
                ToolRiskPolicy.POLICY_AUTO);
        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
                BusinessException.class,
                () -> assembler.assemble(TENANT, CONVERSATION_ID, overflow, agentVersion(), true));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode(),
                "🔴 纪律段不计入预算 ≠ 预算判定被放宽");
    }

    @Test
    @DisplayName("模板无占位符 → 原样注入为末块（不注入时间，api-spec §7.5.2 ④）")
    void templateWithoutPlaceholderIsInjectedAsIs() {
        when(businessConfig.requireString(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE)).thenReturn("只传必填参数。");

        List<AiMessage> context = assembler.assemble(TENANT, CONVERSATION_ID, runtime(),
                agentVersion(), true);

        assertEquals("你是礼遇顾问\n\n只传必填参数。", context.get(0).content(),
                "🔴 块间分隔符沿用既有 SystemPromptBudget.SECTION_SEPARATOR，禁止另造可见文案");
    }

    // ===================== 辅助 =====================

    private String today() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }

    private AgentRuntime runtime() {
        return new AgentRuntime(1L, 2L, "你是礼遇顾问", "hunyuan", "hunyuan-a13b",
                new BigDecimal("0.7"), 4096,
                AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 60,
                ToolRiskPolicy.POLICY_AUTO);
    }

    private AgentVersion agentVersion() {
        AgentVersion version = new AgentVersion();
        version.setId(2L);
        version.setVersion(1L);
        version.setToolPolicy(ToolRiskPolicy.POLICY_AUTO);
        return version;
    }

    private Message user(long id, String content) {
        Message message = new Message();
        message.setId(id);
        message.setRole(Message.ROLE_USER);
        message.setContent(content);
        message.setStatus(Message.STATUS_COMPLETED);
        message.setCreatedAt(Instant.parse("2026-08-17T00:00:00Z"));
        return message;
    }
}
