package com.eyes.albedo.quota.service;

/**
 * Redis 在额度判定路径上不可用（🔴 内部信号，<b>绝不</b>对外暴露为错误码）。
 *
 * <p>捕获方是 {@code QuotaService}，动作是 <b>降级为 DB 直判</b>（AR-024）：
 * <pre>
 * 🔴 明确**否决 fail-open**（与 QPM 的"Redis 故障放行"**有意不同**）：
 *    QPM 放行只是抗突发能力下降，而日额度放行等于**当天无限量** —— 直接对应模型调用成本。
 * 降级仍保留"已结算数 ≥ limit 即拒绝"这条硬闸门 → 超发上界 = 该用户当时的并发数（不是无限）。
 * </pre>
 */
class QuotaRedisUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    QuotaRedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
