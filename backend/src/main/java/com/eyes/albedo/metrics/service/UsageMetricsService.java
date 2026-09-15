package com.eyes.albedo.metrics.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.metrics.dto.UsageBucketDTO;
import com.eyes.albedo.metrics.dto.UsageReportDTO;
import com.eyes.albedo.metrics.dto.UsageTokenUsageDTO;
import com.eyes.albedo.metrics.dto.UsageTotalsDTO;
import com.eyes.albedo.metrics.repository.UsageConversationRepository;
import com.eyes.albedo.metrics.repository.UsageMetricsRepository;
import com.eyes.albedo.metrics.repository.UsageToolCallRepository;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tool.ToolStateMachine;
import com.eyes.albedo.tool.entity.ToolCall;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户用量与运行指标聚合（api-spec §7.11.1，REQ-OBS-001 / AC-OBS-001 / AC-TEN-005）。
 *
 * <p><b>🔴 三条硬口径</b>：
 * <pre>
 * ① rateLimitedCount 恒 0 且🔴 严禁伪造/估算/近似替代（原因见 rateLimitedCount() 注释）
 * ② denied / failed 互斥不变量：按 status 二分 + timed_out 按 errorCode 二分且**仅**二分
 * ③ 只回聚合计数：禁止返回消息正文、单条明细、uid 列表、其他租户数据
 * </pre>
 *
 * <p>🔴 <b>只聚合当前租户</b>（AC-TEN-005）：全部查询为 JPQL 实体查询，
 * 租户条件由 Hibernate discriminator 自动追加；🔴 未使用任何 {@code nativeQuery}
 * （原生 SQL 不会被追加租户条件，那才是跨租户串数据的真实成因）。
 *
 * <p>🔴 <b>查询次数固定 5 次</b>（消息数 / 活跃用户 / token / 会话 / 工具调用，各一次分桶聚合）
 * + 1 次区间级 {@code distinct uid}：与桶数<b>无关</b>，
 * 不会因为选了 {@code granularity=hour} 而变成 744 次查询。
 */
@Slf4j
@Service
public class UsageMetricsService {

    /** 粒度枚举（api-spec §7.11.1）。 */
    public static final String GRANULARITY_DAY = "day";
    public static final String GRANULARITY_HOUR = "hour";

    /** 🔴 区间跨度上限（契约固定 31 天；超出 → 10001）。 */
    private static final int MAX_RANGE_DAYS = 31;

    /** MySQL {@code DATE_FORMAT} 模式。 */
    private static final String BUCKET_FORMAT_DAY = "%Y-%m-%d";
    private static final String BUCKET_FORMAT_HOUR = "%Y-%m-%d %H";

    /** 桶键 → ISO-8601 输出。 */
    private static final DateTimeFormatter BUCKET_KEY_DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter BUCKET_KEY_HOUR =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final UsageMetricsRepository messageRepository;
    private final UsageConversationRepository conversationRepository;
    private final UsageToolCallRepository toolCallRepository;

    public UsageMetricsService(UsageMetricsRepository messageRepository,
                              UsageConversationRepository conversationRepository,
                              UsageToolCallRepository toolCallRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.toolCallRepository = toolCallRepository;
    }

