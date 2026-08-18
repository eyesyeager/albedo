package com.eyes.albedo.site.controller;

import com.eyes.albedo.common.Result;
import com.eyes.albedo.site.dto.SiteConfigDTO;
import com.eyes.albedo.site.service.SiteConfigService;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开站点配置接口（api-spec.md §4.2.2）。
 *
 * <p>权限 {@code NO}：匿名访客也必须能拿到品牌与文案，否则首屏白屏（PRD §4 规则 5）。
 * 租户上下文<b>必需</b> —— 由 Host 决定，客户端传入的 tenantId 一律忽略。
 *
 * <p>错误码：30010（未知/draft/archived Host）、30011（暂停）、30012（无可用配置），
 * 全部以 <b>HTTP 200 + code</b> 返回，前端据此路由到 {@code error/*} 视图。
 */
@RestController
@RequestMapping("/api/v1/site")
public class SiteConfigController {

    private final SiteConfigService siteConfigService;

    public SiteConfigController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @Permission(PermissionEnum.NO)
    @GetMapping("/config")
    public Result<SiteConfigDTO> config() {
        return Result.success(siteConfigService.currentConfig());
    }
}
