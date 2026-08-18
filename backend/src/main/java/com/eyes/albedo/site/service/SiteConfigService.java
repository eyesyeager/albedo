package com.eyes.albedo.site.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.platform.dto.TenantProfile;
import com.eyes.albedo.platform.service.TenantService;
import com.eyes.albedo.site.dto.SiteConfigConflictDTO;
import com.eyes.albedo.site.dto.SiteConfigContent;
import com.eyes.albedo.site.dto.SiteConfigDTO;
import com.eyes.albedo.site.dto.SiteConfigVersionDTO;
import com.eyes.albedo.site.dto.ViolationDTO;
import com.eyes.albedo.site.entity.SiteConfigVersion;
import com.eyes.albedo.site.repository.SiteConfigVersionRepository;
import com.eyes.albedo.tenant.TenantCacheKeys;
import com.eyes.albedo.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站点配置服务：读取当前已发布配置 + 草稿 / 校验 / 发布 / 回滚。
 *
 * <p>读取路径（M1 主链路，性能红线 P95 ≤20ms）：
 * <pre>
 *   tenants.config_version（随租户解析快照一并缓存）
 *     → Redis albedo:{env}:{tenantId}:site:config:{configVersion}
 *       → MySQL site_config_versions(version, status=published)
 * </pre>
 * 缓存键内含版本号 → <b>发布新版本天然失效</b>，不存在「改了不生效」的窗口。
 *
 * <p>降级（EX-006）：指针指向的版本缺失或已归档时，退回「最后一个成功发布版本」并告警；
 * 完全无可用版本才抛 {@code 30012}，绝不返回半成品配置。
 *
 * <p>写入路径（草稿 → 校验 → 发布，架构 §10）：发布在<b>单事务</b>内完成
 * 「生成不可变版本行 + 归档旧版本 + 切换指针 + 失效缓存」，任一步失败线上保持上一版本。
 */
@Slf4j
@Service
public class SiteConfigService {

    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(1800);

    private final SiteConfigVersionRepository repository;
    private final TenantService tenantService;
    private final SiteConfigValidator validator;
    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final ObjectMapper objectMapper;

    public SiteConfigService(SiteConfigVersionRepository repository,
                             TenantService tenantService,
                             SiteConfigValidator validator,
                             StringRedisTemplate redis,
                             TenantCacheKeys cacheKeys,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.tenantService = tenantService;
        this.validator = validator;
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.objectMapper = objectMapper;
    }

    // ===================== 读取（M1 公开接口） =====================

