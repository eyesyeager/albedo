package com.eyes.albedo.quota.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户额度与限流策略<b>版本流</b>（{@code scope=tenant}，architecture.md §13.5.11 / ADR-020 ①）。
 *
 * <p><b>为什么是专用表而不是通用 {@code tenant_config}</b>（ADR-020 ① 已裁决）：
 * 字段有限且语义封闭，可用<b>强类型列 + 数据库约束</b>表达，不需要再造一套类型系统 / 校验 /
 * 继承 / 缓存分层；🔴 且结构上<b>不可能</b>覆盖 {@code mcp.require_https} 之类的平台安全键。
 *
 * <p>🔴 <b>四个覆盖列全部 NULLable，且 {@code NULL} 有确定语义</b>：
 * <pre>
 * 列 IS NULL       → 未覆盖 → **继承平台默认**（sys_config: ratelimit.*）
 * 列 NOT NULL 合法 → 使用覆盖值
 * 列 NOT NULL 非法 → 🔴 50003 + ERROR 日志，**禁止静默继承平台默认**（AC-QUOTA-014）
 * </pre>
 * 🔴 <b>DDL 里严禁写 {@code DEFAULT 3} / {@code DEFAULT 50}</b>：那会成为"代码之外的
 * 第二个默认值来源"，直接违反反硬编码红线（唯一默认值来源是 {@code sys_config}）。
 *
 * <p>🔴 <b>仅追加的版本流</b>：调整策略 = <b>插入一条新行</b>；读取时按
 * {@code effective_at <= now} 取<b>最新一行</b>（{@code QuotaPolicyResolver}）。
 * 因此 🔴 <b>不需要任何定时任务 / 调度器</b>（"生效时间"由读取时过滤天然实现，幂等且自愈：
 * 服务重启、时钟回拨、DBA 补插历史行都不需要补偿动作）。
 * 🔴 <b>撤销一个覆盖的正确方式 = 插入一条该列为 {@code NULL} 的新行</b>，而不是删除历史行。
 *
 * <p>🔴 <b>本表无 {@code deleted_at} 且不补加</b>（按 §13.5.10 体例<b>显式豁免</b> §13.2 第 4 条）：
 * 历史行是"为何当时是这个额度"的唯一解释来源，从不删除 → 软删列恒 {@code NULL} = 永真条件；
 * 且引入软删会给出"撤销覆盖"的<b>第二种</b>手段（与"插一条 NULL 行"语义重叠）。
 *
 * <p>🔴 <b>不缓存</b>（§12.1.1 / ADR-020 ①）：一期直读 MySQL，"改库即生效"天然成立
 * （AC-QUOTA-015 免实现），🔴 因此本增量<b>零新增缓存键、零新增 TTL 配置键</b>。
 */
@Getter
@Setter
@Entity
@Table(name = "tenant_quota_policies")
public class TenantQuotaPolicy extends BaseTenantEntity {

    /** {@code NULL} = 未覆盖（继承平台默认）；否则覆盖 QPM 开关。 */
    @Column(name = "qpm_enabled")
    private Boolean qpmEnabled;

    /** {@code NULL} = 未覆盖；🔴 非 {@code NULL} 必须 ≥1，非法即 {@code 50003}（禁止静默回落）。 */
    @Column(name = "qpm_limit")
    private Integer qpmLimit;

    /** {@code NULL} = 未覆盖；否则覆盖每日额度开关。 */
    @Column(name = "daily_quota_enabled")
    private Boolean dailyQuotaEnabled;

    /** {@code NULL} = 未覆盖；🔴 非 {@code NULL} 必须 ≥1，非法即 {@code 50003}。 */
    @Column(name = "daily_quota_limit")
    private Integer dailyQuotaLimit;

    /**
     * 生效时刻（UTC）。
     *
     * <p>🔴 读取时只取 {@code effective_at <= now} 的最新一行 → <b>未来行对当前尝试完全无效</b>；
     * 一行 = 一个版本，四个覆盖列<b>整组原子生效</b>（这正是通用 KV 表达不了的东西）。
     */
    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    /** 运维备注；🔴 <b>禁记个人信息</b>（它会随策略行长期留存）。 */
    @Column(name = "note", nullable = false, length = 200)
    private String note = "";

    /** 乐观锁（供二期管理端；一期由 DBA 直接插行，不参与运行时判定）。 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;
}
