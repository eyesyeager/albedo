package com.eyes.albedo.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import com.eyes.albedo.tenant.TenantAwareTaskDecorator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步线程池配置（SSE 流式生成专用）。
 *
 * <p>关键点：
 * <ul>
 *   <li>装配 {@link TenantAwareTaskDecorator}：复制租户上下文与登录身份到执行线程；
 *       🔴 缺失租户上下文的任务直接拒绝执行（RISK-001）</li>
 *   <li>有界队列 + {@link ThreadPoolExecutor.CallerRunsPolicy}：过载时回压而非丢任务</li>
 *   <li>优雅关闭：等待正在生成的会话落库，避免消息停留在 {@code streaming}</li>
 *   <li>🔴 业务代码禁止 {@code new Thread(...)} / {@code CompletableFuture.supplyAsync(...)}（默认池无上下文）</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    public static final String AI_STREAM_EXECUTOR = "aiStreamExecutor";

    @Bean(name = AI_STREAM_EXECUTOR)
    public Executor aiStreamExecutor(AppProperties properties, AsyncPoolMetrics metrics) {
        AppProperties.Async config = properties.getAsync();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.getCorePoolSize());
        executor.setMaxPoolSize(config.getMaxPoolSize());
        executor.setQueueCapacity(config.getQueueCapacity());
        executor.setKeepAliveSeconds(config.getKeepAliveSeconds());
        executor.setThreadNamePrefix(config.getThreadNamePrefix());
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        // 🔴 过载时回压而非丢任务；🔴 每次回压都计数 + WARN（AR-008：确认等待可能挂满线程池）
        ThreadPoolExecutor.CallerRunsPolicy callerRuns = new ThreadPoolExecutor.CallerRunsPolicy();
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            metrics.recordCallerRuns();
            callerRuns.rejectedExecution(runnable, pool);
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(config.getAwaitTerminationSeconds());
        executor.initialize();
        metrics.bind(executor);
        return executor;
    }
}
