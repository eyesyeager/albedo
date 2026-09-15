package com.eyes.albedo.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检：数据库、Redis、必备平台配置。
 *
 * <p>为什么要 fail-fast：本服务的租户识别、限流、幂等、取消标记全部依赖 Redis，
 * 业务阈值全部依赖 {@code sys_config}。若这些缺失还继续启动，故障会以「运行时零散报错」
 * 的形式暴露（例如某个用户发消息时才 50003），排障成本远高于启动即失败。
 *
 * <p>🔴 校验项缺失一律抛异常终止启动，不允许"打个 WARN 继续跑"。
 */
@Slf4j
@Component
public class StartupChecker implements ApplicationRunner {

    /**
     * 必备平台配置（缺一项即无法正常提供服务）。
     *
     * <p>🔴 M3 新增 <b>25</b> 键全部纳入（api-spec §7.1.2 / architecture.md §13.6 落地纪律第 2 条
     * / AR-012；22 → 23（{@code chat.system_prompt_max_chars}）→ <b>25</b>
     * （V1.1.4 #1 补登 {@code observability.analytics_enabled} /
     * {@code observability.analytics_sample_rate}））：
     * 若不在启动时校验，配置缺失会以"某个用户调用工具时才 30060/50003"的零散形式暴露，
     * 排障成本极高；而 {@code BusinessConfig.requireXxx} 又刻意<b>不提供代码默认值兜底</b>
     * （反硬编码红线），两者合起来要求"缺键必须启动即失败"。
     *
     * <p>🔴 <b>埋点两键为什么也必须在列</b>（V1.1.4 #1 裁决否决了"契约锁定 23 键故不纳入"）：
     * §13.6 纪律 4 明令"业务参数禁止用代码默认值兜底"，把它们留在清单外等于给这条纪律
     * 开一个豁免口；且读取侧已改为 <b>fail-closed</b>（缺键 = 全部丢弃埋点），
     * 缺键若不拦在启动，表现就是"埋点静默停摆"。
     *
     * <p>🔴 <b>V1.4.2（ADR-017 / ADR-018）再增 3 键</b>（api-spec §7.1.2 键总数 29 → <b>32</b>）：
     * {@code chat.generation_deadline_seconds} / {@code chat.deadline_grace_seconds} /
     * {@code chat.tool_usage_guideline}。前两者缺失会让 {@code SseEmitter} 无法确定连接寿命
     * （只能退回 BUG-MCP-002 的错误量纲）；纪律段缺失则模型失去"只传必填参数 + 服务器当前时间"
     * 的引导，🔴 <b>不提供关闭开关</b>——要弱化引导请改文案本身。
     */
    private static final List<String[]> REQUIRED_CONFIG = List.of(
            new String[]{ConfigKeys.GROUP_BUSINESS, ConfigKeys.PAGE_SIZE_DEFAULT},
            new String[]{ConfigKeys.GROUP_BUSINESS, ConfigKeys.PAGE_SIZE_MAX},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.MESSAGE_MIN_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.MESSAGE_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_AUTO_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_MESSAGES},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_SUMMARY_ENABLED},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_SUMMARY_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CONTEXT_SUMMARY_ITEM_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.FIRST_TOKEN_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.IDEMPOTENCY_TTL_SECONDS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CANCEL_MARKER_TTL_SECONDS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CANCEL_CHECK_INTERVAL_CHUNKS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.STREAM_HEARTBEAT_SECONDS},
            new String[]{ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE},
            // 🔴 V1.4.5 ADR-020 新增 3 键（api-spec §7.1.2 第 33 / 34 / 35 键，32 → 35）：
            //    QPM 开关 + 日额度开关 + 日额度阈值。三者均为**平台默认值**，
            //    缺键即启动失败（租户覆盖只在 tenant_quota_policies，且 NULL = 继承本处默认）。
            // 🔴 同时**移除** ratelimit.message_per_hour（小时窗业务规则已废除，§13.6 纪律 10）。
            new String[]{ConfigKeys.GROUP_RATELIMIT, ConfigKeys.QPM_ENABLED},
            new String[]{ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_ENABLED},
            new String[]{ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT},
            new String[]{ConfigKeys.GROUP_MODEL, ConfigKeys.MODEL_PROVIDERS},
            // ===== M3 新增 23 键（api-spec §7.1.2 / architecture.md §13.6） =====
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_ROUNDS},
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_DEFAULT_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_MAX_BYTES},
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_ARGS_SUMMARY_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_SUMMARY_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_REQUIRE_HTTPS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CONNECT_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_CALL_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_DISCOVER_TIMEOUT_SECONDS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_BLOCKED_IP_CIDRS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_TRANSPORT_PREFERRED},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_MAX_TOOLS_PER_SERVER},
            // 🔴 V1.4.0 ADR-016 新增 2 键（api-spec §7.1.2 第 28 / 29 键）：
            //    sse 异步形态能力开关（读取侧 fail-closed）+ SSE 流字节上限
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_LEGACY_ENABLED},
            new String[]{ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES},
            new String[]{ConfigKeys.GROUP_SKILL, ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS},
            new String[]{ConfigKeys.GROUP_SKILL, ConfigKeys.SKILL_MAX_VARIABLES},
            new String[]{ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.ANALYTICS_BATCH_MAX},
            new String[]{ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.ANALYTICS_ALLOWED_EVENTS},
            new String[]{ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.ANALYTICS_ANONYMOUS_ENABLED},
            // 🔴 V1.1.4 #1 补登的第 24 / 25 键：埋点总开关与采样率（读取侧 fail-closed）
            new String[]{ConfigKeys.GROUP_OBSERVABILITY,
                    ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED},
            new String[]{ConfigKeys.GROUP_OBSERVABILITY,
                    ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE},
            // 🔴 V1.1.3 / V1.3.2 第 23 键（① 裁决）：system 消息总长预算
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS},
            // 🔴 V1.4.2 ADR-017 / ADR-018 新增 3 键（api-spec §7.1.2 第 30 / 31 / 32 键，29 → 32）：
            //    生成业务总预算 + 收尾宽限 + 平台工具调用纪律段。
            //    🔴 纪律段同样"缺键即启动失败"且**不提供关闭开关**（它是编排正确性的一部分）。
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS},
            new String[]{ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE}
    );

    /**
     * 必备配置键清单（只读视图，供测试断言"api-spec §7.1.2 的 25 键已纳入"，AR-012）。
     */
    public static List<String> requiredConfigKeys() {
        return REQUIRED_CONFIG.stream().map(item -> item[0] + "." + item[1]).toList();
    }

    private final DataSource dataSource;
    private final StringRedisTemplate redis;
    private final ConfigService configService;
    private final AppProperties appProperties;
    private final TenantCacheKeys cacheKeys;
    private final Environment environment;

    /** 生产 profile 名（与 {@code DevHostMappingGuard} 保持一致）。 */
    private static final String PROD_PROFILE = "prod";

    /**
     * 耶瞳鉴权总开关的属性名（🔴 与 {@code EyesAuthConfig} 的 {@code @ConditionalOnProperty}
     * 同一属性；为 {@code false} 时鉴权切面整体不装配，见 architecture.md §8.2.1 / AR-018）。
     */
    private static final String EYES_AUTH_ENABLED_PROPERTY = "eyes-auth.enabled";

    /** JVM DNS 缓存 TTL 属性名（ADR-009 的 SSRF 等价实现依赖它）。 */
    private static final String DNS_CACHE_TTL_PROPERTY = "networkaddress.cache.ttl";
    /** ADR-009 裁定的取值（秒）。 */
    private static final String RECOMMENDED_DNS_CACHE_TTL = "10";

    /**
     * 传输层硬兜底属性名（🔴 ADR-017 的 L1：{@code SseEmitter} 之外的容器级异步超时）。
     *
     * <p>🔴 自 V1.4.2 起它<b>只是</b>传输层兜底，业务封顶已交给
     * {@code sys_config: chat.generation_deadline_seconds}（§9.2 / §9.5.4 不变量 2）。
     */
    private static final String ASYNC_REQUEST_TIMEOUT_PROPERTY = "spring.mvc.async.request-timeout";

    /**
     * 🔴 收尾宽限的下界（秒，ADR-017 ① 的不变量 ②）。
     *
     * <p>它是<b>契约常量</b>而非业务参数（与"函数名 ≤64 字符"、"采样率 ∈ [0,1]" 同类）：
     * 小于 5s 时"落库终态 + 写 error + 写 done"来不及完成，没有任何合法运维语义，
     * 因此不入 {@code sys_config}、也不提供可调开关。
     */
    private static final long MIN_DEADLINE_GRACE_SECONDS = 5L;

    /**
     * 🔴 平台工具调用纪律段的<b>建议</b>长度上界（码点，AR-021 ② / ADR-019 落点 #8）。
     *
     * <p>它是<b>可观测性阈值</b>而非业务参数，因此不入 {@code sys_config}、
     * 也<b>不影响任何运行时行为</b>（超过只打 WARN，🔴 绝不拒绝启动、绝不截断纪律段）：
     * 纪律段按 ADR-019 ③ 不计入任何长度预算，写成长篇会<b>无声挤占</b>上游输入窗口，
     * 表现为"上下文莫名不够 / {@code 50002} 变多"。把这句提示放在启动期，
     * 是为了让 AR-021 ③ 的排障顺序（先查纪律段长度，再查上游）有一个可观测信号。
     *
     * <p>🔴 判据同 {@code chat.system_prompt_max_chars} 的 WARN 先例：
     * 长文案仍可能是运维<b>有意为之</b>的调参，没有理由堵死这条路径。
     */
    private static final int GUIDELINE_ADVISORY_MAX_CODE_POINTS = 500;

    /**
     * 🔴 额度 / 限流阈值的<b>下界</b>（ADR-020 ⑩ 的两条拒绝启动不变量）。
     *
     * <p>它是<b>契约常量</b>而非业务参数（与 {@link #MIN_DEADLINE_GRACE_SECONDS} 同类）：
     * "阈值必须至少允许一次请求"没有任何可调空间，因此不入 {@code sys_config}。
     */
    private static final long MIN_QUOTA_THRESHOLD = 1L;

    public StartupChecker(DataSource dataSource,
                          StringRedisTemplate redis,
                          ConfigService configService,
                          AppProperties appProperties,
                          TenantCacheKeys cacheKeys,
                          Environment environment) {
        this.dataSource = dataSource;
        this.redis = redis;
        this.configService = configService;
        this.appProperties = appProperties;
        this.cacheKeys = cacheKeys;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        checkDatabase();
        checkRedis();
        checkRequiredConfig();
        checkConfigInvariants();
        checkAiConfig();
        checkProductionBlockers();
        checkDnsCacheTtl();
        log.info("启动自检通过：数据库 / Redis / 平台配置 / 模型接入 / 生产阻断项均可用");
    }

    /**
     * 配置取值不变量。
     *
     * <p>🔴 <b>三档规格有意不同，改动前务必读完</b>（api-spec §7.1.2 末尾）：
     * <pre>
     * ① chat.system_prompt_max_chars ≥ skill.instruction_max_chars → **仅 WARN**
     * ② observability.analytics_sample_rate ∈ [0.0, 1.0]          → 🔴 **拒绝启动**
     * ③ mcp.sse_stream_max_bytes ≥ tool.result_max_bytes          → 🔴 **拒绝启动**（V1.4.0）
     * </pre>
     *
     * <p><b>不变量 1（WARN；architecture.md §13.6 纪律 6 / ① 裁决）</b>：
     * 违反时"一个<b>完全合法</b>的满长 Skill 只要被绑定就必然 {@code 30060}"这一自相矛盾状态成立。
     * 🔴 但只 WARN 不拒绝启动：它是<b>取值合理性</b>问题而非<b>安全/语义</b>问题 ——
     * 运维需要按业务临时调小预算的空间（例如上游窗口收缩时先压 system 长度），
     * 升级为启动拒绝会把调参路径直接堵死。与 §9.5.4 不变量 2 的 WARN 先例同规格。
     *
     * <p><b>🔴 不变量 2（拒绝启动；V1.1.4 #1 裁决）</b>：采样率必须落在 {@code [0.0, 1.0]}。
     * 🔴 与不变量 1 的区别在于"区间外<b>有没有</b>合法语义"：
     * {@code system_prompt_max_chars} 调小仍是"运维可能有意为之的调参"，
     * 而 {@code 1.5} / {@code -0.2} 的采样率<b>没有任何合法语义</b> ——
     * 放行只会让"我以为在采 150%"的误配悄悄变成"全量"或（fail-closed 下）"全丢"。
     */
    private void checkConfigInvariants() {
        Integer systemPromptMax = optionalInt(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS);
        Integer instructionMax = optionalInt(ConfigKeys.GROUP_SKILL,
                ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS);
        checkAnalyticsSampleRate();
        checkSseStreamMaxBytes();
        checkGenerationDeadlineBudget();
        checkQuotaThresholds();
        checkToolUsageGuidelineLength();
        if (systemPromptMax == null || instructionMax == null) {
            // 缺键场景已由 checkRequiredConfig() 以启动失败拦下，这里无需重复处理
            return;
        }
        if (systemPromptMax < instructionMax) {
            log.warn("🔴 配置取值不变量被违反：sys_config[{}.{}]={} < sys_config[{}.{}]={}；"
                            + "单个满长 Skill 一旦被 Agent 版本绑定即必然以 30060 "
                            + "rule=systemPromptBudgetExceeded 失败，请复核该预算取值"
                            + "（api-spec §7.1.2 / architecture.md §13.6 纪律 6）",
                    ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, systemPromptMax,
                    ConfigKeys.GROUP_SKILL, ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS, instructionMax);
            return;
        }
        log.info("配置取值不变量校验通过：{}.{}={} ≥ {}.{}={}",
                ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS, systemPromptMax,
                ConfigKeys.GROUP_SKILL, ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS, instructionMax);
    }

    /**
     * 📋 <b>纪律段长度提示（WARN 级，🔴 不拒绝启动）</b>（AR-021 ② / ADR-019 落点 #8）。
     *
     * <p>纪律段自 V1.4.4 起<b>合并进唯一那条 {@code system} 消息</b>且不计入任何预算，
     * 因此长度失控只会以"上下文莫名不够 / {@code 50002} 变多"的形式间接暴露。
     * 这里把它变成一个显式信号：🔴 <b>只记码点数，绝不记文案正文</b>
     * （运行期的三块长度分列在 {@code ContextAssembler} 的注入日志里）。
     */
    private void checkToolUsageGuidelineLength() {
        String guideline = configService.find(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE).orElse(null);
        if (guideline == null || guideline.isBlank()) {
            // 缺键/空白由 checkRequiredConfig() 以启动失败拦下（该键在 REQUIRED_CONFIG 内）
            return;
        }
        int codePoints = guideline.codePointCount(0, guideline.length());
        if (codePoints > GUIDELINE_ADVISORY_MAX_CODE_POINTS) {
            log.warn("sys_config[{}.{}] 长度 {} 码点，超过建议上界 {}：它不计入 "
                            + "chat.system_prompt_max_chars，过长会无声挤占上游输入窗口"
                            + "（排障顺序见 architecture.md AR-021 ③）；🔴 本项仅提示，不影响启动",
                    ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_TOOL_USAGE_GUIDELINE, codePoints,
                    GUIDELINE_ADVISORY_MAX_CODE_POINTS);
        }
    }

    /**
     * 🔴 {@code observability.analytics_sample_rate ∈ [0.0, 1.0]}，否则<b>拒绝启动</b>
     * （api-spec §7.1.2 取值不变量，V1.1.4 #1）。
     *
     * <p>🔴 不可解析（如 {@code "abc"}）同样拒绝启动：读取侧是 fail-closed，
     * 一个写坏的值会让埋点<b>静默全丢</b>，而"静默全丢"正是启动检查存在的理由。
     */
    private void checkAnalyticsSampleRate() {
        String raw = configService.find(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE).orElse(null);
        if (raw == null || raw.isBlank()) {
            // 缺键由 checkRequiredConfig() 拦下（两键已在 REQUIRED_CONFIG）
            return;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("sys_config[" + ConfigKeys.GROUP_OBSERVABILITY + "."
                    + ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE
                    + "] 取值不是合法数字，埋点会 fail-closed 全部丢弃，请修正为 [0.0, 1.0] 区间内的数值");
        }
        if (value < 0.0d || value > 1.0d) {
            throw new IllegalStateException("sys_config[" + ConfigKeys.GROUP_OBSERVABILITY + "."
                    + ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE + "]=" + raw
                    + " 越界：采样率必须落在 [0.0, 1.0]（api-spec §7.1.2 取值不变量）");
        }
        log.info("配置取值不变量校验通过：{}.{}={} ∈ [0.0, 1.0]",
                ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE,
                raw.trim());
    }

    /**
     * 🔴 <b>{@code mcp.sse_stream_max_bytes ≥ tool.result_max_bytes}，否则拒绝启动</b>
     * （ADR-016 ⑥ / api-spec §7.1.2 取值不变量，V1.4.0）。
     *
     * <p>🔴 <b>为什么是"拒绝启动"而不是 WARN</b>（与 {@code chat.system_prompt_max_chars} 有意不同）：
     * 若 SSE 流字节上限小于工具结果上限，则"一个<b>完全合法</b>的满长工具结果"在 {@code sse}
     * 异步形态下<b>必然</b>触发超限关流并失败（{@code protocol_incompatible}）——
     * 该区间没有任何合法运维语义（不像"临时压小 system 预算"那样可能是有意调参），
     * 与 {@code analytics_sample_rate} 越界同类。
     *
     * <p>🔴 不可解析同样拒绝启动：{@code BusinessConfig.requireLong} 在运行期会抛
     * {@code 50003}，表现为"每次 sse 调用都莫名系统错误"，正是启动检查存在的理由。
     */
    private void checkSseStreamMaxBytes() {
        String raw = configService.find(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_SSE_STREAM_MAX_BYTES).orElse(null);
        String resultMaxRaw = configService.find(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_RESULT_MAX_BYTES).orElse(null);
        if (raw == null || raw.isBlank() || resultMaxRaw == null || resultMaxRaw.isBlank()) {
            // 缺键由 checkRequiredConfig() 拦下（两键均在 REQUIRED_CONFIG）
            return;
        }
        long streamMax = parseLongOrFail(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_SSE_STREAM_MAX_BYTES, raw);
        long resultMax = parseLongOrFail(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_RESULT_MAX_BYTES, resultMaxRaw);
        if (streamMax < resultMax) {
            throw new IllegalStateException("sys_config[" + ConfigKeys.GROUP_MCP + "."
                    + ConfigKeys.MCP_SSE_STREAM_MAX_BYTES + "]=" + streamMax + " 小于 sys_config["
                    + ConfigKeys.GROUP_TOOL + "." + ConfigKeys.TOOL_RESULT_MAX_BYTES + "]="
                    + resultMax + "：合法的满长工具结果在 sse 异步形态下必然失败"
                    + "（api-spec §7.1.2 取值不变量 / ADR-016 ⑥）");
        }
        log.info("配置取值不变量校验通过：{}.{}={} ≥ {}.{}={}",
                ConfigKeys.GROUP_MCP, ConfigKeys.MCP_SSE_STREAM_MAX_BYTES, streamMax,
                ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_RESULT_MAX_BYTES, resultMax);
    }

    private long parseLongOrFail(String group, String key, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("sys_config[" + group + "." + key
                    + "] 取值不是合法数字，请修正为字节数（NUMBER）");
        }
    }

    /**
     * 🔴 <b>ADR-017 的两条取值不变量（拒绝启动级）+ 两条 WARN</b>
     * （architecture.md §13.6 纪律 8/9 / api-spec §7.1.2 V1.2.2）。
     *
     * <pre>
     * 拒绝启动：
     *   ① chat.generation_deadline_seconds + chat.deadline_grace_seconds
     *        ≤ spring.mvc.async.request-timeout / 1000
     *   ② chat.deadline_grace_seconds ≥ 5
     * WARN（保留运维调参空间）：
     *   ③ chat.generation_deadline_seconds ≥ chat.first_token_timeout_seconds
     *   ④ tool.max_rounds ×(tool.max_timeout_seconds + tool.confirm_wait_seconds)
     *        ≤ chat.generation_deadline_seconds
     * </pre>
     *
     * <p>🔴 <b>为什么 ①② 是拒绝启动而不是 WARN</b>（判据同采样率："区间外有无合法语义"）：
     * 一旦业务预算 + 宽限 > 传输层上限，<b>传输层必然先超时</b> → {@code SseEmitter} 已关闭 →
     * 生成线程之后写的 {@code done} 会在 {@code SseWriter.markBroken} 里被静默丢弃 →
     * §9.5.4 不变量 1（{@code done} 必发）在该配置下<b>必然被违反</b> ——
     * 这正是 BUG-MCP-002 的成因，没有任何合法运维语义。
     * {@code grace < 5s} 同理：来不及完成"落库终态 + 写 {@code error} + 写 {@code done}"。
     *
     * <p>🔴 <b>为什么 ④ 只 WARN</b>：最坏情形（默认 5×(120+120)=1200s）远大于 300s 是
     * <b>有意为之</b>（单次生成不允许无限延长），只需提示运维"最坏跑不完全部轮次"。
     */
    private void checkGenerationDeadlineBudget() {
        Long deadline = strictLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS);
        Long grace = strictLong(ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS);
        if (deadline == null || grace == null) {
            // 缺键已由 checkRequiredConfig() 以启动失败拦下（两键均在 REQUIRED_CONFIG）
            return;
        }
        if (grace < MIN_DEADLINE_GRACE_SECONDS) {
            throw new IllegalStateException("sys_config[" + ConfigKeys.GROUP_CHAT + "."
                    + ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS + "]=" + grace + " 小于 "
                    + MIN_DEADLINE_GRACE_SECONDS + "：收尾宽限过短会来不及完成"
                    + "\"落库终态 + 写 error + 写 done\"，done 必发在该配置下无法保证"
                    + "（api-spec §7.1.2 取值不变量 ② / ADR-017 ①）");
        }
        Long transportSeconds = asyncRequestTimeoutSeconds();
        if (transportSeconds == null) {
            log.warn("🔴 未配置 {}：无法校验 ADR-017 的层间不等式"
                            + "（业务预算 {}s + 宽限 {}s 必须 ≤ 传输层硬兜底），请按 architecture.md "
                            + "§13.6 纪律 8 在 application.yml 显式配置",
                    ASYNC_REQUEST_TIMEOUT_PROPERTY, deadline, grace);
        } else if (deadline + grace > transportSeconds) {
            throw new IllegalStateException("sys_config[" + ConfigKeys.GROUP_CHAT + "."
                    + ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS + "]=" + deadline + " + sys_config["
                    + ConfigKeys.GROUP_CHAT + "." + ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS + "]="
                    + grace + " 超过 " + ASYNC_REQUEST_TIMEOUT_PROPERTY + "=" + transportSeconds
                    + "s：传输层会先于业务收敛掐断连接，done 帧物理上写不出去"
                    + "（api-spec §7.1.2 取值不变量 ① / ADR-017 ①，BUG-MCP-002 成因）");
        }

        Long firstToken = strictLong(ConfigKeys.GROUP_CHAT, ConfigKeys.FIRST_TOKEN_TIMEOUT_SECONDS);
        if (firstToken != null && deadline < firstToken) {
            log.warn("🔴 配置取值不变量被违反（WARN）：sys_config[{}.{}]={} < sys_config[{}.{}]={}；"
                            + "首字看门狗永不可达，请复核该预算取值"
                            + "（api-spec §7.1.2 WARN 级不变量 / architecture.md §13.6 纪律 9）",
                    ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, deadline,
                    ConfigKeys.GROUP_CHAT, ConfigKeys.FIRST_TOKEN_TIMEOUT_SECONDS, firstToken);
        }
        warnWorstCaseRoundBudget(deadline);
        log.info("生成预算不变量校验通过：{}.{}={}s + {}.{}={}s ≤ {}={}",
                ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, deadline,
                ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS, grace,
                ASYNC_REQUEST_TIMEOUT_PROPERTY,
                transportSeconds == null ? "(未配置)" : transportSeconds + "s");
    }

    /**
     * 最坏轮次预算超出生成预算 → WARN（🔴 不拒绝启动，见
     * {@link #checkGenerationDeadlineBudget()} 的"为什么 ④ 只 WARN"）。
     */
    private void warnWorstCaseRoundBudget(long deadline) {
        Long maxRounds = strictLong(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_ROUNDS);
        Long maxTimeout = strictLong(ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS);
        if (maxRounds == null || maxTimeout == null) {
            return;
        }
        long worstCase = maxRounds * maxTimeout;
        if (worstCase > deadline) {
            log.warn("最坏轮次预算 {}s = {}.{}({}) × {}.{}({}) 超过 {}.{}={}s："
                            + "最坏情形跑不完全部工具轮次，本次生成会以 done(timeout) 收敛"
                            + "（🔴 有意为之：单次生成不允许无限延长，architecture.md §13.6 纪律 9）",
                    worstCase, ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_ROUNDS, maxRounds,
                    ConfigKeys.GROUP_TOOL, ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS, maxTimeout,
                    ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS, deadline);
        }
    }

    /**
     * {@code spring.mvc.async.request-timeout} → 秒（🔴 未配置返回 {@code null}）。
     *
     * <p>它是 {@code application.yml} 的<b>基础设施</b>参数（§7.1 白名单），不是 {@code sys_config} 键，
     * 故从 {@link Environment} 读取；写法既支持毫秒数（{@code 600000}）也支持
     * Spring 的 Duration 简写（{@code 10m}）。
     */
    private Long asyncRequestTimeoutSeconds() {
        Duration timeout;
        try {
            timeout = environment.getProperty(ASYNC_REQUEST_TIMEOUT_PROPERTY, Duration.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(ASYNC_REQUEST_TIMEOUT_PROPERTY
                    + " 取值无法解析为时长（期望毫秒数或 Duration 简写），"
                    + "它是 ADR-017 层间不等式的传输层上限，必须可解析");
        }
        return timeout == null ? null : timeout.toSeconds();
    }

    /**
     * 读取长整型配置：缺失 / 空白返回 {@code null}；🔴 <b>存在但不可解析则拒绝启动</b>。
     *
     * <p>与 {@link #optionalInt} 的区别在于对"写坏的值"的态度：预算类键一旦写坏，
     * 运行期表现是"每次生成都 50003"（{@code BusinessConfig.requireLong} 无默认值兜底），
     * 正是启动检查存在的理由。
     */
    private Long strictLong(String group, String key) {
        String raw = configService.find(group, key).orElse(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseLongOrFail(group, key, raw);
    }

    /**
     * 读取整型配置（缺失或非数字返回 {@code null}，🔴 不抛异常 —— 本方法只服务于 WARN 级检查）。
     */
    private Integer optionalInt(String group, String key) {
        return configService.find(group, key)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return Integer.valueOf(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    /**
     * DNS 缓存 TTL 启动参数检查（ADR-009 / api-spec §7.6.3 的 SSRF 等价实现之③）。
     *
     * <p>🔴 <b>为什么只 WARN 不拒绝启动</b>：它是<b>纵深防御</b>的一环而非唯一防线 ——
     * 即便缺失，"每次调用前重新解析并逐 IP 校验 + 不跟随重定向 + 私网/环回/链路本地 CIDR 全覆盖"
     * 仍然生效；把它升级为启动拒绝会让"忘加一个 JVM 参数"变成"服务起不来"，
     * 与 {@code mcp.require_https} 这类<b>真正的</b>阻断项不同量级。
     *
     * <p>🔴 但缺失确实会放大风险：JVM 默认对<b>成功</b>的 DNS 解析<b>永久缓存</b>
     * （{@code networkaddress.cache.ttl=-1}），
     * 反而使"校验时的 IP"与"连接时的 IP"长期一致 —— 注意这<b>不是</b>安全收益：
     * 真正的问题是 TTL 过长会让"域名已被改指向内网"的变更无法被及时观察到，
     * 而我们的防护依赖"每次调用前的**新鲜**解析结果"。10 秒是 ADR-009 裁定的平衡点。
     */
    private void checkDnsCacheTtl() {
        String ttl = System.getProperty(DNS_CACHE_TTL_PROPERTY);
        if (ttl == null || ttl.isBlank()) {
            log.warn("🔴 未设置启动参数 -D{}={}：MCP 的 SSRF 防护依赖\"每次调用前的新鲜 DNS 解析\""
                            + "（ADR-009 / api-spec §7.6.3），请按 README §3.1 补齐启动参数",
                    DNS_CACHE_TTL_PROPERTY, RECOMMENDED_DNS_CACHE_TTL);
            return;
        }
        log.info("DNS 缓存 TTL 启动参数已设置：{}={}", DNS_CACHE_TTL_PROPERTY, ttl);
    }

    /**
     * 🔴 <b>ADR-020 的额度阈值不变量（2 条拒绝启动 + 1 条 WARN）</b>
     * （architecture.md §13.6 纪律 11 / api-spec §7.1.2 V1.2.5）。
     *
     * <pre>
     * 拒绝启动：
     *   ③ ratelimit.message_per_minute ≥ 1
     *   ④ ratelimit.daily_quota_limit  ≥ 1
     * WARN（保留运维调参空间）：
     *   ⑤ ratelimit.daily_quota_limit ≥ ratelimit.message_per_minute
     * </pre>
     *
     * <p>🔴 <b>为什么 ③④ 是拒绝启动而不是 WARN</b>（判据同采样率："区间外有无合法语义"）：
     * {@code ≤0} 意味着"任何人一次都不能发"，而"关闭 QPM / 关闭日限额"的<b>唯一合法表达</b>
     * 是把对应的 {@code *_enabled} 置 {@code false} —— 故 {@code ≤0} 没有任何合法运维语义，
     * 放行只会让一次误配把整站对话打死，且表现为"每个人第一条消息就被限流"，
     * 极难与真实限流区分。
     *
     * <p>🔴 <b>为什么 ⑤ 只 WARN</b>：日限额小于 QPM 时 QPM 永不先触发（用户一分钟内就能打完
     * 全天额度），但这是"运维<b>有意</b>收紧日额度"的合法调参（如试用租户 daily=2），
     * 不是自相矛盾状态 —— 同 {@code chat.system_prompt_max_chars} 的 WARN 先例。
     *
     * <p>🔴 两个开关键（{@code qpm_enabled} / {@code daily_quota_enabled}）只校验"存在"
     * （已在 {@code REQUIRED_CONFIG}）：它们的可解析性由 {@code BusinessConfig.requireBoolean}
     * 在运行期以 {@code 50003} 兜住，且写坏时表现为"额度接口与发送一律系统错误"（不会静默放行）。
     */
    private void checkQuotaThresholds() {
        Long perMinute = strictLong(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE);
        Long dailyLimit = strictLong(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT);
        if (perMinute == null || dailyLimit == null) {
            // 缺键已由 checkRequiredConfig() 以启动失败拦下（两键均在 REQUIRED_CONFIG）
            return;
        }
        requireAtLeastOne(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, perMinute);
        requireAtLeastOne(ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, dailyLimit);
        if (dailyLimit < perMinute) {
            log.warn("🔴 配置取值不变量被违反（WARN）：sys_config[{}.{}]={} < sys_config[{}.{}]={}；"
                            + "日限额小于 QPM 时分钟窗永不先触发（用户一分钟内即可打完全天额度）。"
                            + "若这是有意收紧日额度可忽略本提示"
                            + "（api-spec §7.1.2 WARN 级不变量 / architecture.md §13.6 纪律 11）",
                    ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, dailyLimit,
                    ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, perMinute);
            return;
        }
        log.info("额度阈值不变量校验通过：{}.{}={} ≥ 1 且 {}.{}={} ≥ 1",
                ConfigKeys.GROUP_RATELIMIT, ConfigKeys.MESSAGE_PER_MINUTE, perMinute,
                ConfigKeys.GROUP_RATELIMIT, ConfigKeys.DAILY_QUOTA_LIMIT, dailyLimit);
    }

    /**
     * 阈值下界校验（🔴 违反即拒绝启动）。
     *
     * <p>下界 {@code 1} 是<b>契约常量</b>而非业务参数（与"收尾宽限 ≥5s"、"采样率 ∈ [0,1]" 同类）：
     * 它表达的是"阈值必须至少允许一次请求"，不存在可调空间。
     */
    private void requireAtLeastOne(String group, String key, long value) {
        if (value < MIN_QUOTA_THRESHOLD) {
            throw new IllegalStateException("sys_config[" + group + "." + key + "]=" + value
                    + " 小于 " + MIN_QUOTA_THRESHOLD + "：该取值意味着任何人一次都不能发，"
                    + "而\"关闭 QPM / 关闭日限额\"的唯一合法表达是把对应 *_enabled 置 false"
                    + "（api-spec §7.1.2 取值不变量 ③④ / ADR-020 ⑩）");
        }
    }

    private void checkDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            // 🔴 不打印完整 JDBC URL（含账号信息的连接串不应进日志）
            log.info("数据库连接可用：catalog={}", connection.getCatalog());
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接数据库，请检查 application.yml 的 spring.datasource 配置", e);
        }
    }

    private void checkRedis() {
        try {
            // 键统一经 TenantCacheKeys 生成（禁止手拼字面量，见 TenantIsolationScanTest）
            redis.hasKey(cacheKeys.platform("startup", "probe"));
            log.info("Redis 连接可用");
        } catch (RuntimeException e) {
            throw new IllegalStateException("无法连接 Redis，请检查 application.yml 的 spring.data.redis 配置", e);
        }
    }

    private void checkRequiredConfig() {
        List<String> missing = REQUIRED_CONFIG.stream()
                .filter(item -> configService.find(item[0], item[1])
                        .filter(value -> !value.isBlank()).isEmpty())
                .map(item -> item[0] + "." + item[1])
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "sys_config 缺少必备配置项，请先通过 DDL/DML 初始化：" + missing);
        }
    }

    private void checkAiConfig() {
        AppProperties.Ai ai = appProperties.getAi();
        if (ai.getBaseUrl() == null || ai.getBaseUrl().isBlank()) {
            throw new IllegalStateException("app.ai.base-url 未配置，流式对话不可用");
        }
        if (ai.getApiKey() == null || ai.getApiKey().isBlank()) {
            throw new IllegalStateException("app.ai.api-key 未配置，流式对话不可用");
        }
    }

    /**
     * 生产阻断项（architecture.md §13.6 落地纪律第 3 条 / §8.2.1 纪律 7 / EX-028 / api-spec §7.13）。
     *
     * <p>为什么必须在启动时拦死而不是"运行时再判"：这三项都是<b>安全策略开关</b>，
     * 一旦在生产为宽松值，MCP 调用即可指向内网 / 云元数据（{@code 169.254.169.254}）、
     * 或整站鉴权切面不装配，属于可被直接利用的漏洞。与
     * {@code tenant.dev_host_mapping_enabled} 同规格：🔴 上线检查表项，命中即终止启动。
     *
     * <p>{@code test} profile 例外：内置 Mock MCP 位于环回地址，需要
     * {@code mcp.require_https=false} + {@code mcp.allowed_internal_cidrs=["127.0.0.1/32"]}
     * （api-spec §7.13）；测试也以 {@code eyes-auth.enabled=false} + 程序化兜底
     * （{@code auth/TenantRoleGuard}）验证越权拒绝。因此本校验只在 {@code prod} profile 生效。
     */
    private void checkProductionBlockers() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
        if (!prod) {
            return;
        }
        checkEyesAuthEnabled();
        boolean requireHttps = configService.getBoolean(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_REQUIRE_HTTPS, false);
        if (!requireHttps) {
            throw new IllegalStateException(
                    "生产环境必须启用 MCP HTTPS 强制校验：请将 sys_config[mcp.require_https] 置为 true");
        }
        String allowedInternal = configService.getString(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS, "[]");
        if (!"[]".equals(allowedInternal.replace(" ", ""))) {
            throw new IllegalStateException(
                    "生产环境禁止配置内网白名单：请将 sys_config[mcp.allowed_internal_cidrs] 置为 []");
        }
        log.info("生产阻断项校验通过：eyes-auth.enabled=true、mcp.require_https=true、"
                + "mcp.allowed_internal_cidrs=[]");
    }

    /**
     * 🔴 <b>{@code prod} profile 下 {@code eyes-auth.enabled} 必须为 {@code true}</b>
     * （architecture.md §8.2.1 纪律 7 / AR-018，V1.1.4 #6 裁决）。
     *
     * <p>🔴 <b>为什么它属于"启动即失败"级别</b>：{@code eyes-auth.enabled=false} 时
     * {@code EyesAuthConfig} 整个不装配 → {@code PermissionAspect} 与 {@code TenantRoleAspect}
     * <b>都不注册</b> → {@code @Permission} / {@code @TenantRole} 退化为装饰，
     * 接口以 {@code code=0} 返回租户数据且<b>不报任何错</b>（静默越权，最难发现的一类安全缺陷）。
     * 程序化兜底（{@code auth/TenantRoleGuard}）能挡住 {@code @TenantRole} 端点，
     * 但平台层 {@code @Permission} 的同类兜底是二期技术债 —— 因此生产必须在启动就拦死。
     *
     * <p>🔴 它<b>不是</b> {@code sys_config} 键而是 {@code application.yml} 的基础设施配置，
     * 故从 {@link Environment} 读取（与 {@code EyesAuthConfig} 的
     * {@code @ConditionalOnProperty} 同一属性名）。
     */
    private void checkEyesAuthEnabled() {
        boolean enabled = Boolean.parseBoolean(
                environment.getProperty(EYES_AUTH_ENABLED_PROPERTY, "false"));
        if (!enabled) {
            throw new IllegalStateException("生产环境必须启用耶瞳鉴权：请将 application.yml 的 "
                    + EYES_AUTH_ENABLED_PROPERTY + " 置为 true（为 false 时 @Permission / "
                    + "@TenantRole 切面整体不装配，等于全站放行，见 architecture.md §8.2.1 / AR-018）");
        }
    }
}
