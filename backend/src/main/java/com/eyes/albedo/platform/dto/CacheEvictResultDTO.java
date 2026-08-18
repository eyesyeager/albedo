package com.eyes.albedo.platform.dto;

import java.util.List;

/**
 * 缓存失效结果（api-spec §7.2.1 响应体 {@code data}）。
 *
 * <p>🔴 {@code incompleteScopes} 非空时接口必须返回 {@code 30061}，<b>禁止伪报成功</b>（EX-033）。
 *
 * @param requestedScope  请求的作用域字面量
 * @param results         各内部作用域的实际失效条目数与状态
 * @param totalL1Evicted  L1（本 JVM 进程内）合计
 * @param totalL2Evicted  L2（Redis）合计
 * @param incompleteScopes 未完成作用域清单（🔴 部分失败时必须列出）
 * @param auditEventId    审计事件 ID（32 位小写 hex，🔴 原样返回不截断）
 */
public record CacheEvictResultDTO(String requestedScope,
                                  List<CacheEvictScopeResultDTO> results,
                                  long totalL1Evicted,
                                  long totalL2Evicted,
                                  List<String> incompleteScopes,
                                  String auditEventId) {
}
