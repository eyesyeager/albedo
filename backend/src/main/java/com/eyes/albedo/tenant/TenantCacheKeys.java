package com.eyes.albedo.tenant;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 缓存键唯一入口（docs/architecture.md §12）。
 *
 * <p>格式：{@code albedo:{env}:{tenantId|platform}:{module}:{resource}:{idOrVersion}}
 *
 * <p>🔴 纪律：业务代码<b>禁止</b>手拼 Redis key，也禁止使用 Spring Cache 的默认键
 * （默认键不含租户维度，会造成跨租户命中，违反 AC-TEN-005）。
 */
@Component
public class TenantCacheKeys {

    public static final String PREFIX = "albedo";
    public static final String PLATFORM_SCOPE = "platform";
    /** 租户解析未命中时写入的空值哨兵（防缓存穿透）。 */
    public static final String ABSENT_MARKER = "__ABSENT__";

    private final String env;

    public TenantCacheKeys(@Value("${app.cache.env}") String env) {
        this.env = env;
    }

    public String env() {
        return env;
    }

    /**
     * 平台级键：{@code albedo:{env}:platform:{module}:{resource}[:{id}]}
     */
    public String platform(String module, String resource, String... idParts) {
        return join(PLATFORM_SCOPE, module, resource, idParts);
    }

