package com.eyes.albedo.auth;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.context.UserInfoHolder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 🔴 <b>{@code @TenantRole} 的程序化 fail-closed 兜底（唯一实现）</b>
 * —— api-spec §3（V1.1.4 #6 裁决）/ architecture.md §8.2.1 纪律 5 / AR-018，<b>安全漏洞级</b>。
 *
 * <p><b>🔴 为什么必须有它（这是本项目最隐蔽的一类安全缺陷）</b>：
 * <pre>
 * {@link TenantRoleAspect} 与 {@link PermissionAspect} 均由 {@link EyesAuthConfig} 装配，
 * 而后者带 @ConditionalOnProperty(prefix="eyes-auth", name="enabled", havingValue="true")。
 * 👉 eyes-auth.enabled=false 时（test profile 即如此，🔴 生产也可能被误配/漏配）：
 *    两个切面**整个不注册** → @Permission / @TenantRole 退化为
 *    "看起来有准入、实际全放行"的装饰 —— 接口直接以 code=0 返回租户数据，
 *    🔴 **静默越权**：不抛异常、不打 ERROR、用例还会"通过"。
 * </pre>
 *
 * <p><b>🔴 纪律（凡 {@code /api/v1/**} 上出现 {@code @TenantRole} 的端点一律适用）</b>：
 * <ol>
 *   <li>注解<b>必须保留</b>（契约可读性 + 切面生效时的正常路径），🔴 不得以"有兜底了"为由删注解；</li>
 *   <li>🔴 方法入口<b>必须</b>调用本类做一次程序化判定；</li>
 *   <li>🔴 两条路径的失败码<b>必须相同</b>（均 {@code 10003}）——
 *       开关状态不得改变对外契约，否则 @测试 会得到两套断言；</li>
 *   <li>🔴 "只有注解、没有兜底" = 缺陷（等级：安全），<b>不因</b>"只有测试环境才会这样"而豁免
 *       —— 开关是<b>运维态</b>，不是环境常量。</li>
 * </ol>
 * 守护测试：{@code TenantRoleGuardScanTest}（反射扫出全部 {@code @TenantRole} 端点，
 * 断言其集合 ⊆ 已被兜底覆盖的集合；新增端点漏兜底 → 🔴 测试红）。
 * 生产侧另由 {@code StartupChecker.checkProductionBlockers()} 在 {@code prod} profile
 * 断言 {@code eyes-auth.enabled=true}，否则 🔴 启动失败。
 *
 * <p>🔴 <b>为什么不用"补一个假切面"（{@code @ConditionalOnMissingBean}）替代</b>：那会让
 * {@code eyes-auth.enabled=false} 时 {@code @Permission} 也表现为放行（平台层洞更大），
 * 且掩盖配置错误本身 —— 暴露配置错误的正确位置是 {@code StartupChecker}，不是切面。
 *
 * <p>🔴 判定顺序与 {@link TenantRoleAspect} <b>逐步一致</b>（保证两条路径行为等价）：
 * 租户上下文可用（{@code 30010/30011}）→ 取 uid（无 → {@code 10003}）→
 * {@code ensureMembership}（成员禁用 → {@code 10003}）→ 角色不足 → {@code 10003}。
 */
@Slf4j
@Component
public class TenantRoleGuard {

    private final TenantMembershipPort membershipPort;

    public TenantRoleGuard(TenantMembershipPort membershipPort) {
        this.membershipPort = membershipPort;
    }

    /**
     * 要求当前请求者在<b>当前租户</b>内具备 {@code allowed} 之一的角色。
     *
     * @param allowed 允许的租户内角色（🔴 传空视为"任何角色都不允许"，直接拒绝 —— fail-closed）
     * @return 实际角色（便于调用方做进一步的细分判定）
     * @throws BusinessException 10003 无身份 / 成员禁用 / 角色不足；
     *                           30010/30011 租户上下文不可用（由 {@code TenantContext} 抛出）
     */
    public TenantRoleEnum require(TenantRoleEnum... allowed) {
        // 🔴 必须先确认租户上下文可用：没有租户就无从谈"租户内角色"
        TenantContext.requireEnabled();
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            // 生产由 @Permission 提前拦截；此处兜住 eyes-auth.enabled=false 的场景
            log.warn("[SECURITY] 缺少登录身份且鉴权切面可能未装配，按权限不足拒绝（fail-closed）");
            throw BusinessException.permissionDenied("需要登录后操作");
        }
        Set<TenantRoleEnum> allowedRoles = allowed == null || allowed.length == 0
                ? EnumSet.noneOf(TenantRoleEnum.class)
                : EnumSet.copyOf(Arrays.asList(allowed));
        TenantRoleEnum actual = membershipPort.ensureMembership(uid);
        if (!allowedRoles.contains(actual)) {
            log.warn("[SECURITY] 租户内角色不足，拒绝访问：uid={} actual={} allowed={}",
                    uid, actual, allowedRoles);
            throw BusinessException.permissionDenied("无权执行该操作");
        }
        return actual;
    }

    /**
     * 管理面常用组合（{@code TENANT_ADMIN} / {@code TENANT_OPERATOR}）。
     *
     * <p>🔴 只是便捷方法，不构成第二套判定逻辑（内部仍走 {@link #require}）。
     */
    public TenantRoleEnum requireTenantAdminOrOperator() {
        return require(TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR);
    }
}
