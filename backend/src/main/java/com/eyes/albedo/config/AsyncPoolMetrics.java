package com.eyes.albedo.config;

import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 生成线程池可观测指标（🔴 <b>AR-008 的兜底观测</b>，architecture.md §15 / §18）。
 *
 * <p><b>为什么必须有</b>：高风险确认会让生成线程<b>挂起最长 {@code tool.confirm_wait_seconds}</b>
 * （默认 120s）。最坏情形是 {@code max-pool-size}（默认 64）个线程全部挂在等待上，
 * 新请求被 {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} 回压到 Tomcat 线程。
 * 这种"服务还活着但越来越慢"的故障<b>只能靠指标发现</b>——
 * 没有计数器时，现场表现是"偶发变慢"，排障会走向数据库或模型上游等错误方向。
 *
 * <p>🔴 <b>不引入监控中间件</b>（架构红线 §2.2）：只做进程内计数 + 结构化日志，
 * 运维用 {@code grep} 即可取到；二期接入 Micrometer 时可原地替换实现。
 *
 * <p>观测项（三者共同回答"是否已被确认等待拖垮"）：
 * <ol>
 *   <li>{@link #activeCount()} —— 线程池活跃线程数</li>
 *   <li>{@link #callerRunsCount()} —— {@code CallerRunsPolicy} 触发次数（🔴 &gt;0 即已开始回压）</li>
 *   <li>确认等待挂起数 —— 由 {@code tool/ToolConfirmRegistry.pendingCount()} 提供并一起打日志</li>
 * </ol>
 */
@Slf4j
@Component
public class AsyncPoolMetrics {

    private final AtomicLong callerRuns = new AtomicLong();
    private volatile ThreadPoolTaskExecutor executor;

    /**
     * 绑定线程池（由 {@link AsyncConfig} 在创建后调用）。
     *
     * <p>🔴 用"事后绑定"而不是构造注入：否则 {@code executor → metrics → executor} 成环。
     */
    void bind(ThreadPoolTaskExecutor executor) {
        this.executor = executor;
    }

    /** {@code CallerRunsPolicy} 触发计数（🔴 每次触发都记 WARN，便于事后定位回压时刻）。 */
    void recordCallerRuns() {
        long total = callerRuns.incrementAndGet();
        log.warn("[BACKPRESSURE] aiStreamExecutor 队列已满，任务回压到调用线程执行："
                + "callerRunsTotal={} active={} queue={}", total, activeCount(), queueSize());
    }

    public long callerRunsCount() {
        return callerRuns.get();
    }

    public int activeCount() {
        ThreadPoolTaskExecutor current = executor;
        return current == null ? 0 : current.getActiveCount();
    }

    public int poolSize() {
        ThreadPoolTaskExecutor current = executor;
        return current == null ? 0 : current.getPoolSize();
    }

    public int queueSize() {
        ThreadPoolTaskExecutor current = executor;
        return current == null || current.getThreadPoolExecutor() == null
                ? 0 : current.getThreadPoolExecutor().getQueue().size();
    }

    /** 一行可读快照（供确认等待前后打点，🔴 不含任何业务数据）。 */
    public String snapshot() {
        return "active=" + activeCount() + " pool=" + poolSize()
                + " queue=" + queueSize() + " callerRuns=" + callerRunsCount();
    }
}