    /**
     * 当前租户的已发布站点配置。
     *
     * <p>🔴 性能纪律（P95 ≤20ms，AC-NFR-001）：缓存命中路径<b>零数据库访问</b>——
     * 版本指针直接取自租户解析快照（{@code TenantContext.Snapshot.configVersion}，
     * 已随 Host 解析结果一并缓存），因此本方法<b>不能</b>加 {@code @Transactional}：
     * 远程 MySQL 上「获取连接 + 开启/提交只读事务」本身就有上百毫秒开销，
     * 会让 Redis 缓存完全失去意义。仅缓存未命中时才触达数据库。
     *
     * <p>指针时效性：发布时 {@code TenantService.switchConfigVersion} 会失效 Host 解析缓存，
     * 下一次请求即拿到新指针，不存在「改了不生效」窗口。
     *
     * @throws BusinessException 30010 / 30011 租户不可用；30012 无可用已发布配置
     */
    public SiteConfigDTO currentConfig() {
        TenantContext.Snapshot snapshot = TenantContext.requireEnabled();

        long pointer = snapshot.configVersion();
        if (pointer <= 0) {
            throw new BusinessException(ErrorCode.TENANT_CONFIG_UNAVAILABLE);
        }

        String cacheKey = cacheKeys.siteConfig(snapshot.tenantId(), pointer);
        SiteConfigDTO cached = readCache(cacheKey);
        if (cached != null) {
            log.debug("[SiteConfig] currentConfig 命中缓存 tenantId={} pointer={}", snapshot.tenantId(), pointer);
            return cached;
        }
        log.info("[SiteConfig] currentConfig 缓存未命中，查库 tenantId={} pointer={}", snapshot.tenantId(), pointer);

        // 缓存未命中：此时才允许触达数据库（timezone / locale 亦随 DTO 一并缓存）
        TenantProfile profile = tenantService.currentProfile();
        SiteConfigVersion version = resolvePublished(pointer)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_CONFIG_UNAVAILABLE));
        SiteConfigDTO dto = SiteConfigDTO.of(profile.tenantId(), version.getVersion(),
                profile.timezone(), profile.locale(), parseContent(version));
        writeCache(cacheKey, dto);
        log.info("[SiteConfig] currentConfig 加载完成 tenantId={} pointer={} version={}",
                profile.tenantId(), pointer, version.getVersion());
        return dto;
    }

    /**
     * 站点是否具备可用的已发布配置（{@code /site/status} 的 503 判定，不返回任何租户数据）。
     */
    @Transactional(readOnly = true)
    public boolean hasUsableConfig() {
        try {
            TenantProfile profile = tenantService.currentProfile();
            return profile.configVersion() > 0 && resolvePublished(profile.configVersion()).isPresent();
        } catch (BusinessException e) {
            return false;
        }
    }

    // ===================== 草稿 / 校验 / 发布 / 回滚 =====================

    /**
     * 读取当前工作草稿；不存在时以「当前已发布内容」为基线返回（PRD 规则 9：编辑必须基于已发布版本）。
     */
    @Transactional(readOnly = true)
    public SiteConfigContent loadDraft() {
        TenantContext.requireEnabled();
        Optional<SiteConfigVersion> draft = latestDraft();
        if (draft.isPresent()) {
            return parseContent(draft.get());
        }
        TenantProfile profile = tenantService.currentProfile();
        return resolvePublished(profile.configVersion())
                .map(this::parseContent)
                .orElseGet(() -> new SiteConfigContent("", "", "", "", "", "", "", "", "", "", "", ""));
    }

    /**
     * 保存草稿（不影响线上）。
     *
     * @param content         草稿内容
     * @param expectedVersion 并发令牌（{@code tenants.version}），不一致抛 30020 + 差异摘要
     */
    @Transactional
    public SiteConfigContent saveDraft(SiteConfigContent content, int expectedVersion) {
        TenantContext.requireEnabled();
        TenantProfile profile = tenantService.currentProfile();
        requireNoConflict(profile, expectedVersion, content);

        SiteConfigContent normalized = content.normalized();
        SiteConfigVersion draft = latestDraft().orElseGet(() -> {
            SiteConfigVersion created = new SiteConfigVersion();
            created.setVersion(nextVersion());
            created.setStatus(SiteConfigVersion.STATUS_DRAFT);
            return created;
        });
        draft.setContent(writeContent(normalized));
        repository.saveAndFlush(draft);
        log.info("站点配置草稿已保存：tenantId={} draftVersion={}", profile.tenantId(), draft.getVersion());
        return normalized;
    }

    /**
     * 校验草稿内容（不落库）。
     *
     * @return 违规清单，空表示可发布
     */
    public List<ViolationDTO> validate(SiteConfigContent content) {
        TenantContext.requireEnabled();
        return validator.validate(content);
    }

    /**
     * 发布草稿：单事务内「生成不可变版本 + 归档旧版本 + 切指针 + 失效缓存」。
     *
     * @param content         待发布内容
     * @param expectedVersion 并发令牌（{@code tenants.version}）
     * @param operatorUid     发布人 uid
     * @return 新的已发布版本号
     * @throws BusinessException 30021 校验失败（线上保持上一版本）；30020 并发冲突
     */
    @Transactional
    public long publish(SiteConfigContent content, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        TenantProfile profile = tenantService.currentProfile();
        requireNoConflict(profile, expectedVersion, content);

        List<ViolationDTO> violations = validator.validate(content);
        if (!violations.isEmpty()) {
            // 🔴 校验失败不动线上版本（EX-005 / AC-CFG-002）
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "站点配置校验未通过",
                    java.util.Map.of("violations", violations));
        }
        return doPublish(profile, content.normalized(), expectedVersion, operatorUid);
    }

    /**
     * 回滚到历史版本：以历史内容<b>生成新版本</b>并切指针，🔴 不修改历史行（AC-CFG-002）。
     *
     * @param targetVersion   目标历史版本号
     * @param expectedVersion 并发令牌
     * @param operatorUid     操作人 uid
     * @return 新生成的版本号
     */
    @Transactional
    public long rollback(long targetVersion, int expectedVersion, long operatorUid) {
        TenantContext.requireEnabled();
        TenantProfile profile = tenantService.currentProfile();
        if (profile.version() != expectedVersion) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "站点配置已被其他操作更新，请刷新后重试",
                    new SiteConfigConflictDTO(expectedVersion, profile.version(), List.of()));
        }
        SiteConfigVersion target = repository.findByVersion(targetVersion)
                .orElseThrow(BusinessException::notFound);
        SiteConfigContent content = parseContent(target);

        List<ViolationDTO> violations = validator.validate(content);
        if (!violations.isEmpty()) {
            throw new BusinessException(ErrorCode.PUBLISH_VALIDATE_FAILED, "历史版本内容不再满足校验规则",
                    java.util.Map.of("violations", violations));
        }
        long newVersion = doPublish(profile, content, expectedVersion, operatorUid);
        log.info("站点配置已回滚：tenantId={} from={} newVersion={}",
                profile.tenantId(), targetVersion, newVersion);
        return newVersion;
    }

    /**
     * 版本列表（倒序），供回滚选择与审计追溯。
     */
    @Transactional(readOnly = true)
    public List<SiteConfigVersionDTO> listVersions(int page, int pageSize) {
        TenantContext.requireEnabled();
        long pointer = tenantService.currentProfile().configVersion();
        return repository.findAllByOrderByVersionDesc(PageRequest.of(page - 1, pageSize))
                .map(v -> new SiteConfigVersionDTO(
                        v.getVersion(),
                        v.getStatus(),
                        v.getVersion() == pointer,
                        Ids.toStr(v.getPublishedBy()),
                        TimeFormat.iso(v.getPublishedAt()),
                        TimeFormat.iso(v.getCreatedAt())))
                .getContent();
    }

    // ===================== 内部实现 =====================

    private long doPublish(TenantProfile profile, SiteConfigContent content,
                           int expectedVersion, long operatorUid) {
        // ① 归档旧的已发布版本（历史内容不变，仅状态流转 published → archived）
        repository.findByStatus(SiteConfigVersion.STATUS_PUBLISHED).forEach(old -> {
            old.setStatus(SiteConfigVersion.STATUS_ARCHIVED);
            repository.save(old);
        });
        // ② 复用当前草稿行（若有）或新建行，落为不可变的 published 版本
        SiteConfigVersion published = latestDraft().orElseGet(SiteConfigVersion::new);
        if (published.getVersion() == null) {
            published.setVersion(nextVersion());
        }
        published.setContent(writeContent(content));
        published.setStatus(SiteConfigVersion.STATUS_PUBLISHED);
        published.setPublishedBy(operatorUid);
        published.setPublishedAt(Instant.now());
        repository.saveAndFlush(published);

        // ③ 原子切换指针（乐观锁；失败则整个事务回滚，线上仍是旧版本）
        tenantService.switchConfigVersion(profile.tenantId(), published.getVersion(), expectedVersion);

        // ④ 失效缓存（新键含新版本号，此处清旧键避免堆积）
        evictConfigCache(profile.tenantId(), profile.configVersion());
        log.info("站点配置已发布：tenantId={} version={} by={}",
                profile.tenantId(), published.getVersion(), operatorUid);
        return published.getVersion();
    }

    /**
     * 并发检测：版本不一致时给出<b>差异字段摘要</b>，让后提交者能重新应用（PRD §8.9）。
     */
    private void requireNoConflict(TenantProfile profile, int expectedVersion, SiteConfigContent submitted) {
        if (profile.version() == expectedVersion) {
            return;
        }
        List<String> changed = diffFields(submitted);
        throw new BusinessException(ErrorCode.VERSION_CONFLICT,
                "站点配置已被其他操作更新，请对比差异后重试",
                new SiteConfigConflictDTO(expectedVersion, profile.version(), changed));
    }

    private List<String> diffFields(SiteConfigContent submitted) {
        List<String> changed = new ArrayList<>();
        if (submitted == null) {
            return changed;
        }
        SiteConfigContent current = loadDraft();
        SiteConfigContent left = submitted.normalized();
        compare(changed, "siteTitle", left.siteTitle(), current.siteTitle());
        compare(changed, "logoUrl", left.logoUrl(), current.logoUrl());
        compare(changed, "faviconUrl", left.faviconUrl(), current.faviconUrl());
        compare(changed, "welcomeText", left.welcomeText(), current.welcomeText());
        compare(changed, "inputPlaceholder", left.inputPlaceholder(), current.inputPlaceholder());
        compare(changed, "loginText", left.loginText(), current.loginText());
        compare(changed, "registerText", left.registerText(), current.registerText());
        compare(changed, "newChatText", left.newChatText(), current.newChatText());
        compare(changed, "emptySessionText", left.emptySessionText(), current.emptySessionText());
        compare(changed, "agentUnavailableText", left.agentUnavailableText(), current.agentUnavailableText());
        compare(changed, "footerDisclaimer", left.footerDisclaimer(), current.footerDisclaimer());
        compare(changed, "themePrimaryColor", left.themePrimaryColor(), current.themePrimaryColor());
        return changed;
    }

    private void compare(List<String> changed, String field, String submitted, String current) {
        if (!java.util.Objects.equals(submitted, current == null ? "" : current)) {
            changed.add(field);
        }
    }

    /**
     * 指针优先；缺失或非 published 时退回最后一个已发布版本（EX-006）。
     */
    private Optional<SiteConfigVersion> resolvePublished(long pointer) {
        if (pointer > 0) {
            Optional<SiteConfigVersion> exact =
                    repository.findByVersionAndStatus(pointer, SiteConfigVersion.STATUS_PUBLISHED);
            if (exact.isPresent()) {
                return exact;
            }
            // 指针漂移（人工改库 / 归档竞态）：降级到最后一个成功发布版本并告警
            log.warn("站点配置指针指向的版本不可用，降级到最后一个已发布版本：pointer={}", pointer);
        }
        List<SiteConfigVersion> published = repository.findByStatus(SiteConfigVersion.STATUS_PUBLISHED);
        return published.stream().max(java.util.Comparator.comparingLong(SiteConfigVersion::getVersion));
    }

    private Optional<SiteConfigVersion> latestDraft() {
        List<SiteConfigVersion> drafts = repository.findByStatusOrderByVersionDesc(
                SiteConfigVersion.STATUS_DRAFT, PageRequest.of(0, 1));
        return drafts.isEmpty() ? Optional.empty() : Optional.of(drafts.get(0));
    }

    private long nextVersion() {
        Long max = repository.findMaxVersion();
        return max == null ? 1L : max + 1L;
    }

    private SiteConfigContent parseContent(SiteConfigVersion version) {
        try {
            return objectMapper.readValue(version.getContent(), SiteConfigContent.class).normalized();
        } catch (JsonProcessingException e) {
            log.error("站点配置内容解析失败：version={}", version.getVersion(), e);
            throw new BusinessException(ErrorCode.TENANT_CONFIG_UNAVAILABLE);
        }
    }

    private String writeContent(SiteConfigContent content) {
        try {
            return objectMapper.writeValueAsString(content.normalized());
        } catch (JsonProcessingException e) {
            log.error("站点配置内容序列化失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private void evictConfigCache(String tenantId, long configVersion) {
        if (configVersion <= 0) {
            return;
        }
        try {
            redis.delete(cacheKeys.siteConfig(tenantId, configVersion));
        } catch (RuntimeException e) {
            log.warn("失效站点配置缓存失败：tenantId={} version={}", tenantId, configVersion, e);
        }
    }

    private SiteConfigDTO readCache(String cacheKey) {
        try {
            String cached = redis.opsForValue().get(cacheKey);
            return cached == null ? null : objectMapper.readValue(cached, SiteConfigDTO.class);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("读取站点配置缓存失败，降级直连数据库", e);
            return null;
        }
    }

    private void writeCache(String cacheKey, SiteConfigDTO dto) {
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(dto), CONFIG_CACHE_TTL);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("写入站点配置缓存失败（不影响本次响应）", e);
        }
    }
}
