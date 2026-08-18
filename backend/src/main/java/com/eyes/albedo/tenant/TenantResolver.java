package com.eyes.albedo.tenant;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户解析器：Host 规范化 + dev 映射 + L1 进程内缓存 + Redis 缓存（含空值哨兵防穿透）。
 *
 * <p>唯一实现，{@link TenantFilter} 只调用本类，不自行拼接缓存键或做字符串猜测。
 *
 * <p>落库查询委托给 {@link TenantLookupPort}（由 @后端 在平台模块实现）。骨架阶段若该 Bean 尚未提供，
 * 本类返回空并打印 WARN，使 {@code /api/v1/sys-config} 等平台接口仍可用。
 *
 * <p><b>缓存分层</b>（architecture.md §12.1 / ADR-005）：租户解析处在<b>每一个请求</b>的最前端，
 * 若其自身就要消耗一次远程 Redis 往返，则「租户识别 + 配置读取 P95 ≤20ms」在跨网部署下不可能达成
 * （实测单次远程 Redis RTT ≈10ms）。因此本类持有 L1 进程内缓存：
 * <pre>
 *   L1 ConcurrentHashMap（TTL 5s） → L2 Redis（TTL 见 sys_config） → MySQL
 * </pre>
 * 快照可安全进 L1 的依据：{@link TenantContext.Snapshot} 是不可变 record、只含租户标识与版本指针、
 * <b>不含任何租户业务内容</b>；且依据 ADR-001（单体单一 JAR），{@link #evictHost} / {@link #evictTenantId}
 * 与读取同处一个 JVM，清 L1 即保证「启停/发布立即生效」。5s TTL 仅用于兜底人工改库等旁路变更。
 */
@Slf4j
@Component
public class TenantResolver {

    private static final long DEFAULT_HOST_TTL_SECONDS = 300L;
    private static final long DEFAULT_ABSENT_TTL_SECONDS = 60L;
    /** L1 TTL 刻意远小于 Redis TTL：租户 status 变更影响可用性，旁路改库也应尽快收敛。 */
    private static final long LOCAL_TTL_NANOS = Duration.ofSeconds(5).toNanos();

    private final ObjectProvider<TenantLookupPort> lookupProvider;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    /** L1 缓存：{@code snapshot == null} 表示「确认不存在」，与 Redis 空值哨兵语义一致。 */
    private final ConcurrentHashMap<String, LocalEntry> localCache = new ConcurrentHashMap<>();

    private record LocalEntry(TenantContext.Snapshot snapshot, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }

    public TenantResolver(ObjectProvider<TenantLookupPort> lookupProvider,
                          StringRedisTemplate redis,
                          TenantCacheKeys cacheKeys,
                          ConfigService configService,
                          ObjectMapper objectMapper) {
        this.lookupProvider = lookupProvider;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析租户。
     *
     * @param rawHost 已小写、已去末尾点、<b>保留端口</b>的 Host（dev 映射需要端口）
     */
    public Optional<TenantContext.Snapshot> resolve(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return Optional.empty();
        }
        TenantLookupPort lookup = lookupProvider.getIfAvailable();
        if (lookup == null) {
            log.warn("TenantLookupPort 尚未实现（骨架阶段），跳过租户解析：host={}", rawHost);
            return Optional.empty();
        }

        // ① 非生产环境的 dev Host 映射（生产由 DevHostMappingGuard 强制关闭）
        Optional<String> mappedTenantId = devMappedTenantId(rawHost);
        if (mappedTenantId.isPresent()) {
            String tenantId = mappedTenantId.get();
            log.debug("dev host 映射命中：{} -> {}", rawHost, tenantId);
            // 🔴 dev 映射同样必须走缓存：否则每个请求都回源 tenants 表，
            // 而 dev 映射是非生产环境的主验证路径，会让 P95 ≤20ms 无法达成。
            return resolveCached(cacheKeys.tenantByCode(tenantId),
                    () -> lookup.resolveByTenantId(tenantId));
        }

        // ② 正式路径：规范化 Host（去端口）→ 缓存 → 精确匹配 tenant_domains
        String normalizedHost = stripPort(rawHost);
        return resolveCached(cacheKeys.tenantByHost(normalizedHost),
                () -> lookup.resolveByHost(normalizedHost));
    }

    /**
     * 统一的「L1 → Redis → 回源 → 双层回填」解析流程（含空值哨兵防穿透）。
     *
     * <p>两条解析路径共用，避免其中一条漏加缓存（本项目曾因此使 dev 路径每请求打库）。
     */
    private Optional<TenantContext.Snapshot> resolveCached(
            String cacheKey, Supplier<Optional<TenantContext.Snapshot>> loader) {
        LocalEntry local = localCache.get(cacheKey);
        if (local != null && !local.expired()) {
            return Optional.ofNullable(local.snapshot());
        }

        String cached = safeGet(cacheKey);
        if (TenantCacheKeys.ABSENT_MARKER.equals(cached)) {
            putLocal(cacheKey, null);
            return Optional.empty();
        }
        if (cached != null) {
            // 同样净化：Redis 中可能残留历史版本写入的带 uid 快照
            Optional<TenantContext.Snapshot> fromCache = sanitize(deserialize(cached));
            if (fromCache.isPresent()) {
                putLocal(cacheKey, fromCache.get());
                return fromCache;
            }
        }
        Optional<TenantContext.Snapshot> resolved = sanitize(loader.get());
        cache(cacheKey, resolved);
        putLocal(cacheKey, resolved.orElse(null));
        return resolved;
    }

    /**
     * 缓存前净化：强制清空 {@code uid}。
     *
     * <p>🔴 安全不变量：{@link TenantContext.Snapshot} 含 {@code uid} 字段，而解析结果是
     * <b>跨请求、跨用户共享</b>的缓存对象（L1 与 Redis 皆然）。一旦某个
     * {@link TenantLookupPort} 实现顺手填入了 uid，就会造成「A 用户的身份被 B 用户复用」。
     * uid 的唯一合法来源是鉴权成功后由 {@code PermissionAspect} 调用
     * {@link TenantContext.Snapshot#withUid(Long)} 回填，故此处一律置空，从机制上封死该路径。
     */
    private Optional<TenantContext.Snapshot> sanitize(Optional<TenantContext.Snapshot> resolved) {
        return resolved.map(s -> s.uid() == null ? s : s.withUid(null));
    }

    private void putLocal(String cacheKey, TenantContext.Snapshot snapshot) {
        localCache.put(cacheKey, new LocalEntry(snapshot, System.nanoTime() + LOCAL_TTL_NANOS));
    }

    /**
     * 失效指定 Host 的解析缓存（域名绑定变更、租户启停时必须调用，M2）。
     *
     * <p>⚠️ 与 {@link #evictHostAndCount(String)} 的差异：本方法<b>吞掉 Redis 故障并记 WARN</b>
     * （M1 语义：不让缓存故障把"发布配置"这类业务动作升级为失败）；
     * 缓存失效接口必须如实回报失败，故走 {@code *AndCount} 变体。
     */
    public void evictHost(String host) {
        try {
            evictHostAndCount(host);
        } catch (RuntimeException e) {
            log.warn("失效租户解析缓存失败（已忽略）：host={}", host, e);
        }
    }

    /**
     * 失效指定 Host 的解析缓存并<b>回报实际条目数</b>（缓存失效接口 api-spec §7.2.1 需要真实计数）。
     *
     * <p>与 {@link #evictTenantIdAndCount(String)} 必须<b>成对调用</b>，理由见后者 Javadoc。
     */
    public CacheEviction evictHostAndCount(String host) {
        if (host == null || host.isBlank()) {
            return CacheEviction.NONE;
        }
        String key = cacheKeys.tenantByHost(stripPort(host.trim().toLowerCase()));
        return evictKey(key, "host=" + host);
    }

    /**
     * 失效指定租户号的解析缓存（dev 映射路径）。
     *
     * <p>与 {@link #evictHost(String)} 必须成对调用：配置发布 / 租户启停后若只失效 Host 键，
     * dev 环境会在 TTL 内继续读到旧的 configVersion，表现为「发布了但不生效」。
     */
    public void evictTenantId(String tenantId) {
        try {
            evictTenantIdAndCount(tenantId);
        } catch (RuntimeException e) {
            log.warn("失效租户解析缓存失败（已忽略）：tenantId={}", tenantId, e);
        }
    }

    /**
     * 失效指定租户号的解析缓存并<b>回报实际条目数</b>。
     *
     * <p>🔴 D-003 / D-006 教训：租户号键（dev 映射路径）与 Host 键是<b>两条独立缓存路径</b>，
     * 只清一个会让另一条路径在 TTL 内继续返回旧 {@code configVersion}。
     */
    public CacheEviction evictTenantIdAndCount(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return CacheEviction.NONE;
        }
        return evictKey(cacheKeys.tenantByCode(tenantId), "tenantId=" + tenantId);
    }

    /**
     * 清空本 JVM 的 L1 租户解析缓存（仅供 {@code scope=all} 的缓存失效接口使用）。
     *
     * <p>🔴 只影响 L1；Redis 侧由调用方按键模式删除。依据 ADR-001（单实例）成立。
     *
     * @return 实际移除的 L1 条目数
     */
    public int evictAllLocal() {
        int size = localCache.size();
        localCache.clear();
        return size;
    }

    private CacheEviction evictKey(String key, String label) {
        int l1 = localCache.remove(key) == null ? 0 : 1;
        long l2 = 0L;
        try {
            l2 = Boolean.TRUE.equals(redis.delete(key)) ? 1L : 0L;
        } catch (RuntimeException e) {
            log.warn("失效租户解析缓存失败：{}", label, e);
            // 🔴 不吞掉失败：向上抛出，由缓存失效接口计入 incompleteScopes 并返回 30061
            throw e;
        }
        return CacheEviction.of(l1, l2);
    }

    /**
     * Host 规范化：trim → 小写 → 去末尾点（<b>保留端口</b>）。
     */
    public static String normalizeRawHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim().toLowerCase();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 去端口（IPv6 字面量形如 {@code [::1]:8080} 也一并处理）。
     */
    public static String stripPort(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        if (rawHost.startsWith("[")) {
            int end = rawHost.indexOf(']');
            return end > 0 ? rawHost.substring(0, end + 1) : rawHost;
        }
        int idx = rawHost.indexOf(':');
        return idx > 0 ? rawHost.substring(0, idx) : rawHost;
    }

    private Optional<String> devMappedTenantId(String rawHost) {
        boolean enabled = configService.getBoolean(ConfigKeys.GROUP_TENANT,
                ConfigKeys.DEV_HOST_MAPPING_ENABLED, false);
        if (!enabled) {
            return Optional.empty();
        }
        Map<String, String> mapping = configService.getJson(ConfigKeys.GROUP_TENANT,
                ConfigKeys.DEV_HOST_MAPPING, new TypeReference<Map<String, String>>() {
                }, Map.of());
        String tenantId = mapping.get(rawHost);
        if (tenantId == null) {
            tenantId = mapping.get(stripPort(rawHost));
        }
        return Optional.ofNullable(tenantId).filter(v -> !v.isBlank());
    }

    private void cache(String cacheKey, Optional<TenantContext.Snapshot> resolved) {
        if (resolved.isEmpty()) {
            long absentTtl = configService.getLong(ConfigKeys.GROUP_TENANT,
                    ConfigKeys.HOST_ABSENT_TTL_SECONDS, DEFAULT_ABSENT_TTL_SECONDS);
            safeSet(cacheKey, TenantCacheKeys.ABSENT_MARKER, Duration.ofSeconds(absentTtl));
            return;
        }
        long ttl = configService.getLong(ConfigKeys.GROUP_TENANT,
                ConfigKeys.HOST_CACHE_TTL_SECONDS, DEFAULT_HOST_TTL_SECONDS);
        try {
            safeSet(cacheKey, objectMapper.writeValueAsString(resolved.get()), Duration.ofSeconds(ttl));
        } catch (JsonProcessingException e) {
            log.warn("序列化租户解析结果失败（不影响本次请求）", e);
        }
    }

    private Optional<TenantContext.Snapshot> deserialize(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, TenantContext.Snapshot.class));
        } catch (JsonProcessingException e) {
            log.warn("租户解析缓存解析失败，将回源数据库", e);
            return Optional.empty();
        }
    }

    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取租户解析缓存失败，降级直连数据库：{}", key, e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("写入租户解析缓存失败：{}", key, e);
        }
    }
}
