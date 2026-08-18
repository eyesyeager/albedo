package com.eyes.albedo.chat.ai;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 流式生成看门狗（首字超时 / 整体超时的唯一实现）。
 *
 * <p>为什么必须有：JDK {@code HttpClient} 的 {@code HttpRequest.timeout} 只覆盖到<b>响应头</b>，
 * 一旦上游返回 200 后长时间不吐字（或中途卡死），阻塞式读取无法自我超时，
 * 线程会被永久占用 —— 这是流式接口最典型的资源泄漏事故。
 *
 * <p>做法：调度一个延时任务，到点后<b>关闭上游流</b>，读循环随即抛 IO 异常并按超时收敛。
 * 线程池只有 2 个 <b>daemon</b> 线程：它只负责"踢一脚"，不承载业务。
 */
@Slf4j
@Component
public class StreamWatchdog {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-stream-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 延时执行。
     *
     * @param delaySeconds 延时秒数（≤0 时不调度）
     * @return 可取消的句柄；未调度时返回 null
     */
    public ScheduledFuture<?> schedule(Runnable task, long delaySeconds) {
        if (delaySeconds <= 0) {
            return null;
        }
        return scheduler.schedule(task, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * 周期执行（SSE 心跳用）。
     *
     * @param periodSeconds 周期秒数（≤0 时不调度）
     * @return 可取消的句柄；未调度时返回 null
     */
    public ScheduledFuture<?> scheduleHeartbeat(Runnable task, long periodSeconds) {
        if (periodSeconds <= 0) {
            return null;
        }
        return scheduler.scheduleAtFixedRate(task, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /**
     * 取消调度（生成正常结束时调用）。
     */
    public void cancel(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        log.info("流式生成看门狗已关闭");
    }
}
