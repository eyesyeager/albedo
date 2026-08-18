package com.eyes.albedo.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * CIDR 匹配工具（无网络访问的纯计算，供 SSRF 校验复用）。
 *
 * <p>用途：把 {@code sys_config: mcp.blocked_ip_cidrs} / {@code mcp.allowed_internal_cidrs}
 * 与目标 IP 做逐项比对。🔴 阈值与网段一律来自 {@code sys_config}，
 * 本类<b>不内置任何默认网段</b>（否则就是把安全策略硬编码进代码）。
 *
 * <p>⚠️ 本类只做"给定 IP 是否命中 CIDR"。完整 SSRF 校验（协议 / 端口 / DNS 解析全部
 * A/AAAA 记录 / 每次调用前重校验 / 不跟随重定向）由 M3 第二阶段的 {@code SsrfGuard} 承担，
 * 实现口径见 ADR-009（JDK17 下不做 pin IP，而是"解析后逐 IP 校验 → 以原域名连接保住 TLS 校验
 * → {@code -Dnetworkaddress.cache.ttl=10} 收窄 TOCTOU → 每次调用前重校验"）。
 */
public final class CidrMatcher {

    private CidrMatcher() {
    }

    /**
     * 目标地址是否命中任一 CIDR。
     *
     * @param address 目标 IP（已解析）
     * @param cidrs   CIDR 列表（如 {@code 10.0.0.0/8}、{@code fc00::/7}）；非法项忽略
     */
    public static boolean matchesAny(InetAddress address, List<String> cidrs) {
        if (address == null || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        byte[] target = address.getAddress();
        for (String cidr : cidrs) {
            if (matches(target, cidr)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 字面量 IP 是否命中任一 CIDR（不做 DNS 解析；非 IP 字面量返回 {@code false}）。
     *
     * <p>🔴 有意<b>不</b>在此触发 DNS：配置校验入口必须能在"不发起任何网络行为"的前提下运行，
     * 否则校验本身就成了 SSRF 探测通道。
     */
    public static boolean literalIpMatchesAny(String host, List<String> cidrs) {
        if (host == null || host.isBlank() || !isLiteralIp(host)) {
            return false;
        }
        try {
            return matchesAny(InetAddress.getByName(stripBrackets(host)), cidrs);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 是否是 IP 字面量（IPv4 点分十进制或方括号 / 冒号形式的 IPv6）。
     */
    public static boolean isLiteralIp(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String value = stripBrackets(host);
        if (value.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] target, String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return false;
        }
        int slash = cidr.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String base = cidr.substring(0, slash).trim();
        int prefixBits;
        try {
            prefixBits = Integer.parseInt(cidr.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        byte[] network;
        try {
            network = InetAddress.getByName(base).getAddress();
        } catch (UnknownHostException e) {
            return false;
        }
        // IPv4 与 IPv6 不互相匹配（长度不同）
        if (network.length != target.length) {
            return false;
        }
        if (prefixBits < 0 || prefixBits > network.length * 8) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != target[i]) {
                return false;
            }
        }
        int remainingBits = prefixBits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (network[fullBytes] & mask) == (target[fullBytes] & mask);
    }

    private static String stripBrackets(String host) {
        String value = host.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
