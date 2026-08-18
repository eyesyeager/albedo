package com.eyes.albedo.chat.service;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.quota.dto.QuotaContext;
import com.eyes.albedo.quota.dto.QuotaReservation;
import com.eyes.albedo.quota.service.QuotaService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 生成准入（🔴 <b>五步顺序的唯一实现处</b>，architecture.md §9.6.1 / api-spec §7.15.3）。
 *
 * <p>🔴 <b>顺序不可调整</b>，且全部发生在<b>建流之前</b>（因此拒绝一律 HTTP 200 + JSON）：
 * <pre>
 * 0 幂等回放命中 → 直接回放（🔴 不计 QPM、不占额度、不重复结算）—— 由 ChatController 在调用本类**之前**完成
 * 1 解析有效策略 + 额度窗口   ← 🔴 一次准入**只解析一次**，快照传给 ②③④
 * 2 日额度**只读**预检        ← 用尽 → 30070（🔴 此时 QPM 计数器**未被触碰**）
 * 3 QPM 消费（原子 INCR）     ← 超限 → 10005 + retryAfterSeconds（🔴 此时日额度**未被预占**）
 *                              qpm_enabled=false → **整步跳过**
 * 4 日额度**预占**（原子 Lua） ← 抢不到 → 30070（🔴 必须在创建消息之前，否则留下孤儿消息）
 * 5 建消息 + 建流             ← 由调用方执行；🔴 任何异常必须在 catch/finally 中释放预占
 * </pre>
 *
 * <p><b>🔴 顺序错会怎样</b>（逐条对应 §9.6.1 的表）：
 * <ul>
 *   <li>②③ 交换 → "日额度已用尽仍增加 QPM 计数"<b>必然发生</b>（违反 AC-QUOTA-012 前半句）</li>
 *   <li>③④ 交换 → "QPM 超限却已占用日额度"<b>必然发生</b>（违反 AC-QUOTA-012 后半句）</li>
 *   <li>④ 放到 ⑤ 之后 → 被拒时留下孤儿 user/assistant 消息（PRD 明文"不新建生成尝试"）</li>
 * </ul>
 *
 * <p>🔴 <b>唯一被明文接受的偏差</b>：④ 因并发失败时 ③ 已计数。判据见 api-spec §7.15.3 裁决框
 * （与 AC-QUOTA-004"QPM 次数不回退"一致）。🔴 <b>严禁</b>为此实现 QPM 的 {@code DECR} 回退 ——
 * 那会让"被限流期间重试不延长封禁窗口"这一既有性质失效，并引入"计数可被外部行为回拨"的新竞态。
 */
@Slf4j
@Service
public class GenerationAdmission {

    private final QuotaService quotaService;
    private final MessageRateLimiter rateLimiter;

    public GenerationAdmission(QuotaService quotaService, MessageRateLimiter rateLimiter) {
        this.quotaService = quotaService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 执行准入第 1~4 步。
     *
     * @param uid 🔴 只来自 {@code UserInfoHolder}（接口层已取），绝不来自请求参数（EX-003）
     * @return 日额度预占凭据（未启用额度时为 no-op 哨兵，🔴 不是 {@code null}）
     * @throws BusinessException 30070 今日额度已用尽（{@code data} = 快照）；
     *                           10005 分钟窗超限（{@code data.retryAfterSeconds}）；
     *                           50003 额度配置或租户时区异常
     */
    public QuotaReservation admit(String tenantId, long uid) {
        // ① 解析（🔴 QPM 与日限额必须来自同一次解析，否则会出现策略错配）
        QuotaContext ctx = quotaService.resolve(tenantId, uid);
        // ② 日额度只读预检（🔴 不写任何计数器）
        quotaService.precheckDaily(ctx);
        // ③ QPM 消费（阈值来自 ① 的快照，🔴 限流器不再自行读 sys_config）
        if (ctx.policy().qpmEnabled()) {
            rateLimiter.check(tenantId, uid, ctx.policy().qpmLimit());
        }
        // ④ 日额度预占（🔴 单条 Lua 原子完成"剪过期 → 读镜像 → 读在途 → 判定 → ZADD"）
        return quotaService.reserve(ctx);
    }
}
