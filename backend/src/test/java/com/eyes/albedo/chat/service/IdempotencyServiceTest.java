package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 幂等服务单测（api-spec §1.4 / EX-013）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyServiceTest {

    private static final long UID = 10086L;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private BusinessConfig businessConfig;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(businessConfig.requireLong(ConfigKeys.GROUP_CHAT, ConfigKeys.IDEMPOTENCY_TTL_SECONDS))
                .thenReturn(600L);
        service = new IdempotencyService(redis, new TenantCacheKeys("test"), businessConfig);
        TenantContext.bind(new TenantContext.Snapshot("gift", 1L, "host",
                TenantStatus.ENABLED, 1L, UID));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("缺少或格式非法的幂等键 → 10001")
    void requireKeyValidation() {
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class, () -> service.requireKey(null)).getCode());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class, () -> service.requireKey("  ")).getCode());
        // 过短 / 含非法字符
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class, () -> service.requireKey("abc")).getCode());
        assertEquals(ErrorCode.VALIDATION_FAILED,
                assertThrows(BusinessException.class,
                        () -> service.requireKey("bad key with space")).getCode());

        String uuid = "550e8400-e29b-41d4-a716-446655440000";
        assertEquals(uuid, service.requireKey(uuid));
    }

    @Test
    @DisplayName("首次执行：抢占成功后执行业务并记录资源 ID")
    void firstExecution() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        String result = service.executeOnce(UID, "key-12345678", () -> "1001",
                id -> "replayed-" + id, id -> id);

        assertEquals("1001", result);
        verify(valueOps).set(anyString(), org.mockito.ArgumentMatchers.eq("1001"), any(Duration.class));
    }

    @Test
    @DisplayName("EX-013：重复请求命中已完成键 → 回放原结果，业务不再执行")
    void replaysRecordedResult() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("1001");
        AtomicInteger executions = new AtomicInteger();

        String result = service.executeOnce(UID, "key-12345678", () -> {
            executions.incrementAndGet();
            return "should-not-run";
        }, id -> "replayed-" + id, id -> id);

        assertEquals("replayed-1001", result);
        assertEquals(0, executions.get(), "🔴 幂等命中时业务动作绝不能再次执行");
    }

    @Test
    @DisplayName("业务失败必须释放幂等键，否则用户在 TTL 内无法重试")
    void releasesKeyOnFailure() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> service.executeOnce(UID, "key-12345678",
                () -> {
                    throw new IllegalStateException("boom");
                }, id -> id, id -> id));

        verify(redis).delete(anyString());
    }

    @Test
    @DisplayName("并发重复提交（前一次仍在执行）→ 30020，绝不放行第二次执行")
    void concurrentDuplicateRejected() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn("__IN_FLIGHT__");
        AtomicInteger executions = new AtomicInteger();

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.executeOnce(UID, "key-12345678", () -> {
                    executions.incrementAndGet();
                    return "x";
                }, id -> id, id -> id));

        assertEquals(ErrorCode.VERSION_CONFLICT, e.getCode());
        assertEquals(0, executions.get());
    }

    @Test
    @DisplayName("Redis 不可用：降级执行业务（数据库唯一键仍兜底），不阻断用户")
    void degradesWhenRedisUnavailable() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis down"));

        assertEquals("1001", service.executeOnce(UID, "key-12345678", () -> "1001",
                id -> "replayed", id -> id));
    }

    @Test
    @DisplayName("幂等键含租户与用户维度：不同租户/用户互不影响")
    void keyIsTenantAndUserScoped() {
        TenantCacheKeys keys = new TenantCacheKeys("test");
        String gift = keys.chatIdempotency("gift", UID, "key-1");
        String redbook = keys.chatIdempotency("redbook", UID, "key-1");
        String other = keys.chatIdempotency("gift", 999L, "key-1");

        assertTrue(gift.contains(":gift:"));
        assertTrue(!gift.equals(redbook));
        assertTrue(!gift.equals(other));
    }

    @Test
    @DisplayName("tryAcquire：抢占失败返回 false（用于 SSE 场景自行决定回放方式）")
    void tryAcquire() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertTrue(!service.tryAcquire(UID, "key-12345678"));

        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertTrue(service.tryAcquire(UID, "key-12345678"));
    }

    @Test
    @DisplayName("findRecorded：占位值不算已完成结果")
    void findRecordedIgnoresInFlight() {
        when(valueOps.get(anyString())).thenReturn("__IN_FLIGHT__");
        assertTrue(service.findRecorded(UID, "key-12345678").isEmpty());

        when(valueOps.get(anyString())).thenReturn("2002");
        assertEquals("2002", service.findRecorded(UID, "key-12345678").orElseThrow());
    }

    @Test
    @DisplayName("release 不吞掉 Redis 异常导致业务失败")
    void releaseIsSafe() {
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis down"));
        service.release(UID, "key-12345678");
        verify(redis).delete(anyString());
    }

    @Test
    @DisplayName("record 记录结果 ID")
    void recordStoresId() {
        service.record(UID, "key-12345678", "3003");
        verify(valueOps).set(anyString(), org.mockito.ArgumentMatchers.eq("3003"), any(Duration.class));
    }

    @Test
    @DisplayName("record(null) 不写入（避免把 null 当结果缓存）")
    void recordIgnoresNull() {
        service.record(UID, "key-12345678", null);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }
}
