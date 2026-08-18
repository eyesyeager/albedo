package com.eyes.albedo.metrics.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.eyes.albedo.agent.repository.AgentRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.conversation.repository.ConversationRepository;
import com.eyes.albedo.metrics.AnalyticsFieldGuard;
import com.eyes.albedo.metrics.dto.AnalyticsBatchRequest;
import com.eyes.albedo.metrics.dto.AnalyticsBatchResultDTO;
import com.eyes.albedo.metrics.dto.AnalyticsEventRequest;
import com.eyes.albedo.metrics.entity.AnalyticsEvent;
import com.eyes.albedo.metrics.repository.AnalyticsEventRepository;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 产品埋点上报（api-spec §7.10.1，REQ-OBS-001 / AC-OBS-001）。
 *
 * <p><b>🔴 三条不可妥协的纪律</b>：
 * <pre>
 * ① 绝不拖慢主链路：本服务只被 POST /api/v1/events 这条**独立**请求调用，
 *    🔴 对话热路径（ChatController / ChatStreamRunner）**不调用**本类，
 *    也不存在"生成过程中同步写埋点"的路径。写库失败一律吞掉并计入 discarded，
 *    响应恒 code=0 —— 埋点是观测手段，不是业务功能。
 * ② 服务端强制补齐 tenantId：租户来自 Host 解析（TenantContext），
 *    🔴 请求体内的 tenantId **一律忽略**（DTO 上根本没有该字段）并记安全日志（EX-003）。
 * ③ 字段白名单 + 值级拦截双层防御（AnalyticsFieldGuard）：
 *    🔴 禁止落库消息正文 / systemPrompt / Skill 正文 / token / 凭据 / 完整手机号邮箱。
 * </pre>
 *
 * <p><b>🔴 第四条（V1.1.4 #1 裁决新增）：开关与采样率的读取一律 fail-closed</b>
 * （见 {@link #analyticsEnabled()} / {@link #sampleRate()}）——
 * {@code observability.analytics_enabled} / {@code analytics_sample_rate} 缺失、不可解析或越界时
 * <b>全部丢弃</b>并记 <b>ERROR</b>，🔴 <b>严禁</b>回退为"全量接收 + WARN"。
 * 判据：可恢复的功能损失（埋点缺一段）< 不可撤销的隐私损失（超范围采集）。
 *
 * <p><b>🔴 去重与采样必须相互一致</b>（api-spec §7.10.1 采样第 2 条）：
 * 采样判定用 {@code hash(clientEventId)} 而<b>不是</b>随机数 —— 否则同一条事件重试时
 * "第一次被采样丢弃、第二次被接受"，去重键就失去意义（同一事实可能落库也可能不落库，
 * 统计结果无法复现）。
 *
 * <p><b>🔴 事务边界</b>：🔴 <b>整批不开共享事务</b>，逐条 {@code save} 各自提交。
 * 原因见 {@code persist(...)} 的注释 —— 共享事务下"catch 住唯一键冲突继续处理"
 * 会在 commit 时整批失败，与"重复只计 duplicated、其余照常接受"的契约直接冲突。
 */
@Slf4j
@Service
public class AnalyticsEventService {

    /** {@code clientEventId} 格式（api-spec §7.10.1）。 */
    private static final Pattern CLIENT_EVENT_ID =
            Pattern.compile(AnalyticsEvent.CLIENT_EVENT_ID_PATTERN);

    /** 与服务端时间的最大偏差（🔴 协议级防御，非业务阈值，故不入 sys_config）。 */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofHours(24);

    /** 数值字段上限（api-spec §7.10.1 字段表：0 ~ 600000）。 */
    private static final int DURATION_MAX_MILLIS = 600_000;

    /** {@code source} / {@code action} / {@code status} 的长度上限（同上，来自字段表）。 */
    private static final int SHORT_TEXT_MAX = 32;
    private static final int STATUS_MAX = 32;
    private static final int TOOL_KEY_MAX = 64;
    private static final int PAGE_PATH_MAX = 512;

    private final AnalyticsEventRepository repository;
    private final ConversationRepository conversationRepository;
    private final AgentRepository agentRepository;
    private final BusinessConfig businessConfig;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public AnalyticsEventService(AnalyticsEventRepository repository,
                                ConversationRepository conversationRepository,
                                AgentRepository agentRepository,
                                BusinessConfig businessConfig,
                                ConfigService configService,
                                ObjectMapper objectMapper) {
        this.repository = repository;
        this.conversationRepository = conversationRepository;
        this.agentRepository = agentRepository;
        this.businessConfig = businessConfig;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /**
     * 批量上报。
     *
     * @param uid 当前登录 uid；{@code null} = 匿名事件
     * @throws BusinessException 10001 {@code events} 为空 / 超批量上限 / 缺 {@code clientEventId}
     *                           或 {@code eventName}；30010/30011 未知或暂停租户
     */
    public AnalyticsBatchResultDTO report(Long uid, AnalyticsBatchRequest request) {
        // 🔴 租户由 Host 解析且必须可用；请求体内的 tenantId 根本不存在（DTO 无该字段）
        String tenantId = TenantContext.requireEnabled().tenantId();
        List<AnalyticsEventRequest> events = request == null ? null : request.events();
        int batchMax = businessConfig.requireInt(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.ANALYTICS_BATCH_MAX);
        if (events == null || events.isEmpty()) {
            throw BusinessException.validation("events 不能为空");
        }
        if (events.size() > batchMax) {
            throw BusinessException.validation("单次上报不能超过 " + batchMax + " 条");
        }

        // 🔴 总开关关闭 → 全部丢弃，仍返回 code=0（前端无需感知，api-spec §7.10.1 采样第 1 条）
        if (!analyticsEnabled()) {
            log.debug("埋点总开关关闭，本批全部丢弃：tenantId={} size={}", tenantId, events.size());
            return new AnalyticsBatchResultDTO(0, 0, events.size());
        }

        List<AnalyticsEventRequest> candidates = new ArrayList<>(events.size());
        int discarded = 0;
        double sampleRate = sampleRate();
        for (AnalyticsEventRequest event : events) {
            // 🔴 必填缺失是**契约违约**（不是脏数据）→ 10001，让前端立刻发现自己传错了
            if (event == null || isBlank(event.getClientEventId()) || isBlank(event.getEventName())) {
                throw BusinessException.validation("clientEventId 与 eventName 必填");
            }
            if (!CLIENT_EVENT_ID.matcher(event.getClientEventId()).matches()) {
                throw BusinessException.validation("clientEventId 格式非法");
            }
            if (!acceptable(tenantId, uid, event, sampleRate)) {
                discarded++;
                continue;
            }
            candidates.add(event);
        }
        if (candidates.isEmpty()) {
            return new AnalyticsBatchResultDTO(0, 0, discarded);
        }

        // 🔴 批量预检去重：一次 IN 查询（走 uk_tenant_client_event），禁止逐条 exists（N+1）
        Set<String> existing = new HashSet<>(repository
                .findByClientEventIdIn(candidates.stream()
                        .map(AnalyticsEventRequest::getClientEventId).distinct().toList())
                .stream().map(AnalyticsEvent::getClientEventId).toList());

        return persist(tenantId, uid, candidates, existing, discarded);
    }

    /**
     * 落库（🔴 <b>逐条独立提交，有意不开批事务</b>）。
     *
     * <p><b>🔴 为什么不能包一个共享事务</b>（这是最容易写错的地方，写错了测试还看不出来）：
     * <pre>
     * 契约要求"重复条目只计入 duplicated，其余照常接受"。
     * 若整批共享一个事务，则某条撞唯一键抛 DataIntegrityViolationException 后，
     * 该事务已被标记 rollback-only —— 🔴 我们 catch 住继续 save 也没用，
     *   commit 时会整批失败（UnexpectedRollbackException），
     *   表现为"一条重复导致整批丢失"，与契约直接冲突。
     * 因此让 JpaRepository.save 各自开事务（Spring Data 默认 REQUIRED，
     * 无外层事务时即一条一个事务），一条失败只影响它自己。
     * </pre>
     * 代价：50 条上限下最多 50 次提交。埋点是低频旁路写入且<b>不在任何主链路上</b>，
     * 这个代价换来的是"单条脏数据不污染整批"，值得。
     *
     * <p>🔴 本方法<b>刻意不加 {@code @Transactional}</b>：加了反而破坏上述语义；
     * 同时它也是自调用（Spring 代理不介入），加了也不会生效 —— 两个理由指向同一个结论。
     */
    private AnalyticsBatchResultDTO persist(String tenantId, Long uid,
                                            List<AnalyticsEventRequest> candidates,
                                            Set<String> existing, int discardedSoFar) {
        int accepted = 0;
        int duplicated = 0;
        int discarded = discardedSoFar;
        Set<String> seenInBatch = new HashSet<>();
        for (AnalyticsEventRequest event : candidates) {
            String clientEventId = event.getClientEventId();
            // 🔴 同一批内重复也按 duplicated 处理（否则会撞唯一键并让本条抛异常）
            if (existing.contains(clientEventId) || !seenInBatch.add(clientEventId)) {
                duplicated++;
                continue;
            }
            try {
                repository.save(toEntity(tenantId, uid, event));
                accepted++;
            } catch (DataIntegrityViolationException e) {
                // 并发上报撞唯一键：语义就是"重复"，🔴 静默丢弃（绝不报错给前端）
                duplicated++;
            } catch (RuntimeException e) {
                // 🔴 埋点写入失败绝不影响任何业务请求：如实计入 discarded 并告警
                log.warn("埋点事件写入失败，已计入 discarded：tenantId={} eventName={}",
                        tenantId, event.getEventName());
                discarded++;
            }
        }
        log.debug("埋点上报处置：tenantId={} accepted={} duplicated={} discarded={}",
                tenantId, accepted, duplicated, discarded);
        return new AnalyticsBatchResultDTO(accepted, duplicated, discarded);
    }

    // ===================== 判定 =====================

    /**
     * 是否接受该事件（🔴 任一不满足 → 整条 {@code discarded}，不报错、不回显原因）。
     */
    private boolean acceptable(String tenantId, Long uid, AnalyticsEventRequest event,
                               double sampleRate) {
        // ① 事件名白名单（api-spec §7.10.1）：不在白名单 → 丢弃并计数，🔴 不得整批失败
        if (!allowedEvents().contains(event.getEventName())) {
            log.debug("埋点事件名未命中白名单，丢弃：eventName={}", event.getEventName());
            return false;
        }
        // ② 匿名开关
        if (uid == null && !anonymousEnabled()) {
            return false;
        }
        // ③ 时间偏差
        Instant occurredAt = parseInstant(event.getOccurredAt());
        if (occurredAt == null) {
            return false;
        }
        Duration skew = Duration.between(occurredAt, Instant.now()).abs();
        if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
            return false;
        }
        // ④ 🔴 禁止字段 / 敏感取值（双层拦截）→ 整条丢弃 + 安全日志（不回显命中细节）
        //    🔴 顺序要点：pagePath 必须**先剥离 query/hash 再判定**。
        //    SSO 回跳后 URL 里**本来就会**带 ?authorization=<jwt>（PRD §6.2.2），
        //    若先判定后剥离，几乎所有页面浏览类埋点都会被判"命中 Token"而整条丢弃 ——
        //    那不是安全，是把 tenantSiteView 这类核心指标全部丢掉。
        //    剥离之后仍命中，才说明**路径本身**被污染（真正该丢的情况）。
        if (AnalyticsFieldGuard.hasForbiddenKey(event.extras())
                || AnalyticsFieldGuard.anySensitive(event.getSource(),
                sanitizePagePath(event.getPagePath()), event.getToolKey(), event.getStatus(),
                event.getAction())) {
            log.warn("[SECURITY] 埋点事件命中禁止字段或敏感取值，整条丢弃：tenantId={} eventName={}",
                    tenantId, event.getEventName());
            return false;
        }
        // ⑤ 采样（🔴 按 clientEventId 稳定采样，保证多次上报判定一致）
        return sampled(event.getClientEventId(), sampleRate);
    }

    /**
     * 稳定采样：{@code sha256(clientEventId)} 前 4 字节映射到 {@code [0,1)}。
     *
     * <p>🔴 <b>不用 {@code Random}、不用 {@code String.hashCode()}</b>：
     * 前者破坏"同一 clientEventId 判定一致"；后者在不同 JVM 版本/实现上不保证稳定，
     * 会让同一事件在滚动发布期间前后不一致。
     */
    private boolean sampled(String clientEventId, double sampleRate) {
        if (sampleRate >= 1.0d) {
            return true;
        }
        if (sampleRate <= 0.0d) {
            return false;
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clientEventId.getBytes(StandardCharsets.UTF_8));
            long value = ((long) (digest[0] & 0xFF) << 24) | ((long) (digest[1] & 0xFF) << 16)
                    | ((long) (digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
            return (value / (double) 0xFFFFFFFFL) < sampleRate;
        } catch (java.security.NoSuchAlgorithmException e) {
            // 🔴 SHA-256 是 JDK 强制算法，不可能缺失；真缺失时按 fail-closed 全部丢弃
            //    （V1.1.4 #1：采集类判定的失败方向必须是"不采"，绝不是"全采"）
            log.error("🔴 SHA-256 不可用，采样判定按 fail-closed 处理：本条埋点丢弃");
            return false;
        }
    }

    // ===================== 映射 =====================

    /**
     * 入参 → 实体（🔴 <b>只映射白名单字段</b>；越界值一律置空而非报错）。
     *
     * <p>为什么越界置空而不是丢弃整条：这些是<b>可选</b>的辅助字段，
     * 一个越界的 {@code durationMs} 不影响事件本身的统计价值；
     * 而丢掉整条会让"消息发送次数"这类核心指标凭空少一条。
     */
    private AnalyticsEvent toEntity(String tenantId, Long uid, AnalyticsEventRequest request) {
        AnalyticsEvent entity = new AnalyticsEvent();
        // 🔴 tenantId 由 Hibernate discriminator 自动填充（BaseTenantEntity），
        //    业务代码禁止 set —— 这正是"客户端传的 tenantId 不可能生效"的结构性保证
        entity.setClientEventId(request.getClientEventId());
        entity.setEventName(request.getEventName());
        entity.setOccurredAt(parseInstant(request.getOccurredAt()));
        entity.setUid(uid);
        // 🔴 匿名事件强制 anonymous：不信任客户端自称的 loginState
        entity.setLoginState(uid == null
                ? AnalyticsEvent.LOGIN_STATE_ANONYMOUS : AnalyticsEvent.LOGIN_STATE_LOGGED_IN);
        entity.setConfigVersion(nonNegative(request.getConfigVersion()));
        entity.setConversationId(ownedConversationId(uid, request.getConversationId()));
        entity.setAgentId(sameTenantAgentId(request.getAgentId()));
        entity.setAgentVersion(nonNegative(request.getAgentVersion()));
        entity.setToolType(enumValue(request.getToolType(), Set.of("local", "mcp")));
        entity.setToolKey(truncateOrNull(request.getToolKey(), TOOL_KEY_MAX));
        entity.setStatus(truncateOrNull(request.getStatus(), STATUS_MAX));
        entity.setResult(enumValue(request.getResult(), Set.of(AnalyticsEvent.RESULT_SUCCESS,
                AnalyticsEvent.RESULT_FAILED, AnalyticsEvent.RESULT_DENIED)));
        entity.setErrorCode(registeredErrorCode(request.getErrorCode()));
        entity.setDurationMs(boundedMillis(request.getDurationMs()));
        entity.setLatencyMs(boundedMillis(request.getLatencyMs()));
        entity.setCharCount(nonNegativeInt(request.getCharCount()));
        entity.setTokenUsage(tokenUsageJson(request.getTokenUsage()));
        entity.setSource(truncateOrNull(request.getSource(), SHORT_TEXT_MAX));
        entity.setPagePath(sanitizePagePath(request.getPagePath()));
        entity.setAction(truncateOrNull(request.getAction(), SHORT_TEXT_MAX));
        return entity;
    }

    /**
     * 🔴 {@code pagePath} 强制去 query 与 hash（AC-AUTH-002：防 Token 经 URL 泄露）。
     *
     * <p>为什么在<b>服务端</b>做而不是信任前端：SSO 回跳后 URL 里<b>确实</b>带过
     * {@code ?authorization=<jwt>}，前端只要有一次没清干净，Token 就会随埋点永久落库。
     * 🔴 同时只保留站内 path：绝对 URL（含 scheme/host）会把外站地址带进来。
     */
    private String sanitizePagePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        int cut = value.length();
        int query = value.indexOf('?');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            cut = Math.min(cut, hash);
        }
        value = value.substring(0, cut);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            // 🔴 绝对 URL：只取 path 部分，丢掉 scheme + host
            int slash = value.indexOf('/', scheme + 3);
            value = slash >= 0 ? value.substring(slash) : "/";
        }
        if (value.isBlank()) {
            return null;
        }
        return truncateOrNull(value, PAGE_PATH_MAX);
    }

    /**
     * {@code conversationId} 必须属<b>当前租户 + 当前 uid</b>，否则置空。
     *
     * <p>🔴 置空而非报错：报错等于告诉调用方"这个会话确实存在但不属于你"（存在性泄露）。
     */
    private Long ownedConversationId(Long uid, String raw) {
        Long id = parseId(raw);
        if (id == null || uid == null) {
            return null;
        }
        // 🔴 租户维度由 discriminator 保证；用户维度显式带 uid
        return conversationRepository.findByIdAndUidAndDeletedAtIsNull(id, uid)
                .map(conversation -> id).orElse(null);
    }

    private Long sameTenantAgentId(String raw) {
        Long id = parseId(raw);
        if (id == null) {
            return null;
        }
        return agentRepository.findByIdAndDeletedAtIsNull(id).map(agent -> id).orElse(null);
    }

    /**
     * 🔴 {@code errorCode} 必须是 api-spec §2.2 已登记码，否则置空。
     *
     * <p>为什么严格：埋点里的 {@code errorCode} 会被用来做错误分布统计，
     * 混入未登记码（例如前端自造的 {@code -1}）会让统计口径失效，且掩盖真实错误分布。
     */
    private Integer registeredErrorCode(Integer code) {
        return code != null && ErrorCode.isRegistered(code) ? code : null;
    }

    private String tokenUsageJson(AnalyticsEventRequest.TokenUsagePayload usage) {
        if (usage == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(usage);
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 配置读取 =====================

    private Set<String> allowedEvents() {
        return new HashSet<>(configService.getJson(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.ANALYTICS_ALLOWED_EVENTS, new TypeReference<List<String>>() {
                }, List.of()));
    }

    private boolean anonymousEnabled() {
        return businessConfig.requireBoolean(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.ANALYTICS_ANONYMOUS_ENABLED);
    }

    /**
     * 埋点总开关（🔴 <b>fail-closed</b>，api-spec §7.10.1 采样第 5 条 / V1.1.4 #1 裁决）。
     *
     * <p>🔴 <b>缺失 / 不可解析 → 按 {@code false}（全部丢弃），并记 ERROR</b>（不是 WARN）。
     * <pre>
     * 为什么不能 fail-open（本轮明确否决了原实现的"缺行 = 全量接收 + WARN"）：
     * ① 🔴 隐私纪律不允许：fail-open 意味着"配置一旦丢失就默默转为**全量采集**"——
     *    恰好把唯一的关停手段变成"失效即最大化采集"。运维原按 sample_rate=0.05 采 5%，
     *    某次误删配置行后系统静默转为 100%，且只有一条 WARN —— 没人会因为 WARN 去看日志；
     * ② 🔴 代价不对称：埋点丢失 = 分析数据缺一段（**可恢复**、不影响任何用户可见功能）；
     *    超范围采集 = **不可撤销**的隐私事实（已落库的行删不掉"曾经采集过"）。
     *    判据：可恢复的功能损失 < 不可撤销的隐私损失；
     * ③ 与限流"Redis 不可用即放行"（§7.12）不冲突：那里 fail-open 的对象是**保护措施**，
     *    这里 fail-open 的对象是**采集行为本身**。
     *    🔴 一般原则：**"要不要拦"的开关可 fail-open，"要不要采/写"的开关必须 fail-closed**。
     * </pre>
     *
     * <p>🔴 两键均已纳入 {@code StartupChecker.REQUIRED_CONFIG}（缺键即启动失败，键总数 25），
     * 因此本分支在正常部署下<b>不可达</b> —— fail-closed 只是"万一"时的方向选择，成本为零。
     * 🔴 无论如何本方法都<b>不抛异常</b>：埋点端点一律 {@code code=0}（永不影响主流程）。
     */
    private boolean analyticsEnabled() {
        Optional<String> raw = configService.find(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED);
        if (raw.isEmpty() || raw.get().isBlank()) {
            log.error("🔴 sys_config[{}.{}] 缺失，按 fail-closed 处理：本批埋点**全部丢弃**"
                            + "（该键已在 StartupChecker.REQUIRED_CONFIG，出现此日志说明配置被运行时删改）",
                    ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED);
            return false;
        }
        String value = raw.get().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "0".equals(value)) {
            return false;
        }
        // 🔴 不可解析（如 "yes" / "on"）也按 false：绝不把"写坏的值"当成"开启"
        log.error("🔴 sys_config[{}.{}] 取值不可解析，按 fail-closed 处理：本批埋点**全部丢弃**",
                ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_ENABLED);
        return false;
    }

    /**
     * 采样率（🔴 <b>fail-closed</b>：缺失 / 不可解析 / <b>越界</b> → {@code 0.0} 全部丢弃 + ERROR）。
     *
     * <p>🔴 <b>越界不再夹取到 [0,1]</b>（原实现用 {@code Math.max/min} 夹取，等于把 {@code 1.5}
     * 悄悄当成"全量"）：区间外的采样率没有任何合法语义，夹取是<b>把误配变成静默的最大化采集</b>。
     * 取值不变量另由 {@code StartupChecker} 在启动时<b>拒绝启动</b>（api-spec §7.1.2）。
     */
    private double sampleRate() {
        Optional<String> raw = configService.find(ConfigKeys.GROUP_OBSERVABILITY,
                ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE);
        if (raw.isEmpty() || raw.get().isBlank()) {
            log.error("🔴 sys_config[{}.{}] 缺失，按 fail-closed 处理：采样率取 0.0（全部丢弃）",
                    ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE);
            return 0.0d;
        }
        double value;
        try {
            value = Double.parseDouble(raw.get().trim());
        } catch (NumberFormatException e) {
            log.error("🔴 sys_config[{}.{}] 取值非法，按 fail-closed 处理：采样率取 0.0（全部丢弃）",
                    ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE);
            return 0.0d;
        }
        if (value < 0.0d || value > 1.0d) {
            log.error("🔴 sys_config[{}.{}]={} 越界（必须 ∈ [0.0, 1.0]），按 fail-closed 处理："
                            + "采样率取 0.0（全部丢弃），🔴 绝不夹取为全量",
                    ConfigKeys.GROUP_OBSERVABILITY, ConfigKeys.OBSERVABILITY_ANALYTICS_SAMPLE_RATE,
                    raw.get().trim());
            return 0.0d;
        }
        return value;
    }

    // ===================== 小工具 =====================

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long nonNegative(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private Integer nonNegativeInt(Integer value) {
        return value == null || value < 0 ? null : value;
    }

    private Integer boundedMillis(Integer value) {
        if (value == null || value < 0 || value > DURATION_MAX_MILLIS) {
            return null;
        }
        return value;
    }

    private String enumValue(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : null;
    }

    private String truncateOrNull(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.codePointCount(0, trimmed.length()) <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, maxChars));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
