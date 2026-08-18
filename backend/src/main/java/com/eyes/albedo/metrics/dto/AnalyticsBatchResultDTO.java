package com.eyes.albedo.metrics.dto;

/**
 * 批量埋点上报结果（api-spec §7.10.1 的 {@code data}）。
 *
 * <p>🔴 埋点失败<b>永不影响主流程</b>：本接口一律 {@code code=0}，
 * 三个计数如实反映处置结果（🔴 禁止把丢弃伪报成 accepted）。
 *
 * @param accepted   实际落库条数
 * @param duplicated 命中 {@code uk(tenant_id, client_event_id)} 的重复上报（<b>静默丢弃</b>）
 * @param discarded  白名单未命中 / 禁止字段命中 / 时间偏差过大 / 采样丢弃 / 总开关关闭
 */
public record AnalyticsBatchResultDTO(int accepted, int duplicated, int discarded) {
}
