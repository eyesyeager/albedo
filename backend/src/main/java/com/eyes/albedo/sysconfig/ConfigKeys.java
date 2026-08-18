package com.eyes.albedo.sysconfig;

/**
 * 平台级配置键常量（{@code sys_config} 表的 group/key 契约）。
 *
 * <p>存在意义：避免"反硬编码"退化为"把魔法字符串从数值搬到 key 名"。所有基础设施读取
 * {@code sys_config} 时必须使用本类常量，禁止字面量。
 *
 * <p>🔴 新增配置项必须：① 在 docs/architecture.md §13.3 的初始化数据表登记
 * ② 在此新增常量 ③ 由 @后端 插入 sys_config 记录。
 */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    // ===== group: tenant（租户识别基础设施） =====
    public static final String GROUP_TENANT = "tenant";
    /** 是否信任 {@code X-Forwarded-Host}（BOOLEAN）。 */
    public static final String TRUST_FORWARDED_HOST = "trust_forwarded_host";
    /** dev Host 映射开关（BOOLEAN）；🔴 生产必须 false，否则启动失败。 */
    public static final String DEV_HOST_MAPPING_ENABLED = "dev_host_mapping_enabled";
    /** dev Host 映射表（JSON）：{@code {"localhost:5173":"gift"}}。 */
    public static final String DEV_HOST_MAPPING = "dev_host_mapping";
    /** 租户解析缓存 TTL（NUMBER，秒）。 */
    public static final String HOST_CACHE_TTL_SECONDS = "host_cache_ttl_seconds";
    /** 租户解析空值哨兵 TTL（NUMBER，秒，防穿透）。 */
    public static final String HOST_ABSENT_TTL_SECONDS = "host_absent_ttl_seconds";
    /** 租户成员角色缓存 TTL（NUMBER，秒）；🔴 必须为分钟级，否则禁用成员无法及时生效。 */
    public static final String MEMBER_ROLE_CACHE_TTL_SECONDS = "member_role_cache_ttl_seconds";
    /** 成员最近访问时间的最小写库间隔（NUMBER，秒，避免每请求一次 UPDATE）。 */
    public static final String MEMBER_ACCESS_TOUCH_INTERVAL_SECONDS = "member_access_touch_interval_seconds";

    // ===== group: chat（对话） =====
    public static final String GROUP_CHAT = "chat";
    /** 单条消息最小 Unicode 字符数（NUMBER，去首尾空白后）。 */
    public static final String MESSAGE_MIN_CHARS = "message_min_chars";
    /** 单条消息最大 Unicode 字符数（NUMBER，前后端一致校验）。 */
    public static final String MESSAGE_MAX_CHARS = "message_max_chars";
    /** 会话标题最大字符数（NUMBER）。 */
    public static final String TITLE_MAX_CHARS = "title_max_chars";
    /** 自动标题取首条用户消息的前 N 个字符（NUMBER，PRD §6.5）。 */
    public static final String TITLE_AUTO_CHARS = "title_auto_chars";
    /** 上下文保留消息条数（NUMBER）。 */
    public static final String CONTEXT_MAX_MESSAGES = "context_max_messages";
    /**
     * 上下文消息窗口<b>总长预算</b>（NUMBER，Unicode 码点）。
     *
     * <p>🔴 <b>为什么"条数"不够、必须再加长度预算</b>：单条消息上限
     * {@link #MESSAGE_MAX_CHARS}（20000）× {@link #CONTEXT_MAX_MESSAGES}（20）
     * = 40 万字符，远超任何模型的上下文窗口。一旦某个会话的历史变长，
     * 上游会拒绝<b>整个请求</b>，而窗口每轮都会重新纳入同样的超长历史 ——
     * 表现为<b>该会话永久不可用</b>（用户重试多少次都失败，只能新建会话）。
     * 长度预算把窗口总长收敛为有界，从根上消除这种不可恢复态。
     */
    public static final String CONTEXT_MAX_CHARS = "context_max_chars";
    /** 是否启用历史摘要（BOOLEAN，失败降级为滑动窗口）。 */
    public static final String CONTEXT_SUMMARY_ENABLED = "context_summary_enabled";
    /** 历史摘要最大字符数（NUMBER）。 */
    public static final String CONTEXT_SUMMARY_MAX_CHARS = "context_summary_max_chars";
    /**
     * 历史摘要中<b>单条消息</b>的截断上限（NUMBER，Unicode 码点）。
     *
     * <p>🔴 原为代码里的字面量 {@code 120}，属反硬编码红线违规：它是业务参数，
     * 决定"摘要保留多少细节"，运维必须能在不改代码的前提下调整。
     */
    public static final String CONTEXT_SUMMARY_ITEM_MAX_CHARS = "context_summary_item_max_chars";
    /** 首字超时秒数（NUMBER）。 */
    public static final String FIRST_TOKEN_TIMEOUT_SECONDS = "first_token_timeout_seconds";
    /** 幂等键 TTL（NUMBER，秒）。 */
    public static final String IDEMPOTENCY_TTL_SECONDS = "idempotency_ttl_seconds";
    /** 生成取消标记 TTL（NUMBER，秒）。 */
    public static final String CANCEL_MARKER_TTL_SECONDS = "cancel_marker_ttl_seconds";
    /** 每 N 个分片检查一次 Redis 取消标记（NUMBER）。 */
    public static final String CANCEL_CHECK_INTERVAL_CHUNKS = "cancel_check_interval_chunks";
    /** SSE 无分片心跳间隔（NUMBER，秒）。 */
    public static final String STREAM_HEARTBEAT_SECONDS = "stream_heartbeat_seconds";
    /**
     * 🔴 M3 第 23 键（api-spec V1.1.3 §7.1.2 / §7.5.2 ① 裁决）：{@code ContextAssembler}
     * 拼出的 <b>system 消息总码点数</b>上限（NUMBER，默认 100000）。
     *
     * <p>判定对象 = {@code agent_versions.system_prompt} + 全部已绑定 Skill 的
     * {@code instruction} + {@code output_constraint}（🔴 按<b>变量替换后</b>的实际长度计，
     * 否则可用长变量值绕过）；度量口径 = {@code String.codePointCount}（不用 UTF-16 length，
     * 否则 emoji / 生僻字被算两次）。
     *
     * <p>🔴 超限 = <b>整体失败</b> {@code 30060} {@code rule=systemPromptBudgetExceeded}，
     * <b>禁止任何截断</b>：注入顺序（systemPrompt → instruction → output_constraint）本身是
     * 语义依赖链，截掉尾部 {@code output_constraint} 会让模型以"没有输出约束"的形态运行 ——
     * 那是<b>静默降级</b>，排障时表现为"AI 偶尔不守格式"却查不出原因。
     *
     * <p>🔴 取值不变量：必须 ≥ {@link #SKILL_INSTRUCTION_MAX_CHARS}，否则"单个合法的满长 Skill
     * 一旦绑定即必然 30060"这一自相矛盾状态成立；违反时 {@code StartupChecker} 打 <b>WARN</b>
     * （🔴 不拒绝启动 —— 运维需要调参空间）。
     */
    public static final String CHAT_SYSTEM_PROMPT_MAX_CHARS = "system_prompt_max_chars";
    /**
     * 🔴 V1.4.2 新增（ADR-017 ①，api-spec §7.1.2 第 30 键）：<b>单次生成的业务总预算</b>
     * （NUMBER，秒，库中默认 {@code 300}）= 全部模型轮次 + 工具执行 + <b>等待用户确认</b>之和。
     *
     * <p>🔴 它是"生成最长驻留"的<b>唯一权威</b>，取代原先由
     * {@code spring.mvc.async.request-timeout} 兼任的职责（后者降级为<b>纯传输层硬兜底</b>）。
     * 耗尽 → {@code error(50002)} + {@code done(finishReason=timeout, status=failed)}，
     * 已生成内容与 reasoning <b>必须落库保留</b>（EX-015）。
     *
     * <p>🔴 <b>为什么必须有这个键</b>（BUG-MCP-002 实测根因）：{@code SseEmitter} 曾用 Agent 的
     * {@code requestTimeoutSeconds}（<b>单轮</b>模型预算）当整条流的连接寿命，二者量纲不同 ——
     * {@code requestTimeoutSeconds=60s} 而 {@code tool.confirm_wait_seconds=120s} 时，
     * 连接必然在第一次确认等待期间被传输层掐断，此后写的 {@code done} 会被静默丢弃。
     *
     * <p>🔴 取值不变量见 {@link #CHAT_DEADLINE_GRACE_SECONDS}。
     */
    public static final String CHAT_GENERATION_DEADLINE_SECONDS = "generation_deadline_seconds";
    /**
     * 🔴 V1.4.2 新增（ADR-017 ①，api-spec §7.1.2 第 31 键）：<b>收尾宽限</b>
     * （NUMBER，秒，库中默认 {@code 15}），<b>一值两用</b>：
     * <pre>
     * ⓐ SseEmitter 的 timeout = generation_deadline + grace
     *    （🔴 传输层比业务多活 grace 秒，保证 error + done 一定写得出去）
     * ⓑ 业务侧在 remaining ≤ grace 时**不再开启任何新工作**
     *    （新轮模型 / 新确认卡 / 新工具执行）
     * </pre>
     *
     * <p>🔴 <b>取值不变量（拒绝启动级，architecture.md §13.6 纪律 8）</b>：
     * <pre>
     * ① generation_deadline_seconds + deadline_grace_seconds
     *      ≤ spring.mvc.async.request-timeout / 1000
     * ② deadline_grace_seconds ≥ 5
     * </pre>
     * 🔴 为什么是拒绝启动而非 WARN：业务预算 + 宽限 > 传输上限 → 传输层必然先超时 →
     * {@code done} 帧物理上写不出去 → "done 必发"在该配置下<b>必然被违反</b>，
     * 没有任何合法运维语义（{@code grace < 5s} 同理：来不及"落库终态 + 写 error + 写 done"）。
     */
    public static final String CHAT_DEADLINE_GRACE_SECONDS = "deadline_grace_seconds";
    /**
     * 🔴 V1.4.2 新增（ADR-018 ②，api-spec §7.1.2 第 32 键）：<b>平台级工具调用纪律段</b>
     * （STRING，文案见 api-spec §7.1.2 默认文案框，🔴 <b>代码中禁止出现该文案字面量</b>）。
     *
     * <p>注入口径（api-spec §7.5.2 ⑥）：
     * <ul>
     *   <li>🔴 <b>仅当本次生成确实下发了 {@code tools}</b>（工具清单非空）时注入；无工具的生成零影响</li>
     *   <li>🔴 <b>独立的第二条 {@code system} 消息</b>（不与租户段拼接 —— 否则排障时
     *       无法区分"谁说的这句话"，且会连带吃掉租户预算）</li>
     *   <li>🔴 <b>不计入</b> {@link #CHAT_SYSTEM_PROMPT_MAX_CHARS}：该预算的判定对象恒为
     *       "system_prompt + Skill instruction + output_constraint"（<b>租户配置</b>），
     *       平台段不在其列；若计入，本次变更会把既有满配租户直接打成 {@code 30060}</li>
     *   <li>🔴 唯一占位符 {@code {{currentTime}}}（服务器当前时间 ISO-8601）——
     *       直接消灭"模型按训练期知识把『最近』推算成 2024 年时间戳"这一实测失败模式</li>
     * </ul>
     *
     * <p>🔴 缺键 / 空白即<b>启动失败</b>（已入 {@code REQUIRED_CONFIG}）：它是编排正确性的一部分，
     * 🔴 <b>不提供关闭开关</b>——要弱化引导请改文案本身。
     */
    public static final String CHAT_TOOL_USAGE_GUIDELINE = "tool_usage_guideline";

    // ===== group: business（通用业务参数） =====
    public static final String GROUP_BUSINESS = "business";
    /** 默认分页大小（NUMBER）。 */
    public static final String PAGE_SIZE_DEFAULT = "page_size_default";
    /** 最大分页大小（NUMBER）。 */
    public static final String PAGE_SIZE_MAX = "page_size_max";

    // ===== group: display（前端展示映射） =====
    public static final String GROUP_DISPLAY = "display";
    /** 工具调用状态展示映射（JSON，M3）。 */
    public static final String TOOL_STATUS_LABELS = "tool_status_labels";
    /** 工具风险等级展示文案（JSON，M3；🔴 前端禁止硬编码）。 */
    public static final String TOOL_RISK_LABELS = "tool_risk_labels";

    // ===== group: tool（工具编排，M3；api-spec §7.1.2） =====
    public static final String GROUP_TOOL = "tool";
    /** 单次生成的工具调用轮次上限（NUMBER），超限 → 30054。 */
    public static final String TOOL_MAX_ROUNDS = "max_rounds";
    /** 高风险工具确认等待上限（NUMBER，秒）；超时按拒绝收敛。 */
    public static final String TOOL_CONFIRM_WAIT_SECONDS = "confirm_wait_seconds";
    /** 生成线程等待确认的兜底轮询间隔（NUMBER，毫秒）。 */
    public static final String TOOL_CONFIRM_POLL_INTERVAL_MILLIS = "confirm_poll_interval_millis";
    /** 工具执行默认超时（NUMBER，秒）。 */
    public static final String TOOL_DEFAULT_TIMEOUT_SECONDS = "default_timeout_seconds";
    /** 工具执行超时上限（NUMBER，秒）；注册值越界 → 30060。 */
    public static final String TOOL_MAX_TIMEOUT_SECONDS = "max_timeout_seconds";
    /** 单次工具结果字节上限（NUMBER）；超出截断并标记 truncated。 */
    public static final String TOOL_RESULT_MAX_BYTES = "result_max_bytes";
    /** argsSummary 截断阈值（NUMBER，字符）。 */
    public static final String TOOL_ARGS_SUMMARY_MAX_CHARS = "args_summary_max_chars";
    /** resultSummary 截断阈值（NUMBER，字符）。 */
    public static final String TOOL_RESULT_SUMMARY_MAX_CHARS = "result_summary_max_chars";

    // ===== group: mcp（MCP 治理与运行时，M2-min/M3） =====
    public static final String GROUP_MCP = "mcp";
    /** endpoint 是否强制 HTTPS（BOOLEAN）；🔴 生产必须 true，否则启动失败。 */
    public static final String MCP_REQUIRE_HTTPS = "require_https";
    /** 建连超时（NUMBER，秒）。 */
    public static final String MCP_CONNECT_TIMEOUT_SECONDS = "connect_timeout_seconds";
    /** tools/call 超时（NUMBER，秒）；与 mcp_servers.timeout_seconds 取较小值。 */
    public static final String MCP_CALL_TIMEOUT_SECONDS = "call_timeout_seconds";
    /** tools/list 超时（NUMBER，秒）。 */
    public static final String MCP_DISCOVER_TIMEOUT_SECONDS = "discover_timeout_seconds";
    /** SSRF 禁止范围（JSON 数组，CIDR）。 */
    public static final String MCP_BLOCKED_IP_CIDRS = "blocked_ip_cidrs";
    /** 平台授权内网白名单（JSON 数组，CIDR）；🔴 生产必须为 []，否则启动失败。 */
    public static final String MCP_ALLOWED_INTERNAL_CIDRS = "allowed_internal_cidrs";
    /** 首选传输（STRING）。 */
    public static final String MCP_TRANSPORT_PREFERRED = "transport_preferred";
    /** 单服务发现工具数上限（NUMBER）；超出 → 30060。 */
    public static final String MCP_MAX_TOOLS_PER_SERVER = "max_tools_per_server";
    /**
     * 🔴 V1.4.0 新增（ADR-016 ⑨ / api-spec §7.1.2 第 28 键）：是否允许 {@code sse} 传输进入
     * <b>MCP 2024-11-05 异步推送形态</b>（POST 回 {@code 202} 空体、结果从 GET 事件流推送）。
     *
     * <p>BOOLEAN，库中默认 {@code true}。🔴 <b>读取侧 fail-closed</b>
     * （缺行 / 不可解析 → 按 {@code false}）：本键是"要不要<b>发起并持有</b>一条 SSE 流"的
     * <b>能力开关</b>，按 §11 一般原则取保守值 —— 关闭即<b>完整回到</b> ADR-016 之前的行为
     * （POST 空体 → {@code protocol_incompatible} / {@code 30052}），
     * 给运维留一个<b>不改代码的止血手段</b>。
     *
     * <p>🔴 一般原则复述：<b>"要不要拦"的开关可 fail-open，"要不要采/写/连"的开关必须 fail-closed</b>。
     */
    public static final String MCP_SSE_LEGACY_ENABLED = "sse_legacy_enabled";
    /**
     * 🔴 V1.4.0 新增（ADR-016 ⑥ / api-spec §7.1.2 第 29 键）：单次 exchange 内 SSE 流的
     * <b>累计字节上限</b>（NUMBER，库中默认 {@code 4194304} = 4MB）。
     *
     * <p>超限 → 强制关流 + {@code protocol_incompatible} + {@code [SECURITY]} 日志。
     * 它是"无限流"的唯一硬防线：上游若持续推送噪声帧，没有该上限就会把内存与一个挂起线程
     * 一起拖死（AR-020 ②）。
     *
     * <p>🔴 <b>取值不变量</b>：必须 ≥ {@link #TOOL_RESULT_MAX_BYTES}，否则"一个<b>完全合法</b>的
     * 满长工具结果必然失败"这一自相矛盾状态成立 —— 违反时 {@code StartupChecker}
     * <b>拒绝启动</b>（⚠️ 与 {@link #CHAT_SYSTEM_PROMPT_MAX_CHARS} 的"仅 WARN"规格<b>有意不同</b>：
     * 小于结果上限没有任何合法运维语义，与采样率越界同类）。
     */
    public static final String MCP_SSE_STREAM_MAX_BYTES = "sse_stream_max_bytes";

    // ===== group: skill（Skill 版本，M2-min/M3） =====
    public static final String GROUP_SKILL = "skill";
    /** Skill 指令正文字符上限（NUMBER）；超限 → 30060。 */
    public static final String SKILL_INSTRUCTION_MAX_CHARS = "instruction_max_chars";
    /** 单 Skill 声明变量数上限（NUMBER）。 */
    public static final String SKILL_MAX_VARIABLES = "max_variables";

    // ===== group: model（模型提供方清单） =====
    public static final String GROUP_MODEL = "model";
    /** 平台允许的模型提供方与模型清单（JSON）。 */
    public static final String MODEL_PROVIDERS = "providers";

    // ===== group: observability（埋点与可观测，M3） =====
    public static final String GROUP_OBSERVABILITY = "observability";
    /**
     * 埋点总开关（BOOLEAN，默认 {@code true}）。
     *
     * <p>🔴 <b>V1.1.4 #1 裁决：正式登记为第 24 键</b>（api-spec §7.1.2 /
     * architecture.md §13.6）。此前 §7.1.2 末注把本键与 {@link #OBSERVABILITY_ANALYTICS_SAMPLE_RATE}
     * 误列为"沿用既有键"，实则从未在任何登记表出现 —— 属**登记缺口**，已订正。
     *
     * <p>🔴 已纳入 {@code StartupChecker.REQUIRED_CONFIG}（缺键即<b>启动失败</b>），
     * 且读取侧为 <b>fail-closed</b>：缺失 / 不可解析 → 按 {@code false}（<b>全部丢弃</b>）。
     * 🔴 <b>禁止改回 fail-open</b>：那会让"配置一旦丢失就默默转为全量采集"，
     * 把唯一的关停手段变成"失效即最大化采集"（不可撤销的隐私事实）。
     * 一般原则：<b>"要不要拦"的开关可 fail-open，"要不要采/写"的开关必须 fail-closed</b>。
     */
    public static final String OBSERVABILITY_ANALYTICS_ENABLED = "analytics_enabled";
    /**
     * 埋点采样率（NUMBER，默认 {@code 1.0}）；以 {@code hash(clientEventId)} <b>稳定</b>采样。
     *
     * <p>🔴 <b>V1.1.4 #1 裁决：正式登记为第 25 键</b>，同样纳入 {@code REQUIRED_CONFIG}。
     *
     * <p>🔴 取值不变量：必须落在 <b>[0.0, 1.0]</b>（含端点），
     * 违反时 {@code StartupChecker} <b>拒绝启动</b>
     * （⚠️ 与 {@link #CHAT_SYSTEM_PROMPT_MAX_CHARS} 的"仅 WARN"规格<b>有意不同</b>：
     * 区间外的采样率没有任何合法语义，而预算键调小仍可能是运维有意为之的调参）。
     * 🔴 读取侧 fail-closed：缺失 / 不可解析 / 越界 → 按 {@code 0.0}（全部丢弃）。
     */
    public static final String OBSERVABILITY_ANALYTICS_SAMPLE_RATE = "analytics_sample_rate";
    /** POST /api/v1/events 单次批量上限（NUMBER）。 */
    public static final String ANALYTICS_BATCH_MAX = "analytics_batch_max";
    /** 允许上报的事件名白名单（JSON 数组）；未命中整条 discarded。 */
    public static final String ANALYTICS_ALLOWED_EVENTS = "analytics_allowed_events";
    /** 是否接收匿名事件（BOOLEAN）。 */
    public static final String ANALYTICS_ANONYMOUS_ENABLED = "analytics_anonymous_enabled";

    // ===== group: ratelimit（限流与每日额度，M3 / M3.1） =====
    public static final String GROUP_RATELIMIT = "ratelimit";
    /**
     * 每用户每分钟消息数上限的<b>平台默认值</b>（NUMBER，库中默认 {@code 3}）。
     *
     * <p>🔴 V1.4.5（ADR-020）起它只是<b>平台默认</b>：最终生效值由
     * {@code quota/QuotaPolicyResolver} 合成（租户 {@code tenant_quota_policies.qpm_limit}
     * 非 {@code NULL} 时覆盖），并作为<b>入参</b>传给 {@code MessageRateLimiter}。
     *
     * <p>🔴 取值不变量（拒绝启动级）：{@code ≥ 1} —— {@code ≤0} 意味着"任何人一次都不能发"，
     * 而"关闭 QPM"的唯一合法表达是 {@link #QPM_ENABLED} 置 {@code false}。
     */
    public static final String MESSAGE_PER_MINUTE = "message_per_minute";
    /**
     * 🔴 V1.4.5 新增（ADR-020 / api-spec §7.1.2 第 33 键）：<b>QPM 限流总开关的平台默认值</b>
     * （BOOLEAN，库中默认 {@code true}）。
     *
     * <p>{@code false} → 准入五步的第 3 步<b>整步跳过</b>（🔴 不影响日限额）。
     *
     * <p>🔴 读取一律走 {@code BusinessConfig.requireBoolean}：本键缺失 / 不可解析 → {@code 50003}。
     * 它虽是"拦截型开关"（§11 原则允许 fail-open），但<b>反硬编码红线优先</b> ——
     * 平台默认值缺失必须暴露，不得静默按 {@code true} 或 {@code false} 继续。
     */
    public static final String QPM_ENABLED = "qpm_enabled";
    /**
     * 🔴 V1.4.5 新增（ADR-020 / api-spec §7.1.2 第 34 键）：<b>每日额度总开关的平台默认值</b>
     * （BOOLEAN，库中默认 {@code true}）。
     *
     * <p>{@code false} → 该租户用户不受每日次数限制，额度快照为
     * {@code status=unlimited} + {@code limit=null} + {@code remaining=null}
     * （🔴 <b>禁止</b>伪造一个数值上限，也禁止把真实 {@code used} 伪造成 0）。
     */
    public static final String DAILY_QUOTA_ENABLED = "daily_quota_enabled";
    /**
     * 🔴 V1.4.5 新增（ADR-020 / api-spec §7.1.2 第 35 键）：<b>每日额度阈值的平台默认值</b>
     * （NUMBER，库中默认 {@code 50}；单位 = 次 / 租户当地日历日 / 用户）。
     *
     * <p>🔴 取值不变量：拒绝启动级 {@code ≥ 1}；WARN 级 {@code ≥ } {@link #MESSAGE_PER_MINUTE}
     * （日限额小于 QPM 时 QPM 永不先触发，但那是"运维有意收紧日额度"的合法调参）。
     *
     * <p>🔴 代码中禁止出现该默认值字面量：租户可用 {@code tenant_quota_policies.daily_quota_limit}
     * 覆盖，非 {@code NULL} 且非法（{@code <1}）时 🔴 {@code 50003}，<b>禁止静默继承平台默认</b>。
     */
    public static final String DAILY_QUOTA_LIMIT = "daily_quota_limit";
    // 🔴 V1.4.5（ADR-020 ⑦ / §13.6 纪律 10）：ratelimit.message_per_hour 已**废弃**，
    //    常量随代码分支一并删除 —— 留一个无人读的常量会诱导复用，并留下
    //    "库里写着 120、改它却毫无效果"的幽灵配置（AR-021 要消灭的"配置骗人"模式）。
}
