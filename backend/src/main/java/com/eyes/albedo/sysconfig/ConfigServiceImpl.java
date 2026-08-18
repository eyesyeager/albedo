package com.eyes.albedo.sysconfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.eyes.albedo.tenant.CacheEviction;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * {@link ConfigService} 默认实现：MySQL 为准，Redis 为缓存，写后主动失效。
 *
 * <p>缓存策略（docs/architecture.md §12）：
 * <pre>
 *   L1 进程内 ConcurrentHashMap                     单项，TTL 30s
 *   albedo:{env}:platform:sysconfig:{group}:{key}   单项，TTL 600s，空值哨兵 60s 防穿透
 *   albedo:{env}:platform:sysconfig:frontend[:g]    前端聚合，TTL 300s
 * </pre>
 *
 * <p>为什么需要 L1：配置项在热路径上被高频读取（{@code TenantResolver} 每请求就要读 2 项），
 * 而 Redis 在跨机部署下单次往返即达 10ms 量级，仅靠 Redis 无法满足
 * 「租户识别 + 配置读取 P95 ≤20ms」（AC-NFR-001）。
 *
 * <p>一致性：本项目是<b>单体单一 JAR</b>（架构 ADR-001），
 * {@link #evict(String, String)} / {@link #evictAll()} 与所有读取都发生在同一 JVM，
 * 因此写后清 L1 即可保证「改完即生效」；L1 另有 30s TTL 兜底人工改库等旁路变更。
 *
 * <p>此处的 TTL 属于「基础设施自举参数」，允许以常量存在（若入库会造成读配置需先读配置的循环依赖）。
 */
@Slf4j
@Service
public class ConfigServiceImpl implements ConfigService {

    private static final Duration ITEM_TTL = Duration.ofSeconds(600);
    private static final Duration ABSENT_TTL = Duration.ofSeconds(60);
    private static final Duration FRONTEND_TTL = Duration.ofSeconds(300);
    private static final long LOCAL_TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Integer FRONTEND_FLAG = 1;

    private final SysConfigRepository repository;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final ObjectMapper objectMapper;

    /** L1 单项缓存：值为 {@code null} 表示「确认不存在」，与 Redis 空值哨兵语义一致。 */
    private final ConcurrentHashMap<String, LocalEntry> localCache = new ConcurrentHashMap<>();

    private record LocalEntry(String value, long expiresAtNanos) {
        boolean expired() {
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }

    public ConfigServiceImpl(SysConfigRepository repository,
                             StringRedisTemplate redis,
                             TenantCacheKeys cacheKeys,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<String> find(String group, String key) {
        String cacheKey = cacheKeys.sysConfigItem(group, key);

        LocalEntry local = localCache.get(cacheKey);
        if (local != null && !local.expired()) {
            return Optional.ofNullable(local.value());
        }

        String cached = safeGet(cacheKey);
        if (TenantCacheKeys.ABSENT_MARKER.equals(cached)) {
            putLocal(cacheKey, null);
            return Optional.empty();
        }
        if (cached != null) {
            putLocal(cacheKey, cached);
            return Optional.of(cached);
        }
        Optional<String> value = repository.findByConfigGroupAndConfigKey(group, key)
                .map(SysConfig::getConfigValue);
        safeSet(cacheKey, value.orElse(TenantCacheKeys.ABSENT_MARKER),
                value.isPresent() ? ITEM_TTL : ABSENT_TTL);
        putLocal(cacheKey, value.orElse(null));
        return value;
    }

    @Override
    public String getString(String group, String key, String defaultValue) {
        return find(group, key).filter(v -> !v.isBlank()).orElse(defaultValue);
    }

    @Override
    public int getInt(String group, String key, int defaultValue) {
        return (int) getLong(group, key, defaultValue);
    }

    @Override
    public long getLong(String group, String key, long defaultValue) {
        Optional<String> raw = find(group, key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.get().trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {}:{} 期望 NUMBER，实际值非法：{}", group, key, raw.get());
            return defaultValue;
        }
    }

    @Override
    public boolean getBoolean(String group, String key, boolean defaultValue) {
        Optional<String> raw = find(group, key);
        if (raw.isEmpty()) {
            return defaultValue;
        }
        String value = raw.get().trim();
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        log.warn("配置项 {}:{} 期望 BOOLEAN，实际值非法：{}", group, key, value);
        return defaultValue;
    }

    @Override
    public <T> T getJson(String group, String key, TypeReference<T> type, T defaultValue) {
        Optional<String> raw = find(group, key);
        if (raw.isEmpty() || raw.get().isBlank()) {
            return defaultValue;
        }
        try {
            return objectMapper.readValue(raw.get(), type);
        } catch (JsonProcessingException e) {
            log.warn("配置项 {}:{} 期望 JSON，解析失败", group, key, e);
            return defaultValue;
        }
    }

    @Override
    public Map<String, Map<String, Object>> frontendConfig() {
        String cacheKey = cacheKeys.sysConfigFrontend();
        String cached = safeGet(cacheKey);
        if (cached != null) {
            Optional<Map<String, Map<String, Object>>> parsed = readAggregated(cached);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        List<SysConfig> items =
                repository.findByIsFrontendOrderByConfigGroupAscSortOrderAscConfigKeyAsc(FRONTEND_FLAG);
        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (SysConfig item : items) {
            grouped.computeIfAbsent(item.getConfigGroup(), g -> new LinkedHashMap<>())
                    .put(toCamelCase(item.getConfigKey()), convert(item));
        }
        writeJson(cacheKey, grouped);
        return grouped;
    }

    @Override
    public Map<String, Object> frontendConfig(String group) {
        String cacheKey = cacheKeys.sysConfigFrontend(group);
        String cached = safeGet(cacheKey);
        if (cached != null) {
            Optional<Map<String, Object>> parsed = readGroup(cached);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        List<SysConfig> items =
                repository.findByIsFrontendAndConfigGroupOrderBySortOrderAscConfigKeyAsc(FRONTEND_FLAG, group);
        Map<String, Object> result = new LinkedHashMap<>();
        for (SysConfig item : items) {
            result.put(toCamelCase(item.getConfigKey()), convert(item));
        }
        writeJson(cacheKey, result);
        return result;
    }

    @Override
    public void evict(String group, String key) {
        try {
            evictAndCount(group, key);
        } catch (RuntimeException e) {
            log.warn("失效配置缓存失败（已忽略）：{}:{}", group, key, e);
        }
    }

    @Override
    public void evictAll() {
        try {
            evictAllAndCount(null);
        } catch (RuntimeException e) {
            log.warn("失效全部配置缓存失败（已忽略）", e);
        }
    }

    @Override
    public CacheEviction evictAndCount(String group, String key) {
        // 🔴 L1 与 Redis 必须同时失效，否则本 JVM 在 L1 TTL 内仍读到旧值
        String itemKey = cacheKeys.sysConfigItem(group, key);
        int l1 = localCache.remove(itemKey) == null ? 0 : 1;
        // 前端聚合键随单项一并失效（否则 /api/v1/sys-config 仍返回旧值）
        Long deleted = redis.delete(Set.of(itemKey,
                cacheKeys.sysConfigFrontend(),
                cacheKeys.sysConfigFrontend(group)));
        return CacheEviction.of(l1, deleted == null ? 0L : deleted);
    }

    @Override
    public CacheEviction evictAllAndCount(String group) {
        boolean wholeScope = group == null || group.isBlank();
        int l1;
        if (wholeScope) {
            l1 = localCache.size();
            localCache.clear();
        } else {
            String prefix = cacheKeys.sysConfigItem(group, "") + ":";
            List<String> hit = localCache.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
            hit.forEach(localCache::remove);
            l1 = hit.size();
        }
        Set<String> keys = redis.keys(cacheKeys.sysConfigItemPattern(group));
        long deleted = 0L;
        if (keys != null && !keys.isEmpty()) {
            Long removed = redis.delete(keys);
            deleted = removed == null ? 0L : removed;
        }
        // 分组失效时聚合键不在上面的模式内，需单独清理
        if (!wholeScope) {
            Long aggregated = redis.delete(Set.of(cacheKeys.sysConfigFrontend(),
                    cacheKeys.sysConfigFrontend(group)));
            deleted += aggregated == null ? 0L : aggregated;
        }
        return CacheEviction.of(l1, deleted);
    }

    // ===================== 内部工具 =====================

    private void putLocal(String cacheKey, String value) {
        localCache.put(cacheKey, new LocalEntry(value, System.nanoTime() + LOCAL_TTL_NANOS));
    }

    private Object convert(SysConfig item) {
        String raw = item.getConfigValue();
        return switch (SysConfig.ValueType.of(item.getValueType())) {
            case NUMBER -> parseNumber(item, raw);
            case BOOLEAN -> "true".equalsIgnoreCase(raw) || "1".equals(raw);
            case JSON -> parseJson(item, raw);
            case STRING -> raw;
        };
    }

    private Object parseNumber(SysConfig item, String raw) {
        try {
            if (raw.contains(".")) {
                return Double.valueOf(raw.trim());
            }
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("配置项 {}:{} 声明为 NUMBER 但值非法：{}", item.getConfigGroup(), item.getConfigKey(), raw);
            return raw;
        }
    }

    private Object parseJson(SysConfig item, String raw) {
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("配置项 {}:{} 声明为 JSON 但解析失败", item.getConfigGroup(), item.getConfigKey(), e);
            return raw;
        }
    }

    private Optional<Map<String, Map<String, Object>>> readAggregated(String json) {
        try {
            return Optional.of(objectMapper.readValue(json,
                    new TypeReference<Map<String, Map<String, Object>>>() {
                    }));
        } catch (JsonProcessingException e) {
            log.warn("前端配置缓存解析失败，将回源数据库", e);
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> readGroup(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (JsonProcessingException e) {
            log.warn("前端配置分组缓存解析失败，将回源数据库", e);
            return Optional.empty();
        }
    }

    private void writeJson(String cacheKey, Object value) {
        try {
            safeSet(cacheKey, objectMapper.writeValueAsString(value), FRONTEND_TTL);
        } catch (JsonProcessingException e) {
            log.warn("前端配置写缓存失败（不影响本次响应）", e);
        }
    }

    /**
     * Redis 不可用时不得阻断配置读取（降级为直连数据库）。
     */
    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取配置缓存失败，降级直连数据库：{}", key, e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("写入配置缓存失败：{}", key, e);
        }
    }

    /**
     * snake_case → camelCase（对外字段命名统一，框架 §14.1）。
     */
    static String toCamelCase(String snake) {
        if (snake == null || snake.isEmpty()) {
            return snake;
        }
        StringBuilder sb = new StringBuilder(snake.length());
        boolean upperNext = false;
        for (char c : snake.toCharArray()) {
            if (c == '_' || c == '-') {
                upperNext = true;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }
}
