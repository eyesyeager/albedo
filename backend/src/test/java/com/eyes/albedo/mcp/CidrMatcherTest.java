package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CIDR 匹配测试（SSRF 禁止范围判定的基础，ADR-009）。
 *
 * <p>网段取值来自 {@code sys_config: mcp.blocked_ip_cidrs} 的默认值，
 * 🔴 用例里出现这些字面量是<b>断言契约默认值</b>，不是代码硬编码。
 */
class CidrMatcherTest {

    private static final List<String> BLOCKED = List.of(
            "127.0.0.0/8", "::1/128", "0.0.0.0/8", "169.254.0.0/16", "10.0.0.0/8",
            "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "fc00::/7", "fe80::/10");

    @Test
    @DisplayName("🔴 环回 / 私网 / 链路本地 / 云元数据一律命中禁止范围")
    void blockedRangesMatch() {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
                "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0"}) {
            assertTrue(CidrMatcher.literalIpMatchesAny(ip, BLOCKED), "应命中禁止范围：" + ip);
        }
        assertTrue(CidrMatcher.literalIpMatchesAny("[::1]", BLOCKED), "IPv6 环回应命中");
        assertTrue(CidrMatcher.literalIpMatchesAny("fe80::1", BLOCKED), "链路本地应命中");
    }

    @Test
    @DisplayName("公网地址不命中禁止范围")
    void publicAddressesDoNotMatch() {
        for (String ip : new String[]{"203.0.113.10", "8.8.8.8", "172.32.0.1", "9.255.255.255"}) {
            assertFalse(CidrMatcher.literalIpMatchesAny(ip, BLOCKED), "不应命中：" + ip);
        }
    }

    @Test
    @DisplayName("域名不触发 DNS 解析（校验入口禁止发起网络行为）")
    void domainNamesAreNotResolved() {
        assertFalse(CidrMatcher.literalIpMatchesAny("mcp.example.com", BLOCKED));
        assertFalse(CidrMatcher.isLiteralIp("mcp.example.com"));
        assertTrue(CidrMatcher.isLiteralIp("10.0.0.1"));
        assertTrue(CidrMatcher.isLiteralIp("[fe80::1]"));
    }

    @Test
    @DisplayName("白名单可豁免：127.0.0.1/32 命中白名单（test profile 的 Mock MCP 场景）")
    void whitelistMatch() {
        assertTrue(CidrMatcher.literalIpMatchesAny("127.0.0.1", List.of("127.0.0.1/32")));
        assertFalse(CidrMatcher.literalIpMatchesAny("127.0.0.2", List.of("127.0.0.1/32")));
    }

    @Test
    @DisplayName("非法 CIDR 与空清单安全降级为不匹配（不抛异常）")
    void invalidInputsDegradeSafely() {
        assertFalse(CidrMatcher.literalIpMatchesAny("10.0.0.1", List.of()));
        assertFalse(CidrMatcher.literalIpMatchesAny("10.0.0.1", List.of("not-a-cidr", "10.0.0.0")));
        assertFalse(CidrMatcher.literalIpMatchesAny(null, BLOCKED));
        assertFalse(CidrMatcher.literalIpMatchesAny("999.1.1.1", BLOCKED));
        // IPv4 与 IPv6 不互相匹配
        assertFalse(CidrMatcher.literalIpMatchesAny("10.0.0.1", List.of("fc00::/7")));
    }
}
