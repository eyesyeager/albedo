package com.eyes.albedo.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 客户端配置（JDK17 {@link HttpClient}）。
 *
 * <p>用途：
 * <ul>
 *   <li>消费 AI 上游（OpenAI 兼容混元）的 SSE 流式响应</li>
 *   <li>M3 访问 MCP 服务（{@code streamable_http} / {@code sse}）</li>
 * </ul>
 *
 * <p>决策（ADR-006）：不引入 WebClient / OkHttp。JDK 原生客户端零额外依赖，
 * 配合 {@code BodyHandlers.ofLines()} 即可逐行解析 SSE，线程模型可控。
 *
 * <p>🔴 安全约束（M3）：MCP 调用<b>禁止跟随跨主机重定向</b>，且必须在建连前完成 SSRF 校验，
 * 因此重定向策略固定为 {@link HttpClient.Redirect#NEVER}。
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient(AppProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(properties.getAi().getConnectTimeoutSeconds()))
                .build();
    }
}