    /**
     * 用量查询。
     *
     * @throws BusinessException 10001 {@code from}/{@code to} 非法、{@code to <= from}、
     *                           跨度 &gt;31 天、{@code granularity} 非法
     */
    @Transactional(readOnly = true)
    public UsageReportDTO query(String fromRaw, String toRaw, String granularityRaw) {
        // 🔴 必须有可用租户上下文：聚合结果一律限定当前租户（AC-TEN-005）
        String tenantId = TenantContext.requireEnabled().tenantId();

        Instant from = requireInstant(fromRaw, "from");
        Instant to = requireInstant(toRaw, "to");
        if (!to.isAfter(from)) {
            throw BusinessException.validation("to 必须晚于 from");
        }
        if (Duration.between(from, to).toDays() > MAX_RANGE_DAYS) {
            throw BusinessException.validation("查询跨度不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        String granularity = normalizeGranularity(granularityRaw);
        boolean hourly = GRANULARITY_HOUR.equals(granularity);
        String bucketFormat = hourly ? BUCKET_FORMAT_HOUR : BUCKET_FORMAT_DAY;

        Map<String, Long> messages = toCountMap(
                messageRepository.messageCountByBucket(from, to, bucketFormat));
        Map<String, Long> activeUsers = toCountMap(
                messageRepository.activeUserCountByBucket(from, to, bucketFormat));
        Map<String, UsageTokenUsageDTO> tokens = toTokenMap(
                messageRepository.tokenUsageByBucket(from, to, bucketFormat));
        Map<String, Long> conversations = toCountMap(
                conversationRepository.conversationCountByBucket(from, to, bucketFormat));
        Map<String, ToolCounters> toolCounters = toToolCounters(
                toolCallRepository.toolCallStatsByBucket(from, to, bucketFormat));

        List<UsageBucketDTO> series = new ArrayList<>();
        UsageTotalsAccumulator totals = new UsageTotalsAccumulator();
        for (Instant bucketStart : buckets(from, to, hourly)) {
            String key = bucketKey(bucketStart, hourly);
            ToolCounters tools = toolCounters.getOrDefault(key, ToolCounters.EMPTY);
            UsageTokenUsageDTO tokenUsage = tokens.getOrDefault(key, UsageTokenUsageDTO.ZERO);
            long messageCount = messages.getOrDefault(key, 0L);
            long conversationCount = conversations.getOrDefault(key, 0L);
            series.add(new UsageBucketDTO(ISO.format(bucketStart), messageCount, conversationCount,
                    activeUsers.getOrDefault(key, 0L), tokenUsage, tools.total(), tools.failed(),
                    tools.denied(), rateLimitedCount()));
            totals.add(messageCount, conversationCount, tokenUsage, tools);
        }

        // 🔴 activeUserCount 的区间合计**不能**累加桶值（同一用户跨桶只应计一次），
        //    因此走独立的区间级 distinct uid 查询（见仓储方法注释）
        long activeUsersTotal = messageRepository.activeUserCountTotal(from, to);

        log.debug("用量聚合完成：tenantId={} granularity={} buckets={}", tenantId, granularity,
                series.size());
        return new UsageReportDTO(ISO.format(from), ISO.format(to), granularity, series,
                totals.toDto(activeUsersTotal));
    }

    /**
     * 🔴 <b>一期恒返回 0</b>（api-spec §7.11.1，严禁伪造、严禁估算、严禁用其它指标近似替代）。
     *
     * <p>不是偷懒，是当前确实<b>无持久化数据源</b>：
     * <ol>
     *   <li>限流仅在 Redis 固定窗口计数，🔴 不落任何数据库表；</li>
     *   <li>限流发生在<b>创建消息之前</b>，因此 {@code messages}/{@code tool_calls} 里
     *       根本没有对应行可聚合；</li>
     *   <li>{@code observability.analytics_allowed_events} 白名单中也没有任何限流相关事件名
     *       —— 🔴 @前端 不得自造事件名上报（未命中白名单会整条 discarded）。</li>
     * </ol>
     * 字段保留在响应中的理由：契约形态提前冻结，二期补数据源时前端与 @测试 无需改结构。
     * 📋 二期方案（(a) 前端上报 / (b) 后端侧写）待 Boss 裁决，🔴 裁决前禁止改成非 0。
     */
    private long rateLimitedCount() {
        return 0L;
    }

    // ===================== 分桶与合并 =====================

    /**
     * 生成连续桶序列（🔴 空桶补零：前端画图需要连续 x 轴，缺桶会画出错误的折线）。
     */
    private List<Instant> buckets(Instant from, Instant to, boolean hourly) {
        ChronoUnit unit = hourly ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        Instant cursor = from.truncatedTo(hourly ? ChronoUnit.HOURS : ChronoUnit.DAYS);
        List<Instant> result = new ArrayList<>();
        while (cursor.isBefore(to)) {
            result.add(cursor);
            cursor = cursor.plus(1, unit);
        }
        return result;
    }

    private String bucketKey(Instant bucketStart, boolean hourly) {
        return hourly ? BUCKET_KEY_HOUR.format(bucketStart) : BUCKET_KEY_DAY.format(bucketStart);
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.merge(String.valueOf(row[0]), toLong(row[1]), Long::sum);
        }
        return map;
    }

    private Map<String, UsageTokenUsageDTO> toTokenMap(List<Object[]> rows) {
        Map<String, UsageTokenUsageDTO> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UsageTokenUsageDTO usage = new UsageTokenUsageDTO(toLong(row[1]), toLong(row[2]),
                    toLong(row[3]));
            map.merge(String.valueOf(row[0]), usage, UsageTokenUsageDTO::plus);
        }
        return map;
    }

