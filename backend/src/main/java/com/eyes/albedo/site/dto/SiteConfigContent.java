package com.eyes.albedo.site.dto;

/**
 * 站点配置内容快照（存入 {@code site_config_versions.content} 的 JSON 结构）。
 *
 * <p>字段语义与校验规则见 PRD §9.2；🔴 这里只放<b>单租户品牌与文案</b>，
 * 平台阈值/开关一律入 {@code sys_config}（架构 §7 双层配置边界，禁止混用）。
 *
 * @param siteTitle            站点标题（1~60）
 * @param logoUrl              Logo 地址（可空，HTTPS）
 * @param faviconUrl           Favicon 地址（可空，HTTPS）
 * @param welcomeText          欢迎语（1~100）
 * @param inputPlaceholder     输入框引导语（1~80）
 * @param loginText            登录按钮文案（1~20）
 * @param registerText         注册按钮文案（1~20）
 * @param newChatText          新建会话文案（1~20）
 * @param emptySessionText     空会话引导语（1~100）
 * @param agentUnavailableText 无可用 Agent 时的语义（1~100）
 * @param footerDisclaimer     页脚声明（1~300）
 * @param themePrimaryColor    主题主色（需满足 WCAG AA，具体值由 @UI 定）
 */
public record SiteConfigContent(String siteTitle,
                                String logoUrl,
                                String faviconUrl,
                                String welcomeText,
                                String inputPlaceholder,
                                String loginText,
                                String registerText,
                                String newChatText,
                                String emptySessionText,
                                String agentUnavailableText,
                                String footerDisclaimer,
                                String themePrimaryColor) {

    /**
     * 把可空字段规范化为空串，避免前端出现 undefined 判断分支。
     */
    public SiteConfigContent normalized() {
        return new SiteConfigContent(
                trim(siteTitle), trim(logoUrl), trim(faviconUrl), trim(welcomeText),
                trim(inputPlaceholder), trim(loginText), trim(registerText), trim(newChatText),
                trim(emptySessionText), trim(agentUnavailableText), trim(footerDisclaimer),
                trim(themePrimaryColor));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
