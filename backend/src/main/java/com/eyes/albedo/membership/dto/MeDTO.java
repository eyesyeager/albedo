package com.eyes.albedo.membership.dto;

/**
 * 当前用户与租户内身份（api-spec.md §4.3.1）。
 *
 * <p>🔴 禁止返回手机号、邮箱、token（契约核对清单第 10 项）。
 *
 * @param uid           eyesUser uid，对外为 <b>string</b>（ADR-004）
 * @param nickname      昵称（取自成员资料快照，缺失为空串）
 * @param avatarUrl     头像（缺失为空串，前端按占位处理）
 * @param tenantRole    租户内角色：TENANT_ADMIN / TENANT_OPERATOR / END_USER
 * @param platformAdmin 是否 eyesUser 平台管理员（{@code role=ROLE_admin}）；
 *                      🔴 为 true 也<b>不代表</b>拥有任何租户内权限（AC-AUTH-007）
 * @param memberStatus  成员状态：active（disabled 在此之前已由 10003 拦截）
 */
public record MeDTO(String uid,
                    String nickname,
                    String avatarUrl,
                    String tenantRole,
                    boolean platformAdmin,
                    String memberStatus) {
}
