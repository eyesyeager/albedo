package com.eyes.albedo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用级基础设施属性（{@code application.yml} 的 {@code app.*}）。
 *
 * <p>🔴 白名单纪律（docs/architecture.md §7.1）：此处只允许放<b>基础设施与凭据</b>参数，
 * 任何业务语义参数（分页默认值、限流阈值、状态机、字典、业务文案、功能开关）
 * 必须入 {@code sys_config}；租户品牌配置必须入 {@code site_config_versions}。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** AI 上游（OpenAI 兼容接口）连接参数。 */
    private final Ai ai = new Ai();

    /** 凭据加密参数（M2 MCP 凭据 AES-256-GCM）。 */
    private final Crypto crypto = new Crypto();

    /** 业务线程池参数（SSE 流式生成）。 */
    private final Async async = new Async();

    /** 缓存环境标识（拼接 Redis key，多环境共用实例不串）。 */
    private final Cache cache = new Cache();

    /** 跨域（仅开发环境放开）。 */
    private final Cors cors = new Cors();

    @Getter
    @Setter
    public static class Ai {
        /** OpenAI 兼容 base_url，例如 https://api.hunyuan.cloud.tencent.com/v1 。 */
        private String baseUrl;
        /** 上游 api_key（凭据类，不入库）。 */
        private String apiKey;
        /** 默认模型（可被 Agent 版本快照覆盖）。 */
        private String defaultModel;
        /** 建连超时（秒）。 */
        private int connectTimeoutSeconds = 10;
        /** 整体请求超时（秒），实际以 Agent 的 requestTimeoutSeconds 为准，本值为上限兜底。 */
        private int requestTimeoutSeconds = 300;
    }

    @Getter
    @Setter
    public static class Crypto {
        /** 对称加密主密钥（Base64 或 ≥32 字符原文），仅写 application.yml，不提交 Git。 */
        private String secret;
    }

    @Getter
    @Setter
    public static class Async {
        private int corePoolSize = 16;
        private int maxPoolSize = 64;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;
        private String threadNamePrefix = "ai-stream-";
        /** 优雅关闭等待秒数（保证正在生成的会话能落库）。 */
        private int awaitTerminationSeconds = 30;
    }

    @Getter
    @Setter
    public static class Cache {
        /** dev / test / prod。 */
        private String env = "dev";
    }

    @Getter
    @Setter
    public static class Cors {
        /** 允许的来源模式；生产放开前端站点泛域名（albedo-*.eyescode.top），dev 放开 localhost。 */
        private String[] allowedOriginPatterns = new String[0];
    }
}
