package com.eyes.albedo.platform.controller;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.platform.dto.CacheEvictRequest;
import com.eyes.albedo.platform.dto.CacheEvictResultDTO;
import com.eyes.albedo.platform.service.CacheEvictService;
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
 * 平台缓存失效接口（api-spec §7.2.1，M2-min / REQ-CFG-004 / AC-CFG-005 + AC-AUD-003）。
 *
 * <p>路径处在 {@code /api/v1/platform/**} 白名单下，<b>不需要租户上下文</b>
 * （{@code TenantFilter} 跳过绑定）；目标租户由请求体显式给出。
 *
 * <p>🔴 权限：{@code @Permission(ADMIN)} 仅平台管理员。
 * ✅ <b>已由 @架构师 裁决（api-spec V1.1.5 G-4/G-5，§3 二分表 + §8.3 D3）</b>：
 * 原先被当作"契约歧义"的两个码<b>不是矛盾，而是执行顺序的物理必然</b>，二者按 profile 二分：
 * <ul>
 *   <li>生产（{@code eyes-auth.enabled=true}）：{@code PermissionAspect}（{@code @Order(10)}）
 *       在方法体执行<b>之前</b>拦截，非 ADMIN → {@code 20000}（前端清 token 跳 SSO）；
 *       🔴 此时 {@link #requirePlatformAdmin()} <b>根本到不了</b>；</li>
 *   <li>test profile（{@code eyes-auth.enabled=false}，切面不装配）：由
 *       {@link #requirePlatformAdmin()} 程序化兜底 → {@code 10003}（展示无权限态，不跳登录）</li>
 * </ul>
 * 共同不变量 = <b>绝不 {@code code=0}</b>；两个码均已在 §2.2 登记，🔴 不需要（也不得）统一为其中之一。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/cache")
public class PlatformCacheController {

    private final CacheEvictService cacheEvictService;

    public PlatformCacheController(CacheEvictService cacheEvictService) {
        this.cacheEvictService = cacheEvictService;
    }

    /**
     * 按作用域失效缓存（🔴 L1 + L2 同时失效；Host 键与租户号键成对失效；每次调用独立审计）。
     *
     * <p>错误码：{@code 10001}（scope 非法 / 条件必填缺失 / 缺 reason）、
     * {@code 10003}（非平台管理员）、{@code 20000~20005}、
     * {@code 30061}（部分或全部失效失败，带 {@code data.incompleteScopes[]}）、
     * {@code 50003}（审计写入失败导致整体失败，EX-024）。
     */
    @Permission(PermissionEnum.ADMIN)
    @PostMapping("/evict")
    public Result<CacheEvictResultDTO> evict(@RequestBody @Valid CacheEvictRequest request) {
        requirePlatformAdmin();
        return Result.success(cacheEvictService.evict(request));
    }

    /**
     * 平台管理员兜底校验（fail-closed）。
     *
     * <p>为什么还要在代码里查一遍：{@code eyes-auth.enabled=false} 时鉴权切面不装配，
     * 若只依赖注解，接口会在该配置下<b>完全敞开</b>。安全校验不能建立在"某个开关一定为 true"之上。
     *
     * <p>🔴 <b>生效边界（api-spec V1.1.5 G-4 裁决，§3 二分表；注释即契约）</b>：
     * <pre>
     * 生产 / eyes-auth.enabled=true ：PermissionAspect（@Order(10)）**先**执行 →
     *                                非 ADMIN 一律 20000，🔴 本方法**不会被执行到**；
     * test profile / enabled=false  ：切面缺席 → 本程序化兜底生效 → 返回 10003。
     * 🔴 该「20000 / 10003」二分是**执行顺序的物理必然**（切面永远早于方法体），
     *    不是契约歧义、不是缺陷，也<b>无法</b>在单一链路上收敛为一个码；
     *    共同不变量 = 绝不 code=0。见 api-spec §3 二分表 / §8.3 判据 D3。
     * 🔴 因此：不得删除本兜底（会让 test profile 下接口敞开），
     *    也不得把 10003 改成 20000（会让前端在无 SSO 的测试环境被误清退）。
     * </pre>
     */
    private void requirePlatformAdmin() {
        String role = UserInfoHolder.getRole();
        if (!AuthConfigConstant.ROLE_ADMIN.equals(role)) {
            log.warn("[SECURITY] 非平台管理员尝试调用缓存失效接口：uid={}", UserInfoHolder.getUid());
            throw BusinessException.permissionDenied("仅平台管理员可执行缓存失效");
        }
    }
}
