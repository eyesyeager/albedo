package com.eyes.albedo.metrics.controller;

import com.eyes.albedo.auth.TenantRole;
import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.auth.TenantRoleGuard;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.metrics.dto.UsageReportDTO;
import com.eyes.albedo.metrics.service.UsageMetricsService;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户用量与运行指标（api-spec §7.11.1，REQ-OBS-001 / AC-OBS-001）。
 *
 * <p>权限：{@code @Permission(USER)} + {@code @TenantRole({TENANT_ADMIN, TENANT_OPERATOR})}
 * （🔴 与契约逐字一致）；角色不足 → {@code 10003}。
 *
 * <p>🔴 一期<b>无管理 UI</b>：本接口仅供接口实测与数据核验使用（PRD §8.10 第 7 条）。
 *
 * <p>🔴 <b>只聚合当前租户</b>（AC-TEN-005）：租户由 Host 解析，
 * 请求里<b>没有也不接受</b> {@code tenantId} 参数 —— 想查别的租户唯一途径是从那个租户的 Host 进来。
 *
 * <p>🔴 响应<b>不是分页结构</b>（固定时间序列），也不伪装成分页（契约明确）。
 */
@Slf4j
@RestController
public class UsageMetricsController {

    private final UsageMetricsService usageMetricsService;
    private final TenantRoleGuard tenantRoleGuard;

    public UsageMetricsController(UsageMetricsService usageMetricsService,
                                  TenantRoleGuard tenantRoleGuard) {
        this.usageMetricsService = usageMetricsService;
        this.tenantRoleGuard = tenantRoleGuard;
    }

    /**
     * 用量查询。
     *
     * <p>错误码：{@code 10001}（{@code from}/{@code to} 非法、{@code to<=from}、跨度 &gt;31 天、
     * {@code granularity} 非法）、{@code 10003}（角色不足 / 成员禁用）、{@code 20001~20005}、
     * {@code 30010}/{@code 30011}、{@code 50003}。
     */
    @Permission(PermissionEnum.USER)
    @TenantRole({TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR})
    @GetMapping("/api/v1/admin/metrics/usage")
    public Result<UsageReportDTO> usage(@RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to,
                                        @RequestParam(required = false) String granularity) {
        authorize();
        return Result.success(usageMetricsService.query(from, to, granularity));
    }

    /**
     * 租户内角色准入的<b>程序化兜底</b>（{@code TENANT_ADMIN} / {@code TENANT_OPERATOR}）。
     *
     * <p>🔴 <b>为什么除了 {@code @TenantRole} 还要再判一遍</b>（api-spec §3 /
     * architecture.md §8.2.1 已升格为<b>全局纪律</b>，AR-018）：{@code TenantRoleAspect} 由
     * {@code EyesAuthConfig} 装配，而后者带
     * {@code @ConditionalOnProperty(eyes-auth.enabled=true)}。一旦该开关为 {@code false}
     * （测试 profile 即如此，生产也可能被误配），切面<b>整个不注册</b>，
     * 注解就成了"看起来有准入、其实全放行"的装饰 —— 🔴 这类失效<b>不会报错</b>，
     * 只会静默越权（本轮实测正是先写了纯注解版本，用例直接以 {@code code=0} 通过，
     * 才暴露出这条陷阱）。
     *
     * <p>🔴 判定统一委托 {@link TenantRoleGuard}（唯一实现，禁止各控制器各写一份），
     * 两条路径的失败码都是契约已登记的 {@code 10003}。
     */
    private void authorize() {
        tenantRoleGuard.require(TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR);
    }
}
