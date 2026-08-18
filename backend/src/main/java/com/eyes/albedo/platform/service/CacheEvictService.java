package com.eyes.albedo.platform.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.platform.dto.CacheEvictRequest;
import com.eyes.albedo.platform.dto.CacheEvictResultDTO;
import com.eyes.albedo.platform.dto.CacheEvictScopeResultDTO;
import com.eyes.albedo.platform.entity.TenantDomain;
import com.eyes.albedo.platform.repository.TenantDomainRepository;
import com.eyes.albedo.platform.repository.TenantRepository;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tenant.CacheEviction;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantResolver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台缓存失效服务（api-spec §7.2.1 / ADR-013 三件套之②）。
 *
 * <p><b>为什么一期必须有这个接口</b>：租户级配置由 DBA 直接改库（DEC-010），
 * 而 L1/L2 缓存在 TTL 内仍返回旧值，表现为"改了没生效"（M1 缺陷 D-003 / D-006 已实测踩坑）。
 * 本接口把"发布后 30 秒内生效"变成"调一次接口即刻生效"，且<b>失败可见、可重试</b>。
 *
 * <p>🔴 四条硬约束（缺一即为缺陷）：
 * <ol>
 *   <li><b>L1 与 L2 同时失效</b>：只清 Redis 会让本 JVM 在 L1 TTL 内继续读旧值</li>
 *   <li><b>Host 键与租户号键成对失效</b>：dev 映射路径走租户号键、正式路径走 Host 键，
 *       只清一个就会让另一条路径继续读到旧 {@code configVersion}（D-003 / D-006）</li>
 *   <li><b>禁止删除运行时状态键</b>：{@code chat:idem} / {@code chat:cancel} / {@code tool:confirm} /
 *       {@code limit:msg} 不是缓存，删除会破坏幂等、停止生成、工具确认与限流语义；
 *       即便 {@code scope=all} 也必须跳过（判定入口 {@link TenantCacheKeys#isProtectedRuntimeStateKey}）</li>
 *   <li><b>禁止伪报成功</b>：部分失败返回 {@code 30061} + {@code incompleteScopes[]}（EX-033）</li>
 * </ol>
 *
 * <p>🔴 一期<b>不</b>失效 Skill 版本快照 / MCP 工具授权清单 / 本地 Tool 授权与能力绑定：
 * 这三类数据一期<b>不缓存、直读 MySQL</b>（api-spec §7.1.2 末尾 / architecture.md §12.1.1），
 * DBA 改库后本就立即生效。这不是漏项，@测试 也不应将其纳入失效断言。
 *
 * <p>🔴 事务边界（ADR-010 / §7.14）：本类方法与审计<b>同一事务</b>——
 * 审计写入失败 → 整体回滚 → 返回 {@code 50003}（EX-024，"不得执行后伪报未审计"）。
 * ⚠️ 注意：Redis 删除不受数据库事务保护（不可回滚），因此顺序是「先失效 → 再审计」，
 * 审计失败时数据库无残留但缓存已清 —— 清缓存是幂等且无害的，重试即可，这是本设计有意接受的代价。
 *
 * <p>🔴 ADR-001 单实例前提：L1 是进程内缓存，本接口只能失效<b>本 JVM</b> 的 L1。
 * 若未来打破 ADR-001（多实例），必须同步引入 L1 失效广播，否则本契约失效。
 */
@Slf4j
@Service
public class CacheEvictService {

    /** 作用域字面量（api-spec §7.2.1 请求 {@code scope} 枚举）。 */
    public static final String SCOPE_TENANT = "tenant";
    public static final String SCOPE_HOST = "host";
    public static final String SCOPE_SYSCONFIG = "sysconfig";
    public static final String SCOPE_AGENT_VERSION = "agentVersion";
    public static final String SCOPE_ALL = "all";

    private static final Set<String> SUPPORTED_SCOPES =
            Set.of(SCOPE_TENANT, SCOPE_HOST, SCOPE_SYSCONFIG, SCOPE_AGENT_VERSION, SCOPE_ALL);

    /** 内部作用域名（响应 {@code results[].scope}，@测试 据此断言）。 */
    private static final String INNER_TENANT_HOST = "tenantHost";
    private static final String INNER_TENANT_CODE = "tenantCode";
    private static final String INNER_SITE_CONFIG = "siteConfig";
    private static final String INNER_AGENT_VERSION = "agentVersion";
    private static final String INNER_MEMBER_ROLE = "memberRole";
    private static final String INNER_SYSCONFIG = "sysconfig";

    private final TenantResolver tenantResolver;
    private final ConfigService configService;
    private final TenantCacheKeys cacheKeys;
    private final StringRedisTemplate redis;
    private final TenantRepository tenantRepository;
    private final TenantDomainRepository tenantDomainRepository;
    private final AuditService auditService;

    public CacheEvictService(TenantResolver tenantResolver,
                            ConfigService configService,
                            TenantCacheKeys cacheKeys,
                            StringRedisTemplate redis,
                            TenantRepository tenantRepository,
                            TenantDomainRepository tenantDomainRepository,
                            AuditService auditService) {
        this.tenantResolver = tenantResolver;
        this.configService = configService;
        this.cacheKeys = cacheKeys;
        this.redis = redis;
        this.tenantRepository = tenantRepository;
        this.tenantDomainRepository = tenantDomainRepository;
        this.auditService = auditService;
    }

    /**
     * 按作用域失效缓存。
     *
     * @throws BusinessException 10001 参数非法；30061 部分或全部失效失败；50003 审计写入失败
     */
    @Transactional
    public CacheEvictResultDTO evict(CacheEvictRequest request) {
        String scope = normalizeScope(request.scope());
        List<CacheEvictScopeResultDTO> results = new ArrayList<>();

        switch (scope) {
            case SCOPE_TENANT -> evictTenant(requireTenantId(request.tenantId()), null, results);
            case SCOPE_HOST -> evictHost(requireHost(request.host()), results);
            case SCOPE_SYSCONFIG -> evictSysConfig(blankToNull(request.configGroup()), results);
            case SCOPE_AGENT_VERSION -> evictAgentVersion(requireTenantId(request.tenantId()),
                    parseAgentId(request.agentId()), results);
            case SCOPE_ALL -> evictAll(results);
            default -> throw BusinessException.validation("scope 非法");
        }

        List<String> incomplete = results.stream()
                .filter(CacheEvictScopeResultDTO::failed)
                .map(CacheEvictScopeResultDTO::scope)
                .distinct()
                .toList();
        long totalL1 = results.stream().mapToLong(CacheEvictScopeResultDTO::l1Evicted).sum();
        long totalL2 = results.stream().mapToLong(CacheEvictScopeResultDTO::l2Evicted).sum();

        // 🔴 每次调用都必须独立审计（§1.4），含部分失败；与业务同事务（ADR-010）
        String eventId = auditService.record(AuditEvent.platform(
                AuditActions.PLATFORM_CACHE_EVICT,
                incomplete.isEmpty() ? AuditResults.SUCCESS : AuditResults.FAILED,
                "cacheScope",
                auditTarget(scope, request),
                request.reason(),
                incomplete.isEmpty() ? null : ErrorCode.CACHE_INVALIDATION_FAILED));

        CacheEvictResultDTO data = new CacheEvictResultDTO(scope, results, totalL1, totalL2,
                incomplete, eventId);
        if (!incomplete.isEmpty()) {
            // 🔴 禁止伪报成功（EX-033）：如实返回 30061 + 未完成作用域
            log.warn("缓存失效未全部完成：scope={} incomplete={}", scope, incomplete);
            throw new BusinessException(ErrorCode.CACHE_INVALIDATION_FAILED,
                    ErrorCode.defaultMessage(ErrorCode.CACHE_INVALIDATION_FAILED), data);
        }
        log.info("缓存失效完成：scope={} l1={} l2={} auditEventId={}", scope, totalL1, totalL2, eventId);
        return data;
    }

    // ===================== 各作用域实现 =====================

    /**
     * {@code scope=tenant}：Host 键 + 租户号键（🔴 成对）+ 站点配置快照 + Agent 版本快照 + 成员角色。
     */
    private void evictTenant(String tenantId, Long agentId, List<CacheEvictScopeResultDTO> results) {
        // ① 该租户全部绑定 Host（含 tenants.primary_host，防止绑定表与主机名不同步时漏清）
        Set<String> hosts = new LinkedHashSet<>();
        tenantDomainRepository.findByTenantId(tenantId).stream()
                .map(TenantDomain::getHost)
                .filter(h -> h != null && !h.isBlank())
                .forEach(hosts::add);
        tenantRepository.findByTenantIdAndDeletedAtIsNull(tenantId)
                .map(t -> t.getPrimaryHost())
                .filter(h -> h != null && !h.isBlank())
                .ifPresent(hosts::add);

        CacheEviction hostTotal = CacheEviction.NONE;
        boolean hostFailed = false;
        for (String host : hosts) {
            try {
                hostTotal = hostTotal.plus(tenantResolver.evictHostAndCount(host));
            } catch (RuntimeException e) {
                log.warn("失效 Host 解析缓存失败：host={}", host, e);
                hostFailed = true;
            }
        }
        results.add(result(INNER_TENANT_HOST, hosts.isEmpty() ? "-" : String.join(",", hosts),
                hostTotal, hostFailed));

        // ② 🔴 租户号键必须与 Host 键成对失效（D-003 / D-006：dev 映射路径不经过 tenant_domains）
        results.add(safely(INNER_TENANT_CODE, tenantId,
                () -> tenantResolver.evictTenantIdAndCount(tenantId)));

        // ③ 站点配置快照（键内含版本号，全部版本一并清理，避免旧键堆积）
        results.add(safely(INNER_SITE_CONFIG, tenantId,
                () -> deleteByPattern(cacheKeys.siteConfigPattern(tenantId))));

        // ④ Agent 版本快照
        results.add(safely(INNER_AGENT_VERSION, tenantId,
                () -> deleteByPattern(cacheKeys.agentVersionPattern(tenantId, agentId))));

        // ⑤ 成员角色缓存（成员被禁用后必须能立刻生效，AC-AUTH-006）
        results.add(safely(INNER_MEMBER_ROLE, tenantId,
                () -> deleteByPattern(cacheKeys.memberRolePattern(tenantId))));
    }

    /**
     * {@code scope=host}：指定 Host 键 + 其解析出的租户号键（🔴 同样成对失效）。
     */
    private void evictHost(String host, List<CacheEvictScopeResultDTO> results) {
        String normalized = TenantResolver.stripPort(TenantResolver.normalizeRawHost(host));
        results.add(safely(INNER_TENANT_HOST, normalized,
                () -> tenantResolver.evictHostAndCount(normalized)));

        Optional<String> tenantId = tenantDomainRepository.findByHost(normalized)
                .map(TenantDomain::getTenantId);
        if (tenantId.isPresent()) {
            String code = tenantId.get();
            results.add(safely(INNER_TENANT_CODE, code,
                    () -> tenantResolver.evictTenantIdAndCount(code)));
        } else {
            // Host 未绑定任何租户：不是错误（可能刚解绑），如实回报 0 条
            log.info("Host 未绑定租户，仅失效 Host 键：host={}", normalized);
            results.add(result(INNER_TENANT_CODE, "-", CacheEviction.NONE, false));
        }
    }

    /**
     * {@code scope=sysconfig}：平台配置单项 + 前端聚合 + {@code ConfigService} 的 L1。
     */
    private void evictSysConfig(String configGroup, List<CacheEvictScopeResultDTO> results) {
        results.add(safely(INNER_SYSCONFIG, configGroup == null ? "*" : configGroup,
                () -> configService.evictAllAndCount(configGroup)));
    }

    /**
     * {@code scope=agentVersion}：指定租户（可选指定 Agent）的 Agent 版本快照。
     *
     * <p>⚠️ 一期<b>不含</b>能力绑定快照：{@code agent_capability_bindings} 一期不缓存、直读 DB
     * （api-spec §7.1.2 / architecture.md §12.1.1）。
     */
    private void evictAgentVersion(String tenantId, Long agentId,
                                   List<CacheEvictScopeResultDTO> results) {
        results.add(safely(INNER_AGENT_VERSION, agentId == null ? tenantId : tenantId + "/" + agentId,
                () -> deleteByPattern(cacheKeys.agentVersionPattern(tenantId, agentId))));
    }

    /**
     * {@code scope=all}：平台配置 + 全部租户的解析键与快照。
     *
     * <p>🔴 高危操作，{@code reason} 必须含工单号（由调用方纪律保证，接口只强制非空）。
     * 🔴 运行时状态键即便在此也必须跳过（{@link #deleteByPattern} 内已过滤）。
     */
    private void evictAll(List<CacheEvictScopeResultDTO> results) {
        evictSysConfig(null, results);

        // 租户解析 L1 整体清空（Redis 侧按键模式删除）
        int localCleared = tenantResolver.evictAllLocal();
        results.add(safely(INNER_TENANT_HOST, "*",
                () -> CacheEviction.of(localCleared,
                        deleteByPattern(cacheKeys.tenantByHost("*")).l2Evicted())));
        results.add(safely(INNER_TENANT_CODE, "*",
                () -> deleteByPattern(cacheKeys.tenantByCode("*"))));

        // 各租户的站点配置 / Agent 快照 / 成员角色：用 env 级模式一次覆盖
        results.add(safely(INNER_SITE_CONFIG, "*",
                () -> deleteByPattern(cacheKeys.ofTenant("*", "site", "config", "*"))));
        results.add(safely(INNER_AGENT_VERSION, "*",
                () -> deleteByPattern(cacheKeys.ofTenant("*", "agent", "version", "*"))));
        results.add(safely(INNER_MEMBER_ROLE, "*",
                () -> deleteByPattern(cacheKeys.ofTenant("*", "member", "role", "*"))));
    }

    // ===================== 内部工具 =====================

    /**
     * 按键模式删除，🔴 命中「运行时状态键」一律<b>拒绝删除</b>并记 WARN。
     *
     * <p>为什么在最底层做这道过滤：作用域组合会不断增加（尤其 {@code all}），
     * 把保护放在唯一的删除出口上，才能保证"以后新增作用域也不会误删"。
     */
    private CacheEviction deleteByPattern(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys == null || keys.isEmpty()) {
            return CacheEviction.NONE;
        }
        List<String> deletable = new ArrayList<>(keys.size());
        for (String key : keys) {
            if (cacheKeys.isProtectedRuntimeStateKey(key)) {
                // 不是缓存而是运行时状态（幂等/取消/确认/限流），删除会破坏语义
                log.warn("[CACHE-GUARD] 拒绝删除运行时状态键：pattern={}", pattern);
                continue;
            }
            deletable.add(key);
        }
        if (deletable.isEmpty()) {
            return CacheEviction.NONE;
        }
        Long removed = redis.delete(deletable);
        return CacheEviction.of(0, removed == null ? 0L : removed);
    }

    /**
     * 执行单个作用域并把异常收敛为 {@code status=failed}（🔴 不让一个作用域失败掩盖其他作用域结果）。
     */
    private CacheEvictScopeResultDTO safely(String scope, String target,
                                            java.util.function.Supplier<CacheEviction> action) {
        try {
            return result(scope, target, action.get(), false);
        } catch (RuntimeException e) {
            log.warn("缓存失效作用域失败：scope={} target={}", scope, target, e);
            return result(scope, target, CacheEviction.NONE, true);
        }
    }

    private CacheEvictScopeResultDTO result(String scope, String target,
                                            CacheEviction eviction, boolean failed) {
        return new CacheEvictScopeResultDTO(scope, target,
                eviction.l1Evicted(), eviction.l2Evicted(),
                failed ? CacheEvictScopeResultDTO.STATUS_FAILED
                        : CacheEvictScopeResultDTO.STATUS_SUCCEEDED);
    }

    private String normalizeScope(String scope) {
        String value = scope == null ? "" : scope.trim();
        if (!SUPPORTED_SCOPES.contains(value)) {
            throw BusinessException.validation("scope 非法，仅支持 tenant/host/sysconfig/agentVersion/all");
        }
        return value;
    }

    private String requireTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.validation("该 scope 下 tenantId 必填");
        }
        return tenantId.trim().toLowerCase(Locale.ROOT);
    }

    private String requireHost(String host) {
        if (host == null || host.isBlank()) {
            throw BusinessException.validation("scope=host 时 host 必填");
        }
        return host.trim();
    }

    private Long parseAgentId(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(agentId.trim());
        } catch (NumberFormatException e) {
            throw BusinessException.validation("agentId 格式非法");
        }
    }

    private String auditTarget(String scope, CacheEvictRequest request) {
        return switch (scope) {
            case SCOPE_TENANT, SCOPE_AGENT_VERSION -> request.tenantId();
            case SCOPE_HOST -> request.host();
            case SCOPE_SYSCONFIG -> blankToNull(request.configGroup()) == null
                    ? "*" : request.configGroup();
            default -> "*";
        };
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
