package com.eyes.albedo.site.dto;

/**
 * 公开站点配置响应（api-spec.md §4.2.2）。
 *
 * <p>结构 = 租户身份信息（tenantId/configVersion/timezone/locale）+ 已发布内容快照的<b>平铺</b>字段。
 * 平铺是契约要求，前端 {@code siteStore} 直接消费，不再做二级解包。
 */
public record SiteConfigDTO(String tenantId,
                            long configVersion,
                            String timezone,
                            String locale,
                            String siteTitle,
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

    public static SiteConfigDTO of(String tenantId,
                                   long configVersion,
                                   String timezone,
                                   String locale,
                                   SiteConfigContent content) {
        SiteConfigContent c = content.normalized();
        return new SiteConfigDTO(tenantId, configVersion, timezone, locale,
                c.siteTitle(), c.logoUrl(), c.faviconUrl(), c.welcomeText(), c.inputPlaceholder(),
                c.loginText(), c.registerText(), c.newChatText(), c.emptySessionText(),
                c.agentUnavailableText(), c.footerDisclaimer(), c.themePrimaryColor());
    }
}
