package com.eyes.albedo.sysconfig;

import java.util.Optional;

import com.eyes.albedo.tenant.TenantCacheKeys;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 进程内配置缓存的回归守护。
 *
 * <p>背景（M1 缺陷 D-004）：{@code TenantResolver} 每个请求都要读 2 个配置项，
 * 若每次都走远程 Redis，仅此一项就吃掉 20ms 性能红线（AC-NFR-001）的全部预算。
 * 本测试锁定两条不可退化的行为：
 * <ol>
 *   <li>重复读取同一配置项<b>不得</b>反复回源（Redis / DB 各自最多一次）</li>
 *   <li>{@code evict} / {@code evictAll} 后<b>必须</b>重新回源（保证「改完即生效」）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigService L1 缓存")
class ConfigServiceLocalCacheTest {

    private static final String GROUP = "tenant";
    private static final String KEY = "dev_host_mapping_enabled";

    @Mock
    private SysConfigRepository repository;

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private ConfigServiceImpl configService;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        configService = new ConfigServiceImpl(repository, redis,
                new TenantCacheKeys("test"), new ObjectMapper());
    }

    private SysConfig item(String value) {
        SysConfig config = new SysConfig();
        config.setConfigGroup(GROUP);
        config.setConfigKey(KEY);
        config.setConfigValue(value);
        config.setValueType(SysConfig.ValueType.BOOLEAN.name());
        return config;
    }

    @Test
    @DisplayName("命中 L1 后不再访问 Redis 与数据库")
    void shouldServeRepeatedReadsFromLocalCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(repository.findByConfigGroupAndConfigKey(GROUP, KEY)).thenReturn(Optional.of(item("true")));

        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();

        // 首次回源一次即可，后续两次必须全部命中 L1
        verify(repository, times(1)).findByConfigGroupAndConfigKey(GROUP, KEY);
        verify(valueOps, times(1)).get(anyString());
    }

    @Test
    @DisplayName("Redis 命中时写入 L1，后续不再访问 Redis")
    void shouldPromoteRedisHitIntoLocalCache() {
        when(valueOps.get(anyString())).thenReturn("true");

        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();

        verify(valueOps, times(1)).get(anyString());
        verify(repository, times(0)).findByConfigGroupAndConfigKey(anyString(), anyString());
    }

    @Test
    @DisplayName("空值哨兵同样进入 L1，避免反复穿透")
    void shouldCacheAbsentMarkerLocally() {
        when(valueOps.get(anyString())).thenReturn(TenantCacheKeys.ABSENT_MARKER);

        assertThat(configService.find(GROUP, KEY)).isEmpty();
        assertThat(configService.find(GROUP, KEY)).isEmpty();

        verify(valueOps, times(1)).get(anyString());
        verify(repository, times(0)).findByConfigGroupAndConfigKey(anyString(), anyString());
    }

    @Test
    @DisplayName("evict 后必须重新回源，保证改完即生效")
    void shouldReloadAfterEvict() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(repository.findByConfigGroupAndConfigKey(GROUP, KEY))
                .thenReturn(Optional.of(item("true")))
                .thenReturn(Optional.of(item("false")));

        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        configService.evict(GROUP, KEY);
        assertThat(configService.getBoolean(GROUP, KEY, true)).isFalse();

        verify(repository, times(2)).findByConfigGroupAndConfigKey(GROUP, KEY);
    }

    @Test
    @DisplayName("evictAll 清空 L1")
    void shouldClearLocalCacheOnEvictAll() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(repository.findByConfigGroupAndConfigKey(GROUP, KEY))
                .thenReturn(Optional.of(item("true")))
                .thenReturn(Optional.of(item("false")));
        when(redis.keys(anyString())).thenReturn(null);

        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        configService.evictAll();
        assertThat(configService.getBoolean(GROUP, KEY, true)).isFalse();

        verify(repository, times(2)).findByConfigGroupAndConfigKey(GROUP, KEY);
    }

    @Test
    @DisplayName("Redis 不可用时降级直连数据库，不抛异常")
    void shouldDegradeWhenRedisUnavailable() {
        when(valueOps.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        when(repository.findByConfigGroupAndConfigKey(GROUP, KEY)).thenReturn(Optional.of(item("true")));
        lenient().doThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"))
                .when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));

        assertThat(configService.getBoolean(GROUP, KEY, false)).isTrue();
        verify(repository, times(1)).findByConfigGroupAndConfigKey(eq(GROUP), eq(KEY));
    }
}
