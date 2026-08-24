package com.eyes.albedo.tool.handler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云 API 3.0 签名（TC3-HMAC-SHA256）工具（🔴 仅供内置联网搜索 Tool 使用）。
 *
 * <p>实现腾讯云标准签名流程，用于 {@code SearchPro}（联网搜索 API）调用：
 * <pre>
 *   CanonicalRequest = HTTPRequestMethod + "\n" + CanonicalURI + "\n" + CanonicalQueryString
 *                    + "\n" + CanonicalHeaders + "\n" + SignedHeaders + "\n" + HashedRequestPayload
 *   StringToSign     = "TC3-HMAC-SHA256" + "\n" + Timestamp + "\n"
 *                    + Date + "/" + Service + "/tc3_request" + "\n" + Hex(Hash(CanonicalRequest))
 *   Authorization    = "TC3-HMAC-SHA256 Credential={secretId}/{date}/{service}/tc3_request, "
 *                    + "SignedHeaders={signedHeaders}, Signature={signature}"
 * </pre>
 *
 * <p>🔴 <b>凭据纪律</b>：SecretId / SecretKey 仅写 {@code application.yml}（{@code app.*} 白名单），
 * 不入库、不入日志、不入异常消息；签名结果只存在于内存，返回后即不再引用。
 *
 * <p>🔴 <b>为什么手写签名而非引入腾讯云 SDK</b>：本工具只调一个固定接口（{@code SearchPro}），
 * 引入完整 SDK 会拖入大量无关依赖（违反本项目"最小依赖"纪律，ADR-006 仅引入 JDK 原生 HttpClient）。
 * TC3 签名是公开、稳定的算法，手写实现可控且可被单测锁定。
 */
public final class TencentTc3Signer {

    /** 签名算法标识（腾讯云固定字面量）。 */
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    /** 联网搜索服务名（腾讯云固定字面量）。 */
    private static final String SERVICE = "wsa";
    /** 日期格式（UTC，{@code yyyy-MM-dd}，用于 CredentialScope 与派生密钥）。 */
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private TencentTc3Signer() {
    }

    /**
     * 构造腾讯云请求所需的鉴权头与公共头。
     *
     * @param secretId  腾讯云 SecretId
     * @param secretKey 腾讯云 SecretKey
     * @param host      接口域名（如 {@code wsa.tencentcloudapi.com}）
     * @param action    接口名（{@code SearchPro}）
     * @param version   API 版本（{@code 2025-05-08}）
     * @param payload   请求体 JSON（参与签名，签名后不得再改动）
     * @param now       当前时间（秒级时间戳来源，便于测试注入）
     * @return 按字母序排列的请求头列表（含 {@code Authorization} / {@code Content-Type} /
     *         {@code X-TC-*} 公共头）
     */
    public static List<String> sign(String secretId, String secretKey, String host, String action,
                                    String version, String payload, Instant now) {
        String date = DATE_FORMAT.format(now);
        // 🔴 StringToSign 中的 RequestTimestamp 必须是 UNIX 时间戳（纯数字秒），
        //    与 X-TC-Timestamp 头取值完全一致（腾讯云签名方法 v3 规范）。
        String timestamp = String.valueOf(now.getEpochSecond());

        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-tc-action:" + action.toLowerCase() + "\n";
        String signedHeaders = "content-type;host;x-tc-action";

        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + sha256Hex(payload);

        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = ALGORITHM + "\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = toHex(hmac(secretSigning, stringToSign));

        String authorization = ALGORITHM + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        List<String> headers = new ArrayList<>(6);
        headers.add("Authorization: " + authorization);
        headers.add("Content-Type: application/json; charset=utf-8");
        headers.add("Host: " + host);
        headers.add("X-TC-Action: " + action);
        headers.add("X-TC-Timestamp: " + String.valueOf(now.getEpochSecond()));
        headers.add("X-TC-Version: " + version);
        Collections.sort(headers);
        return headers;
    }

    /**
     * 兼容重载：以当前 UTC 时间签名（生产路径）。
     */
    public static List<String> sign(String secretId, String secretKey, String host, String action,
                                    String version, String payload) {
        return sign(secretId, secretKey, host, action, version, payload, Instant.now());
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
