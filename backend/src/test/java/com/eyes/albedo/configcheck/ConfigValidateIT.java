package com.eyes.albedo.configcheck;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyes.albedo.support.TestAuthConfig;
import com.eyes.eyesAuth.constant.AuthConfigConstant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 独立配置校验入口测试（api-spec §7.3.1 / REQ-CFG-003 / AC-CFG-003 / AC-CFG-004 / EX-031 / EX-032）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>合法配置 → {@code code=0}（warnings 不阻断）</li>
 *   <li>非法配置 → {@code 30060} + 字段级 {@code violations[]}（🔴 不含密钥 / 内部地址 / 堆栈）</li>
 *   <li>🔴 跨租户 objectId → {@code 10004}（不泄露存在性，AC-TEN-004）</li>
 *   <li>{@code objectType} 非法 → {@code 10001}；租户角色不足 → {@code 10003}</li>
 *   <li>🔴 MCP：{@code transport=stdio} / 非 HTTPS / 内网地址 / 凭据密文非法 → {@code 30060}</li>
 * </ul>
 *
 * <p>数据纪律：全部数据写在<b>临时租户</b>下（gift/redbook 是验收种子数据，不得污染），
 * 并在 {@code @AfterEach} 按 {@code tenant_id} 精确清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class ConfigValidateIT {

    private static final String PATH = "/api/v1/admin/config/validate";
    private static final long ADMIN_UID = 900000211L;
    private static final long OTHER_TENANT_ADMIN_UID = 900000212L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantA;
    private String hostA;
    private String tenantB;
    private String hostB;

    @BeforeEach
    void setUp() {
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        tenantA = "cva" + suffix;
        hostA = "cva-" + suffix + ".test.invalid";
        tenantB = "cvb" + suffix;
        hostB = "cvb-" + suffix + ".test.invalid";
        createTenant(tenantA, hostA);
        createTenant(tenantB, hostB);
        // 租户管理员身份（校验入口要求 TENANT_ADMIN / TENANT_OPERATOR）
        grantTenantAdmin(tenantA, ADMIN_UID);
        grantTenantAdmin(tenantB, OTHER_TENANT_ADMIN_UID);
    }

    @AfterEach
    void cleanUp() {
        for (String tenant : new String[]{tenantA, tenantB}) {
            jdbcTemplate.update("DELETE FROM skill_versions WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM skills WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM mcp_tools WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM mcp_servers WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_tool_grants WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenant);
            jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenant);
        }
        jdbcTemplate.update("DELETE FROM local_tools WHERE tool_key LIKE ?", "it_cv_%");
    }

    @Test
    @DisplayName("AC-CFG-003：合法 Skill 版本 → code=0（未使用变量只进 warnings，不阻断）")
    void validSkillVersionPasses() throws Exception {
        long skillId = insertSkill(tenantA, "greeter", 1);
        long versionId = insertSkillVersion(tenantA, skillId, 1,
                "你好 {{city}} 的用户，请用简洁语言回答。",
                "[{\"name\":\"city\",\"required\":true},{\"name\":\"unusedOne\",\"required\":false}]");

        JsonNode data = ok("skillVersion", String.valueOf(versionId));
        assertTrue(data.get("valid").asBoolean(), "合法配置必须 valid=true：" + data);
        assertTrue(data.get("violations").isEmpty());
        assertFalse(data.get("warnings").isEmpty(), "声明但未使用的变量应进 warnings");
        assertEquals("unusedVariable", data.get("warnings").get(0).get("rule").asText());
    }

    @Test
    @DisplayName("🔴 EX-031：Skill 引用未声明变量 → 30060 + violations[rule=undeclaredVariable]")
    void undeclaredVariableRejected() throws Exception {
        long skillId = insertSkill(tenantA, "broken", 1);
        long versionId = insertSkillVersion(tenantA, skillId, 1,
                "你好 {{userName}}，请回答。", "[]");

        JsonNode data = expect("skillVersion", String.valueOf(versionId), 30060);
        assertFalse(data.get("valid").asBoolean());
        assertTrue(hasRule(data.get("violations"), "undeclaredVariable"),
                "必须给出 undeclaredVariable：" + data.get("violations"));
        assertEquals("instruction", data.get("violations").get(0).get("field").asText());
    }

    @Test
    @DisplayName("🔴 Skill 变量占用平台保留名（tenantId/locale/timezone/nowIso）→ 30060")
    void reservedVariableRejected() throws Exception {
        long skillId = insertSkill(tenantA, "reserved", 1);
        long versionId = insertSkillVersion(tenantA, skillId, 1,
                "当前租户 {{tenantId}}", "[{\"name\":\"tenantId\",\"required\":false}]");

        JsonNode data = expect("skillVersion", String.valueOf(versionId), 30060);
        assertTrue(hasRule(data.get("violations"), "reservedVariable"));
    }

    @Test
    @DisplayName("🔴 EX-032：MCP transport=stdio / 非 HTTPS / 内网地址 / 密文非法 → 30060，且不回显内部信息")
    void mcpConfigViolations() throws Exception {
        long mcpId = insertMcp(tenantA, "crm", "stdio", "http://10.0.0.5:8080/v1",
                "bearer", "not-a-cipher", 999);

        JsonNode data = expect("mcp", String.valueOf(mcpId), 30060);
        JsonNode violations = data.get("violations");
        assertTrue(hasRule(violations, "transportForbidden"), "stdio 必须被拒绝：" + violations);
        assertTrue(hasRule(violations, "ssrfRejected"), "非 HTTPS/内网地址必须被拒绝");
        assertTrue(hasRule(violations, "invalidCipher"), "凭据密文非法必须被拒绝");
        assertTrue(hasRule(violations, "outOfRange"), "超时越界必须被拒绝");

        // 🔴 禁含内部 IP / 端口 / 密文片段
        String json = violations.toString();
        assertFalse(json.contains("10.0.0.5"), "🔴 不得回显解析出的内网地址：" + json);
        assertFalse(json.contains("8080"), "🔴 不得回显端口");
        assertFalse(json.contains("not-a-cipher"), "🔴 不得回显密文片段");
    }

    @Test
    @DisplayName("合法 MCP（HTTPS + 有效密文 + 合法超时）→ code=0")
    void validMcpPasses() throws Exception {
        // 密文由 CredentialCipher 规则生成：v1:{iv}:{ct||tag}（此处只需格式合法即通过格式校验）
        String cipher = new com.eyes.albedo.mcp.CredentialCipher("albedo-test-crypto-secret-32bytes!!!")
                .encrypt("sk-live-abcdef123456", tenantA, "crm");
        long mcpId = insertMcp(tenantA, "crm", "streamable_http", "https://mcp.example.com/v1",
                "bearer", cipher, 30);

        JsonNode data = ok("mcp", String.valueOf(mcpId));
        assertTrue(data.get("valid").asBoolean(), "合法 MCP 必须通过：" + data.get("violations"));
    }

    @Test
    @DisplayName("🔴 AC-TEN-004：跨租户 objectId → 10004（不泄露存在性）")
    void crossTenantObjectReturnsNotFound() throws Exception {
        long skillId = insertSkill(tenantB, "other-tenant-skill", 0);

        // 以租户 A 的 Host + A 的管理员访问 B 的对象
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("skill", String.valueOf(skillId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10004)))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("objectType 非法 → 10001；objectId 非数值 → 10004（不通过报错差异探测 ID 空间）")
    void parameterValidation() throws Exception {
        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("unknownType", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10001)));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("skill", "not-a-number")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10004)));
    }

    @Test
    @DisplayName("租户内角色不足（END_USER）→ 10003")
    void endUserRejected() throws Exception {
        long skillId = insertSkill(tenantA, "role-check", 0);
        long endUserUid = 900000213L;

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, endUserUid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("skill", String.valueOf(skillId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)));

        jdbcTemplate.update("DELETE FROM tenant_users WHERE tenant_id = ? AND uid = ?",
                tenantA, endUserUid);
    }

    @Test
    @DisplayName("🔴 平台作用域对象 localTool：非平台管理员 → 10003；平台管理员可校验")
    void localToolRequiresPlatformAdmin() throws Exception {
        long toolId = insertLocalTool("it_cv_refund",
                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}", 30);

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("localTool", String.valueOf(toolId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)));

        mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("localTool", String.valueOf(toolId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)));
    }

    @Test
    @DisplayName("🔴 本地 Tool input_schema 根类型非 object / 超时越界 → 30060")
    void localToolSchemaViolations() throws Exception {
        long toolId = insertLocalTool("it_cv_bad", "{\"type\":\"array\"}", 9999);

        MvcResult result = mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("localTool", String.valueOf(toolId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30060)))
                .andReturn();
        JsonNode violations = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("violations");
        assertTrue(hasRule(violations, "invalidFormat"), "根类型必须为 object：" + violations);
        assertTrue(hasRule(violations, "outOfRange"), "超时越界必须被拒绝");
    }

    @Test
    @DisplayName("🔴 toolGrant.config 出现脚本/表达式片段 → 30060 rule=executableConfig")
    void executableGrantConfigRejected() throws Exception {
        insertLocalTool("it_cv_grant", "{\"type\":\"object\"}", 30);
        long grantId = insertToolGrant(tenantA, "it_cv_grant",
                "{\"callback\":\"javascript:alert(1)\"}");

        JsonNode data = expect("toolGrant", String.valueOf(grantId), 30060);
        assertTrue(hasRule(data.get("violations"), "executableConfig"));
    }

    @Test
    @DisplayName("🔴 Skill 版本引用其他租户的 Skill → 30060 rule=crossTenantReference")
    void crossTenantSkillReference() throws Exception {
        long skillInB = insertSkill(tenantB, "b-skill", 0);
        // 在租户 A 下插入一条指向 B 的 skill_id 的版本（模拟 DBA 误写库）
        long versionId = insertSkillVersion(tenantA, skillInB, 1, "正文", "[]");

        JsonNode data = expect("skillVersion", String.valueOf(versionId), 30060);
        assertTrue(hasRule(data.get("violations"), "crossTenantReference"));
    }

    // ===================== 辅助 =====================

    private JsonNode ok(String objectType, String objectId) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(objectType, objectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode expect(String objectType, String objectId, int code) throws Exception {
        MvcResult result = mockMvc.perform(post(PATH)
                        .header(HttpHeaders.HOST, hostA)
                        .header(TestAuthConfig.HEADER_TEST_UID, ADMIN_UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, AuthConfigConstant.ROLE_ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(objectType, objectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(code)))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private boolean hasRule(JsonNode violations, String rule) {
        for (JsonNode node : violations) {
            if (rule.equals(node.get("rule").asText())) {
                return true;
            }
        }
        return false;
    }

    private String requestBody(String objectType, String objectId) throws Exception {
        return objectMapper.writeValueAsString(
                java.util.Map.of("objectType", objectType, "objectId", objectId));
    }

    private void createTenant(String tenantId, String host) {
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "配置校验测试租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
    }

    private void grantTenantAdmin(String tenantId, long uid) {
        jdbcTemplate.update("INSERT INTO tenant_users (tenant_id, uid, tenant_role, status)"
                + " VALUES (?,?,'TENANT_ADMIN','active')", tenantId, uid);
    }

    private long insertSkill(String tenantId, String skillKey, int currentVersion) {
        jdbcTemplate.update("INSERT INTO skills (tenant_id, skill_key, name, description, status,"
                        + " current_version, version) VALUES (?,?,?,'','disabled',?,0)",
                tenantId, skillKey, skillKey, currentVersion);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM skills WHERE tenant_id = ? AND skill_key = ?",
                Long.class, tenantId, skillKey);
    }

    private long insertSkillVersion(String tenantId, long skillId, int version,
                                    String instruction, String variablesSchema) {
        jdbcTemplate.update("INSERT INTO skill_versions (tenant_id, skill_id, version, instruction,"
                        + " variables_schema, output_constraint, status)"
                        + " VALUES (?,?,?,?,?,'','published')",
                tenantId, skillId, version, instruction, variablesSchema);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM skill_versions WHERE tenant_id = ? AND skill_id = ? AND version = ?",
                Long.class, tenantId, skillId, version);
    }

    private long insertMcp(String tenantId, String mcpKey, String transport, String endpoint,
                           String authType, String cipher, int timeoutSeconds) {
        jdbcTemplate.update("INSERT INTO mcp_servers (tenant_id, mcp_key, name, transport, endpoint,"
                        + " auth_type, credential_cipher, credential_last4, credential_key_version,"
                        + " timeout_seconds, status, version)"
                        + " VALUES (?,?,?,?,?,?,?,'1234',1,?,'enabled',0)",
                tenantId, mcpKey, mcpKey, transport, endpoint, authType, cipher, timeoutSeconds);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM mcp_servers WHERE tenant_id = ? AND mcp_key = ?",
                Long.class, tenantId, mcpKey);
    }

    private long insertLocalTool(String toolKey, String inputSchema, int timeoutSeconds) {
        jdbcTemplate.update("INSERT INTO local_tools (tool_key, name, version, description,"
                        + " input_schema, risk_level, idempotent, timeout_seconds, status)"
                        + " VALUES (?,?,1,'',?,'high',0,?,'enabled')",
                toolKey, toolKey, inputSchema, timeoutSeconds);
        return jdbcTemplate.queryForObject("SELECT id FROM local_tools WHERE tool_key = ?",
                Long.class, toolKey);
    }

    private long insertToolGrant(String tenantId, String toolKey, String config) {
        jdbcTemplate.update("INSERT INTO tenant_tool_grants (tenant_id, tool_key, granted, config,"
                + " status) VALUES (?,?,1,?,'enabled')", tenantId, toolKey, config);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tenant_tool_grants WHERE tenant_id = ? AND tool_key = ?",
                Long.class, tenantId, toolKey);
    }
}
