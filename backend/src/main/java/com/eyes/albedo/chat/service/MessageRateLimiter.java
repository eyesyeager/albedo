package com.eyes.albedo.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tenant.TenantCacheKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 消息发送频率限流（PRD §8.8 / api-spec §7.12 / AC-LMT-001 / AC-LMT-003~005）。
 *
 * <p>算法：Redis <b>固定窗口</b> + Lua 原子计数（{@code INCR} + 首次 {@code EXPIRE} + 返回 TTL）。
 * 选择固定窗口而非滑动窗口：只需保护主链路不被刷爆，固定窗口实现简单、无额外内存开销。
 *
 * <p>🔴 隔离：键由 {@link TenantCacheKeys} 生成，含 {@code tenantId + uid}，
 * 因此单租户 / 单用户的行为绝不占用他人额度（AC-LMT-001 / AC-LMT-004）。
 *
 * <p><b>🔴 V1.4.5（ADR-020）两处订正</b>：
 * <pre>
 * ① 阈值来源**上移**：本类**不再自行读 sys_config**，阈值由 quota/QuotaPolicyResolver
 *    解析出的有效策略作为**入参**传入（chat/service/GenerationAdmission 负责编排）。
 *    🔴 为什么：QPM 与日限额必须来自**同一次**策略解析，否则会出现
 *    "QPM 取平台默认、日额度取租户覆盖"的错配（api-spec §7.12 阈值行）。
 *    ✅ 副作用：本类退化为一个**纯机制**组件（固定窗口计数器），对 quota 包**零编译期依赖**，
 *       因此仍留在 chat 包（搬动只会打断既有类路径与测试引用而不改变任何行为，§5.1.2）。
 * ② 🔴 **小时窗业务规则已废除**（PRD V1.4 §8.11.9：50 次/日下无独立业务价值）：
 *    HOUR_WINDOW / HOUR_SECONDS / perHour 读取分支**整体删除**，
 *    ratelimit.message_per_hour 键一并从 REQUIRED_CONFIG 与 ConfigKeys 移除。
 *    🔴 明确否决"保留键但不再读取"：那会留下"库里写着 120、改它却毫无效果"的幽灵配置。
 *    ⚠️ 残留的 Redis 小时窗键（…:limit:msg:{uid}:h{yyyyMMddHH}）由自身 TTL ≤1h 自然回收。
 * </pre>
 *
 * <p>🔴 <b>分钟窗仍以 UTC 纪元构造</b>（{@code m}+{@code yyyyMMddHHmm}，ADR-020 ③ 明确追认）：
 * "一分钟"与时区无关，改用租户时区只会让同一 UTC 时刻的窗口边界因租户而异（零收益）
 * 并打断既有键格式与既有测试。🔴 时区只影响<b>日历日</b>边界（那是日额度的事）。
 *
 * <p>🔴 <b>时钟由构造注入</b>（ADR-020 ⑧）：窗口标识就是 Redis 键名，属<b>业务判定</b>，
 * 必须能被确定性验证（如"跨分钟边界"）；本类生产代码中禁止再出现 {@code Instant.now()}。
 *
 * <p>Redis 不可用时<b>放行</b>：限流是保护措施，不应让缓存故障升级为业务不可用。
 * ⚠️ 与日额度<b>有意不同</b>（日额度降级为 DB 直判而<b>不放行</b>，ADR-020 ② / AR-024）：
 * QPM 丢一分钟窗口只是抗突发能力下降，日额度放行等于<b>当天无限量</b>（直接对应模型成本）。
 *
 * <p>⚠️ 已知且<b>有意接受</b>的行为：超限后计数仍会 {@code INCR}（不影响 TTL，因为
 * {@code EXPIRE} 只在首次设置）。这让"被限流期间持续重试"不会延长封禁窗口。
 * 🔴 同理<b>严禁</b>为"回退 QPM"实现 {@code DECR}（api-spec §7.15.3 裁决框）——
 * 那会让上述性质失效，并引入"计数可被外部行为回拨"的新竞态。
 */
@Slf4j
@Service
public class MessageRateLimiter {

    /**
     * 固定窗口计数脚本：返回 {@code [当前计数, 剩余秒数]}。
     */
    private static final String LUA = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
              ttl = tonumber(ARGV[1])
            end
            return {current, ttl}
            """;

    /** 分钟窗标识前缀（🔴 与日窗口的 {@code d} 前缀同体例，§12.2）。 */
    private static final String MINUTE_WINDOW_PREFIX = "m";

    private static final DateTimeFormatter MINUTE_WINDOW =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    private static final long MINUTE_SECONDS = 60L;

    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final Clock clock;
    private final RedisScript<List> script;

    public MessageRateLimiter(StringRedisTemplate redis,
                              TenantCacheKeys cacheKeys,
                              Clock clock) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.clock = clock;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(LUA);
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    /**
     * 校验并累加分钟窗计数（准入五步的<b>第 3 步</b>）。
     *
     * <p>🔴 调用方（{@link GenerationAdmission}）必须保证：本方法<b>晚于</b>日额度只读预检、
     * <b>早于</b>日额度预占 —— 否则"日额度用尽仍增 QPM 计数"或"QPM 超限却占日额度"必然发生
     * （AC-QUOTA-012）。
     *
     * @param perMinuteLimit 有效 QPM 阈值（🔴 来自 {@code EffectiveQuotaPolicy}，恒 ≥1）
     * @throws BusinessException 10005 超限，{@code data.retryAfterSeconds} 给出剩余等待秒数
     */
    public void check(String tenantId, long uid, int perMinuteLimit) {
        Instant now = clock.instant();
        consume(tenantId, uid, MINUTE_WINDOW_PREFIX + MINUTE_WINDOW.format(now),
                MINUTE_SECONDS, perMinuteLimit);
    }

    private void consume(String tenantId, long uid, String window, long windowSeconds, int limit) {
        String key = cacheKeys.messageRateLimit(tenantId, uid, window);
        List<?> result;
        try {
            result = redis.execute(script, List.of(key), String.valueOf(windowSeconds));
        } catch (RuntimeException e) {
            log.warn("限流计数失败（Redis 不可用），本次放行：uid={}", uid);
            return;
        }
        if (result == null || result.size() < 2) {
            return;
        }
        long current = toLong(result.get(0));
        long ttl = toLong(result.get(1));
        if (current > limit) {
            long retryAfter = Math.max(ttl, 1L);
            log.info("消息发送触发限流：uid={} window={} limit={} current={}", uid, window, limit, current);
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "发送过于频繁，请 " + retryAfter + " 秒后重试",
                    Map.of("retryAfterSeconds", retryAfter));
        }
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
