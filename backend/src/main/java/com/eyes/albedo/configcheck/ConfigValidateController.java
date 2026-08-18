package com.eyes.albedo.configcheck;

import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.auth.TenantRoleGuard;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.configcheck.dto.ConfigValidateRequest;
import com.eyes.albedo.configcheck.dto.ConfigValidationReportDTO;
import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 独立配置校验入口（api-spec §7.3.1 / ADR-013 ①，REQ-CFG-003 / AC-CFG-003）。
 *
 * <p>一期没有管理 UI：本接口 + {@code backend/scripts/validate-config.sh} 就是 DBA 的"校验命令"。
 * 标准操作顺序（README 运维手册）：<b>改库 → 跑校验 → 调缓存失效接口</b>。
 *
 * <p>🔴 权限实现说明（已回报 @架构师）：api-spec §7.3.1 要求
 * "{@code @Permission(USER)} + {@code @TenantRole({TENANT_ADMIN, TENANT_OPERATOR})}，
 * 但 {@code objectType=localTool}（平台作用域对象）改用 {@code @Permission(ADMIN)}"。
 * 同一条路径的权限<b>依赖请求体取值</b>，静态注解无法表达（一个方法只能有一组注解），
 * 因此本实现：注解声明 {@code @Permission(USER)}（保证 SSO 身份有效），
 * 再按 {@code objectType} <b>程序化</b>判定：
 * <ul>
 *   <li>{@code localTool} → 必须是平台管理员（eyesUser {@code role=ADMIN}），否则 {@code 10003}</li>
 *   <li>其余 → 必须是租户内 {@code TENANT_ADMIN} / {@code TENANT_OPERATOR}，否则 {@code 10003}</li>
 * </ul>
 * 两条分支的失败码均为 §7.3.1 已登记的 {@code 10003}，语义与契约一致。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/config")
public class ConfigValidateController {

    private final RuntimeConfigValidator validator;
    private final TenantRoleGuard tenantRoleGuard;

    public ConfigValidateController(RuntimeConfigValidator validator,
                                    TenantRoleGuard tenantRoleGuard) {
        this.validator = validator;
        this.tenantRoleGuard = tenantRoleGuard;
    }

    /**
     * 校验单对象及其引用链。
     *
     * <p>合法 → {@code code=0}（{@code warnings[]} 不阻断）；
     * 非法 → {@code 30060} + {@code data.violations[]}（字段级失败）。
     *
     * <p>错误码：{@code 10001}（objectType 非法）、{@code 10003}（角色不足 / 成员禁用）、
     * {@code 10004}（对象不存在或跨租户）、{@code 20001~20005}、{@code 30010/30011}、
     * {@code 30060}（校验失败）、{@code 50003}。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/validate")
    public Result<ConfigValidationReportDTO> validate(@RequestBody @Valid ConfigValidateRequest request) {
        String objectType = request.objectType() == null ? "" : request.objectType().trim();
        authorize(objectType);

        ConfigValidationReportDTO report = validator.validate(objectType, request.objectId(),
                request.includeReferencesOrDefault());
        if (!report.valid()) {
            // 🔴 30060 + 字段级 violations；data 原样承载报告，禁止只回一句"配置错误"
            throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置校验失败", report);
        }
        return Result.success(report);
    }

    /**
     * 按对象作用域判定权限（fail-closed）。
     *
     * <p>🔴 租户作用域分支统一走 {@link TenantRoleGuard}（api-spec §3 / architecture.md §8.2.1
     * 的唯一实现）：本端点<b>无法</b>用 {@code @TenantRole} 注解表达（权限依赖请求体取值），
     * 因此程序化兜底在这里既是"兜底"也是<b>唯一</b>判定路径 —— 更不能各写一份。
     */
    private void authorize(String objectType) {
        if (RuntimeConfigValidator.TYPE_LOCAL_TOOL.equals(objectType)) {
            // 平台作用域对象：仅平台管理员（🔴 与租户内角色严格分离，AC-AUTH-007）
            Long uid = UserInfoHolder.getUid();
            if (uid == null) {
                // 无身份即拒绝（生产由 @Permission(USER) 提前拦截；此处兜底 eyes-auth.enabled=false）
                throw BusinessException.permissionDenied("需要登录后操作");
            }
            if (!AuthConfigConstant.ROLE_ADMIN.equals(UserInfoHolder.getRole())) {
                log.warn("[SECURITY] 非平台管理员尝试校验平台作用域对象：uid={}", uid);
                throw BusinessException.permissionDenied("仅平台管理员可校验平台作用域对象");
            }
            return;
        }
        // 租户作用域对象：必须已建立可用租户上下文 + 租户内管理/运营角色
        tenantRoleGuard.require(TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR);
    }
}
