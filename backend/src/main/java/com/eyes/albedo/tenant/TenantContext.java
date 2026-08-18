package com.eyes.albedo.tenant;

import java.util.Optional;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

/**
 * 租户上下文（ThreadLocal 持有的<b>只读快照</b>）。
 *
 * <p>唯一写入方：{@link TenantFilter}（绑定 Host 解析结果）与
 * {@link com.eyes.albedo.auth.PermissionAspect}（鉴权成功后回填 uid）。
 *
 * <p>🔴 业务代码纪律：
 * <ul>
 *   <li>禁止自行构造并 bind 上下文；禁止读取请求参数中的 tenantId（一律忽略）</li>
 *   <li>需要租户的业务入口必须先调用 {@link #requireEnabled()}，用确定错误码替代"空结果"</li>
 *   <li>异步执行必须通过 {@code AsyncConfig} 的 {@link TenantAwareTaskDecorator} 传播，
 *       禁止在异步线程中读取 Servlet 请求</li>
 * </ul>
 */
public final class TenantContext {

    /**
     * 缺失租户上下文时提供给 Hibernate 的哨兵值。
     *
     * <p>作用：让租户实体（{@link BaseTenantEntity}）在无上下文时查询结果必然为空（fail-closed），
     * 而不是意外扫全表。业务层仍必须显式抛出 30013 / 30010。
     */
    public static final String NONE_TENANT = "__none__";

    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 租户上下文快照（不可变）。
     *
     * @param tenantId      租户号（PRD 的 tenantId，如 {@code gift}），同时是 Hibernate discriminator 值
     * @param tenantPk      tenants 表自增主键（仅平台表内部使用）
     * @param host          命中的规范化 Host
     * @param status        租户状态
     * @param configVersion 当前已发布站点配置版本，0 表示未发布
     * @param uid           当前登录用户 eyesUser uid；未登录为 null
     */
    public record Snapshot(String tenantId,
                           Long tenantPk,
                           String host,
                           TenantStatus status,
                           long configVersion,
                           Long uid) {

        public Snapshot withUid(Long newUid) {
            return new Snapshot(tenantId, tenantPk, host, status, configVersion, newUid);
        }

        public Snapshot withConfigVersion(long newConfigVersion) {
            return new Snapshot(tenantId, tenantPk, host, status, newConfigVersion, uid);
        }
    }

    public static void bind(Snapshot snapshot) {
        HOLDER.set(snapshot);
    }

    public static Optional<Snapshot> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * 要求存在租户上下文。
     *
     * @throws BusinessException 30010 未能建立租户上下文（未知 / draft / archived Host）
     */
    public static Snapshot require() {
        Snapshot snapshot = HOLDER.get();
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        }
        return snapshot;
    }

    /**
     * 要求存在且已启用的租户上下文。
     *
     * @throws BusinessException 30010（不存在 / draft / archived）、30011（suspended）
     */
    public static Snapshot requireEnabled() {
        Snapshot snapshot = require();
        return switch (snapshot.status()) {
            case ENABLED -> snapshot;
            case SUSPENDED -> throw new BusinessException(ErrorCode.TENANT_SUSPENDED);
            case DRAFT, ARCHIVED -> throw new BusinessException(ErrorCode.TENANT_NOT_FOUND);
        };
    }

    /**
     * 当前租户号；无上下文时返回 {@link #NONE_TENANT} 哨兵（供 Hibernate resolver 使用）。
     */
    public static String tenantIdOrNone() {
        Snapshot snapshot = HOLDER.get();
        return snapshot == null ? NONE_TENANT : snapshot.tenantId();
    }

    /**
     * 鉴权成功后回填 uid（生成新的不可变快照）。
     */
    public static void bindUid(Long uid) {
        Snapshot snapshot = HOLDER.get();
        if (snapshot != null) {
            HOLDER.set(snapshot.withUid(uid));
        }
    }

    /**
     * 当前登录用户 uid。
     *
     * @throws BusinessException 30013 缺少租户上下文
     */
    public static Optional<Long> uid() {
        return current().map(Snapshot::uid);
    }

    /**
     * 抓取用于异步任务的上下文快照。
     */
    public static Snapshot snapshotForAsync() {
        return HOLDER.get();
    }

    /**
     * 在异步线程中恢复上下文。
     *
     * @throws BusinessException 30013 快照缺失（禁止无租户上下文的业务任务执行，RISK-001）
     */
    public static void restore(Snapshot snapshot) {
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.TENANT_CONTEXT_MISSING);
        }
        HOLDER.set(snapshot);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
