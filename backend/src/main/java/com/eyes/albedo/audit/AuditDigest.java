package com.eyes.albedo.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 审计摘要工具（architecture.md §11.1.2 的 {@code before_digest} / {@code after_digest} 口径）。
 *
 * <p>🔴 审计中的敏感值<b>只允许</b>三种形态：
 * <ol>
 *   <li>{@code sha256(值)} 的<b>前 16 hex</b>（本类 {@link #digest(String)}）</li>
 *   <li>{@link #CHANGED} / {@link #UNCHANGED} 变化标记（凭据类只记"是否变化"，PRD §15.1）</li>
 *   <li>api-spec §5.4.3 生成的脱敏摘要（由 tool 模块的 ToolSummaryScrubber 产出，M3 第二阶段）</li>
 * </ol>
 * 其余一律视为安全缺陷（AC-AUD-002）。
 *
 * <p>为什么是 16 hex 而不是全长：审计的用途是"能对照同一值是否一致"，不是"能还原值"。
 * 截断降低了彩虹表反查短值（如 4 位验证码）的收益，同时仍足以区分不同凭据。
 */
public final class AuditDigest {

    private AuditDigest() {
    }

    /** 值已变化（不记录变化前后的任何内容）。 */
    public static final String CHANGED = "changed";
    /** 值未变化。 */
    public static final String UNCHANGED = "unchanged";
    /** 摘要长度（hex 字符数）。 */
    public static final int DIGEST_HEX_LENGTH = 16;

    /**
     * 🔴 <b>可原样记录的安全枚举字面量白名单</b>（api-spec §7.8.2 ⑤ / §7.4.3 G3）。
     *
     * <p>为什么需要它：部分审计的 {@code before/after} 承载的<b>本身就是枚举字面量</b>，
     * 摘要化只会让审计变得<b>不可读且无用</b>：
     * <pre>
     * tool.confirm_conflict：before=既有决定(allow|deny)、after=被拒绝的提交值 ——
     *   契约明确"二者均为枚举字面量，非敏感值，可原样记"。
     *   🔴 若被摘要成 3026a0ca485e5831，排障时根本看不出"用户把 allow 翻成了 deny"，
     *      而这恰恰是这条审计存在的**唯一目的**（举证决定被翻转）。
     * mcp.tool_grant_revoked：after='removed'（Schema 已不存在）。
     * </pre>
     * 🔴 纪律：本白名单<b>只允许放入固定枚举字面量</b>（取值集合有限且与用户输入无关）；
     * 任何可能承载用户数据的值一律不得加入 —— 否则这个白名单就变成了明文泄露通道。
     */
    private static final java.util.Set<String> SAFE_LITERALS = java.util.Set.of(
            "allow", "deny", "removed");

    /**
     * sha256 前 16 hex；入参为 null / 空白时返回空串（不产生"空值的摘要"这种误导性数据）。
     */
    public static String digest(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DIGEST_HEX_LENGTH);
            for (int i = 0; i < DIGEST_HEX_LENGTH / 2; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法，不可能缺失；仍不返回明文，避免降级成"泄露"
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 变化标记（用于凭据等"内容绝不可记"的字段）。 */
    public static String changeMarker(boolean changed) {
        return changed ? CHANGED : UNCHANGED;
    }

    /**
     * 是否为合法的 digest 形态（空串 / 16 位小写 hex / changed / unchanged /
     * {@link #SAFE_LITERALS} 白名单内的枚举字面量）。
     *
     * <p>🔴 {@link AuditWriter} 在写入前用本方法做防御性校验：不合法的值会被<b>强制摘要化</b>，
     * 不能因为"调用方传错"就把明文写进审计。
     */
    public static boolean isValidForm(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (CHANGED.equals(value) || UNCHANGED.equals(value)) {
            return true;
        }
        if (SAFE_LITERALS.contains(value)) {
            return true;
        }
        if (value.length() != DIGEST_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
