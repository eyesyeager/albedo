package com.eyes.albedo.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.eyes.albedo.config.AsyncPoolMetrics;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantCacheKeys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 高风险工具确认的<b>等待原语</b>（🔴 ADR-008 第 2/3/9 条，api-spec §7.8.1）。
 *
 * <p><b>形状与 {@code chat/service/CancelRegistry} 完全同构</b>（"本机句柄注册表 + Redis 跨实例兜底"）：
 * 两套"外部信号唤醒生成线程"的机制保持同一心智模型，@后端 与 @测试 的负担最低。
 *
 * <p>🔴 <b>它是等待原语，不是缓存</b>：因此不受 §12.1 的 L1 缓存准入清单约束
 * （缓存准入表里明确写了这一条）。
 *
 * <p>🔴 <b>裁决点唯一 = 数据库行锁</b>（ADR-008 第 5 条）：
 * {@link CompletableFuture} 只负责<b>唤醒</b>，绝不负责<b>判定</b>。
 * 状态机流转与审计由 {@code ToolConfirmWriter} 在 {@code SELECT … FOR UPDATE} 短事务内完成，
 * <b>提交之后</b>才调用 {@link #complete}。二次 {@code complete} 返回 {@code false} 即丢弃（幂等）。
 *
 * <p>🔴 <b>四路收敛（AR-010 + 🔴 V1.4.2 ADR-017 ③ⓑ 新增第 4 路）</b>：{@link #await} 会在下列
 * <b>任一</b>信号到达时立即返回：
 * <ol>
 *   <li>confirm 接口 {@link #complete}（本机）或 Redis 兜底信号（跨实例，轮询间隔
 *       {@code tool.confirm_poll_interval_millis}）</li>
 *   <li>{@link ToolCancellation#cancelled()}（停止生成 / 会话删除 / 客户端断连）</li>
 *   <li>等待超过 {@code tool.confirm_wait_seconds} → {@link ToolConfirmDecision#TIMEOUT}</li>
 *   <li>🔴 <b>生成预算收紧</b>：本次实际等待上限 =
 *       {@code min(tool.confirm_wait_seconds, 生成剩余预算 − chat.deadline_grace_seconds)}
 *       （{@link #await(String, long, long, ToolCancellation, long)}）。<br>
 *       🔴 存在理由：确认等待若能突破生成总预算，连接会在等待期间被<b>传输层</b>掐断，
 *       {@code done} 帧物理上写不出去（BUG-MCP-002 的触发路径）。<br>
 *       🔴 上限 {@code ≤ 0} 时编排层<b>根本不发确认卡</b>（不发一张必然超时的卡片），
 *       本方法只做防御性 {@code TIMEOUT} 收敛。</li>
 * </ol>
 * 🔴 因此<b>绝不存在永久挂起的生成线程</b>（§9.5.4 不变量 ⑤）。
 *
 * <p>🔴 <b>死锁纪律（ADR-010，最高危）</b>：{@link #await} 期间调用方<b>绝不允许持有
 * {@code tool_calls} 行锁或任何数据库事务</b> —— 否则 confirm 接口的 {@code FOR UPDATE}
 * 会与生成线程互等，且<b>低并发单测测不出来</b>。本类因此：
 * <ul>
 *   <li>整类<b>无 {@code @Transactional}</b>（由 {@code TransactionDisciplineScanTest} 静态守护）</li>
 *   <li>不注入任何 Repository —— 从依赖上就无法在等待期间碰数据库</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolConfirmRegistry {

    /** {@code toolCallId} → 等待者。 */
    private final Map<Long, Waiter> waiters = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final TenantCacheKeys cacheKeys;
    private final BusinessConfig businessConfig;
    private final AsyncPoolMetrics poolMetrics;

    public ToolConfirmRegistry(StringRedisTemplate redis,
                               TenantCacheKeys cacheKeys,
                               BusinessConfig businessConfig,
                               AsyncPoolMetrics poolMetrics) {
        this.redis = redis;
        this.cacheKeys = cacheKeys;
        this.businessConfig = businessConfig;
        this.poolMetrics = poolMetrics;
    }

    /**
     * 一个等待者（{@code messageId} 用于"按消息批量唤醒"）。
     */
    private record Waiter(long messageId, CompletableFuture<ToolConfirmDecision> future) {
    }

    /**
     * 等待用户确认（🔴 阻塞当前生成线程，最长 {@code tool.confirm_wait_seconds}）。
     *
     * <p>🔴 <b>V1.4.2 起本方法只服务于"无生成预算"的调用方</b>（单测 / 非生成链路）：
     * 生成链路必须走 {@link #await(String, long, long, ToolCancellation, long)}，
     * 把等待上限收紧到 {@code min(tool.confirm_wait_seconds, remaining − grace)}（ADR-017 ③ⓑ）。
     *
     * @param tenantId     租户号（快照值；🔴 异步段禁止读 ThreadLocal，§9.5.2 第 3 条）
     * @param messageId    assistant 消息 ID（停止生成按它批量唤醒）
     * @param toolCallId   工具调用 ID
     * @param cancellation 取消信号（不得为 null，用 {@link ToolCancellation#NEVER} 表示不可取消）
     * @return 四态结论（🔴 永不返回 null）
     */
    public ToolConfirmDecision await(String tenantId, long messageId, long toolCallId,
                                    ToolCancellation cancellation) {
        return await(tenantId, messageId, toolCallId, cancellation, configuredWaitSeconds());
    }

    /**
     * 等待用户确认，<b>等待上限由调用方给定</b>（🔴 ADR-017 ③ⓑ 的落点）。
     *
     * <p>🔴 {@code maxWaitSeconds} 语义 = 本次<b>实际</b>等待上限 =
     * {@code min(sys_config: tool.confirm_wait_seconds, 生成剩余预算 − 宽限)}；
     * 编排层在 {@code ≤ 0} 时🔴 <b>根本不调用本方法</b>（不发一张必然超时的确认卡），
     * 因此这里对 {@code ≤ 0} 只做防御性收敛（立即 {@link ToolConfirmDecision#TIMEOUT}）。
     *
     * @param maxWaitSeconds 本次等待上限（秒）
     */
    public ToolConfirmDecision await(String tenantId, long messageId, long toolCallId,
                                     ToolCancellation cancellation, long maxWaitSeconds) {
        if (maxWaitSeconds <= 0L) {
            // 🔴 防御性：正常路径由编排层拦下；走到这里等价于"等待预算已耗尽"，按拒绝收敛
            log.warn("确认等待上限 ≤0，直接按超时（=拒绝）收敛：toolCallId={} maxWaitSeconds={}",
                    toolCallId, maxWaitSeconds);
            return ToolConfirmDecision.TIMEOUT;
        }
        long waitSeconds = maxWaitSeconds;
        long pollMillis = Math.max(1L, businessConfig.requireLong(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_CONFIRM_POLL_INTERVAL_MILLIS));
        CompletableFuture<ToolConfirmDecision> future = register(messageId, toolCallId);
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(waitSeconds).toNanos();
        // 🔴 AR-008 观测：挂起数 + 线程池快照（"服务活着但越来越慢"只能靠这行日志发现）
        log.info("等待高风险工具确认：toolCallId={} waitSeconds={} confirmPending={} {}",
                toolCallId, waitSeconds, pendingCount(), poolMetrics.snapshot());

        ToolConfirmDecision decision = ToolConfirmDecision.TIMEOUT;
        try {
            while (true) {
                if (cancellation != null && cancellation.cancelled()) {
                    decision = ToolConfirmDecision.CANCELLED;
                    break;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) {
                    decision = ToolConfirmDecision.TIMEOUT;
                    break;
                }
                long sliceNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(pollMillis), remaining);
                try {
                    decision = future.get(sliceNanos, TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException e) {
                    // 本机未收到 → 轮询 Redis 兜底信号（confirm 可能落在别的实例上）
                    ToolConfirmDecision signal = readSignal(tenantId, toolCallId);
                    if (signal != null) {
                        decision = signal;
                        break;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 线程被中断（应用关闭）：按取消收敛，绝不当成"用户同意"
            decision = ToolConfirmDecision.CANCELLED;
        } catch (ExecutionException e) {
            // Future 不应异常完成；出现即按超时（= 拒绝）收敛，fail-safe
            log.error("确认等待 Future 异常完成，按拒绝收敛：toolCallId={}", toolCallId, e);
            decision = ToolConfirmDecision.TIMEOUT;
        } finally {
            unregister(toolCallId);
        }
        log.info("高风险工具确认等待结束：toolCallId={} decision={} confirmPending={}",
                toolCallId, decision, pendingCount());
        return decision;
    }

    /**
     * {@code sys_config: tool.confirm_wait_seconds}（🔴 <b>绝对</b>上限，不含生成预算收紧）。
     *
     * <p>供编排层计算 {@code min(绝对上限, remaining − grace)} 使用 —— 🔴 编排层
     * <b>不得</b>自己去读这个键：等待原语与它的上限必须同源，否则两处漂移时
     * "倒计时"与"真实等待"会不一致（前端倒计时骗人正是本轮 P1 的一部分）。
     */
    public long configuredWaitSeconds() {
        return businessConfig.requireLong(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS);
    }

    /**
     * 注册等待者（🔴 同一 {@code toolCallId} 重复注册视为实现缺陷，后者覆盖前者并告警）。
     */
    CompletableFuture<ToolConfirmDecision> register(long messageId, long toolCallId) {
        CompletableFuture<ToolConfirmDecision> future = new CompletableFuture<>();
        Waiter previous = waiters.put(toolCallId, new Waiter(messageId, future));
        if (previous != null) {
            log.warn("同一 toolCallId 重复进入确认等待：toolCallId={}", toolCallId);
            previous.future().complete(ToolConfirmDecision.CANCELLED);
        }
        return future;
    }

    /** 注销（🔴 必须在 {@code finally} 中调用，避免 Map 泄漏）。 */
    void unregister(long toolCallId) {
        waiters.remove(toolCallId);
    }

    /**
     * 唤醒等待中的生成线程（🔴 由 confirm 接口在<b>短事务提交之后</b>调用）。
     *
     * @return 是否命中本机等待者（未命中不代表失败：可能落在别的实例，走 Redis 兜底）
     */
    public boolean complete(long toolCallId, ToolConfirmDecision decision) {
        Waiter waiter = waiters.get(toolCallId);
        if (waiter == null) {
            return false;
        }
        boolean applied = waiter.future().complete(decision);
        if (!applied) {
            // 幂等：二次 complete 丢弃（三方竞态由行锁裁决，Future 只管唤醒）
            log.debug("确认信号重复到达，已丢弃：toolCallId={} decision={}", toolCallId, decision);
        }
        return applied;
    }

    /**
     * 按消息唤醒全部等待者为 {@link ToolConfirmDecision#CANCELLED}（🔴 ADR-008 第 9 条 / §9.5.4 ②）。
     *
     * <p><b>为什么必须有</b>：关闭上游流<b>不会</b>唤醒挂在确认等待上的线程（它没在读流）。
     * 若不额外唤醒，用户点了"停止"或直接关页面后，线程仍会白等满
     * {@code tool.confirm_wait_seconds}（默认 120s）——
     * 64 个线程被这样占住就是一次可用性事故（AR-008）。
     *
     * <p>调用点：{@code ChatCancelService.cancel(...)}（停止生成 / 会话删除）
     * 与 {@code SseEmitter.onError/onCompletion/onTimeout}（客户端断连）。
     *
     * @return 被唤醒的等待者数量
     */
    public int cancelByMessage(long messageId) {
        List<Long> hit = new ArrayList<>();
        waiters.forEach((toolCallId, waiter) -> {
            if (waiter.messageId() == messageId) {
                hit.add(toolCallId);
            }
        });
        int cancelled = 0;
        for (Long toolCallId : hit) {
            Waiter waiter = waiters.get(toolCallId);
            if (waiter != null && waiter.future().complete(ToolConfirmDecision.CANCELLED)) {
                cancelled++;
            }
        }
        if (cancelled > 0) {
            log.info("已唤醒确认等待为 cancelled（停止生成 / 断连）：messageId={} count={}",
                    messageId, cancelled);
        }
        return cancelled;
    }

    /** 🔴 AR-008 指标：当前挂在确认等待上的生成线程数。 */
    public int pendingCount() {
        return waiters.size();
    }

    /** 本机是否有该 {@code toolCallId} 的等待者（供确认接口决定是否需要 Redis 兜底）。 */
    public boolean waiting(long toolCallId) {
        return waiters.containsKey(toolCallId);
    }

    /**
     * 写 Redis 兜底信号（🔴 跨实例通道，键见 {@link TenantCacheKeys#toolConfirm}）。
     *
     * <p>TTL = {@code tool.confirm_wait_seconds}：信号的生命周期不应超过等待上限。
     * 🔴 该键属"运行时状态键"，缓存失效接口禁止删除（否则用户已提交的决定会丢失）。
     * Redis 不可用时只记 WARN：本机 {@link #complete} 已经唤醒，跨实例兜底是<b>加固</b>而非前提。
     */
    public void publishSignal(String tenantId, long toolCallId, ToolConfirmDecision decision) {
        if (decision == null || decision.literal() == null) {
            return;
        }
        try {
            long ttl = businessConfig.requireLong(ConfigKeys.GROUP_TOOL,
                    ConfigKeys.TOOL_CONFIRM_WAIT_SECONDS);
            redis.opsForValue().set(cacheKeys.toolConfirm(tenantId, toolCallId),
                    decision.literal(), Duration.ofSeconds(ttl));
        } catch (RuntimeException e) {
            log.warn("写入工具确认信号失败（本机唤醒仍生效）：toolCallId={}", toolCallId);
        }
    }

    /** 清理 Redis 兜底信号（终态后即可回收；失败不影响语义，TTL 会兜住）。 */
    public void clearSignal(String tenantId, long toolCallId) {
        try {
            redis.delete(cacheKeys.toolConfirm(tenantId, toolCallId));
        } catch (RuntimeException e) {
            log.debug("清理工具确认信号失败（TTL 会兜住）：toolCallId={}", toolCallId);
        }
    }

    private ToolConfirmDecision readSignal(String tenantId, long toolCallId) {
        try {
            return ToolConfirmDecision.ofSignal(
                    redis.opsForValue().get(cacheKeys.toolConfirm(tenantId, toolCallId)));
        } catch (RuntimeException e) {
            // Redis 不可用：本机通道仍可唤醒；🔴 绝不因此误判为"已同意"
            log.warn("读取工具确认信号失败，继续等待本机信号：toolCallId={}", toolCallId);
            return null;
        }
    }
}
