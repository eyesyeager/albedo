package com.eyes.albedo.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.support.TestAuthConfig;
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
 * M3 新增两个接口的<b>实测取证</b>（供 @测试 与签署材料引用，非断言型用例）。
 *
 * <p>🔴 为什么单独一个用例：交付回报需要"接口的<b>真实响应体</b>"，
 * 而正常用例只断言字段、不打印整体响应。本用例把响应原样打印到测试日志，
 * 使证据可复现（🔴 数据全部写在临时租户，取证完即清理，不污染 gift / redbook 验收种子）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class M3EndpointEvidenceIT {

    private static final long UID = 900000121L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tenantId;
    private String host;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = "ev" + suffix;
        host = "ev-" + suffix + ".test.invalid";
        jdbcTemplate.update("INSERT INTO tenants (tenant_id, name, primary_host, status, timezone,"
                        + " locale, config_version, version)"
                        + " VALUES (?,?,?,'enabled','Asia/Shanghai','zh-CN',0,0)",
                tenantId, "取证租户", host);
        jdbcTemplate.update("INSERT INTO tenant_domains (host, tenant_id, is_primary, status)"
                + " VALUES (?,?,1,'active')", host, tenantId);
        jdbcTemplate.update("INSERT INTO tenant_users (tenant_id, uid, tenant_role, status)"
                        + " VALUES (?,?,?,'active')",
                tenantId, UID, TenantRoleEnum.TENANT_ADMIN.name());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM analytics_events WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_users WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenant_domains WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tenants WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("取证｜POST /api/v1/events 与 GET /api/v1/admin/metrics/usage 的真实响应")
    void printRealResponses() throws Exception {
        String clientEventId = "ce" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20);
        String eventBody = "{\"events\":["
                + "{\"clientEventId\":\"" + clientEventId + "\",\"eventName\":\"messageSend\","
                + "\"occurredAt\":\"" + Instant.now().toString() + "\",\"charCount\":42,"
                + "\"source\":\"newChat\",\"pagePath\":\"/chat/new?authorization=xxx\"},"
                // 第二条：重复的 clientEventId → duplicated
                + "{\"clientEventId\":\"" + clientEventId + "\",\"eventName\":\"messageSend\","
                + "\"occurredAt\":\"" + Instant.now().toString() + "\"},"
                // 第三条：白名单外事件名 → discarded
                + "{\"clientEventId\":\"ce" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20) + "\",\"eventName\":\"notWhitelisted\","
                + "\"occurredAt\":\"" + Instant.now().toString() + "\"}"
                + "]}";

        MvcResult events = mockMvc.perform(post("/api/v1/events")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody))
                .andReturn();
        print("POST /api/v1/events", events);

        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        MvcResult usage = mockMvc.perform(get("/api/v1/admin/metrics/usage")
                        .header(HttpHeaders.HOST, host)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID)
                        .param("from", to.minus(1, ChronoUnit.DAYS).toString())
                        .param("to", to.toString())
                        .param("granularity", "day"))
                .andReturn();
        print("GET /api/v1/admin/metrics/usage", usage);
    }

    private void print(String label, MvcResult result) throws Exception {
        String body = new String(result.getResponse().getContentAsByteArray(),
                StandardCharsets.UTF_8);
        System.out.println("===== EVIDENCE " + label + " status=" + result.getResponse().getStatus());
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(body)));
        System.out.println("===== EVIDENCE END");
    }
}
