package com.eyes.albedo.site.controller;

import java.util.Optional;

import com.eyes.albedo.site.service.SiteConfigService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tenant.TenantStatus;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点级状态页（ADR-005：<b>后端唯一允许返回非 200 的端点</b>）。
 *
 * <table border="1">
 *   <caption>Host 状态映射</caption>
 *   <tr><th>租户状态</th><th>HTTP</th><th>语义</th></tr>
 *   <tr><td>未知 / draft / archived</td><td>404</td><td>站点不存在</td></tr>
 *   <tr><td>suspended</td><td>403</td><td>站点暂停服务</td></tr>
 *   <tr><td>enabled 但无可用已发布配置</td><td>503</td><td>站点配置异常</td></tr>
 *   <tr><td>enabled 且配置可用</td><td>200</td><td>OK</td></tr>
 * </table>
 *
 * <p>🔴 返回极简 {@code text/html}：不含任何租户业务数据、不含登录入口、带 {@code noindex}。
 * 用途：Nginx {@code error_page} 映射、运维探测、@测试 验收 AC-NFR-004。
 *
 * <p>⚠️ 本端点不在 {@code /api/v1/**} 之下，因此不受「恒 200」约束；
 * 反过来说，任何业务语义都<b>不得</b>在此表达（那属于 30010/30011/30012）。
 */
@Slf4j
@RestController
public class SiteStatusController {

    private final SiteConfigService siteConfigService;

    public SiteStatusController(SiteConfigService siteConfigService) {
        this.siteConfigService = siteConfigService;
    }

    @Permission(PermissionEnum.NO)
    @GetMapping(value = "/site/status", produces = MediaType.TEXT_HTML_VALUE + ";charset=utf-8")
    public ResponseEntity<String> status() {
        Optional<TenantContext.Snapshot> snapshot = TenantContext.current();
        if (snapshot.isEmpty()) {
            return html(HttpStatus.NOT_FOUND, "站点不存在");
        }
        TenantStatus status = snapshot.get().status();
        return switch (status) {
            case DRAFT, ARCHIVED -> html(HttpStatus.NOT_FOUND, "站点不存在");
            case SUSPENDED -> html(HttpStatus.FORBIDDEN, "站点暂停服务");
            case ENABLED -> siteConfigService.hasUsableConfig()
                    ? html(HttpStatus.OK, "OK")
                    : html(HttpStatus.SERVICE_UNAVAILABLE, "站点配置异常");
        };
    }

    private ResponseEntity<String> html(HttpStatus status, String message) {
        String body = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8">
                <meta name="robots" content="noindex">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                </head>
                <body><main><h1>%s</h1></main></body>
                </html>
                """.formatted(message, message);
        return ResponseEntity.status(status)
                .contentType(MediaType.valueOf(MediaType.TEXT_HTML_VALUE + ";charset=utf-8"))
                .header("Cache-Control", "no-store")
                .body(body);
    }
}
