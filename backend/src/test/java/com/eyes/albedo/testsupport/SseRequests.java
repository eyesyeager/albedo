package com.eyes.albedo.testsupport;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.eyes.albedo.support.TestAuthConfig;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 🔴 <b>SSE 端点请求构造的唯一入口</b>（api-spec §8.3 <b>L1 / L5</b>，ADR-021 / AR-029）。
 *
 * <p><b>为什么必须收敛到单一 helper</b>：BUG-QUOTA-001 的全部技术内容就是
 * "请求带上 {@code Accept: text/event-stream} 之后，建流前失败的响应形态被 Spring 内容协商
 * 悄悄改成 500 + 空体"。而真实浏览器（{@code fetch + ReadableStream}）<b>恒带</b>该头，
 * 测试侧却一直没带 —— 于是 1166 个用例全绿而缺陷照样上线。
 *
 * <p>🔴 L5 明文裁定：<b>逐个用例手加 Accept 头的做法不被接受</b>（它必然漏）。
 * 因此所有 SSE 端点（{@link #PATTERN_SEND} / {@link #PATTERN_REGENERATE}）的测试请求
 * 必须经由本类构造，由本类<b>强制注入</b> {@code Accept: text/event-stream}。
 *
 * <p>🔴 <b>新增 SSE 端点时</b>：必须同时在 {@link #COVERED_PATTERNS} 登记，
 * 否则 {@code SseTransportDisciplineScanTest} 的反射扫描会直接变红（L5 ⓐ）。
 */
public final class SseRequests {

    /** 🔴 真实浏览器恒带的 Accept（本类强制注入的那一个）。 */
    public static final String ACCEPT_SSE = MediaType.TEXT_EVENT_STREAM_VALUE;

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /** 发送消息（建流）端点的映射模式。 */
    public static final String PATTERN_SEND = "/api/v1/conversations/{conversationId}/messages";
    /** 重新生成（建流）端点的映射模式。 */
    public static final String PATTERN_REGENERATE = "/api/v1/messages/{messageId}/regenerate";
    /** 🔴 AG-UI 运行端点（彻底替换协议的新建流端点）。 */
    public static final String PATTERN_AGUI_RUN = "/api/v1/agui/run";

    /**
     * 🔴 已被 L1/L2 类用例覆盖的 SSE 端点集合（{@code SseTransportDisciplineScanTest} 的比对基准）。
     */
    public static final List<String> COVERED_PATTERNS =
            List.of(PATTERN_SEND, PATTERN_REGENERATE, PATTERN_AGUI_RUN);

    private SseRequests() {
    }

    // ===================== 路径构造 =====================

    /** {@code POST /api/v1/conversations/{id}/messages} 的具体路径。 */
    public static String sendPath(String conversationId) {
        return "/api/v1/conversations/" + conversationId + "/messages";
    }

    /** 首发（{@code conversationId=new}，原子建会话）。 */
    public static String sendNewPath() {
        return sendPath("new");
    }

    /** {@code POST /api/v1/messages/{id}/regenerate} 的具体路径。 */
    public static String regeneratePath(String messageId) {
        return "/api/v1/messages/" + messageId + "/regenerate";
    }

    // ===================== MockMvc 请求构造（L1） =====================

    /**
     * 构造 SSE 端点请求：Host + 测试 uid + 随机 {@code Idempotency-Key} +
     * 🔴 强制 {@code Accept: text/event-stream}。
     */
    public static MockHttpServletRequestBuilder post(String path, String host, long uid) {
        return post(path, host, uid, UUID.randomUUID().toString());
    }

    /**
     * 同上，但由调用方指定幂等键（幂等回放用例需要复用同一个键）。
     *
     * @param idempotencyKey 传 {@code null} 表示<b>刻意不带</b>该头（10001 用例）
     */
    public static MockHttpServletRequestBuilder post(String path, String host, long uid,
                                                     String idempotencyKey) {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.HOST, host)
                .header(TestAuthConfig.HEADER_TEST_UID, uid)
                // 🔴 承重行：真实浏览器恒带此头，测试必须与之同形（L1）
                .accept(MediaType.TEXT_EVENT_STREAM);
        if (idempotencyKey != null) {
            builder = builder.header(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return builder;
    }

    /**
     * 构造带 JSON 请求体的 SSE 端点请求（{@code jsonBody} 为 {@code null} 时不带体，
     * 用于 {@code regenerate}）。
     */
    public static MockHttpServletRequestBuilder postJson(String path, String host, long uid,
                                                        String idempotencyKey, String jsonBody) {
        MockHttpServletRequestBuilder builder = post(path, host, uid, idempotencyKey);
        if (jsonBody != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return builder;
    }

    /** {@link #postJson} 的随机幂等键版本。 */
    public static MockHttpServletRequestBuilder postJson(String path, String host, long uid,
                                                        String jsonBody) {
        return postJson(path, host, uid, UUID.randomUUID().toString(), jsonBody);
    }

    // ===================== 真实 HTTP 栈请求构造（L2 / L3 / L4） =====================

    /**
     * 🔴 <b>真实 HTTP 栈</b>（{@code RANDOM_PORT} + JDK {@code HttpClient}）的 SSE 端点请求构造。
     *
     * <p>🔴 <b>为什么 {@code accept} 是入参而不是常量</b>：L4 要求同一失败场景在
     * 通配 Accept（星号斜杠星号）、{@code application/json}、{@code text/event-stream}
     * 三种取值下给出<b>一致</b>的响应形态 —— 该比对本身就是判据。
     * 除此之外的任何 SSE 用例都必须用 {@link #ACCEPT_SSE}。
     *
     * @param idempotencyKey 传 {@code null} 表示<b>刻意不带</b>该头（10001 用例）
     */
    public static HttpRequest.Builder realHttpPost(int port, String path, long uid, String accept,
                                                   String idempotencyKey, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, accept)
                .header(TestAuthConfig.HEADER_TEST_UID, String.valueOf(uid))
                .timeout(Duration.ofSeconds(20));
        if (idempotencyKey != null) {
            builder = builder.header(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(
                jsonBody == null ? "{}" : jsonBody));
    }

    /** 真实 HTTP 栈的常规 SSE 请求（Accept 恒为 {@code text/event-stream} + 随机幂等键）。 */
    public static HttpRequest.Builder realHttpPost(int port, String path, long uid,
                                                   String jsonBody) {
        return realHttpPost(port, path, uid, ACCEPT_SSE, UUID.randomUUID().toString(), jsonBody);
    }
}
