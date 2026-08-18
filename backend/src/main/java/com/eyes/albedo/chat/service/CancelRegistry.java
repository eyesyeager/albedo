package com.eyes.albedo.chat.service;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本机生成句柄注册表（停止生成 ≤1s 的关键，AC-CHAT-002）。
 *
 * <p>为什么需要它：仅写 Redis 取消标记只能在「下一次分片检查」时生效，
 * 若上游长时间不吐字（或一次性吐很大分片），用户观感会明显超过 1 秒。
 * 因此本机直接持有上游流句柄，收到停止请求时<b>立即关闭连接</b>，读循环随即结束。
 *
 * <p>Redis 标记依然保留：多实例部署时，停止请求可能落在<b>非生成实例</b>上，
 * 此时靠标记让生成实例在下一次检查时停止（跨实例兜底）。
 */
@Slf4j
@Component
public class CancelRegistry {

    private final Map<Long, Closeable> handles = new ConcurrentHashMap<>();

    /**
     * 注册生成句柄。
     */
    public void register(long messageId, Closeable handle) {
        handles.put(messageId, handle);
    }

    /**
     * 注销（生成结束时必须调用，避免句柄泄漏）。
     */
    public void unregister(long messageId) {
        handles.remove(messageId);
    }

    /**
     * 关闭本机上游流。
     *
     * @return 是否在本机命中
     */
    public boolean close(long messageId) {
        Closeable handle = handles.remove(messageId);
        if (handle == null) {
            return false;
        }
        try {
            handle.close();
            log.debug("已关闭本机上游流：messageId={}", messageId);
        } catch (Exception e) {
            // 关闭失败不影响取消语义：Redis 标记 + 读循环检查仍会终止生成
            log.warn("关闭上游流失败（将由取消标记兜底）：messageId={}", messageId);
        }
        return true;
    }

    /**
     * 当前本机在途生成数（可观测用）。
     */
    public int inFlightCount() {
        return handles.size();
    }
}
