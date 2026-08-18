package com.eyes.albedo.membership.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.eyes.albedo.auth.TenantMembershipPort;
import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.membership.dto.ProfileSnapshot;
import com.eyes.albedo.membership.entity.TenantUser;
import com.eyes.albedo.membership.repository.TenantUserRepository;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantMembershipPort} 实现：惰性建户 + 租户内角色解析（PRD §6.2.4）。
 *
 * <p>行为矩阵：
 * <table border="1">
 *   <caption>惰性建户</caption>
 *   <tr><th>成员关系</th><th>动作</th></tr>
 *   <tr><td>不存在</td><td>建立默认 {@code END_USER}（并发时靠 {@code uk_tenant_uid} 兜底重查）</td></tr>
 *   <tr><td>存在 active</td><td>返回角色，节流更新 {@code last_access_at}</td></tr>
 *   <tr><td>存在 disabled</td><td>🔴 抛 10003，<b>不新建、不恢复</b>（AC-AUTH-006 / EX-009）</td></tr>
 * </table>
 *
 * <p>性能：该方法在每个 {@code @Permission(USER/ADMIN)} 接口都会执行，
 * 因此命中路径为「Redis 短 TTL 角色缓存 → 单条唯一索引查询」，避免成为 P95 ≤500ms 的瓶颈。
 *
 * <p>🔴 缓存 TTL 必须是分钟级：成员被禁用后最迟一个 TTL 内生效。
 */
@Slf4j
@Service
public class TenantMembershipService implements TenantMembershipPort {

    /** 缓存中表示「成员已被禁用」的哨兵，避免被禁用用户高频打库。 */
    private static final String DISABLED_MARKER = "__DISABLED__";
    private static final long DEFAULT_ROLE_CACHE_TTL_SECONDS = 60L;
    private static final long DEFAULT_TOUCH_INTERVAL_SECONDS = 300L;

    private final TenantUserRepository repository;
    private final UserProfileFetcher profileFetcher;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public TenantMembershipService(TenantUserRepository repository,
                                   UserProfileFetcher profileFetcher,
                                   StringRedisTemplate redis,
                                   TenantCacheKeys cacheKeys,
                                   ConfigService configService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.profileFetcher = profileFetcher;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    @Override
    public TenantRoleEnum ensureMembership(long uid) {
        // 租户必须已启用：暂停租户即便持有有效 Token 也不得建立/使用成员关系（AC-TEN-006）
        TenantContext.Snapshot tenant = TenantContext.requireEnabled();
        String cacheKey = cacheKeys.memberRole(tenant.tenantId(), uid);

        String cached = safeGet(cacheKey);
        if (DISABLED_MARKER.equals(cached)) {
            throw BusinessException.permissionDenied("当前站点的成员身份已被禁用，请联系管理员");
        }
        if (cached != null && !cached.isBlank()) {
            return TenantRoleEnum.of(cached);
        }

        TenantUser member = loadOrCreate(uid);
        if (!member.isActive()) {
            safeSet(cacheKey, DISABLED_MARKER, roleCacheTtl());
            throw BusinessException.permissionDenied("当前站点的成员身份已被禁用，请联系管理员");
        }
        safeSet(cacheKey, member.getTenantRole(), roleCacheTtl());
        return TenantRoleEnum.of(member.getTenantRole());
    }

    /**
     * 读取当前租户内的成员关系（供 {@code GET /api/v1/me} 使用）。
     */
    @Transactional(readOnly = true)
    public Optional<TenantUser> findMember(long uid) {
        return repository.findByUid(uid);
    }

    /**
     * 解析资料快照；解析失败按空快照返回（不抛异常，避免个人资料损坏阻断登录态）。
     */
    public ProfileSnapshot readProfile(TenantUser member) {
        String raw = member == null ? null : member.getProfileSnapshot();
        if (raw == null || raw.isBlank()) {
            return ProfileSnapshot.empty();
        }
        try {
            return objectMapper.readValue(raw, ProfileSnapshot.class).normalized();
        } catch (JsonProcessingException e) {
            log.warn("成员资料快照解析失败，按空快照返回：uid={}", member.getUid());
            return ProfileSnapshot.empty();
        }
    }

    /**
     * 失效角色缓存（M2 成员角色/状态变更后必须调用）。
     */
    public void evictRoleCache(String tenantId, long uid) {
        try {
            redis.delete(cacheKeys.memberRole(tenantId, uid));
        } catch (RuntimeException e) {
            log.warn("失效成员角色缓存失败：uid={}", uid, e);
        }
    }

    // ===================== 内部实现 =====================

    /**
     * 查询或创建成员关系。
     *
     * <p>此处<b>刻意不加</b> {@code @Transactional}：① 本方法由同类方法调用，注解不会经代理生效
     * （自调用陷阱）；② 每个仓储操作自身即原子，且并发创建由唯一键 {@code uk_tenant_uid} 兜底，
     * 无需跨语句事务；③ 鉴权切面在请求最前端执行，不应持有数据库事务。
     */
    private TenantUser loadOrCreate(long uid) {
        Optional<TenantUser> existing = repository.findByUid(uid);
        if (existing.isPresent()) {
            TenantUser member = existing.get();
            if (member.isActive()) {
                touch(member);
            }
            return member;
        }
        return create(uid);
    }

    private TenantUser create(long uid) {
        TenantUser member = new TenantUser();
        member.setUid(uid);
        // 惰性建户默认角色固定为终端用户；eyesUser ADMIN 不自动获得任何租户内角色（AC-AUTH-007）
        member.setTenantRole(TenantRoleEnum.END_USER.name());
        member.setStatus(TenantUser.STATUS_ACTIVE);
        member.setProfileSnapshot(writeProfile(profileFetcher.fetch(uid)));
        member.setLastAccessAt(Instant.now());
        try {
            return repository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            // 并发首次访问：唯一键 uk_tenant_uid 冲突后重查，保证幂等
            log.debug("并发惰性建户命中唯一键，回查既有成员：uid={}", uid);
            return repository.findByUid(uid).orElseThrow(() -> e);
        }
    }

    /**
     * 节流更新最近访问时间：避免每个受保护请求都产生一次 UPDATE。
     */
    private void touch(TenantUser member) {
        long intervalSeconds = configService.getLong(ConfigKeys.GROUP_TENANT,
                ConfigKeys.MEMBER_ACCESS_TOUCH_INTERVAL_SECONDS, DEFAULT_TOUCH_INTERVAL_SECONDS);
        Instant last = member.getLastAccessAt();
        Instant now = Instant.now();
        if (last != null && last.isAfter(now.minusSeconds(intervalSeconds))) {
            return;
        }
        member.setLastAccessAt(now);
        repository.save(member);
    }

    private String writeProfile(ProfileSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot.normalized());
        } catch (JsonProcessingException e) {
            log.warn("序列化成员资料快照失败，按空快照落库", e);
            return null;
        }
    }

    private Duration roleCacheTtl() {
        return Duration.ofSeconds(configService.getLong(ConfigKeys.GROUP_TENANT,
                ConfigKeys.MEMBER_ROLE_CACHE_TTL_SECONDS, DEFAULT_ROLE_CACHE_TTL_SECONDS));
    }

    private String safeGet(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("读取成员角色缓存失败，降级直连数据库", e);
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        try {
            redis.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("写入成员角色缓存失败：{}", key, e);
        }
    }
}
