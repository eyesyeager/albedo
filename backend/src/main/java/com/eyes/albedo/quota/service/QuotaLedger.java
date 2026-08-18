package com.eyes.albedo.quota.service;

import java.time.Instant;
import java.time.LocalDate;

import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.entity.UserDailyQuotaUsage;
import com.eyes.albedo.quota.repository.UserDailyQuotaUsageRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日额度<b>账本</b>访问层（🔴 {@code used} 的唯一权威，architecture.md §13.5.12）。
 *
 * <p>🔴 <b>为什么单独一个 Bean 而不是写在 {@code QuotaService} 里</b>：结算是
 * {@code @Modifying} 的原生 UPSERT，必须跑在事务里；而 {@code QuotaService.settle(...)}
 * 还要做 Redis 动作 —— 若把 {@code @Transactional} 打在同一个方法上，
 * 数据库事务会横跨 Redis 往返（AR-011 要消灭的"短事务被拉长"）；
 * 而在同类内自调用又不会被 Spring 代理（事务静默失效）。
 * 因此把"事务边界"独立成本类：🔴 事务只包住那一条 SQL。
 */
@Slf4j
@Component
public class QuotaLedger {

    private final UserDailyQuotaUsageRepository repository;

    public QuotaLedger(UserDailyQuotaUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * 当日<b>已结算</b>次数（🔴 {@code used} 的权威值；无账本行 = 0）。
     */
    @Transactional(readOnly = true)
    public long settledCount(long uid, LocalDate quotaDate) {
        return repository.findByUidAndDate(uid, quotaDate)
                .map(UserDailyQuotaUsage::getSettledCount)
                .map(Integer::longValue)
                .orElse(0L);
    }

    /**
     * 🔴 结算 +1（单语句原子 UPSERT，独立短事务）。
     *
     * <p>{@code REQUIRES_NEW}：结算发生在 SSE 流内（首个"资源已消耗证据"帧 flush 之后），
     * 此时<b>不应该</b>有任何外层业务事务；若将来有调用方误在事务内调用它，
     * 独立事务能保证"账本已落账"不被外层回滚抹掉（账本是权威，🔴 永不失真）。
     *
     * @throws RuntimeException 数据库异常（🔴 调用方必须捕获：结算失败<b>绝不影响本次生成</b>）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(QuotaReservation reservation, Instant now) {
        repository.settle(reservation.tenantId(),
                reservation.uid(),
                reservation.window().localDate(),
                reservation.window().timezone(),
                reservation.window().periodStart(),
                reservation.window().resetsAt(),
                now);
    }
}
