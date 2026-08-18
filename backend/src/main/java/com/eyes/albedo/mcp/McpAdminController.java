package com.eyes.albedo.mcp;

import com.eyes.albedo.auth.TenantRole;
import com.eyes.albedo.auth.TenantRoleEnum;
import com.eyes.albedo.auth.TenantRoleGuard;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.mcp.dto.McpConnectionTestResult;
import com.eyes.albedo.mcp.dto.McpDiscoveryReport;
import com.eyes.albedo.mcp.dto.McpServerDetailDTO;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.repository.McpServerRepository;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 治理接口（api-spec §7.4，REQ-MCP-001 / REQ-MCP-002）。
 *
 * <p>🔴 <b>一期没有管理 UI</b>（DEC-010）：本控制器的三个端点是<b>契约已登记项</b>，
 * 验收方式为「接口实测 + 数据核验」（PRD §8.10 第 7 条）。
 * "没有页面"不构成缺陷，但"接口不存在 / 没有 {@code @TenantRole} 准入 / 没有审计"是缺陷。
 *
 * <p>🔴 <b>租户隔离</b>：三个端点都要求已建立租户上下文；
 * 跨租户 {@code mcpId} 一律按 {@code 10004} 处理且<b>不泄露存在性</b>（AC-TEN-004）——
 * 靠 {@code findOneById}（派生查询会被 Hibernate 追加 {@code tenant_id}）实现，
 * 🔴 <b>禁止</b> {@code findById}（主键直载不追加租户条件，M2-min 已实测踩坑）。
 *
 * <p>🔴 <b>响应纪律</b>：一律 HTTP 200 + 数字 {@code code} + {@code timestamp}；
 * {@code mcpId} 对外 string；不回显凭据明文 / 密文片段 / 解析出的 IP。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/mcp")
public class McpAdminController {

    private final McpServerRepository mcpServerRepository;
    private final McpCredentialResolver credentialResolver;
    private final McpToolGrantService grantService;
    private final McpConnectionTester connectionTester;
    private final McpDiscoveryService discoveryService;
    private final TenantRoleGuard tenantRoleGuard;

    public McpAdminController(McpServerRepository mcpServerRepository,
                              McpCredentialResolver credentialResolver,
                              McpToolGrantService grantService,
                              McpConnectionTester connectionTester,
                              McpDiscoveryService discoveryService,
                              TenantRoleGuard tenantRoleGuard) {
        this.mcpServerRepository = mcpServerRepository;
        this.credentialResolver = credentialResolver;
        this.grantService = grantService;
        this.connectionTester = connectionTester;
        this.discoveryService = discoveryService;
        this.tenantRoleGuard = tenantRoleGuard;
    }

    /**
     * MCP 配置只读查询（api-spec §7.4.1）。
     *
     * <p>错误码：{@code 10003}、{@code 10004}（跨租户/不存在）、{@code 20001~20005}、
     * {@code 30010/30011}、{@code 50003}。
     */
    @Permission(PermissionEnum.USER)
    @TenantRole({TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR})
    @GetMapping("/{mcpId}")
    public Result<McpServerDetailDTO> detail(@PathVariable String mcpId) {
        McpServer server = require(mcpId);
        return Result.success(toDetail(server));
    }

    /**
     * 连接测试（api-spec §7.4.2）。
     *
     * <p>🔴 诊断结论以 {@code code=0} + {@code data.result} 承载；
     * <b>唯一例外</b> {@code ssrf_rejected} → {@code 30050}（EX-029），
     * 且此时 {@code data.result} 仍然给出（前端据此展示"地址不被允许"）。
     *
     * <p>错误码：{@code 10003}、{@code 10004}、{@code 20001~20005}、{@code 30010/30011}、
     * {@code 30050}（SSRF 拒绝）、{@code 30060}（配置非法，如 {@code transport=stdio} /
     * 凭据密文非法）、{@code 50003}（审计写入失败 → 整体失败，EX-024）。
     */
    @Permission(PermissionEnum.USER)
    @TenantRole({TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR})
    @PostMapping("/{mcpId}/test")
    public Result<McpConnectionTestResult> test(@PathVariable String mcpId) {
        McpServer server = require(mcpId);
        McpConnectionTestResult result = connectionTester.test(server);
        if (McpCheckResult.SSRF_REJECTED.literal().equals(result.result())) {
            // 🔴 在审计与 last_check 已提交之后才抛：否则回滚会让审计消失（EX-029 / AC-AUD-003）
            throw new BusinessException(ErrorCode.TOOL_DENIED, SsrfGuard.REJECT_MESSAGE, result);
        }
        return Result.success(result);
    }

