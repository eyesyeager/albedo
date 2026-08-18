package com.eyes.albedo.tenant;

import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 租户解析缓存的<b>回归守护</b>测试（对应缺陷 D-003 / D-004 与 ADR-005）。
 *
 * <p>守护的三条不变量（architecture.md §14.1）：
 * <ol>
 *   <li>解析结果命中缓存时<b>不得回源数据库</b>——dev 映射路径曾因漏加缓存导致每请求打库</li>
 *   <li>稳态下<b>不得重复访问 Redis</b>——租户解析处在每个请求最前端，一次远程往返即 ~10ms</li>
 *   <li>{@code evictHost} / {@code evictTenantId} 必须<b>同时清除 L1 与 Redis</b>，
 *       否则「租户启停 / 配置发布」在 L1 TTL 内不生效</li>
 * </ol>
 */
class TenantResolverCacheTest {

    private static final String HOST = "albedo-gift.eyescode.top";
    private static final String DEV_HOST = "localhost:5173";
    private static final String TENANT_ID = "gift";

    private TenantLookupPort lookup;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ConfigService configService;
    private TenantCacheKeys cacheKeys;
    private TenantResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lookup = mock(TenantLookupPort.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        configService = mock(ConfigService.class);
        cacheKeys = new TenantCacheKeys("test");

        when(redis.opsForValue()).thenReturn(valueOps);
        when(configService.getLong(anyString(), anyString(), anyLong())).thenReturn(300L);
        // 默认关闭 dev 映射，单个用例按需打开
        when(configService.getBoolean(anyString(), anyString(), anyBoolean())).thenReturn(false);

        ObjectProvider<TenantLookupPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(lookup);

        resolver = new TenantResolver(provider, redis, cacheKeys, configService, new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private void enableDevMapping() {
        when(configService.getBoolean(ConfigKeys.GROUP_TENANT,
                ConfigKeys.DEV_HOST_MAPPING_ENABLED, false)).thenReturn(true);
        when(configService.getJson(eq(ConfigKeys.GROUP_TENANT), eq(ConfigKeys.DEV_HOST_MAPPING),
                any(TypeReference.class), any())).thenReturn(Map.of(DEV_HOST, TENANT_ID));
    }

    private TenantContext.Snapshot snapshot(long configVersion) {
        return new TenantContext.Snapshot(TENANT_ID, 1L, HOST, TenantStatus.ENABLED, configVersion, null);
    }

    @Test
    @DisplayName("dev 映射路径：重复解析只回源一次，且稳态零 Redis 访问（D-003 + D-004 守护）")
    void devMappingPathIsCached() {
        enableDevMapping();
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByTenantId(TENANT_ID)).thenReturn(Optional.of(snapshot(3L)));

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolve(DEV_HOST)).map(TenantContext.Snapshot::tenantId).hasValue(TENANT_ID);
        }

        // 不变量①：仅首次回源数据库
        verify(lookup, times(1)).resolveByTenantId(TENANT_ID);
        // 不变量②：仅首次读 Redis（后续由 L1 承载）
        verify(valueOps, times(1)).get(cacheKeys.tenantByCode(TENANT_ID));
        verify(lookup, never()).resolveByHost(anyString());
    }

    @Test
    @DisplayName("正式 Host 路径：重复解析只回源一次，且稳态零 Redis 访问")
    void hostPathIsCached() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByHost(HOST)).thenReturn(Optional.of(snapshot(2L)));

        for (int i = 0; i < 5; i++) {
            assertThat(resolver.resolve(HOST)).isPresent();
        }

        verify(lookup, times(1)).resolveByHost(HOST);
        verify(valueOps, times(1)).get(cacheKeys.tenantByHost(HOST));
    }

    @Test
    @DisplayName("未知 Host：空值哨兵同样进 L1，稳态不重复打库（防缓存穿透）")
    void absentResultIsCachedToo() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByHost("nope.example.com")).thenReturn(Optional.empty());

        for (int i = 0; i < 4; i++) {
            assertThat(resolver.resolve("nope.example.com")).isEmpty();
        }

        verify(lookup, times(1)).resolveByHost("nope.example.com");
    }

    @Test
    @DisplayName("evictTenantId 必须同时清 L1：清理后下一次解析立即拿到新 configVersion")
    void evictTenantIdClearsLocalCache() {
        enableDevMapping();
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByTenantId(TENANT_ID)).thenReturn(Optional.of(snapshot(3L)));

        assertThat(resolver.resolve(DEV_HOST)).map(TenantContext.Snapshot::configVersion).hasValue(3L);

        // 模拟发布新版本：指针 3 → 4，并按纪律失效缓存
        when(lookup.resolveByTenantId(TENANT_ID)).thenReturn(Optional.of(snapshot(4L)));
        resolver.evictTenantId(TENANT_ID);

        assertThat(resolver.resolve(DEV_HOST)).map(TenantContext.Snapshot::configVersion).hasValue(4L);
        verify(redis).delete(cacheKeys.tenantByCode(TENANT_ID));
    }

    @Test
    @DisplayName("evictHost 必须同时清 L1：清理后下一次解析立即拿到新状态")
    void evictHostClearsLocalCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByHost(HOST)).thenReturn(Optional.of(snapshot(1L)));

        assertThat(resolver.resolve(HOST)).map(TenantContext.Snapshot::status)
                .hasValue(TenantStatus.ENABLED);

        // 模拟租户被暂停
        when(lookup.resolveByHost(HOST)).thenReturn(Optional.of(
                new TenantContext.Snapshot(TENANT_ID, 1L, HOST, TenantStatus.SUSPENDED, 1L, null)));
        resolver.evictHost(HOST);

        assertThat(resolver.resolve(HOST)).map(TenantContext.Snapshot::status)
                .hasValue(TenantStatus.SUSPENDED);
        verify(redis).delete(cacheKeys.tenantByHost(HOST));
    }

    @Test
    @DisplayName("缓存对象一律不得携带 uid：避免 A 用户身份被 B 用户复用")
    void cachedSnapshotNeverCarriesUid() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(lookup.resolveByHost(HOST)).thenReturn(Optional.of(
                new TenantContext.Snapshot(TENANT_ID, 1L, HOST, TenantStatus.ENABLED, 1L, 90001L)));

        assertThat(resolver.resolve(HOST)).map(TenantContext.Snapshot::uid).isEmpty();
        // 第二次由 L1 返回，同样不得带 uid
        assertThat(resolver.resolve(HOST)).map(TenantContext.Snapshot::uid).isEmpty();
    }
}
