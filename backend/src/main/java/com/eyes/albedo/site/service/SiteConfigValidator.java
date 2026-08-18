package com.eyes.albedo.site.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.eyes.albedo.site.dto.SiteConfigContent;
import com.eyes.albedo.site.dto.ViolationDTO;

import org.springframework.stereotype.Component;

/**
 * 站点配置发布校验器（架构 §10 发布校验 + §11 配置文案安全）。
 *
 * <p>为什么校验放在<b>发布时</b>：租户文案会直接渲染到页面，若在渲染时才过滤，
 * 一旦前端某处漏用消毒链就形成 XSS（RISK-007）。因此危险内容在<b>入库发布</b>阶段即拒绝，
 * 形成「存储层干净 + 渲染层再消毒」的双保险。
 *
 * <p>校验维度：必填、长度、URL 协议安全、危险内容（脚本/事件属性/危险协议）。
 */
@Component
public class SiteConfigValidator {

    private static final int SITE_TITLE_MAX = 60;
    private static final int WELCOME_MAX = 100;
    private static final int PLACEHOLDER_MAX = 80;
    private static final int ACTION_TEXT_MAX = 20;
    private static final int EMPTY_SESSION_MAX = 100;
    private static final int AGENT_UNAVAILABLE_MAX = 100;
    private static final int FOOTER_MAX = 300;
    private static final int URL_MAX = 2048;
    private static final int COLOR_MAX = 32;

    /** 危险内容：脚本标签、事件属性、危险协议、CSS 表达式。 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(<\\s*script)|(<\\s*/\\s*script)|(<\\s*iframe)|(<\\s*object)|(<\\s*embed)"
                    + "|(javascript\\s*:)|(vbscript\\s*:)|(data\\s*:\\s*text/html)"
                    + "|(\\bon[a-z]+\\s*=)|(expression\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    /**
     * 校验草稿内容。
     *
     * @return 违规清单；空表示通过
     */
    public List<ViolationDTO> validate(SiteConfigContent raw) {
        SiteConfigContent content = raw == null ? null : raw.normalized();
        List<ViolationDTO> violations = new ArrayList<>();
        if (content == null) {
            violations.add(ViolationDTO.required("content"));
            return violations;
        }

        requireText(violations, "siteTitle", content.siteTitle(), 1, SITE_TITLE_MAX);
        requireText(violations, "welcomeText", content.welcomeText(), 1, WELCOME_MAX);
        requireText(violations, "inputPlaceholder", content.inputPlaceholder(), 1, PLACEHOLDER_MAX);
        requireText(violations, "loginText", content.loginText(), 1, ACTION_TEXT_MAX);
        requireText(violations, "registerText", content.registerText(), 1, ACTION_TEXT_MAX);
        requireText(violations, "newChatText", content.newChatText(), 1, ACTION_TEXT_MAX);
        requireText(violations, "emptySessionText", content.emptySessionText(), 1, EMPTY_SESSION_MAX);
        requireText(violations, "agentUnavailableText", content.agentUnavailableText(), 1, AGENT_UNAVAILABLE_MAX);
        requireText(violations, "footerDisclaimer", content.footerDisclaimer(), 1, FOOTER_MAX);
        requireText(violations, "themePrimaryColor", content.themePrimaryColor(), 1, COLOR_MAX);

        optionalUrl(violations, "logoUrl", content.logoUrl());
        optionalUrl(violations, "faviconUrl", content.faviconUrl());
        return violations;
    }

    private void requireText(List<ViolationDTO> violations, String field, String value, int min, int max) {
        if (value == null || value.isBlank()) {
            violations.add(ViolationDTO.required(field));
            return;
        }
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            violations.add(ViolationDTO.length(field, min, max));
            return;
        }
        if (DANGEROUS.matcher(value).find()) {
            violations.add(ViolationDTO.unsafeContent(field, "包含脚本、事件属性或危险协议，已拒绝发布"));
        }
    }

    /**
     * 可空 URL：允许空串、站内相对路径（{@code /xxx}）或 https 绝对地址；
     * 🔴 拒绝 http、javascript、data 等不安全协议。
     */
    private void optionalUrl(List<ViolationDTO> violations, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (value.codePointCount(0, value.length()) > URL_MAX) {
            violations.add(ViolationDTO.length(field, 0, URL_MAX));
            return;
        }
        if (DANGEROUS.matcher(value).find()) {
            violations.add(ViolationDTO.unsafeContent(field, "包含危险协议或脚本内容，已拒绝发布"));
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        boolean siteRelative = lower.startsWith("/") && !lower.startsWith("//");
        if (!siteRelative && !lower.startsWith("https://")) {
            violations.add(ViolationDTO.insecureUrl(field));
        }
    }
}
