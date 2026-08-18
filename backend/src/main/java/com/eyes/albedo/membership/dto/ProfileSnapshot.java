package com.eyes.albedo.membership.dto;

/**
 * 成员资料快照（存入 {@code tenant_users.profile_snapshot} 的 JSON 结构）。
 *
 * <p>🔴 隐私红线（PRD §8.3）：只允许展示必需字段。**禁止**加入手机号、邮箱、token
 * 或任何可直接定位自然人的完整联系方式。
 *
 * @param nickname  昵称（取 eyesUser username，缺失为空串）
 * @param avatarUrl 头像地址（获取失败为空串，🔴 不阻断身份建立）
 */
public record ProfileSnapshot(String nickname, String avatarUrl) {

    public static ProfileSnapshot empty() {
        return new ProfileSnapshot("", "");
    }

    public ProfileSnapshot normalized() {
        return new ProfileSnapshot(nickname == null ? "" : nickname, avatarUrl == null ? "" : avatarUrl);
    }
}
