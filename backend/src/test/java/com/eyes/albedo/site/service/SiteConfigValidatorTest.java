package com.eyes.albedo.site.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.eyes.albedo.site.dto.SiteConfigContent;
import com.eyes.albedo.site.dto.ViolationDTO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 站点配置发布校验单测（架构 §10 发布校验 + §11 配置文案安全）。
 */
class SiteConfigValidatorTest {

    private final SiteConfigValidator validator = new SiteConfigValidator();

    @Test
    @DisplayName("合法内容：无违规项")
    void validContentPasses() {
        assertTrue(validator.validate(valid()).isEmpty());
    }

    @Test
    @DisplayName("必填缺失：逐字段给出 required 违规")
    void requiredFields() {
        SiteConfigContent content = new SiteConfigContent("", "", "", "", "", "", "", "", "", "", "", "");
        List<ViolationDTO> violations = validator.validate(content);

        assertEquals(10, violations.size(), "10 个必填字段都应报错：" + violations);
        assertTrue(violations.stream().allMatch(v -> "required".equals(v.rule())));
    }

    @Test
    @DisplayName("超长文案：length 违规（siteTitle 上限 60）")
    void lengthLimit() {
        SiteConfigContent content = withTitle("标".repeat(61));
        List<ViolationDTO> violations = validator.validate(content);

        assertEquals(1, violations.size());
        assertEquals("length", violations.get(0).rule());
        assertEquals("siteTitle", violations.get(0).field());
    }

    @Test
    @DisplayName("AC-CHAT-005 关联：文案含脚本/事件属性/危险协议 → 发布即拒绝（存储层保持干净）")
    void rejectsDangerousContent() {
        assertRejected(withTitle("<script>alert(1)</script>"), "siteTitle");
        assertRejected(withTitle("<img src=x onerror=alert(1)>"), "siteTitle");
        assertRejected(withFooter("点我：javascript:alert(1)"), "footerDisclaimer");
        assertRejected(withFooter("<iframe src=\"https://evil\"></iframe>"), "footerDisclaimer");
    }

    @Test
    @DisplayName("URL 只允许 https 或站内相对路径；http / data / 协议相对一律拒绝")
    void urlProtocolSafety() {
        assertTrue(validator.validate(withLogo("https://cdn.example.com/a.png")).isEmpty());
        assertTrue(validator.validate(withLogo("/static/logo.svg")).isEmpty());
        assertTrue(validator.validate(withLogo("")).isEmpty(), "logoUrl 可空");

        assertEquals("insecureUrl", onlyViolation(withLogo("http://cdn.example.com/a.png")).rule());
        assertEquals("insecureUrl", onlyViolation(withLogo("//cdn.example.com/a.png")).rule());
        assertEquals("unsafeContent", onlyViolation(withLogo("data:text/html;base64,xxx")).rule());
    }

    // ===================== 辅助 =====================

    private void assertRejected(SiteConfigContent content, String field) {
        List<ViolationDTO> violations = validator.validate(content);
        assertTrue(violations.stream().anyMatch(v -> field.equals(v.field())
                        && "unsafeContent".equals(v.rule())),
                "应拒绝危险内容：" + violations);
    }

    private ViolationDTO onlyViolation(SiteConfigContent content) {
        List<ViolationDTO> violations = validator.validate(content);
        assertEquals(1, violations.size(), "预期只有一个违规项：" + violations);
        return violations.get(0);
    }

    private SiteConfigContent valid() {
        return new SiteConfigContent("站点标题", "https://cdn.example.com/logo.png",
                "https://cdn.example.com/favicon.ico", "欢迎语", "请输入内容",
                "登录", "注册", "新对话", "暂无会话", "暂不可用", "免责声明", "#2E6BE6");
    }

    private SiteConfigContent withTitle(String siteTitle) {
        SiteConfigContent v = valid();
        return new SiteConfigContent(siteTitle, v.logoUrl(), v.faviconUrl(), v.welcomeText(),
                v.inputPlaceholder(), v.loginText(), v.registerText(), v.newChatText(),
                v.emptySessionText(), v.agentUnavailableText(), v.footerDisclaimer(), v.themePrimaryColor());
    }

    private SiteConfigContent withFooter(String footer) {
        SiteConfigContent v = valid();
        return new SiteConfigContent(v.siteTitle(), v.logoUrl(), v.faviconUrl(), v.welcomeText(),
                v.inputPlaceholder(), v.loginText(), v.registerText(), v.newChatText(),
                v.emptySessionText(), v.agentUnavailableText(), footer, v.themePrimaryColor());
    }

    private SiteConfigContent withLogo(String logoUrl) {
        SiteConfigContent v = valid();
        return new SiteConfigContent(v.siteTitle(), logoUrl, v.faviconUrl(), v.welcomeText(),
                v.inputPlaceholder(), v.loginText(), v.registerText(), v.newChatText(),
                v.emptySessionText(), v.agentUnavailableText(), v.footerDisclaimer(), v.themePrimaryColor());
    }
}
