package com.eyes.albedo.audit;

/**
 * 审计 {@code result} 字面量（architecture.md §11.1.1 的 result 取值列）。
 */
public final class AuditResults {

    private AuditResults() {
    }

    /** 动作成功（含"连接测试成功"这类诊断成功）。 */
    public static final String SUCCESS = "success";
    /** 动作执行失败（如缓存失效部分作用域失败）。 */
    public static final String FAILED = "failed";
    /** 动作被拒绝（未授权 / 用户拒绝 / 确认超时 / SSRF 拒绝 / 跨租户探测）。 */
    public static final String DENIED = "denied";

    public static boolean isRegistered(String result) {
        return SUCCESS.equals(result) || FAILED.equals(result) || DENIED.equals(result);
    }
}
