package com.eyes.albedo.agent.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.agent.dto.AgentAdminDTO;
import com.eyes.albedo.agent.dto.AgentDTO;
import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.dto.AgentVersionDraft;
import com.eyes.albedo.agent.entity.Agent;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentRepository;
import com.eyes.albedo.agent.repository.AgentVersionRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.site.dto.ViolationDTO;
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
 * Agent 服务：展示、运行解析、版本发布与治理。
 *
 * <p>M1 关键语义：
 * <ul>
 *   <li>只有「{@code status=enabled} 且 {@code currentVersion>0}」的 Agent 可展示与新建会话（AC-AGT-001）</li>
 *   <li>会话固定绑定创建时的已发布版本；🔴 发现新版本只提示、<b>绝不静默切版</b>（RISK-005 / AC-AGT-002）</li>
 *   <li>停用后：不可新建、不可继续发送（{@code 30031}），历史会话只读</li>
 *   <li>发布 = 生成不可变快照 + 切 {@code currentVersion} 指针；校验失败不影响线上（{@code 30021}）</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentService {

    private static final Duration RUNTIME_CACHE_TTL = Duration.ofSeconds(1800);

    private final AgentRepository agentRepository;
    private final AgentVersionRepository versionRepository;
    private final AgentValidator validator;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final ObjectMapper objectMapper;

    public AgentService(AgentRepository agentRepository,
                        AgentVersionRepository versionRepository,
                        AgentValidator validator,
                        StringRedisTemplate redis,
                        TenantCacheKeys cacheKeys,
                        ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.versionRepository = versionRepository;
        this.validator = validator;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.objectMapper = objectMapper;
    }

    // ===================== 展示与运行解析（M1 主链路） =====================

    /**
     * 当前租户可用 Agent 列表（已按 {@code isDefault DESC, sortOrder ASC, id ASC} 排序）。
     */
    @Transactional(readOnly = true)
    public List<AgentDTO> listRunnable() {
        TenantContext.requireEnabled();
        return agentRepository.findRunnable(Agent.STATUS_ENABLED).stream()
                .map(this::toPublicDTO)
                .toList();
    }

    /**
     * 管理视图列表（M2 管理端消费）。
     */
    @Transactional(readOnly = true)
    public List<AgentAdminDTO> listManaged() {
        TenantContext.requireEnabled();
        return agentRepository.findAllManaged().stream()
                .map(this::toAdminDTO)
                .toList();
    }

    /**
     * 新建会话时解析要绑定的 Agent。
     *
     * @param agentIdRaw 客户端指定的 agentId（string，可为空 → 使用默认 Agent）
     * @throws BusinessException 30030 当前租户无可用 Agent；30031 指定 Agent 已停用/未发布
     */
    @Transactional(readOnly = true)
    public Agent resolveForNewConversation(String agentIdRaw) {
        TenantContext.Snapshot tenant = TenantContext.requireEnabled();
        log.info("[Agent] 解析可对话 Agent 入参 agentIdRaw={} tenant={}", agentIdRaw, tenant.tenantId());
        if (agentIdRaw != null && !agentIdRaw.isBlank()) {
            long agentId = Ids.parse(agentIdRaw);
            Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                    // 跨租户 / 不存在统一按「不存在」处理，不暴露存在性（AC-TEN-004）
                    .orElseThrow(BusinessException::notFound);
            if (!agent.runnable()) {
                log.warn("[Agent] Agent 不可运行 agentId={} status={}", agentId, agent.getStatus());
                throw new BusinessException(ErrorCode.AGENT_DISABLED);
            }
            log.info("[Agent] 命中指定 Agent agentId={} key={}", agentId, agent.getAgentKey());
            return agent;
        }
        List<Agent> runnable = agentRepository.findRunnable(Agent.STATUS_ENABLED);
        if (runnable.isEmpty()) {
            log.warn("[Agent] 租户无可用 Agent tenant={}", tenant.tenantId());
            throw new BusinessException(ErrorCode.AGENT_UNAVAILABLE);
        }
        // findRunnable 已按 isDefault DESC 排序，首个即默认 Agent（无默认时取排序首位）
        log.info("[Agent] 使用默认 Agent agentId={} key={} tenant={}",
                runnable.get(0).getId(), runnable.get(0).getAgentKey(), tenant.tenantId());
        return runnable.get(0);
    }

    /**
     * 校验既有会话绑定的 Agent 目前是否仍可继续对话。
     *
     * @throws BusinessException 30031 Agent 已停用 / 归档 / 删除（历史会话转只读语义，EX-011）
     */
    @Transactional(readOnly = true)
    public Agent requireRunnable(long agentId) {
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_DISABLED));
        if (!agent.runnable()) {
            throw new BusinessException(ErrorCode.AGENT_DISABLED);
        }
        return agent;
    }

    /**
     * 读取会话绑定版本的运行时快照（带租户维度缓存）。
     *
     * @throws BusinessException 30031 该版本不存在或已归档
     */
    @Transactional(readOnly = true)
    public AgentRuntime runtime(long agentId, long version) {
        TenantContext.Snapshot tenant = TenantContext.requireEnabled();
        String cacheKey = cacheKeys.agentVersion(tenant.tenantId(), agentId, version);
        AgentRuntime cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        AgentVersion snapshot = versionRepository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_DISABLED));
        AgentRuntime runtime = new AgentRuntime(
                snapshot.getAgentId(),
                snapshot.getVersion(),
                snapshot.getSystemPrompt(),
                snapshot.getProviderKey(),
                snapshot.getModel(),
                snapshot.getTemperature(),
                snapshot.getMaxOutputTokens(),
                snapshot.getContextStrategy(),
                snapshot.getRequestTimeoutSeconds(),
                snapshot.getToolPolicy());
        writeCache(cacheKey, runtime);
        return runtime;
    }

    /**
     * 读取会话绑定版本的<b>实体</b>（M3 工具编排与 Skill 注入需要 {@code agent_versions.id}）。
     *
     * <p>🔴 <b>为什么不能用 {@link #runtime(long, long)} 的缓存快照代替</b>：
     * 工具清单与 Skill 绑定都以 {@code agent_versions.id} 为外键
     * （{@code agent_capability_bindings.agent_version_id}），而 {@link AgentRuntime} 只带
     * {@code (agentId, version)} 业务坐标。
     *
     * <p>🔴 <b>本方法不缓存</b>：能力绑定一期直读 MySQL（api-spec §7.1.2 末尾，
     * 保证 DBA 改绑定后立即生效 AC-CFG-004）；若在这里缓存版本实体，
     * 绑定的"改库即生效"就会被上游的一层缓存悄悄破坏。
     * 它发生在<b>异步段</b>（不占首字预算，§9.5.3）。
     *
     * @throws BusinessException 30031 该版本不存在或已归档
     */
    @Transactional(readOnly = true)
    public AgentVersion requireVersion(long agentId, long version) {
        TenantContext.requireEnabled();
        return versionRepository.findByAgentIdAndVersion(agentId, version)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_DISABLED));
    }

    /**
     * 会话绑定版本之后是否已有更新版本（用于「仅提示、不切换」的前端提示，AC-AGT-002）。
     */
    @Transactional(readOnly = true)
    public boolean hasNewerVersion(long agentId, long boundVersion) {
        return agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .map(agent -> agent.getCurrentVersion() != null && agent.getCurrentVersion() > boundVersion)
                .orElse(false);
    }

    // ===================== 治理（M1 落 Service 层，M2 暴露管理端接口） =====================

    /**
     * 创建 Agent 主体（未发布，不对终端用户可见）。
     */
    @Transactional
    public Agent create(String agentKey, String name, String description, String avatarUrl,
                        Integer sortOrder, long operatorUid) {
        TenantContext.requireEnabled();
        requireValid(validator.validateProfile(agentKey, name, description, sortOrder));
        if (agentRepository.findByAgentKey(agentKey).isPresent()) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "Agent key 在当前站点已存在",
                    Map.of("violations", List.of(new ViolationDTO("agentKey", "duplicated", "当前站点已存在同名 key"))));
        }
        Agent agent = new Agent();
        agent.setAgentKey(agentKey);
        agent.setName(name);
        agent.setDescription(description == null ? "" : description);
        agent.setAvatarUrl(avatarUrl == null ? "" : avatarUrl);
        agent.setSortOrder(sortOrder == null ? 0 : sortOrder);
        agent.setStatus(Agent.STATUS_DISABLED);
        agent.setIsDefault(0);
        agent.setCurrentVersion(0L);
        agent.setCreatedBy(operatorUid);
        agent.setUpdatedBy(operatorUid);
        try {
            return agentRepository.saveAndFlush(agent);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "Agent key 在当前站点已存在",
                    Map.of("violations", List.of(new ViolationDTO("agentKey", "duplicated", "当前站点已存在同名 key"))));
        }
    }

    /**
     * 校验发布内容（不落库），供管理端「发布前二次确认」使用。
     */
    public List<ViolationDTO> validate(AgentVersionDraft draft) {
        return validator.validateVersion(draft);
    }

    /**
     * 发布新版本：生成不可变快照并切换 {@code currentVersion} 指针。
     *
     * @param expectedVersion {@code agents.version} 乐观锁令牌
     * @return 新版本号
     * @throws BusinessException 30021 校验失败（线上版本不变）；30020 并发发布冲突
     */
    @Transactional
    public long publish(long agentId, AgentVersionDraft draft, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(BusinessException::notFound);
        requireExpectedVersion(agent, expectedVersion);

        List<ViolationDTO> violations = validator.validateVersion(draft);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "Agent 校验未通过",
                    Map.of("violations", violations));
        }

        long nextVersion = nextVersion(agentId);
        AgentVersion snapshot = new AgentVersion();
        snapshot.setAgentId(agentId);
        snapshot.setVersion(nextVersion);
        snapshot.setSystemPrompt(draft.systemPrompt());
        snapshot.setProviderKey(draft.providerKey());
        snapshot.setModel(draft.model());
        snapshot.setTemperature(draft.temperature());
        snapshot.setMaxOutputTokens(draft.maxOutputTokens());
        snapshot.setContextStrategy(draft.contextStrategy());
        snapshot.setRequestTimeoutSeconds(draft.requestTimeoutSeconds());
        snapshot.setToolPolicy(draft.toolPolicy());
        snapshot.setStatus(AgentVersion.STATUS_PUBLISHED);
        snapshot.setPublishedBy(operatorUid);
        versionRepository.saveAndFlush(snapshot);

        agent.setCurrentVersion(nextVersion);
        agent.setUpdatedBy(operatorUid);
        agentRepository.saveAndFlush(agent);
        log.info("Agent 已发布新版本：agentId={} version={} by={}", agentId, nextVersion, operatorUid);
        return nextVersion;
    }

    /**
     * 启用 / 停用 Agent。
     *
     * <p>停用不删除数据：历史会话仍可查看（只读），新建与继续发送返回 {@code 30031}。
     */
    @Transactional
    public void changeStatus(long agentId, boolean enabled, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(BusinessException::notFound);
        requireExpectedVersion(agent, expectedVersion);
        if (enabled && (agent.getCurrentVersion() == null || agent.getCurrentVersion() <= 0)) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "未发布版本的 Agent 不可启用",
                    Map.of("violations", List.of(
                            new ViolationDTO("currentVersion", "required", "请先发布一个版本"))));
        }
        agent.setStatus(enabled ? Agent.STATUS_ENABLED : Agent.STATUS_DISABLED);
        agent.setUpdatedBy(operatorUid);
        agentRepository.saveAndFlush(agent);
        log.info("Agent 状态变更：agentId={} status={}", agentId, agent.getStatus());
    }

    /**
     * 设为默认 Agent（每租户最多一个，AC-AGT-002）。
     */
    @Transactional
    public void setDefault(long agentId, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        Agent target = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(BusinessException::notFound);
        requireExpectedVersion(target, expectedVersion);
        if (!target.runnable()) {
            throw new BusinessException(ErrorCode.AGENT_DISABLED);
        }
        agentRepository.findDefaults().stream()
                .filter(existing -> !existing.getId().equals(agentId))
                .forEach(existing -> {
                    existing.setIsDefault(0);
                    agentRepository.save(existing);
                });
        target.setIsDefault(1);
        target.setUpdatedBy(operatorUid);
        agentRepository.saveAndFlush(target);
        log.info("默认 Agent 已切换：agentId={}", agentId);
    }

    /**
     * 更新排序值。
     */
    @Transactional
    public void updateSortOrder(long agentId, int sortOrder, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        Agent agent = agentRepository.findByIdAndDeletedAtIsNull(agentId)
                .orElseThrow(BusinessException::notFound);
        requireExpectedVersion(agent, expectedVersion);
        requireValid(validator.validateProfile(agent.getAgentKey(), agent.getName(),
                agent.getDescription(), sortOrder));
        agent.setSortOrder(sortOrder);
        agent.setUpdatedBy(operatorUid);
        agentRepository.saveAndFlush(agent);
    }

    /**
     * 复制 Agent：生成新 key 的<b>未发布</b>副本。
     *
     * <p>🔴 不复制默认标记、不复制发布版本、不复制审计记录（PRD §8.4）。
     */
    @Transactional
    public Agent copy(long sourceAgentId, String newAgentKey, String newName, long operatorUid) {
        TenantContext.requireEnabled();
        Agent source = agentRepository.findByIdAndDeletedAtIsNull(sourceAgentId)
                .orElseThrow(BusinessException::notFound);
        Agent copy = create(newAgentKey, newName, source.getDescription(), source.getAvatarUrl(),
                source.getSortOrder(), operatorUid);
        log.info("Agent 已复制：source={} target={}", sourceAgentId, copy.getId());
        return copy;
    }

    // ===================== 内部实现 =====================

    private void requireExpectedVersion(Agent agent, int expectedVersion) {
        if (agent.getVersion() == null || agent.getVersion() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                    "该 Agent 已被其他操作更新，请刷新后重试");
        }
    }

    private void requireValid(List<ViolationDTO> violations) {
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "Agent 校验未通过",
                    Map.of("violations", violations));
        }
    }

    private long nextVersion(long agentId) {
        Long max = versionRepository.findMaxVersion(agentId);
        return max == null ? 1L : max + 1L;
    }

    private AgentDTO toPublicDTO(Agent agent) {
        return new AgentDTO(
                Ids.toStr(agent.getId()),
                agent.getAgentKey(),
                agent.getName(),
                agent.getDescription(),
                agent.getAvatarUrl(),
                agent.getCurrentVersion() == null ? 0L : agent.getCurrentVersion(),
                agent.defaultAgent(),
                agent.getSortOrder() == null ? 0 : agent.getSortOrder());
    }

    private AgentAdminDTO toAdminDTO(Agent agent) {
        return new AgentAdminDTO(
                Ids.toStr(agent.getId()),
                agent.getAgentKey(),
                agent.getName(),
                agent.getDescription(),
                agent.getStatus(),
                agent.defaultAgent(),
                agent.getSortOrder() == null ? 0 : agent.getSortOrder(),
                agent.getCurrentVersion() == null ? 0L : agent.getCurrentVersion(),
                agent.getVersion() == null ? 0 : agent.getVersion(),
                TimeFormat.iso(agent.getUpdatedAt()),
                Ids.toStr(agent.getUpdatedBy()));
    }

    private AgentRuntime readCache(String cacheKey) {
        try {
            String cached = redis.opsForValue().get(cacheKey);
            return cached == null ? null : objectMapper.readValue(cached, AgentRuntime.class);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("读取 Agent 版本快照缓存失败，降级直连数据库", e);
            return null;
        }
    }

    private void writeCache(String cacheKey, AgentRuntime runtime) {
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(runtime), RUNTIME_CACHE_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("写入 Agent 版本快照缓存失败（不影响本次请求）", e);
        }
    }

    /**
     * 默认发布草稿（供初始化与测试构造合法内容）。
     */
    public AgentVersionDraft defaultDraft(String systemPrompt, String providerKey, String model) {
        return new AgentVersionDraft(systemPrompt, providerKey, model, new BigDecimal("0.70"),
                4096, AgentVersion.CONTEXT_STRATEGY_SUMMARY_THEN_WINDOW, 120,
                AgentVersion.TOOL_POLICY_DISABLED);
    }

    /**
     * 供审计与排障：记录某 Agent 最近一次版本发布时间（无版本返回 empty）。
     */
    @Transactional(readOnly = true)
    public Optional<Instant> lastPublishedAt(long agentId) {
        Long max = versionRepository.findMaxVersion(agentId);
        if (max == null) {
            return Optional.empty();
        }
        return versionRepository.findByAgentIdAndVersion(agentId, max).map(AgentVersion::getCreatedAt);
    }
}
