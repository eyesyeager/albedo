package com.eyes.albedo.membership;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eyes.albedo.support.TestAuthConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 惰性建户与当前用户接口测试。
 *
 * <p>覆盖：AC-AUTH-005（同一 uid 在两租户形成独立成员关系）、AC-AUTH-006 / EX-009（禁用成员不恢复）、
 * AC-AUTH-007（平台管理员不自动获得租户内角色）、api-spec §4.3.1（不返回手机号/邮箱/token）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestAuthConfig.class)
class MembershipApiIT {

    private static final String HOST_GIFT = "localhost:5173";
    private static final String HOST_REDBOOK = "127.0.0.1:5173";
    private static final long UID = 900000021L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM tenant_users WHERE uid = ?", UID);
        evictRoleCache();
    }

    @Test
    @DisplayName("AC-AUTH-005：同一 uid 首次访问两个租户 → 形成两条独立成员关系")
    void lazyMembershipPerTenant() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.uid", is(String.valueOf(UID))))
                .andExpect(jsonPath("$.data.tenantRole", is("END_USER")))
                .andExpect(jsonPath("$.data.memberStatus", is("active")))
                // 🔴 隐私红线
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(jsonPath("$.data.phone").doesNotExist())
                .andExpect(jsonPath("$.data.token").doesNotExist());

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_REDBOOK)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(jsonPath("$.code", is(0)));

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant_users WHERE uid = ?", Integer.class, UID);
        assertEquals(2, rows, "两个租户必须各有一条独立成员关系");

        String giftTenant = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM tenant_users WHERE uid = ? AND tenant_id = 'gift'",
                String.class, UID);
        assertEquals("gift", giftTenant);
    }

    @Test
    @DisplayName("AC-AUTH-006 / EX-009：被禁用成员再次访问 → 10003，且状态不被自动恢复")
    void disabledMemberIsNotRestored() throws Exception {
        // 先建立成员关系
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(jsonPath("$.code", is(0)));

        // 管理员禁用（M2 管理接口的等效数据变更）
        jdbcTemplate.update("UPDATE tenant_users SET status = 'disabled' WHERE uid = ? AND tenant_id = 'gift'",
                UID);
        evictRoleCache();

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(10003)));

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tenant_users WHERE uid = ? AND tenant_id = 'gift'", String.class, UID);
        assertEquals("disabled", status, "🔴 再次访问不得自动恢复被禁用的成员");

        // 另一个租户不受影响（角色与状态互不继承）
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_REDBOOK)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(jsonPath("$.code", is(0)));
    }

    @Test
    @DisplayName("AC-AUTH-007：eyesUser ADMIN 被识别为平台管理员，但租户内角色仍是 END_USER")
    void platformAdminDoesNotInheritTenantRole() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, HOST_GIFT)
                        .header(TestAuthConfig.HEADER_TEST_UID, UID)
                        .header(TestAuthConfig.HEADER_TEST_ROLE, "ROLE_admin"))
                .andExpect(jsonPath("$.code", is(0)))
                .andExpect(jsonPath("$.data.platformAdmin", is(true)))
                // 🔴 平台管理员不自动获得任何租户内角色
                .andExpect(jsonPath("$.data.tenantRole", is("END_USER")));
    }

    @Test
    @DisplayName("未知 Host 下访问受保护接口 → 30010（租户上下文优先于身份判定）")
    void unknownHostBlocksProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.HOST, "unknown.invalid")
                        .header(TestAuthConfig.HEADER_TEST_UID, UID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(30010)));
    }

    private void evictRoleCache() {
        // 成员角色有短 TTL 缓存，测试中需主动失效以断言状态变更后的行为
        redis.delete(java.util.List.of(
                "albedo:test:gift:member:role:" + UID,
                "albedo:test:redbook:member:role:" + UID));
    }
}
