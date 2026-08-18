package com.eyes.albedo.quota.service;

import java.time.Instant;
import java.util.UUID;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.platform.service.TenantService;
import com.eyes.albedo.quota.dto.EffectiveQuotaPolicy;
import com.eyes.albedo.quota.dto.QuotaContext;
import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.dto.QuotaSnapshotDTO;
import com.eyes.albedo.quota.dto.QuotaWindow;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 额度域对外的<b>唯一出口</b>（architecture.md §5.1.1 / §9.6 / api-spec §7.15）。
 *
 * <p>🔴 {@code chat} 只依赖本类（{@code chat → quota} 是新增的两条依赖边之一）；
 * 🔴 {@code quota} 绝不反向依赖 {@code chat} / {@code tool} / {@code conversation} / {@code agent}
 * —— 额度域只回答"能不能发、发了算几次"，不感知会话 / 消息 / 工具编排。
 *
 * <p><b>五步准入中的职责</b>（顺序由 {@code chat/service/GenerationAdmission} 编排，🔴 不可调整）：
 * <pre>
 * ① {@link #resolve}       解析有效策略 + 额度窗口（1 次策略点查 + 租户主数据）
 * ② {@link #precheckDaily} 日额度**只读**预检（🔴 绝不写任何计数器）→ 用尽即 30070
 * ③ （QPM 由 chat/MessageRateLimiter 消费，本类不参与）
 * ④ {@link #reserve}       日额度**原子预占** → 抢不到即 30070
 * ⑤ 建消息 / 建流失败 → 调用方必须在 catch/finally 中 {@link #release}
 * </pre>
 * 结算见 {@link #settle}（异步段，exactly-once，🔴 先 flush SSE 帧再落账）。
 */
@Slf4j
@Service
public class QuotaService {

    /**
     * 预占过期时刻的<b>固定收尾余量</b>（秒）。
     *
     * <p>预占 TTL = {@code chat.generation_deadline_seconds + chat.deadline_grace_seconds + 本余量}。
     * 🔴 它是<b>基础设施常量</b>而非业务参数（与 {@code StartupChecker.MIN_DEADLINE_GRACE_SECONDS}
     * 同类）：只影响"泄漏的预占多久后被剪除"，🔴 不参与任何业务判定，故不入 {@code sys_config}。
     * 取值必须 &gt;0：否则在"生成刚好用满预算"的边界上，预占会在结算之前被自己判为过期。
     */
    private static final long RESERVATION_MARGIN_SECONDS = 60L;

    /**
     * 两个 Redis 运行时状态键的 TTL 余量（秒）：{@code TTL = (resetsAt − now) + 本余量}。
     *
     * <p>🔴 <b>禁止写死 86400</b>（DST 日为 23 / 25 小时）；余量只影响键的自然回收，
     * 业务判定用的是<b>键名里的当地日期</b>，因此它同样是基础设施常量。
     */
    private static final long KEY_TTL_MARGIN_SECONDS = 120L;

    private final QuotaPolicyResolver policyResolver;
    private final QuotaWindowResolver windowResolver;
    private final TenantService tenantService;
    private final DailyQuotaCounter counter;
    private final QuotaLedger ledger;
    private final BusinessConfig businessConfig;

    public QuotaService(QuotaPolicyResolver policyResolver,
                        QuotaWindowResolver windowResolver,
                        TenantService tenantService,
                        DailyQuotaCounter counter,
                        QuotaLedger ledger,
                        BusinessConfig businessConfig) {
        this.policyResolver = policyResolver;
        this.windowResolver = windowResolver;
        this.tenantService = tenantService;
        this.counter = counter;
        this.ledger = ledger;
        this.businessConfig = businessConfig;
    }

    // ===================== ① 解析 =====================

    /**
     * 解析有效策略与额度窗口（🔴 一次准入<b>只调用一次</b>）。
     *
     * <p>🔴 租户时区必须经 {@link TenantService}（{@code TenantProfile.timezone()}），
     * 禁止直连 {@code TenantRepository}（§5.1.2 的 Service→Service 纪律）。
     *
     * @throws BusinessException 50003 平台默认缺失/非法、租户覆盖非法、或租户时区非法（🔴 不回落 UTC）
     */
    public QuotaContext resolve(String tenantId, long uid) {
        Instant now = windowResolver.now();
        EffectiveQuotaPolicy policy = policyResolver.resolve(tenantId, now);
        String timezone = tenantService.profileOf(tenantId).timezone();
        QuotaWindow window = windowResolver.resolveAt(tenantId, timezone, now);
        return new QuotaContext(tenantId, uid, policy, window, now);
    }

    // ===================== ② 只读预检 =====================

    /**
     * 日额度<b>只读</b>预检（🔴 准入第 2 步，<b>必须早于 QPM 计数</b>）。
     *
     * <p>🔴 <b>绝不写任何计数器</b>：既不 {@code INCR} 镜像也不 {@code ZADD} 预占
     * （唯一的例外是"镜像缺失时从 DB 重建"这一<b>恢复</b>动作，它不改变任何计数语义）。
     * 若与 QPM 交换顺序，"日额度已用尽仍增加 QPM 计数"必然发生（违反 AC-QUOTA-012 前半句）。
     *
     * @throws BusinessException 30070 今日额度已用尽（{@code data} = 额度快照，恰 9 键）
     */
    public void precheckDaily(QuotaContext ctx) {
        if (!ctx.policy().dailyQuotaEnabled()) {
            return;
        }
        int limit = ctx.policy().dailyQuotaLimit();
        try {
            long ttl = ttlSeconds(ctx);
            long settled = counter.readSettled(ctx.tenantId(), ctx.uid(), ctx.window(), ttl,
                    () -> ledger.settledCount(ctx.uid(), ctx.window().localDate()));
            long holds = counter.inFlightHolds(ctx.tenantId(), ctx.uid(), ctx.window(), ctx.asOf());
            if (settled + holds >= limit) {
                throw exhausted(ctx, settled, holds);
            }
        } catch (QuotaRedisUnavailableException e) {
            degradedPrecheck(ctx, limit, e);
        }
    }

    /**
     * 🔴 Redis 不可用时的<b>降级预检</b>：DB 直判，<b>不放行</b>（AR-024）。
     *
     * <p>🔴 与 QPM 的"Redis 故障放行"<b>有意不同</b>：QPM 放行只是抗突发能力下降，
     * 日额度放行等于<b>当天无限量</b>，直接对应模型调用成本。
     * 代价 = 失去预占防超发（超发上界 = 该用户当时的并发数，🔴 不是无限），已登记为 AR-024。
     */
    private void degradedPrecheck(QuotaContext ctx, int limit, RuntimeException cause) {
        long settled = ledger.settledCount(ctx.uid(), ctx.window().localDate());
        log.warn("[QUOTA] 🔴 Redis 不可用，日额度降级为 DB 直判（不放行，失去预占防超发，AR-024）："
                        + "tenantId={} uid={} window={} settled={} limit={} reason={}",
                ctx.tenantId(), ctx.uid(), ctx.window().dateKey(), settled, limit,
                cause.getMessage());
        if (settled >= limit) {
            throw exhausted(ctx, settled, 0L);
        }
    }

    // ===================== ④ 预占 =====================

    /**
     * 日额度<b>原子预占</b>（🔴 准入第 4 步，<b>必须在创建消息之前</b>）。
     *
     * <p>🔴 在创建消息之前是硬要求：否则被拒时会留下<b>孤儿</b> user/assistant 消息
     * （PRD 明文"额度用尽不新建生成尝试"）。
     *
     * <p>🔴 预占<b>不计</b> {@code used}，只占 {@code remaining}（api-spec §7.15.4）。
     *
     * @return 预占凭据；每日额度未启用时为 {@link QuotaReservation#none()}（no-op 哨兵，🔴 不是 null）
     * @throws BusinessException 30070 并发抢走最后一个额度
     */
    public QuotaReservation reserve(QuotaContext ctx) {
        if (!ctx.policy().dailyQuotaEnabled()) {
            return QuotaReservation.none();
        }
        int limit = ctx.policy().dailyQuotaLimit();
        String reservationId = UUID.randomUUID().toString();
        try {
            long ttl = ttlSeconds(ctx);
            boolean granted = counter.reserve(ctx.tenantId(), ctx.uid(), ctx.window(), limit,
                    reservationId, ctx.asOf(), holdExpiresAt(ctx), ttl,
                    () -> ledger.settledCount(ctx.uid(), ctx.window().localDate()));
            if (!granted) {
                log.info("[QUOTA] 日额度预占失败（并发抢走最后一个额度）：tenantId={} uid={} window={} "
                        + "limit={}", ctx.tenantId(), ctx.uid(), ctx.window().dateKey(), limit);
                throw exhausted(ctx, ledger.settledCount(ctx.uid(), ctx.window().localDate()),
                        safeHolds(ctx));
            }
            return new QuotaReservation(true, true, ctx.tenantId(), ctx.uid(), reservationId,
                    ctx.window());
        } catch (QuotaRedisUnavailableException e) {
            degradedPrecheck(ctx, limit, e);
            // 🔴 降级态仍返回 tracked 凭据（结算必须照常落账），但标记 redisBacked=false
            return new QuotaReservation(true, false, ctx.tenantId(), ctx.uid(), reservationId,
                    ctx.window());
        }
    }

    // ===================== 结算与释放（异步段） =====================

    /**
     * 结算 +1（🔴 exactly-once 的<b>跨进程</b>侧；进程内一次性由调用方的一次性标记保证）。
     *
     * <p>🔴 <b>动作顺序固定（DB 先写）</b>（§9.6.2）：
     * <pre>
     * 1. DB：INSERT … ON DUPLICATE KEY UPDATE settled_count = settled_count + 1（🔴 权威）
     * 2. Redis：ZREM 预占 + INCR 计数镜像 + 刷新两键 TTL 至 resetsAt
     * 🔴 若 2 失败 → WARN + **主动 DEL 计数镜像键**（下次读取自然从 DB 账本重建，自愈）
     * 🔴 若 1 失败 → ERROR + **保留预占不释放**（fail-closed：宁可继续占位也不放行），
     *    🔴 但**绝不影响本次生成**（不中断流、不改 done、不改 finishReason）
     * </pre>
     *
     * <p>🔴 {@code ZREM} 返回 0（预占已过期或已被移除）→ 只记 <b>WARN</b>，
     * 🔴 <b>不重复计数、也不回退已落账的 DB</b>：正常路径不可能发生
     * （预占 TTL = 生成预算 + 宽限 + 固定余量，由 ADR-017 保证生成必在其内收敛）。
     *
     * <p>🔴 本方法<b>永不抛异常</b>：结算是账务动作，绝不允许它破坏用户已经拿到的回答。
     */
    public void settle(QuotaReservation reservation) {
        if (!reservation.tracked()) {
            return;
        }
        Instant now = windowResolver.now();
        try {
            ledger.settle(reservation, now);
        } catch (RuntimeException e) {
            // 🔴 保留预占不释放（fail-closed 方向），🔴 但不影响本次生成
            log.error("[QUOTA] 🔴 日额度结算落账失败，已保留预占不释放（fail-closed）；"
                            + "本次生成不受影响：tenantId={} uid={} window={} reservationId={}",
                    reservation.tenantId(), reservation.uid(), reservation.window().dateKey(),
                    reservation.reservationId(), e);
            return;
        }
        if (!reservation.redisBacked()) {
            // 降级态：预占本就不在 Redis 里，DB 账本已是权威，无需（也无法）做 Redis 侧动作
            log.info("[QUOTA] 降级态结算完成（DB 账本已落账，无 Redis 预占）：tenantId={} uid={} "
                    + "window={}", reservation.tenantId(), reservation.uid(),
                    reservation.window().dateKey());
            return;
        }
        try {
            boolean removed = counter.settleInRedis(reservation.tenantId(), reservation.uid(),
                    reservation.window(), reservation.reservationId(),
                    ttlSeconds(reservation.window(), now));
            if (!removed) {
                log.warn("[QUOTA] 结算时预占已不存在（过期或已被移除），🔴 不重复计数："
                                + "tenantId={} uid={} window={} reservationId={}",
                        reservation.tenantId(), reservation.uid(),
                        reservation.window().dateKey(), reservation.reservationId());
            }
        } catch (RuntimeException e) {
            log.warn("[QUOTA] 结算的 Redis 侧动作失败，已删除计数镜像以便下次从 DB 账本重建（自愈）："
                            + "tenantId={} uid={} window={}",
                    reservation.tenantId(), reservation.uid(), reservation.window().dateKey());
            counter.deleteMirror(reservation.tenantId(), reservation.uid(), reservation.window());
        }
    }

    /**
     * 释放预占（🔴 生成前失败、建消息/建流失败、未达证据边界即结束时调用）。
     *
     * <p>🔴 必须对"已结算的预占"是 <b>no-op</b>：结算时已 {@code ZREM}，此处再删返回 0；
     * 因此调用方可以在 {@code finally} 里无条件调用它（AC-QUOTA-004 的实现基础）。
     *
     * <p>🔴 本方法<b>永不抛异常</b>。
     */
    public void release(QuotaReservation reservation) {
        if (!reservation.tracked() || !reservation.redisBacked()) {
            return;
        }
        boolean removed = counter.releaseHold(reservation.tenantId(), reservation.uid(),
                reservation.window(), reservation.reservationId());
        if (removed) {
            log.info("[QUOTA] 日额度预占已释放（本次尝试未达资源消耗边界，used 不增加）："
                            + "tenantId={} uid={} window={} reservationId={}",
                    reservation.tenantId(), reservation.uid(), reservation.window().dateKey(),
                    reservation.reservationId());
        }
    }

    // ===================== 快照（GET /api/v1/me/quota 与 30070 载荷） =====================

    /**
     * 当前用户的额度快照（🔴 恰 9 键，api-spec §7.15.2）。
     *
     * @throws BusinessException 50003 配置或租户时区异常（🔴 不回落任何默认值）
     */
    public QuotaSnapshotDTO snapshot(String tenantId, long uid) {
        QuotaContext ctx = resolve(tenantId, uid);
        long used = ledger.settledCount(uid, ctx.window().localDate());
        return snapshot(ctx, used, ctx.policy().dailyQuotaEnabled() ? safeHolds(ctx) : 0L);
    }

    /**
     * 构造快照（🔴 {@code used} 恒为<b>已结算</b>数；{@code remaining} 采含在途预占的保守口径）。
     */
    private QuotaSnapshotDTO snapshot(QuotaContext ctx, long used, long holds) {
        String periodStart = TimeFormat.iso(ctx.window().periodStart());
        String resetsAt = TimeFormat.iso(ctx.window().resetsAt());
        String asOf = TimeFormat.iso(ctx.asOf());
        if (!ctx.policy().dailyQuotaEnabled()) {
            // 🔴 limit / remaining 必须为 null（禁止伪造数值上限）；used 仍是**真实**已结算数（禁止伪造 0）
            return new QuotaSnapshotDTO(false, null, used, null,
                    QuotaSnapshotDTO.STATUS_UNLIMITED, periodStart, resetsAt,
                    ctx.window().timezone(), asOf);
        }
        int limit = ctx.policy().dailyQuotaLimit();
        long remaining = Math.max(limit - used - holds, 0L);
        String status = remaining == 0L
                ? QuotaSnapshotDTO.STATUS_EXHAUSTED : QuotaSnapshotDTO.STATUS_AVAILABLE;
        return new QuotaSnapshotDTO(true, limit, used, (int) remaining, status,
                periodStart, resetsAt, ctx.window().timezone(), asOf);
    }

    /**
     * {@code 30070} 异常（🔴 {@code data} = 同形额度快照，🔴 <b>禁止</b>携带
     * {@code retryAfterSeconds}）。
     *
     * <p>🔴 message 只给通用语义：不含表名、键名、内部取值或其他租户信息（AC-QUOTA-014 / 016）。
     */
    private BusinessException exhausted(QuotaContext ctx, long used, long holds) {
        QuotaSnapshotDTO payload = snapshot(ctx, used, holds);
        return new BusinessException(ErrorCode.DAILY_QUOTA_EXHAUSTED,
                ErrorCode.defaultMessage(ErrorCode.DAILY_QUOTA_EXHAUSTED), payload);
    }

    /**
     * 在途预占数（🔴 Redis 不可用时按 0 计并记 WARN —— 快照是<b>展示</b>用途，
     * 不能因为缓存故障就让整个额度接口不可用；判定路径的降级见 {@link #degradedPrecheck}）。
     */
    private long safeHolds(QuotaContext ctx) {
        try {
            return counter.inFlightHolds(ctx.tenantId(), ctx.uid(), ctx.window(), ctx.asOf());
        } catch (QuotaRedisUnavailableException e) {
            log.warn("[QUOTA] 读取在途预占失败，快照按 0 在途计（AR-024 降级态）：tenantId={} uid={}",
                    ctx.tenantId(), ctx.uid());
            return 0L;
        }
    }

    /**
     * 预占过期时刻 = {@code now + 生成预算 + 收尾宽限 + 固定余量}。
     *
     * <p>🔴 预算值全部来自 {@code sys_config}（反硬编码红线）：由 ADR-017 保证生成必在
     * "预算 + 宽限"内收敛，因此正常路径下预占绝不会先于结算过期。
     */
    private Instant holdExpiresAt(QuotaContext ctx) {
        long deadline = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_GENERATION_DEADLINE_SECONDS);
        long grace = businessConfig.requireLong(ConfigKeys.GROUP_CHAT,
                ConfigKeys.CHAT_DEADLINE_GRACE_SECONDS);
        return ctx.asOf().plusSeconds(deadline + grace + RESERVATION_MARGIN_SECONDS);
    }

    private long ttlSeconds(QuotaContext ctx) {
        return ttlSeconds(ctx.window(), ctx.asOf());
    }

    /**
     * 两个运行时状态键的 TTL = {@code (resetsAt − now) + 固定余量}（🔴 <b>禁止写死 86400</b>）。
     */
    private long ttlSeconds(QuotaWindow window, Instant now) {
        long remaining = window.resetsAt().getEpochSecond() - now.getEpochSecond();
        return Math.max(remaining, 1L) + KEY_TTL_MARGIN_SECONDS;
    }
}
