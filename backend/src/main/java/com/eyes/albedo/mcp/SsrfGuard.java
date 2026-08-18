package com.eyes.albedo.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import com.eyes.albedo.audit.AuditActions;
import com.eyes.albedo.audit.AuditEvent;
import com.eyes.albedo.audit.AuditResults;
import com.eyes.albedo.audit.AuditService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SSRF 校验（🔴 <b>ADR-009 的等价实现</b>，api-spec §7.6.3 / AC-MCP-004 / EX-029）。
 *
 * <p><b>REQ-MCP-001 / REQ-MCP-003</b>
 *
 * <p><b>🔴 为什么不做 pin IP（不要"顺手改回来"）</b>：
 * <pre>
 * JDK17 的两条硬限制（ADR-009 已裁决，api-spec V1.1.1 已回写）：
 *   ① 无法插拔 DNS 解析器 —— InetAddressResolverProvider 自 JDK18 起才存在
 *   ② 「以 IP 作为 URL host + 覆写 Host 头」不可行：Host 是受限请求头，
 *      且 IP-URL 会使 TLS 证书主机名校验必然失败；关闭端点识别 = 放弃 TLS 校验（更大的风险）
 * 👉 因此采纳等价实现：
 *   ① 调用前主动 InetAddress.getAllByName(host) 解析，逐 IP 比对 blocked/allowed CIDR；
 *      🔴 拒绝则不发起任何网络连接
 *   ② 校验通过后立即以 **原域名** 发起 HTTPS 连接（保留完整证书链校验与 SNI，不做任何降级）
 *   ③ 启动参数 -Dnetworkaddress.cache.ttl=10 使 ② 复用 ① 刚解析的结果，
 *      把 DNS 重绑定（TOCTOU）窗口压到 ≤10s（README §3.1 已写入启动命令）
 *   ④ 🔴 每次调用前都重新校验（§7.6.3 第 4 步）→ 攻击者必须在**每一次调用**上
 *      重新赢得 ≤10s 的竞态，而非一次成功即长期有效
 *   ⑤ followRedirects=NEVER（HttpClientConfig 已固定）→ 3xx 一律 30052，规避"302 跳内网"
 * 残余风险 AR-009 已知并接受；二期升 JDK18+ 后改用 InetAddressResolver 做严格 pin IP。
 * </pre>
 *
 * <p><b>🔴 双点位（ADR-009 决策 1）</b>：
 * ① 校验入口（{@code /admin/config/validate}、{@code /admin/mcp/{id}/test}、{@code /discover}）
 * ② <b>每次 {@code tools/call} 之前</b>的运行时兜底 ——
 * 因为一期 MCP 配置由 DBA 直接 {@code UPDATE} 写库（DEC-010），
 * 🔴 "只在保存时校验"在本项目<b>必然失效</b>（根本没有"保存"这个应用层入口）。
 *
 * <p><b>🔴 信息泄露纪律</b>：拒绝时对外只说"服务地址不在允许范围内"，
 * <b>不回显</b>解析出的 IP / 端口 / 内网信息（api-spec §7.3.1、§7.4.2）；
 * 解析结果只允许出现在本地 DEBUG 日志。
 */
@Slf4j
@Component
public class SsrfGuard {

    /** 仅允许的默认 HTTPS 端口（api-spec §7.6.3：端口仅允许 443 与白名单场景显式端口）。 */
    private static final int HTTPS_DEFAULT_PORT = 443;

    /** 对外统一措辞（🔴 全部拒绝原因共用一句，避免通过差异化文案探测内网拓扑）。 */
    public static final String REJECT_MESSAGE = "服务地址不在允许范围内";

    private final ConfigService configService;
    private final BusinessConfig businessConfig;
    private final AuditService auditService;

    public SsrfGuard(ConfigService configService,
                     BusinessConfig businessConfig,
                     AuditService auditService) {
        this.configService = configService;
        this.businessConfig = businessConfig;
        this.auditService = auditService;
    }

    /**
     * 审计事务边界（🔴 由调用方选择，ADR-010 / api-spec §7.14）。
     */
    public enum AuditMode {
        /**
         * <b>非流式</b>安全操作（连接测试 / 工具发现 / 配置校验）：审计与业务<b>同一事务</b>，
         * 审计失败 → 整体失败（{@code 50003}，EX-024）。
         */
        SAME_TRANSACTION,
        /**
         * <b>流式内</b>安全事件（每次调用前的运行时兜底）：<b>独立短事务</b>；
         * 🔴 审计失败只使该次工具调用失败，<b>绝不中断 SSE 流</b>。
         */
        NEW_TRANSACTION,
        /** 不写审计（仅供纯判定场景，如批量配置校验聚合 violations）。 */
        NONE
    }

    /**
     * 校验结论（🔴 不含解析出的 IP —— 结论本身就是脱敏后的）。
     *
     * @param allowed      是否放行
     * @param whitelisted  是否命中平台内网白名单（决定端口豁免）
     * @param resolvedCount 解析出的地址数（仅用于本地日志的可观测性）
     */
    public record Decision(boolean allowed, boolean whitelisted, int resolvedCount) {

        public static Decision allow(boolean whitelisted, int resolvedCount) {
            return new Decision(true, whitelisted, resolvedCount);
        }

        public static Decision reject() {
            return new Decision(false, false, 0);
        }
    }

