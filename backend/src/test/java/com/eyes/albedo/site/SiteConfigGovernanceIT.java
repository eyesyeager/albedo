package com.eyes.albedo.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.site.dto.SiteConfigContent;
import com.eyes.albedo.site.dto.SiteConfigDTO;
import com.eyes.albedo.site.dto.SiteConfigVersionDTO;
import com.eyes.albedo.site.service.SiteConfigService;
import com.eyes.albedo.support.TempTenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 站点配置治理测试：草稿 → 校验 → 发布 → 回滚（AC-CFG-002 / EX-005 / EX-012）。
 *
 * <p>M1 只交付 Service 层能力（管理端 REST 契约由 @架构师 在 M2 补全），
 * 因此本测试直接驱动 {@link SiteConfigService}，在<b>临时租户</b>上验证真实数据库行为。
 */
@SpringBootTest
class SiteConfigGovernanceIT {

    private static final long OPERATOR_UID = 900000031L;

    @Autowired
    private SiteConfigService siteConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TempTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = TempTenant.create(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        tenant.close();
    }

    @Test
    @DisplayName("未发布任何配置 → 读取抛 30012（站点配置不可用）")
    void noPublishedConfig() {
        assertEquals(ErrorCode.TENANT_CONFIG_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> siteConfigService.currentConfig()).getCode());
        assertTrue(!siteConfigService.hasUsableConfig());
    }

    @Test
    @DisplayName("发布成功：生成版本 1 并切换指针，读取生效")
    void publishCreatesVersionAndSwitchesPointer() {
        long version = siteConfigService.publish(valid("初版标题"), tenant.currentTenantVersion(), OPERATOR_UID);
        assertEquals(1L, version);
        assertEquals(1L, tenant.currentConfigVersion(), "指针必须指向新版本");

        tenant.rebind();
        SiteConfigDTO config = siteConfigService.currentConfig();
        assertEquals("初版标题", config.siteTitle());
        assertEquals(1L, config.configVersion());
        assertEquals(tenant.tenantId(), config.tenantId());
    }

    @Test
    @DisplayName("EX-005 / AC-CFG-002：校验失败 → 30021，且线上版本保持不变")
    void publishValidationFailureKeepsOnlineVersion() {
        siteConfigService.publish(valid("线上版本"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();

        BusinessException e = assertThrows(BusinessException.class,
                () -> siteConfigService.publish(valid("<script>x</script>"),
                        tenant.currentTenantVersion(), OPERATOR_UID));
        assertEquals(ErrorCode.PUBLISH_VALIDATE_FAILED, e.getCode());
        assertTrue(e.getPayload() != null, "必须返回 violations 分类");

        tenant.rebind();
        // 🔴 线上仍是上一个成功版本
        assertEquals("线上版本", siteConfigService.currentConfig().siteTitle());
        assertEquals(1L, tenant.currentConfigVersion());
    }

    @Test
    @DisplayName("EX-012：并发发布（版本令牌过期）→ 30020 且带差异摘要，不覆盖他人结果")
    void concurrentPublishConflict() {
        int staleVersion = tenant.currentTenantVersion();
        siteConfigService.publish(valid("先提交者"), staleVersion, OPERATOR_UID);
        tenant.rebind();

        BusinessException e = assertThrows(BusinessException.class,
                () -> siteConfigService.publish(valid("后提交者"), staleVersion, OPERATOR_UID));
        assertEquals(ErrorCode.VERSION_CONFLICT, e.getCode());

        tenant.rebind();
        assertEquals("先提交者", siteConfigService.currentConfig().siteTitle(),
                "🔴 后提交者不得静默覆盖先提交者");
    }

    @Test
    @DisplayName("草稿不影响线上；草稿基线为当前已发布内容（PRD 规则 9）")
    void draftDoesNotAffectOnline() {
        siteConfigService.publish(valid("已发布"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();

        siteConfigService.saveDraft(valid("草稿中"), tenant.currentTenantVersion());
        tenant.rebind();

        assertEquals("已发布", siteConfigService.currentConfig().siteTitle(), "草稿不得影响线上");
        assertEquals("草稿中", siteConfigService.loadDraft().siteTitle(), "草稿应可回读");
    }

    @Test
    @DisplayName("AC-CFG-002：回滚生成新版本并切指针，历史行不被修改")
    void rollbackCreatesNewVersion() {
        siteConfigService.publish(valid("第一版"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();
        siteConfigService.saveDraft(valid("第二版"), tenant.currentTenantVersion());
        tenant.rebind();
        long second = siteConfigService.publish(valid("第二版"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();
        assertEquals("第二版", siteConfigService.currentConfig().siteTitle());

        long rolledBack = siteConfigService.rollback(1L, tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();

        assertNotEquals(1L, rolledBack, "🔴 回滚必须生成新版本号，不能复用历史版本号");
        assertTrue(rolledBack > second, "新版本号必须递增：" + rolledBack);
        assertEquals("第一版", siteConfigService.currentConfig().siteTitle(), "内容回到历史版本");

        // 历史行仍在，内容未被修改
        String v1Title = jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(content,'$.siteTitle')) FROM site_config_versions"
                        + " WHERE tenant_id = ? AND version = 1", String.class, tenant.tenantId());
        assertEquals("第一版", v1Title);
    }

    @Test
    @DisplayName("版本列表倒序返回，并标记当前生效版本")
    void listVersions() {
        siteConfigService.publish(valid("v1"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();
        siteConfigService.saveDraft(valid("v2"), tenant.currentTenantVersion());
        tenant.rebind();
        siteConfigService.publish(valid("v2"), tenant.currentTenantVersion(), OPERATOR_UID);
        tenant.rebind();

        List<SiteConfigVersionDTO> versions = siteConfigService.listVersions(1, 20);
        assertEquals(2, versions.size());
        assertEquals(2L, versions.get(0).version(), "必须按版本号倒序");
        assertTrue(versions.get(0).current(), "首项应为当前生效版本");
        assertTrue(!versions.get(1).current());
    }

    @Test
    @DisplayName("校验接口独立可用（发布前二次确认用）")
    void validateWithoutPublishing() {
        assertTrue(siteConfigService.validate(valid("合法")).isEmpty());
        assertTrue(!siteConfigService.validate(valid("")).isEmpty());
    }

    private SiteConfigContent valid(String siteTitle) {
        return new SiteConfigContent(siteTitle, "https://cdn.example.com/logo.png",
                "https://cdn.example.com/favicon.ico", "欢迎语", "请输入内容",
                "登录", "注册", "新对话", "暂无会话", "暂不可用", "免责声明", "#2E6BE6");
    }
}