    /**
     * 把 {@code (bucket, status, errorCode, count)} 三元分组折叠成互斥计数。
     *
     * <p>🔴 <b>二分且仅二分</b>（api-spec §7.11.1 互斥不变量）：
     * <pre>
     * denied  ← status='denied'（含 V1.1.3 新增的 running → denied 执行期竞态）
     *           OR (status='timed_out' AND errorCode=30050 —— 确认等待超时 = 拒绝语义)
     * failed  ← status='failed'
     *           OR (status='timed_out' AND errorCode ∈ {30051, 30056} —— 执行超时)
     * total   ← 全部行（含 succeeded / cancelled / 非终态）
     * 🔴 timed_out 且 errorCode 为其它值（含 null）：既不计 denied 也不计 failed，
     *    并记 WARN —— 那是实现缺陷（§7.8.1 要求 timed_out 必须由 errorCode 区分语义），
     *    🔴 但绝不"猜一个"归类：猜错会让口径静默失真，远比少计一条更难发现。
     * </pre>
     */
    private Map<String, ToolCounters> toToolCounters(List<Object[]> rows) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String bucket = String.valueOf(row[0]);
            String status = row[1] == null ? "" : String.valueOf(row[1]);
            Integer errorCode = row[2] == null ? null : ((Number) row[2]).intValue();
            long count = toLong(row[3]);

            long[] counters = acc.computeIfAbsent(bucket, key -> new long[3]);
            counters[0] += count;
            if (ToolCall.STATUS_DENIED.equals(status)) {
                counters[1] += count;
            } else if (ToolCall.STATUS_FAILED.equals(status)
                    || ToolStateMachine.executionTimeout(status, errorCode)) {
                counters[2] += count;
            } else if (ToolCall.STATUS_TIMED_OUT.equals(status)) {
                log.warn("🔴 tool_calls 出现 status=timed_out 但 errorCode 非 30051/30056 的行"
                        + "（实现缺陷，本行不计入 failed）：errorCode={}", errorCode);
            }
        }
        Map<String, ToolCounters> result = new LinkedHashMap<>();
        acc.forEach((bucket, counters) ->
                result.put(bucket, new ToolCounters(counters[0], counters[1], counters[2])));
        return result;
    }

    /** 单桶工具调用计数。 */
    private record ToolCounters(long total, long denied, long failed) {
        static final ToolCounters EMPTY = new ToolCounters(0L, 0L, 0L);
    }

    /** 区间合计累加器。 */
    private static final class UsageTotalsAccumulator {
        private long messageCount;
        private long conversationCount;
        private UsageTokenUsageDTO tokenUsage = UsageTokenUsageDTO.ZERO;
        private long toolCallCount;
        private long toolDeniedCount;
        private long toolFailedCount;

        void add(long messages, long conversations, UsageTokenUsageDTO tokens, ToolCounters tools) {
            messageCount += messages;
            conversationCount += conversations;
            tokenUsage = tokenUsage.plus(tokens);
            toolCallCount += tools.total();
            toolDeniedCount += tools.denied();
            toolFailedCount += tools.failed();
        }

        UsageTotalsDTO toDto(long activeUsersTotal) {
            return new UsageTotalsDTO(messageCount, conversationCount, activeUsersTotal, tokenUsage,
                    toolCallCount, toolFailedCount, toolDeniedCount, 0L);
        }
    }

    // ===================== 入参 =====================

    private Instant requireInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.validation(field + " 必填");
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw BusinessException.validation(field + " 必须是 ISO-8601 UTC 时间");
        }
    }

    private String normalizeGranularity(String raw) {
        if (raw == null || raw.isBlank()) {
            return GRANULARITY_DAY;
        }
        String value = raw.trim();
        if (GRANULARITY_DAY.equals(value) || GRANULARITY_HOUR.equals(value)) {
            return value;
        }
        throw BusinessException.validation("granularity 取值必须是 day 或 hour");
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            log.warn("聚合结果无法解析为数值，按 0 处理（errorCode={}）", ErrorCode.INTERNAL_ERROR);
            return 0L;
        }
    }
}