    /**
     * 当前租户键：{@code albedo:{env}:{tenantId}:{module}:{resource}[:{id}]}
     *
     * @throws BusinessException 30013 缺少租户上下文（禁止无租户维度的缓存写入）
     */
    public String tenant(String module, String resource, String... idParts) {
        String tenantId = TenantContext.current()
                .map(TenantContext.Snapshot::tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_CONTEXT_MISSING));
        return join(tenantId, module, resource, idParts);
    }

    /**
     * 指定租户键（仅平台任务 / 缓存失效场景使用）。
     */
    public String ofTenant(String tenantId, String module, String resource, String... idParts) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ErrorCode.TENANT_CONTEXT_MISSING);
        }
        return join(tenantId, module, resource, idParts);
    }

    // ===== 基础设施固定键 =====

    /** Host → 租户解析结果。 */
    public String tenantByHost(String normalizedHost) {
        return platform("tenant", "host", normalizedHost);
    }

    /**
     * 租户号 → 租户解析结果（dev Host 映射路径专用）。
     *
     * <p>dev 映射不经过 {@code tenant_domains}，若不缓存则每个请求都要回源数据库，
     * 而 dev 映射正是非生产环境的主验证路径，必须同样满足 P95 ≤20ms（AC-NFR-001）。
     */
    public String tenantByCode(String tenantId) {
        return platform("tenant", "code", tenantId);
    }

    /** 平台配置单项。 */
    public String sysConfigItem(String group, String key) {
        return platform("sysconfig", group, key);
    }

    /** 前端配置聚合。 */
    public String sysConfigFrontend() {
        return platform("sysconfig", "frontend");
    }

    /** 前端配置聚合（按分组）。 */
    public String sysConfigFrontend(String group) {
        return platform("sysconfig", "frontend", group);
    }

    // ===== 租户级固定键（architecture.md §12） =====

    /**
     * 租户站点配置快照：{@code albedo:{env}:{tenantId}:site:config:{configVersion}}。
     *
     * <p>键内含版本号 → 发布新版本天然失效，无需显式删除（仍在发布钩子中删旧键）。
     */
    public String siteConfig(String tenantId, long configVersion) {
        return ofTenant(tenantId, "site", "config", String.valueOf(configVersion));
    }

    /**
     * Agent 发布快照：{@code albedo:{env}:{tenantId}:agent:version:{agentId}:{version}}。
     */
    public String agentVersion(String tenantId, long agentId, long version) {
        return ofTenant(tenantId, "agent", "version", String.valueOf(agentId), String.valueOf(version));
    }

    /**
     * 租户成员角色缓存：{@code albedo:{env}:{tenantId}:member:role:{uid}}。
     *
     * <p>惰性建户在每个受保护接口都会命中，用短 TTL 缓存避免成为 P95 瓶颈；
     * 🔴 TTL 必须短（分钟级），否则成员被禁用后无法及时生效（AC-AUTH-006）。
     */
    public String memberRole(String tenantId, long uid) {
        return ofTenant(tenantId, "member", "role", String.valueOf(uid));
    }

    /**
     * 消息幂等：{@code albedo:{env}:{tenantId}:chat:idem:{uid}:{idempotencyKey}}。
     */
    public String chatIdempotency(String tenantId, long uid, String idempotencyKey) {
        return ofTenant(tenantId, "chat", "idem", String.valueOf(uid), idempotencyKey);
    }

    /**
     * 生成取消标记：{@code albedo:{env}:{tenantId}:chat:cancel:{messageId}}。
     */
    public String chatCancel(String tenantId, long messageId) {
        return ofTenant(tenantId, "chat", "cancel", String.valueOf(messageId));
    }

    /**
     * 会话历史摘要（上下文压缩）：{@code albedo:{env}:{tenantId}:chat:summary:{conversationId}}。
     */
    public String chatSummary(String tenantId, long conversationId) {
        return ofTenant(tenantId, "chat", "summary", String.valueOf(conversationId));
    }

    /**
     * 消息限流计数：{@code albedo:{env}:{tenantId}:limit:msg:{uid}:{window}}。
     *
     * <p>🔴 键内含 tenantId + uid，保证单租户行为不占用其他租户额度（AC-LMT-001）。
     */
    public String messageRateLimit(String tenantId, long uid, String window) {
        return ofTenant(tenantId, "limit", "msg", String.valueOf(uid), window);
    }

    /**
     * 工具确认信号（M3，architecture.md §12.2 / ADR-008）：
     * {@code albedo:{env}:{tenantId}:tool:confirm:{toolCallId}}。
     *
     * <p>值为 {@code allow} / {@code deny}，由 confirm 接口写入、生成线程轮询兜底。
     * 🔴 属"运行时状态键"，缓存失效接口<b>禁止删除</b>（见 {@link #isProtectedRuntimeStateKey(String)}）。
     */
    public String toolConfirm(String tenantId, long toolCallId) {
        return ofTenant(tenantId, "tool", "confirm", String.valueOf(toolCallId));
    }

    /**
     * 日额度<b>已结算计数镜像</b>（M3.1，architecture.md §12.2 / ADR-020 ②）：
     * {@code albedo:{env}:{tenantId}:quota:day:{uid}:{dateKey}}。
     *
     * <p>🔴 <b>它是可重建的镜像而非事实来源</b>：事实在 {@code user_daily_quota_usages}。
     * 键缺失时必须<b>先从 DB 账本重建</b>再判定 —— 否则 Redis 重启 / 驱逐
     * 等于给全体用户<b>免费重置额度</b>（额度直接对应模型调用成本，K16 反向断言）。
     *
     * <p>🔴 TTL 到 {@code resetsAt} 为止（{@code resetsAt − now} + 固定收尾余量），
     * 🔴 <b>禁止写死 86400</b> —— DST 日为 23 / 25 小时。
     *
     * <p>🔴 属"运行时状态键"，缓存失效接口<b>禁止删除</b>
     * （见 {@link #isProtectedRuntimeStateKey(String)}）。
     *
     * @param dateKey 窗口标识（{@code d} + 租户当地 {@code yyyyMMdd}，见 {@code QuotaWindow}）
     */
    public String dailyQuotaCount(String tenantId, long uid, String dateKey) {
        return ofTenant(tenantId, "quota", "day", String.valueOf(uid), dateKey);
    }

    /**
     * 日额度<b>在途预占集合</b>（M3.1，architecture.md §12.2 / ADR-020 ②）：
     * {@code albedo:{env}:{tenantId}:quota:hold:{uid}:{dateKey}}。
     *
     * <p><b>ZSET</b>：member = {@code reservationId}，score = 该预占的<b>过期时刻</b>
     * （{@code now + chat.generation_deadline_seconds + chat.deadline_grace_seconds + 固定余量}）。
     * 🔴 每次预占前先 {@code ZREMRANGEBYSCORE 0 now} 剪除过期项 —— 这是
     * <b>预占泄漏（进程崩溃）的自愈机制</b>，🔴 因此<b>不需要任何定时任务</b>。
     *
     * <p>🔴 同属"运行时状态键"：删除它会让在途生成的预占凭空消失，
     * 导致同一用户并发突破日上限。
     */
    public String dailyQuotaHold(String tenantId, long uid, String dateKey) {
        return ofTenant(tenantId, "quota", "hold", String.valueOf(uid), dateKey);
    }

    // ===== 批量失效用的键模式（唯一入口，禁止业务代码手拼 pattern） =====

    /** 平台配置单项模式：某分组或全部。 */
    public String sysConfigItemPattern(String group) {
        return (group == null || group.isBlank())
                ? platform("sysconfig", "*")
                : platform("sysconfig", group, "*");
    }

    /** 某租户的站点配置快照（全部版本）。 */
    public String siteConfigPattern(String tenantId) {
        return ofTenant(tenantId, "site", "config", "*");
    }

    /** 某租户的 Agent 版本快照（可选限定 agentId）。 */
    public String agentVersionPattern(String tenantId, Long agentId) {
        return agentId == null
                ? ofTenant(tenantId, "agent", "version", "*")
                : ofTenant(tenantId, "agent", "version", String.valueOf(agentId), "*");
    }

    /** 某租户的成员角色缓存（全部 uid）。 */
    public String memberRolePattern(String tenantId) {
        return ofTenant(tenantId, "member", "role", "*");
    }

    /**
     * 判断是否为「运行时状态键」——🔴 缓存失效接口<b>命中即必须拒绝</b>。
     *
     * <p>为什么（architecture.md §12.2 末尾 / api-spec §7.2.1）：以下键<b>不是缓存</b>，
     * 而是承载业务语义的运行时状态，删除会直接破坏功能：
     * <ul>
     *   <li>{@code chat:idem:*} —— 幂等回放标记，删除后同一 {@code Idempotency-Key} 会重复建消息</li>
     *   <li>{@code chat:cancel:*} —— 停止生成信号，删除后已停止的生成可能继续写出</li>
     *   <li>{@code tool:confirm:*} —— 高风险工具确认信号，删除后用户已提交的决定丢失，生成线程白等到超时</li>
     *   <li>{@code limit:msg:*} —— 限流窗口计数，删除等于免费重置额度</li>
     *   <li>🔴 <b>V1.4.5 新增</b> {@code quota:day:*} —— 日额度<b>已结算</b>计数镜像，
     *       删除等于把当日已结算数清零（<b>免费重置额度</b>，而额度直接对应模型调用成本）</li>
     *   <li>🔴 <b>V1.4.5 新增</b> {@code quota:hold:*} —— 日额度<b>在途预占</b>集合，
     *       删除会让在途生成的预占凭空消失，导致同一用户并发突破日上限</li>
     * </ul>
     * 🔴 即便 {@code scope=all} 也必须跳过（@测试 以"生成中调用 evict all，流不受影响"断言）。
     *
     * <p>实现口径：按「模块:资源」两段前缀判定，与 {@link #join} 的键格式严格对应，
     * 因此新增运行时状态键时只需在本方法登记一次。
     */
    public boolean isProtectedRuntimeStateKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String segment : PROTECTED_KEY_SEGMENTS) {
            if (key.contains(":" + segment + ":")) {
                return true;
            }
        }
        return false;
    }

    /** 运行时状态键的「模块:资源」片段（唯一登记处）。 */
    private static final java.util.List<String> PROTECTED_KEY_SEGMENTS = java.util.List.of(
            "chat:idem",
            "chat:cancel",
            "tool:confirm",
            "limit:msg",
            // 🔴 V1.4.5（ADR-020 ① / §12.2）：两个日额度运行时状态键（不是缓存）
            "quota:day",
            "quota:hold"
    );

    private String join(String scope, String module, String resource, String... idParts) {
        StringBuilder sb = new StringBuilder(PREFIX)
                .append(':').append(env)
                .append(':').append(scope)
                .append(':').append(module)
                .append(':').append(resource);
        if (idParts != null) {
            for (String part : idParts) {
                if (part != null && !part.isBlank()) {
                    sb.append(':').append(part);
                }
            }
        }
        return sb.toString();
    }
}
