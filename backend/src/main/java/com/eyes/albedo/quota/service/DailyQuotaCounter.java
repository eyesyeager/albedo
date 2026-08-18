package com.eyes.albedo.quota.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.LongSupplier;

import com.eyes.albedo.quota.dto.QuotaWindow;
import com.eyes.albedo.tenant.TenantCacheKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 日额度的 Redis 并发裁决层（🔴 ADR-020 ② 的核心，architecture.md §12.2 两个运行时状态键）。
 *
 * <p><b>为什么是 "Redis 原子计数（并发裁决）+ DB 账本（权威与恢复）"</b>（备选方案已裁决）：
 * <ul>
 *   <li>❌ 只用 Redis：重启 / {@code maxmemory} 驱逐 / 主从切换 = 全体用户当日额度<b>免费重置</b></li>
 *   <li>❌ 只用 DB 行锁：预占横跨整条 SSE 流（可能数分钟），行锁会把短事务拉成长事务（AR-011）</li>
 *   <li>✅ Redis 单线程 Lua 天然串行做并发裁决；{@code used} 的唯一权威仍是 DB 账本，
 *       Redis 侧计数被明确定义为<b>可从账本重建的镜像</b> —— 从结构上消灭
 *       "Redis 丢数据 = 白得额度"这个漏洞</li>
 * </ul>
 *
 * <p>🔴 <b>预占判定必须是单条 Lua，缺一步即失效</b>：
 * <pre>
 * 1) ZREMRANGEBYSCORE hold 0 now   -- 剪除泄漏的预占（🔴 进程崩溃的**自愈**机制，无需定时任务）
 * 2) settled = GET 计数镜像         -- 🔴 **必须在 Lua 内读**
 * 3) holds   = ZCARD hold
 * 4) settled + holds >= limit ?    -- 拒绝
 * 5) ZADD hold 过期时刻 reservationId ; EXPIRE 两键至 resetsAt
 * </pre>
 * 🔴 <b>为什么 settled 镜像不可省</b>（不能改成"DB 读 settled + Lua 只判 holds"）：
 * 在 Lua 之外读到的 settled 可能已被另一次结算推进（结算会把一个 hold 转成 settled），
 * 此时那个 hold 已从 ZSET 移除而我们手上的 settled 是旧值 → 少算 1 → <b>最坏超发 1 次</b>。
 *
 * <p>🔴 <b>镜像缺失（Redis 重启 / 驱逐 / TTL 到期）</b>：Lua 返回哨兵
 * {@link #RESULT_MIRROR_MISSING}，调用方<b>先从 DB 账本重建</b>（{@code SET NX}）再重试一次 ——
 * 🔴 绝不"当作 0 继续判定"（那正是"白得额度"漏洞）。
 */
@Slf4j
@Component
public class DailyQuotaCounter {

    /**
     * 预占脚本返回值第 0 位的哨兵：计数镜像缺失，调用方必须先从 DB 重建再重试。
     *
     * <p>🔴 用哨兵而不是"Lua 内直接当 0"：Lua 拿不到 DB，只有调用方能重建权威值。
     */
    static final long RESULT_MIRROR_MISSING = -1L;

    private static final long RESULT_REJECTED = 0L;

    /**
     * 🔴 预占判定脚本（单条 Lua，见类注释的 5 步）。
     *
     * <pre>
     * KEYS[1] = 已结算计数镜像（String）      KEYS[2] = 在途预占集合（ZSET）
     * ARGV[1] = now（epoch millis）           ARGV[2] = 有效日额度上限
     * ARGV[3] = 本次预占的过期时刻（millis）   ARGV[4] = reservationId
     * ARGV[5] = 两键 TTL（秒，到 resetsAt + 固定余量）
     * 返回     = {结果, settled, holds}
     * </pre>
     */
    private static final String RESERVE_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[1])
            local settled = redis.call('GET', KEYS[1])
            if not settled then
              return {-1, 0, 0}
            end
            settled = tonumber(settled)
            local holds = redis.call('ZCARD', KEYS[2])
            if settled + holds >= tonumber(ARGV[2]) then
              return {0, settled, holds}
            end
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return {1, settled, holds + 1}
            """;

    /**
     * 🔴 结算脚本：{@code ZREM 预占 + INCR 计数镜像 + 刷新两键 TTL}（一次往返、原子）。
     *
     * <pre>
     * 返回 = {ZREM 结果（1=本次移除了预占 / 0=预占已过期或已被移除）, 镜像新值}
     * </pre>
     * 🔴 镜像缺失时<b>不创建</b>（返回 {@code -1} 作为镜像值）：缺失意味着下次读取会从 DB 账本
     * 重建，而 DB 此刻已是权威的最新值 —— 在这里凭 {@code INCR} 从 0 起算反而会造出一个错的镜像。
     */
    private static final String SETTLE_LUA = """
            local removed = redis.call('ZREM', KEYS[2], ARGV[1])
            local mirror = -1
            if redis.call('EXISTS', KEYS[1]) == 1 then
              mirror = redis.call('INCR', KEYS[1])
              redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if redis.call('EXISTS', KEYS[2]) == 1 then
              redis.call('EXPIRE', KEYS[2], ARGV[2])
            end
            return {removed, mirror}
            """;

    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final RedisScript<List> reserveScript;
    private final RedisScript<List> settleScript;

    public DailyQuotaCounter(StringRedisTemplate redis, TenantCacheKeys cacheKeys) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.reserveScript = script(RESERVE_LUA);
        this.settleScript = script(SETTLE_LUA);
    }

    private RedisScript<List> script(String text) {
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(text);
        redisScript.setResultType(List.class);
        return redisScript;
    }

    /**
     * 读取已结算计数镜像；🔴 <b>缺失即从 DB 账本重建</b>（{@code SET NX}，带 TTL）。
     *
     * @param dbSettled 账本读取器（🔴 只在镜像缺失时调用，正常路径零 DB 往返）
     */
    long readSettled(String tenantId, long uid, QuotaWindow window, long ttlSeconds,
                     LongSupplier dbSettled) {
        String key = cacheKeys.dailyQuotaCount(tenantId, uid, window.dateKey());
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return parse(cached);
            }
            long authoritative = dbSettled.getAsLong();
            // 🔴 SET NX：并发重建时只有一个写入生效，其余读回已写入的值（绝不覆盖别人的推进）
            Boolean written = redis.opsForValue().setIfAbsent(key,
                    String.valueOf(authoritative), Duration.ofSeconds(ttlSeconds));
            log.info("[QUOTA] 日额度计数镜像缺失，已从 DB 账本重建：tenantId={} uid={} window={} "
                            + "settled={} written={}",
                    tenantId, uid, window.dateKey(), authoritative, written);
            if (Boolean.TRUE.equals(written)) {
                return authoritative;
            }
            String reread = redis.opsForValue().get(key);
            return reread == null ? authoritative : parse(reread);
        } catch (RuntimeException e) {
            throw unavailable("读取日额度计数镜像失败", e);
        }
    }

    /**
     * 在途预占数（🔴 <b>只读</b>：用 {@code ZCOUNT} 统计 score &gt; now 的成员，不做任何写入）。
     *
     * <p>🔴 只读是准入第 2 步（日额度预检）的硬要求：预检<b>绝不</b>写任何计数器，
     * 否则"日额度已用尽仍留下副作用"就无法反向断言（AC-QUOTA-012）。
     */
    long inFlightHolds(String tenantId, long uid, QuotaWindow window, Instant now) {
        String key = cacheKeys.dailyQuotaHold(tenantId, uid, window.dateKey());
        try {
            Long count = redis.opsForZSet().count(key, now.toEpochMilli() + 1d, Double.MAX_VALUE);
            return count == null ? 0L : count;
        } catch (RuntimeException e) {
            throw unavailable("读取日额度在途预占失败", e);
        }
    }

    /**
     * 原子预占（🔴 准入第 4 步）。
     *
     * @return {@code true} = 取得生成资格；{@code false} = 额度已被占满（并发抢走最后一个额度）
     */
    boolean reserve(String tenantId, long uid, QuotaWindow window, int limit, String reservationId,
                    Instant now, Instant holdExpiresAt, long ttlSeconds, LongSupplier dbSettled) {
        Long outcome = runReserve(tenantId, uid, window, limit, reservationId, now, holdExpiresAt,
                ttlSeconds);
        if (outcome == RESULT_MIRROR_MISSING) {
            // 🔴 镜像缺失：先从 DB 账本重建（SET NX）再**重试一次**，绝不当作 0 放行
            readSettled(tenantId, uid, window, ttlSeconds, dbSettled);
            outcome = runReserve(tenantId, uid, window, limit, reservationId, now, holdExpiresAt,
                    ttlSeconds);
            if (outcome == RESULT_MIRROR_MISSING) {
                // 极端竞态（重建后立刻又被删）：fail-closed，宁可拒绝也不放行
                log.warn("[QUOTA] 🔴 计数镜像重建后仍缺失，本次预占按拒绝处理（fail-closed）："
                        + "tenantId={} uid={} window={}", tenantId, uid, window.dateKey());
                return false;
            }
        }
        return outcome != RESULT_REJECTED;
    }

    private Long runReserve(String tenantId, long uid, QuotaWindow window, int limit,
                            String reservationId, Instant now, Instant holdExpiresAt,
                            long ttlSeconds) {
        List<String> keys = List.of(
                cacheKeys.dailyQuotaCount(tenantId, uid, window.dateKey()),
                cacheKeys.dailyQuotaHold(tenantId, uid, window.dateKey()));
        try {
            List<?> result = redis.execute(reserveScript, keys,
                    String.valueOf(now.toEpochMilli()),
                    String.valueOf(limit),
                    String.valueOf(holdExpiresAt.toEpochMilli()),
                    reservationId,
                    String.valueOf(ttlSeconds));
            if (result == null || result.isEmpty()) {
                throw unavailable("预占脚本返回空结果", null);
            }
            return toLong(result.get(0));
        } catch (QuotaRedisUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("日额度预占失败", e);
        }
    }

    /**
     * 结算的 Redis 侧动作：{@code ZREM 预占 + INCR 镜像 + 刷新 TTL}。
     *
     * @return {@code true} = 本次调用移除了预占（{@code ZREM} 返回 1）；
     *         {@code false} = 预占已过期或已被移除（🔴 调用方只记 WARN，<b>不重复计数</b>）
     */
    boolean settleInRedis(String tenantId, long uid, QuotaWindow window, String reservationId,
                          long ttlSeconds) {
        List<String> keys = List.of(
                cacheKeys.dailyQuotaCount(tenantId, uid, window.dateKey()),
                cacheKeys.dailyQuotaHold(tenantId, uid, window.dateKey()));
        try {
            List<?> result = redis.execute(settleScript, keys, reservationId,
                    String.valueOf(ttlSeconds));
            if (result == null || result.isEmpty()) {
                throw unavailable("结算脚本返回空结果", null);
            }
            return toLong(result.get(0)) > 0L;
        } catch (QuotaRedisUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("日额度结算的 Redis 侧动作失败", e);
        }
    }

    /**
     * 释放预占（{@code ZREM}）。
     *
     * <p>🔴 对<b>已结算</b>的预占必须是 no-op：结算时已 {@code ZREM}，
     * 此处再删返回 0，不产生任何副作用（{@code run()} 的 finally 会无条件调用它）。
     */
    boolean releaseHold(String tenantId, long uid, QuotaWindow window, String reservationId) {
        String key = cacheKeys.dailyQuotaHold(tenantId, uid, window.dateKey());
        try {
            Long removed = redis.opsForZSet().remove(key, reservationId);
            return removed != null && removed > 0L;
        } catch (RuntimeException e) {
            // 🔴 释放失败不得影响调用方（预占会由自身过期分数被下次预占剪除，自愈）
            log.warn("[QUOTA] 释放日额度预占失败，将由下次预占的过期剪除自愈：tenantId={} uid={} "
                    + "window={}", tenantId, uid, window.dateKey());
            return false;
        }
    }

    /**
     * 🔴 主动删除计数镜像（结算的 Redis 侧失败时的<b>自愈动作</b>）。
     *
     * <p>DB 已落账（权威），此时镜像可能落后一个计数 —— 删掉它，
     * 下次读取自然从 DB 账本重建，从而<b>自愈</b>而不需要任何补偿任务。
     */
    void deleteMirror(String tenantId, long uid, QuotaWindow window) {
        try {
            redis.delete(cacheKeys.dailyQuotaCount(tenantId, uid, window.dateKey()));
        } catch (RuntimeException e) {
            log.warn("[QUOTA] 删除日额度计数镜像失败（下次镜像 TTL 到期后仍会自愈）：tenantId={} "
                    + "uid={} window={}", tenantId, uid, window.dateKey());
        }
    }

    private long parse(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            // 🔴 镜像被写坏：按"缺失"处理会更危险（=从 0 起算），因此直接当作不可用 → 降级 DB 直判
            throw unavailable("日额度计数镜像取值不是数字", e);
        }
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private QuotaRedisUnavailableException unavailable(String message, Throwable cause) {
        return new QuotaRedisUnavailableException(message, cause);
    }
}
