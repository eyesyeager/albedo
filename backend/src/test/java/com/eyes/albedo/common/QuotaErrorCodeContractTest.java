package com.eyes.albedo.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code 30070 DAILY_QUOTA_EXHAUSTED} 的登记契约（api-spec §2.1 / §2.2 的 V1.2.5 新登记）。
 *
 * <p>🔴 <b>为什么每一条都必须自动化</b>：
 * <ul>
 *   <li>漏加 {@code REGISTERED} → 该码在埋点里被<b>静默置空</b>（api-spec §2.2 明文警告），
 *       错误分布统计从此少一类，且没有任何报错</li>
 *   <li>落到耶瞳保留段 → 前端 {@code request.ts} 会"清 token + 整页跳 SSO"，
 *       把"今日额度用完"表现成"被强制登出"</li>
 *   <li>子段错位（不在 {@code 30070~30079}）→ §2.1 与 §2.2 两表不一致，属明文缺陷</li>
 * </ul>
 */
class QuotaErrorCodeContractTest {

    @Test
    @DisplayName("🔴 码值恰为 30070 且落在业务子段 30070~30079（未触碰耶瞳保留段）")
    void codeValueAndSegment() {
        assertEquals(30070, ErrorCode.DAILY_QUOTA_EXHAUSTED);
        assertTrue(ErrorCode.DAILY_QUOTA_EXHAUSTED >= 30070
                        && ErrorCode.DAILY_QUOTA_EXHAUSTED <= 30079,
                "🔴 必须落在「用量与额度」子段");
        assertFalse(ErrorCode.isAuthSegment(ErrorCode.DAILY_QUOTA_EXHAUSTED),
                "🔴 占用 20000~20999 会让前端把额度用尽误判为 token 失效并清退登录");
    }

    @Test
    @DisplayName("🔴 必须已加入 ErrorCode.REGISTERED（漏加会让该码在埋点里被静默置空）")
    void codeIsRegistered() {
        assertTrue(ErrorCode.isRegistered(ErrorCode.DAILY_QUOTA_EXHAUSTED));
        assertTrue(ErrorCode.registeredCodes().contains(ErrorCode.DAILY_QUOTA_EXHAUSTED));
    }

    @Test
    @DisplayName("🔴 defaultMessage 有独立分支（不得退化为\"未知错误\"），且不泄露内部取值")
    void defaultMessageIsDefined() {
        String message = ErrorCode.defaultMessage(ErrorCode.DAILY_QUOTA_EXHAUSTED);

        assertFalse("未知错误".equals(message), "🔴 必须新增 defaultMessage 分支");
        assertFalse(message.equals(ErrorCode.defaultMessage(ErrorCode.RATE_LIMITED)),
                "🔴 与 10005 的语义必须可区分（恢复条件相差 5 个数量级）");
        assertFalse(message.contains("sys_config") || message.contains("tenant_quota_policies"),
                "🔴 不得泄露表名 / 键名：" + message);
    }

    @Test
    @DisplayName("🔴 30070 不得与 10005 混用：两者是独立常量（禁止复用限流码表达日额度）")
    void isDistinctFromRateLimited() {
        assertFalse(ErrorCode.DAILY_QUOTA_EXHAUSTED == ErrorCode.RATE_LIMITED);
    }
}
