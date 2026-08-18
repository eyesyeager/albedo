package com.eyes.albedo.tool;

import com.eyes.albedo.tool.dto.ToolProgress;

/**
 * 工具状态流转回调（🔴 {@code tool → chat} 的<b>唯一出口</b>，architecture.md §5.1.3）。
 *
 * <p>存在理由：SSE 写出、事件顺序、{@code meta} 首发 flush 一律归 {@code chat}（{@code SseWriter}），
 * 而 {@code tool} 包<b>禁止依赖 {@code chat}</b>（否则成环）。因此工具侧每次状态流转只
 * "回调一个中立对象"，由 {@code ChatStreamRunner} 决定如何写帧。
 *
 * <p>🔴 <b>实现方纪律</b>：回调实现<b>必须不抛异常</b>（SSE 写失败在 {@code SseWriter} 内部已被吞并标记
 * {@code broken}）—— 一次写帧失败绝不能中断工具执行链路或让 {@code tool_calls} 留在非终态。
 *
 * <p>🔴 <b>调用方纪律</b>：每次状态流转都必须回调一次（api-spec §5.4.2「状态迁移逐帧下发，禁止跳帧」）。
 */
public interface ToolProgressListener {

    /** 空实现（供不关心进度的调用方 / 单测使用）。 */
    ToolProgressListener NOOP = progress -> {
    };

    /**
     * 状态流转通知。
     *
     * @param progress 当前阶段的可见进度（🔴 摘要已脱敏、已截断）
     */
    void onProgress(ToolProgress progress);
}
