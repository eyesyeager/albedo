package com.eyes.albedo.metrics.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.metrics.entity.AnalyticsEvent;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 埋点事件仓储（{@code scope=tenant}）。
 *
 * <p>🔴 去重口径（api-spec §1.4）：{@code uk(tenant_id, client_event_id)}；
 * 重复上报<b>静默丢弃</b>并计入 {@code data.duplicated}，绝不报错
 * （埋点失败永不影响主流程，一律 {@code code=0}）。
 *
 * <p>🔴 无界查询禁止：时间桶聚合必须带 {@code occurredAt} 区间（≤31 天，走 {@code idx_tenant_occurred}）。
 */
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {

    Optional<AnalyticsEvent> findByClientEventId(String clientEventId);

    /** 批量去重预检（一次 IN 查询代替逐条 exists，避免 N+1）。 */
    List<AnalyticsEvent> findByClientEventIdIn(List<String> clientEventIds);

    /** 时间桶内某事件名的计数（用量聚合，🔴 必须带时间区间）。 */
    long countByEventNameAndOccurredAtBetween(String eventName, Instant from, Instant to);
}
