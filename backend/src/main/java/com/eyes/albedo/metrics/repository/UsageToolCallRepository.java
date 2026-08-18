package com.eyes.albedo.metrics.repository;

import java.time.Instant;
import java.util.List;

import com.eyes.albedo.tool.entity.ToolCall;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 工具调用聚合仓储（api-spec §7.11.1 的 {@code toolCallCount}/{@code toolDeniedCount}/
 * {@code toolFailedCount}）。
 *
 * <p><b>🔴 互斥不变量（本仓储的核心，写错就会重复计数或漏计）</b>：
 * <pre>
 * toolCallCount   = 全部行（含 pending / denied —— 即"模型请求调用工具的总次数"）
 * toolDeniedCount = status='denied'  OR (status='timed_out' AND error_code=30050)
 *                   ↑ 确认等待超时按 §7.8.1「语义等同拒绝」归入 denied
 *                   ↑ 🔴 V1.1.3 起 denied 多了一条来源（running → denied 执行期竞态），
 *                     该行同样 status='denied' + error_code=30050 → 仍只计 denied，公式不变
 * toolFailedCount = status='failed'  OR (status='timed_out' AND error_code in (30051,30056))
 *                   ↑ 执行超时归 failed
 * succeeded / cancelled / 非终态 → 🔴 两者都不计
 *
 * ✅ 判据：denied + failed + succeeded + cancelled + 非终态残留 = toolCallCount
 * 🔴 任一行被两个计数同时命中即为缺陷 —— 本实现用 status 二分 + timed_out 按 error_code
 *    二分且**仅**二分，结构上不可能重叠。
 * </pre>
 *
 * <p>🔴 走 {@code idx_tenant_status_created(tenant_id, status, created_at)}；
 * 全部方法强制带时间区间（禁止无界全表扫描）。JPQL 实体查询 → 租户条件由 discriminator 自动追加。
 */
public interface UsageToolCallRepository extends Repository<ToolCall, Long> {

    /**
     * 按 {@code (bucket, status, errorCode)} 分组计数。
     *
     * <p>🔴 <b>为什么一次查回三元分组而不是发三条 SQL</b>：三条 SQL 之间存在<b>时间窗口</b>
     * （区间边界上的行可能在两次查询之间被写入终态），会导致
     * "denied + failed + succeeded ≠ toolCallCount"这种<b>不可复现</b>的不变量破裂。
     * 一次查回后在内存里二分，口径必然自洽。
     *
     * @return 每行 {@code [bucket(String), status(String), errorCode(Integer|null), count(Long)]}
     */
    @Query("select function('date_format', tc.createdAt, :bucketFormat), tc.status, tc.errorCode,"
            + " count(tc)"
            + " from ToolCall tc"
            + " where tc.createdAt >= :from and tc.createdAt < :to"
            + " group by function('date_format', tc.createdAt, :bucketFormat), tc.status,"
            + " tc.errorCode")
    List<Object[]> toolCallStatsByBucket(@Param("from") Instant from,
                                         @Param("to") Instant to,
                                         @Param("bucketFormat") String bucketFormat);
}
