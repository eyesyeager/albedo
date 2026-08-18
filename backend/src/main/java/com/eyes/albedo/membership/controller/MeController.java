package com.eyes.albedo.membership.controller;

import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.membership.dto.MeDTO;
import com.eyes.albedo.membership.dto.ProfileSnapshot;
import com.eyes.albedo.membership.entity.TenantUser;
import com.eyes.albedo.membership.service.TenantMembershipService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户接口（api-spec.md §4.3）。
 *
 * <p>🔴 本项目<b>不存在</b>登录 / 注册 / 登出 / 刷新令牌接口：本接口只是「用已有 Token 读取自身身份」，
 * 命中时由鉴权切面完成惰性建户，因此调用它即可确认成员关系已建立。
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantMembershipService membershipService;

    public MeController(TenantMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Permission(PermissionEnum.USER)
    @GetMapping
    public Result<MeDTO> me() {
        TenantContext.requireEnabled();
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            // 理论不可达（切面已校验）；fail-closed 而非返回空身份
            throw BusinessException.permissionDenied("无法确定当前身份");
        }
        // 切面已完成惰性建户，此处必然存在；缺失说明数据被并发删除，按权限不足处理
        TenantUser member = membershipService.findMember(uid)
                .orElseThrow(() -> BusinessException.permissionDenied("当前站点的成员身份不可用"));
        ProfileSnapshot profile = membershipService.readProfile(member);

        boolean platformAdmin = AuthConfigConstant.ROLE_ADMIN.equals(UserInfoHolder.getRole());
        return Result.success(new MeDTO(
                Ids.toStr(uid),
                profile.nickname(),
                profile.avatarUrl(),
                TenantRoleEnum.of(member.getTenantRole()).name(),
                platformAdmin,
                member.getStatus()));
    }
}
