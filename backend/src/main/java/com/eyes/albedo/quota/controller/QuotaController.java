package com.eyes.albedo.quota.controller;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.quota.dto.QuotaSnapshotDTO;
import com.eyes.albedo.quota.service.QuotaService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户对话额度查询（api-spec §7.15.1，REQ-QUOTA-003）。
 *
 * <p>🔴 <b>零参数</b>：方法签名里<b>没有</b>任何 {@code tenantId} / {@code uid} / {@code date}
 * 参数 —— 这让"不接受客户端指定主体"从一条<b>纪律</b>变成<b>结构性不可能</b>（EX-003 / AC-QUOTA-016）。
 * 传入的任何查询参数都会被 Spring 直接忽略（不绑定、不读取）。
 *
 * <p>🔴 <b>三道结构性隔离防线</b>（不依赖"记得写 where"）：
 * <ol>
 *   <li>两张额度表均继承 {@code BaseTenantEntity} → discriminator 自动追加 {@code tenant_id}</li>
 *   <li>Redis 键经 {@code TenantCacheKeys.ofTenant(...)} 生成，键内必含 {@code tenantId} 与 {@code uid}</li>
 *   <li>接口零参数 + {@code uid} 只取 {@code UserInfoHolder} → 同一 uid 在 gift / redbook
 *       的额度<b>天然独立</b>（AC-QUOTA-002）</li>
 * </ol>
 *
 * <p>🔴 <b>匿名访问不做任何特例</b>：走既有 {@code USER} 端点的标准路径（{@code 20001}/{@code 20002}），
 * 🔴 <b>不返回</b>"未启用 / unlimited" —— 造一个假响应会给匿名用户一个可探测的口径。
 * 正确纪律在<b>前端</b>：{@code uid === null} 时不发起该请求（K12 反向断言）。
 *
 * <p>🔴 <b>为什么不并入 {@code GET /api/v1/me}</b>：{@code /me} 是"身份建立"接口
 * （命中即惰性建户 + Thrift 鉴权），而额度需要在"页面恢复前台 / 生成 done 之后 / 到达 resetsAt"
 * 高频校准 → 耦合会导致"为刷额度而反复触发建户"，且形成两处口径（ADR-020 ⑤）。
 * 🔴 更不能并入匿名可访问且带缓存的 {@code /site/config}（直接违反隐私边界）。
 */
@RestController
@RequestMapping("/api/v1/me/quota")
public class QuotaController {

    private final QuotaService quotaService;

    public QuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * 查询当前用户在<b>当前租户</b>的今日额度快照（🔴 {@code data} 恰 9 键）。
     */
    @Permission(PermissionEnum.USER)
    @GetMapping
    public Result<QuotaSnapshotDTO> quota() {
        String tenantId = TenantContext.requireEnabled().tenantId();
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            // 理论不可达（切面已校验）；fail-closed 而非返回一个"看起来无限"的快照
            throw BusinessException.permissionDenied("无法确定当前身份");
        }
        return Result.success(quotaService.snapshot(tenantId, uid));
    }
}
