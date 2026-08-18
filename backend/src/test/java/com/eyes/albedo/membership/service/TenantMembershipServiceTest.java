package com.eyes.albedo.membership.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.membership.dto.ProfileSnapshot;
import com.eyes.albedo.membership.entity.TenantUser;
import com.eyes.albedo.membership.repository.TenantUserRepository;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * 惰性建户单测（PRD §6.2.4 / AC-AUTH-005 / AC-AUTH-006 / EX-009）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantMembershipServiceTest {

    private static final long UID = 10086L;

    @Mock
    private TenantUserRepository repository;
    @Mock
    private UserProfileFetcher profileFetcher;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ConfigService configService;

    private TenantMembershipService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(configService.getLong(anyString(), anyString(), anyLong())).thenReturn(60L);
        service = new TenantMembershipService(repository, profileFetcher, redis,
                new TenantCacheKeys("test"), configService, new ObjectMapper());
        TenantContext.bind(new TenantContext.Snapshot("gift", 1L, "albedo-gift.eyescode.top",
                TenantStatus.ENABLED, 1L, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("成员不存在 → 建立默认 END_USER 关系")
    void createsDefaultMembership() {
        when(repository.findByUid(UID)).thenReturn(Optional.empty());
        when(profileFetcher.fetch(UID)).thenReturn(new ProfileSnapshot("小明", ""));
        when(repository.saveAndFlush(any(TenantUser.class))).thenAnswer(invocation -> {
            TenantUser saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        assertEquals(TenantRoleEnum.END_USER, service.ensureMembership(UID));
        verify(repository).saveAndFlush(any(TenantUser.class));
    }

    @Test
    @DisplayName("AC-AUTH-006 / EX-009：成员被禁用 → 10003，且不新建、不恢复")
    void disabledMemberIsRejectedAndNotRestored() {
        TenantUser disabled = member(TenantUser.STATUS_DISABLED, TenantRoleEnum.END_USER);
        when(repository.findByUid(UID)).thenReturn(Optional.of(disabled));

        BusinessException e = assertThrows(BusinessException.class, () -> service.ensureMembership(UID));
        assertEquals(ErrorCode.PERMISSION_DENIED, e.getCode());
        // 🔴 绝不能因为再次访问而恢复状态或新建关系
        verify(repository, never()).saveAndFlush(any(TenantUser.class));
        assertEquals(TenantUser.STATUS_DISABLED, disabled.getStatus());
    }

    @Test
    @DisplayName("已存在 active 成员 → 返回其租户内角色（不降级为 END_USER）")
    void returnsExistingRole() {
        when(repository.findByUid(UID))
                .thenReturn(Optional.of(member(TenantUser.STATUS_ACTIVE, TenantRoleEnum.TENANT_ADMIN)));

        assertEquals(TenantRoleEnum.TENANT_ADMIN, service.ensureMembership(UID));
    }

    @Test
    @DisplayName("命中角色缓存时不查库（性能：每个受保护请求都会执行）")
    void usesRoleCache() {
        when(valueOps.get(anyString())).thenReturn(TenantRoleEnum.TENANT_OPERATOR.name());

        assertEquals(TenantRoleEnum.TENANT_OPERATOR, service.ensureMembership(UID));
        verify(repository, never()).findByUid(anyLong());
    }

    @Test
    @DisplayName("禁用哨兵缓存命中 → 直接 10003，不打库")
    void usesDisabledMarkerCache() {
        when(valueOps.get(anyString())).thenReturn("__DISABLED__");

        assertEquals(ErrorCode.PERMISSION_DENIED,
                assertThrows(BusinessException.class, () -> service.ensureMembership(UID)).getCode());
        verify(repository, never()).findByUid(anyLong());
    }

    @Test
    @DisplayName("AC-TEN-006：租户暂停时即使持有有效身份也不得建立/使用成员关系 → 30011")
    void suspendedTenantBlocksMembership() {
        TenantContext.bind(new TenantContext.Snapshot("gift", 1L, "albedo-gift.eyescode.top",
                TenantStatus.SUSPENDED, 1L, null));

        assertEquals(ErrorCode.TENANT_SUSPENDED,
                assertThrows(BusinessException.class, () -> service.ensureMembership(UID)).getCode());
        verify(repository, never()).findByUid(anyLong());
    }

    @Test
    @DisplayName("资料快照只保留昵称与头像（🔴 不得出现邮箱/手机号字段）")
    void profileSnapshotContainsOnlyDisplayFields() throws Exception {
        when(repository.findByUid(UID)).thenReturn(Optional.empty());
        when(profileFetcher.fetch(UID)).thenReturn(new ProfileSnapshot("小明", "https://a/b.png"));
        when(repository.saveAndFlush(any(TenantUser.class))).thenAnswer(invocation -> {
            TenantUser saved = invocation.getArgument(0);
            String json = saved.getProfileSnapshot();
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("nickname"));
            org.junit.jupiter.api.Assertions.assertTrue(json.contains("avatarUrl"));
            org.junit.jupiter.api.Assertions.assertFalse(json.contains("email"));
            org.junit.jupiter.api.Assertions.assertFalse(json.contains("phone"));
            saved.setId(1L);
            return saved;
        });

        service.ensureMembership(UID);
        verify(repository).saveAndFlush(any(TenantUser.class));
    }

    private TenantUser member(String status, TenantRoleEnum role) {
        TenantUser user = new TenantUser();
        user.setId(1L);
        user.setUid(UID);
        user.setStatus(status);
        user.setTenantRole(role.name());
        return user;
    }
}