    /**
     * 工具发现（api-spec §7.4.3）。
     *
     * <p>🔴 新发现工具一律 {@code granted=false} + {@code disabled}；
     * {@code schema_changed} 的已授权工具自动降级；{@code removed} 保留历史行。
     *
     * <p>错误码：{@code 10001}、{@code 10003}、{@code 10004}、{@code 20001~20005}、
     * {@code 30010/30011}、{@code 30050}（SSRF）、{@code 30052}（连接/协议/鉴权失败）、
     * {@code 30060}（配置非法 / 工具数超限）、{@code 50003}。
     */
    @Permission(PermissionEnum.USER)
    @TenantRole({TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR})
    @PostMapping("/{mcpId}/discover")
    public Result<McpDiscoveryReport> discover(@PathVariable String mcpId,
                                              @RequestBody(required = false) DiscoverRequest request) {
        McpServer server = require(mcpId);
        boolean dryRun = request != null && Boolean.TRUE.equals(request.dryRun());
        return Result.success(discoveryService.discover(server, dryRun));
    }

    /**
     * 发现请求体。
     *
     * @param dryRun {@code true} 时只返回比对结果不落库（默认 {@code false}）
     */
    public record DiscoverRequest(Boolean dryRun) {
    }

    /**
     * 加载当前租户的 MCP 配置（含租户内角色<b>程序化兜底</b>校验）。
     *
     * <p>🔴 跨租户 / 不存在一律 {@code 10004}（不泄露存在性）。
     *
     * <p>🔴 <b>为什么除了 {@code @TenantRole} 还要程序化校验一遍</b>（api-spec §3 /
     * architecture.md §8.2.1 已升格为<b>全局纪律</b>，AR-018）：{@code TenantRoleAspect} 由
     * {@code EyesAuthConfig} 装配，而后者带 {@code @ConditionalOnProperty(eyes-auth.enabled=true)}。
     * 一旦该开关为 {@code false}（测试 profile 即如此，生产也可能被误配），
     * 切面<b>整个不注册</b>，注解就成了"看起来有准入、其实全放行"的装饰 ——
     * 这类失效不会报错，只会静默越权。因此统一走 {@link TenantRoleGuard} 做 fail-closed
     * 二次判定（🔴 禁止各控制器各写一份），两条路径的失败码都是 {@code 10003}。
     */
    private McpServer require(String mcpId) {
        authorize();
        long id = Ids.parse(mcpId);
        return mcpServerRepository.findOneById(id)
                .filter(server -> server.getDeletedAt() == null)
                .orElseThrow(BusinessException::notFound);
    }

    /**
     * 租户内角色准入（{@code TENANT_ADMIN} / {@code TENANT_OPERATOR}），不足 → {@code 10003}。
     *
     * <p>🔴 唯一实现在 {@link TenantRoleGuard}（含 {@code TenantContext.requireEnabled()}）。
     */
    private void authorize() {
        tenantRoleGuard.require(TenantRoleEnum.TENANT_ADMIN, TenantRoleEnum.TENANT_OPERATOR);
    }

    private McpServerDetailDTO toDetail(McpServer server) {
        McpCredentialResolver.CredentialView view = credentialResolver.view(server);
        return new McpServerDetailDTO(
                String.valueOf(server.getId()),
                server.getMcpKey(),
                server.getName(),
                server.getTransport(),
                server.getEndpoint(),
                server.getAuthType(),
                new McpServerDetailDTO.CredentialDTO(view.configured(), view.last4(),
                        view.keyVersion(), view.updatedAt()),
                server.getTimeoutSeconds() == null ? 0 : server.getTimeoutSeconds(),
                server.getStatus(),
                server.getLastCheckStatus(),
                server.getLastCheckResult(),
                TimeFormat.iso(server.getLastCheckedAt()),
                grantService.listGrantedToolKeys(server.getId()),
                server.getVersion() == null ? 0 : server.getVersion());
    }
}
