package com.eyes.albedo.common;

/**
 * 对外 ID 序列化辅助（ADR-004）。
 *
 * <p>🔴 所有 {@code id} / {@code uid} / {@code conversationId} / {@code messageId} / {@code agentId}
 * 在 JSON 与 SSE 事件中<b>必须是 string</b>：JS {@code Number} 安全整数上限为 2^53-1，
 * MySQL BIGINT 溢出会静默丢精度（会话打不开、幂等错乱）。
 *
 * <p>🔴 禁止改为全局 Long→String 序列化器：会污染 {@code total/page/pageSize/tokenUsage} 等统计值。
 */
public final class Ids {

    private Ids() {
    }

    /**
     * Long → 对外 string（null 安全）。
     */
    public static String toStr(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /**
     * 对外 string ID → Long。
     *
     * <p>非法格式<b>不抛 10001</b>，而是按「当前租户内不存在」处理：避免通过报错差异探测 ID 空间
     * （AC-TEN-004 不暴露资源存在性）。
     *
     * @throws BusinessException 10004 资源不存在
     */
    public static long parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BusinessException.notFound();
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw BusinessException.notFound();
        }
    }
}
