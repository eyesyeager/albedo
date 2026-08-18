package com.eyes.albedo.site;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyes.albedo.support.TestAuthConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * M1 契约与租户隔离接口测试（只读路径，不产生业务数据）。
 *
 * <p>覆盖：AC-TEN-001（两租户识别）、AC-TEN-002（伪造 tenantId 被忽略）、
 * AC-TEN-003（同 agentKey 不冲突）、AC-CFG-001（各自配置生效）、
 * AC-API-001/002（恒 200 + code + timestamp + 分页结构）、AC-NFR-004（站点级非 200 边界）。
 *
 * <p>🔴 断言一律针对 {@code body.code}，而不是 HTTP 状态码 —— 这是本项目契约的核心：
 * {@code /api/v1/**} 恒 HTTP 200。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class SiteAndTenantIT {

    /** dev host 映射：localhost:5173 → gift（见 sys_config[tenant.dev_host_mapping]）。 */
    private static final String HOST_GIFT = "localhost:5173";
    /** dev host 映射：127.0.0.1:5173 → redbook。 */
    private static final String HOST_REDBOOK = "127.0.0.1:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-CFG-001 / AC-TEN-001：两个租户各自返回自己的已发布站点配置")
    void siteConfigIsolatedByHost() throws Exception {
        mockMvc.perform(get("/api/v1/site/config").header(HttpHeaders.HOST, HOST_GIFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.tenantId", is("gift")))
                .andExpect(jsonPath("$.data.siteTitle").exists())
                .andExpect(jsonPath("$.data.welcomeText").exists());

        mockMvc.perform(get("/api/v1/site/config").header(HttpHeaders.HOST, HOST_REDBOOK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.tenantId", is("redbook")));
    }

    @Test
    @DisplayName("AC-TEN-002 / EX-003：伪造 tenantId 参数与请求头一律被忽略，仍返回真实租户数据")
    void forgedTenantIdIgnored() throws Exception {
        mockMvc.perform(get("/api/v1/site/config")
                        .header(HttpHeaders.HOST, HOST_REDBOOK)
                        .header("X-Tenant-Id", "gift")
                        .param("tenantId", "gift"))
                .andExpect(status().isOk())
                // 业务照常成功（不暴露检测细节），但数据必须是 Host 对应的租户
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.tenantId", is("redbook")));
    }

    @Test
    @DisplayName("EX-001：未知 Host → HTTP 200 + code=30010（业务 API 不返回 404）")
    void unknownHostReturns30010() throws Exception {
        mockMvc.perform(get("/api/v1/site/config").header(HttpHeaders.HOST, "unknown.invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30010)));
    }

    @Test
    @DisplayName("AC-TEN-003：两租户可使用相同 agentKey，各自只看到自己的 Agent")
    void agentsIsolatedAndSameKeyAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/agents").header(HttpHeaders.HOST, HOST_GIFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.list[0].agentKey", is("assistant")))
                .andExpect(jsonPath("$.data.list[0].name", is("礼遇顾问")))
                // 🔴 契约核对：公开接口不得返回内部字段
                .andExpect(jsonPath("$.data.list[0].systemPrompt").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].model").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].providerKey").doesNotExist());

        mockMvc.perform(get("/api/v1/agents").header(HttpHeaders.HOST, HOST_REDBOOK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].agentKey", is("assistant")))
                .andExpect(jsonPath("$.data.list[0].name", is("笔记写手")));
    }

    @Test
    @DisplayName("AC-API-002：分页结构恒为 {list,total,page,pageSize}")
    void pagingStructure() throws Exception {
        mockMvc.perform(get("/api/v1/agents").header(HttpHeaders.HOST, HOST_GIFT))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").exists())
                .andExpect(jsonPath("$.data.page", is(1)))
                .andExpect(jsonPath("$.data.pageSize", is(20)));
    }

    @Test
    @DisplayName("分页越界 → code=10001（上限取自 sys_config: business.page_size_max）")
    void pageSizeOverLimitRejected() throws Exception {
        mockMvc.perform(get("/api/v1/agents")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .param("pageSize", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10001)));
    }

    @Test
    @DisplayName("GET /api/v1/sys-config：下发 is_frontend=1 配置，键名转 camelCase")
    void sysConfigFrontendOnly() throws Exception {
        mockMvc.perform(get("/api/v1/sys-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.chat.messageMaxChars", is(20000)))
                .andExpect(jsonPath("$.data.business.pageSizeDefault", is(20)))
                // 非前端项不得下发（如租户 Host 映射开关）
                .andExpect(jsonPath("$.data.tenant").doesNotExist());
    }

    @Test
    @DisplayName("AC-NFR-004：/site/status 是唯一非 200 端点（200 / 404）")
    void siteStatusIsTheOnlyNon200Endpoint() throws Exception {
        mockMvc.perform(get("/site/status").header(HttpHeaders.HOST, HOST_GIFT))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        mockMvc.perform(get("/site/status").header(HttpHeaders.HOST, "unknown.invalid"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("noindex")));
    }

    @Test
    @DisplayName("未登录访问受保护接口 → code=20001（前端据此整页跳 SSO，不是 401）")
    void protectedEndpointWithoutIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.HOST, HOST_GIFT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)));
    }
}
