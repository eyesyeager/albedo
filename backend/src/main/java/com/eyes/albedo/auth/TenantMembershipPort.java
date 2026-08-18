package com.eyes.albedo.auth;

/**
 * 租户成员关系 SPI —— 由 @后端 在 tenant/membership 模块实现（读写 {@code tenant_users}）。
 *
 * <p>骨架只定义契约，实现必须满足 PRD §6.2.4 惰性建户规则：
 * <ol>
 *   <li>以 {@code (当前租户, uid)} 定位成员关系</li>
 *   <li>不存在 → 建立默认 {@link TenantRoleEnum#END_USER} 关系后返回其角色</li>
 *   <li>存在且 {@code active} → 直接返回角色，并更新 {@code last_access_at}</li>
 *   <li>存在且 {@code disabled} → 抛 {@code BusinessException(10003)}，
 *       🔴 不得新建、不得自动恢复（AC-AUTH-006 / EX-009）</li>
 * </ol>
 *
 * <p>其他要求：
 * <ul>
 *   <li>调用发生在每个 {@code @Permission(USER/ADMIN)} 接口命中时，实现<b>必须</b>轻量
 *       （命中缓存或单条主键查询），不得成为 P95 ≤500ms 的瓶颈</li>
 *   <li>并发首次访问需幂等（唯一键 {@code uk_tenant_uid} + 冲突后重查）</li>
 *   <li>profile 快照按业务必要性同步，🔴 禁止落完整手机号 / 邮箱 / token</li>
 * </ul>
 */
public interface TenantMembershipPort {

    /**
     * 惰性建户并返回租户内角色。
     *
     * @param uid eyesUser uid
     * @return 租户内角色
     */
    TenantRoleEnum ensureMembership(long uid);
}