    /**
     * 纯校验（🔴 <b>不写审计、不抛异常</b>）：供配置校验入口聚合 {@code violations} 使用。
     *
     * <p>校验顺序（api-spec §7.6.3 SSRF 规则表，顺序即安全性）：
     * <ol>
     *   <li>URL 可解析且 host 非空</li>
     *   <li>协议必须 {@code https}（{@code sys_config: mcp.require_https}；🔴 生产必须 true）</li>
     *   <li>DNS 解析<b>全部</b> A/AAAA 记录</li>
     *   <li>命中 {@code mcp.allowed_internal_cidrs} → 豁免（含显式端口）</li>
     *   <li>否则：<b>任一</b> IP 命中 {@code mcp.blocked_ip_cidrs} 即拒绝
     *       （含云元数据 {@code 169.254.169.254}）</li>
     *   <li>端口仅允许 443（白名单场景可用显式端口）</li>
     * </ol>
     */
    public Decision evaluate(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException e) {
            return Decision.reject();
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Decision.reject();
        }

        boolean requireHttps = businessConfig.requireBoolean(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_REQUIRE_HTTPS);
        if (requireHttps && !"https".equalsIgnoreCase(uri.getScheme())) {
            return Decision.reject();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            // 🔴 只允许 http(s)：file: / gopher: / ftp: 等一律拒绝（经典 SSRF 载体）
            return Decision.reject();
        }

        InetAddress[] addresses;
        try {
            // 🔴 解析全部 A/AAAA 记录：只校验第一条会被"多 A 记录里藏一条内网 IP"绕过
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // 解析不出来就无法证明它安全 → fail-closed
            return Decision.reject();
        }
        if (addresses.length == 0) {
            return Decision.reject();
        }

        List<String> blocked = cidrList(ConfigKeys.MCP_BLOCKED_IP_CIDRS);
        List<String> allowedInternal = cidrList(ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS);

        boolean whitelisted = true;
        for (InetAddress address : addresses) {
            if (!CidrMatcher.matchesAny(address, allowedInternal)) {
                whitelisted = false;
                break;
            }
        }
        if (!whitelisted) {
            for (InetAddress address : addresses) {
                if (CidrMatcher.matchesAny(address, blocked)) {
                    // 🔴 只记 host，不记解析出的 IP（DEBUG 级也不记，避免日志成为内网测绘素材）
                    log.warn("[SECURITY] MCP 地址命中 SSRF 禁止范围，拒绝连接：hostHash={}",
                            Integer.toHexString(host.hashCode()));
                    return Decision.reject();
                }
            }
        }

        int port = uri.getPort();
        if (port != -1 && port != HTTPS_DEFAULT_PORT && !whitelisted) {
            return Decision.reject();
        }
        return Decision.allow(whitelisted, addresses.length);
    }

    /**
     * 强制校验（拒绝 → {@code 30050} + 🔴 <b>强制审计</b> {@code mcp.ssrf_rejected}）。
     *
     * <p>🔴 <b>调用点（api-spec §7.6.3 第 4 步，顺序不可调整）</b>：
     * 租户上下文 → 绑定 → 授权 → <b>SSRF</b> → Schema → 风险确认 → 轮次。
     *
     * <p>🔴 <b>事务纪律（ADR-010）</b>：本方法在 {@link AuditMode#NEW_TRANSACTION} 下会写一次
     * 独立短事务审计；<b>严禁</b>在持有 {@code tool_calls} 行锁的事务内调用本方法
     * （本方法含 DNS 解析这类网络 I/O）。
     *
     * @throws BusinessException 30050 校验拒绝；50003 审计写入失败（非流式语境）
     */
    public void requireAllowed(McpServer server, AuditMode mode) {
        Decision decision = evaluate(server.getEndpoint());
        if (decision.allowed()) {
            log.debug("MCP 地址 SSRF 校验通过：mcpId={} resolved={} whitelisted={}",
                    server.getId(), decision.resolvedCount(), decision.whitelisted());
            return;
        }
        writeRejectAudit(server, mode);
        // 🔴 30050：工具未授权 / SSRF 安全校验拒绝（api-spec §2.2 已登记语义）
        throw new BusinessException(ErrorCode.TOOL_DENIED, REJECT_MESSAGE);
    }

    /**
     * 只写"SSRF 拒绝"审计（供连接测试这类"需要把拒绝作为诊断结论返回"的场景复用）。
     *
     * @throws BusinessException 50003 非流式语境下审计写入失败（EX-024 失败关闭）
     */
    public void writeRejectAudit(McpServer server, AuditMode mode) {
        if (mode == AuditMode.NONE) {
            return;
        }
        AuditEvent event = AuditEvent.tenant(server.getTenantId(),
                AuditActions.MCP_SSRF_REJECTED, AuditResults.DENIED,
                "mcpServer", String.valueOf(server.getId()),
                // 🔴 reason 不含 endpoint / IP：审计也在禁记清单内（architecture.md §11.1.2）
                REJECT_MESSAGE, ErrorCode.TOOL_DENIED);
        if (mode == AuditMode.SAME_TRANSACTION) {
            // 非流式：审计失败 → 整体失败（异常上抛，业务随同一事务回滚）
            auditService.record(event);
            return;
        }
        try {
            auditService.recordInNewTransaction(event);
        } catch (RuntimeException e) {
            // 🔴 流式内：审计失败只使该次工具调用失败，绝不中断 SSE 流（ADR-010）
            //    含 AuditWriteException（RuntimeException 子类）与底层数据库异常
            log.error("[SECURITY] SSRF 拒绝事件审计写入失败：mcpId={}", server.getId(), e);
        }
    }

    private List<String> cidrList(String key) {
        return configService.getJson(ConfigKeys.GROUP_MCP, key,
                new TypeReference<List<String>>() {
                }, List.of());
    }
}
