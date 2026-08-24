package com.eyes.albedo.tool.handler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.config.AppProperties;
import com.eyes.albedo.tool.LocalToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内置本地 Tool：联网搜索（腾讯云 SearchPro，api-spec §7.7.1 扩增清单）。
 *
 * <p><b>REQ-TOL-002 · AC-TOL-001</b>
 *
 * <p>🔴 <b>与 ADR-015 现有内置工具的关键差异（已评审并接受，参考 {@code SkillLoadHandler}
 * 的"读数据库"先例再进一步）</b>：
 * <ul>
 *   <li>{@code datetime_now} / {@code calculator} 纯函数；{@code skill_load} 放宽为"一次本地
 *       MySQL 只读查询"；本工具进一步放宽为"<b>一次固定白名单端点的 HTTPS 外呼</b>"。</li>
 *   <li>🔴 <b>为什么可接受</b>：本工具<b>纯只读、无任何业务副作用、幂等</b>（同一搜索词重复
 *       调用不改变任何状态），威胁模型与"退款/导出/工单"（任意业务副作用）本质不同；
 *       且外呼端点<b>固定白名单</b>（{@code wsa.tencentcloudapi.com}，写死不可配置），
 *       不存在 MCP 工具的"任意用户可控地址 SSRF"攻击面。</li>
 *   <li>🔴 仍在当前线程内跑完（不新增线程池，ADR-008 第 8 条），残余风险为"一次 HTTPS
 *       外呼的正常耗时"，由 {@code local_tools.timeout_seconds} 计时兜底（真实超时由
 *       {@link HttpClient} 的 requestTimeout 保证，<b>不是</b>"计时判定不中断"的软超时）。</li>
 * </ul>
 *
 * <p>🔴 <b>凭据纪律</b>：SecretId / SecretKey 仅来自 {@code application.yml}
 * （{@code app.tencent-wsa.*} 白名单），<b>不入库、不入日志、不入异常消息</b>；
 * 签名只在调用前于内存中构造，返回后即不再引用（见 {@link TencentTc3Signer}）。
 *
 * <p>🔴 <b>失败语义</b>：上游返回错误 / 网络失败 → {@code 30057}（工具执行业务失败）；
 * 入参非法（缺 query 等）→ {@code 30053}。二者都不回显堆栈、不回灌内部细节。
 */
@Slf4j
@Component
public class WebSearchHandler implements LocalToolHandler {

    /** 🔴 必须与 {@code local_tools.tool_key} 完全一致。 */
    public static final String TOOL_KEY = "web_search";

    private static final String ARG_QUERY = "query";
    private static final String ARG_MODE = "mode";
    private static final String ARG_CNT = "cnt";
    private static final String ARG_INDUSTRY = "industry";
    private static final String ARG_FRESHNESS = "freshness";

    private static final String FIELD_QUERY = "query";
    private static final String FIELD_RESULTS = "results";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AppProperties appProperties;

    public WebSearchHandler(ObjectMapper objectMapper, HttpClient httpClient,
                            AppProperties appProperties) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.appProperties = appProperties;
    }

    @Override
    public String toolKey() {
        return TOOL_KEY;
    }

    @Override
    public String execute(LocalToolInvocation invocation) {
        Map<String, Object> params = readParams(invocation.argumentsJson());
        String query = (String) params.get(ARG_QUERY);
        if (query == null || query.isBlank()) {
            throw argsInvalid("缺少 query 参数");
        }

        AppProperties.TencentWsa wsa = appProperties.getTencentWsa();
        if (wsa.getSecretId() == null || wsa.getSecretId().isBlank()
                || wsa.getSecretKey() == null || wsa.getSecretKey().isBlank()) {
            // 🔴 平台侧未配置腾讯云凭据 → 属配置缺失，不是入参/业务失败；但实现体只回固定措辞
            log.error("web_search 腾讯云凭据未配置（app.tencent-wsa.secret-id/secret-key）");
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }

        String payload = buildPayload(params);
        String response = call(wsa, payload);
        return buildResult(query, response);
    }

    /**
     * 构造 SearchPro 请求体（🔴 只透传已在 input_schema 中枚举约束的参数）。
     *
     * <p>🔴 腾讯云 SearchPro 参数名大小写敏感，必须用官方字段名（首字母大写）：
     * {@code Query} / {@code Mode} / {@code Cnt} / {@code Industry} / {@code Freshness}；
     * 而对模型暴露的入参名保持小写（OpenAI function calling 习惯），此处仅做映射转换。
     */
    private String buildPayload(Map<String, Object> params) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("Query", (String) params.get(ARG_QUERY));
        if (params.get(ARG_MODE) != null) {
            body.put("Mode", (Integer) params.get(ARG_MODE));
        }
        if (params.get(ARG_CNT) != null) {
            body.put("Cnt", (Integer) params.get(ARG_CNT));
        }
        if (params.get(ARG_INDUSTRY) != null) {
            body.put("Industry", (String) params.get(ARG_INDUSTRY));
        }
        if (params.get(ARG_FRESHNESS) != null) {
            body.put("Freshness", (String) params.get(ARG_FRESHNESS));
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw argsInvalid("入参无法序列化");
        }
    }

    /**
     * 发起 HTTPS 调用（🔴 固定白名单端点，签名紧邻请求）。
     */
    private String call(AppProperties.TencentWsa wsa, String payload) {
        List<String> headers = TencentTc3Signer.sign(wsa.getSecretId(), wsa.getSecretKey(),
                wsa.getHost(), "SearchPro", wsa.getVersion(), payload);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("https://" + wsa.getHost() + "/"))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        for (String header : headers) {
            // 🔴 Host 是 JDK HttpClient 的受限头，无法手动设置，也不能设（否则抛
            //    IllegalArgumentException）。它由 HttpClient 依据 URI 自动填充，且值恒等于
            //    wsa.getHost()，与签名中的 host 一致，故跳过即可。
            if (header.regionMatches(true, 0, "host:", 0, 5)) {
                continue;
            }
            int idx = header.indexOf(':');
            builder.header(header.substring(0, idx).trim(), header.substring(idx + 1).trim());
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("web_search 上游返回非 200：status={}", response.statusCode());
                throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
            }
            return response.body();
        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        } catch (Exception e) {
            log.warn("web_search 上游调用失败", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }
    }

    /**
     * 解析上游响应并整理为模型友好的结果（🔴 Pages 是 JSON 字符串数组，需二次解析）。
     */
    private String buildResult(String query, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode resp = root.path("Response");
            JsonNode error = resp.path("Error");
            if (!error.isMissingNode() && !error.isNull()) {
                log.warn("web_search 上游返回业务错误：code={}", error.path("Code").asText());
                throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
            }
            JsonNode pages = resp.path("Pages");
            ArrayNode results = objectMapper.createArrayNode();
            if (pages.isArray()) {
                for (JsonNode page : pages) {
                    try {
                        // 🔴 每项是 JSON 字符串，解析失败则跳过该条（不拖垮整体）
                        results.add(objectMapper.readTree(page.asText()));
                    } catch (Exception e) {
                        log.warn("web_search 单条结果解析失败，跳过");
                    }
                }
            }
            ObjectNode out = objectMapper.createObjectNode();
            out.put(FIELD_QUERY, query);
            out.set(FIELD_RESULTS, results);
            return objectMapper.writeValueAsString(out);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("web_search 结果解析失败", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }
    }

    private Map<String, Object> readParams(String argumentsJson) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return params;
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            if (!root.isObject()) {
                return params;
            }
            if (root.hasNonNull(ARG_QUERY)) {
                params.put(ARG_QUERY, root.get(ARG_QUERY).asText());
            }
            if (root.hasNonNull(ARG_MODE)) {
                params.put(ARG_MODE, root.get(ARG_MODE).asInt());
            }
            if (root.hasNonNull(ARG_CNT)) {
                params.put(ARG_CNT, root.get(ARG_CNT).asInt());
            }
            if (root.hasNonNull(ARG_INDUSTRY)) {
                params.put(ARG_INDUSTRY, root.get(ARG_INDUSTRY).asText());
            }
            if (root.hasNonNull(ARG_FRESHNESS)) {
                params.put(ARG_FRESHNESS, root.get(ARG_FRESHNESS).asText());
            }
            return params;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw argsInvalid("入参不是合法 JSON 对象");
        }
    }

    private static BusinessException argsInvalid(String message) {
        return new BusinessException(ErrorCode.TOOL_ARGS_INVALID, message);
    }

    /** 供单测断言上游请求体构造是否遗漏参数透传。 */
    static List<String> supportedArgs() {
        List<String> args = new ArrayList<>(5);
        args.add(ARG_QUERY);
        args.add(ARG_MODE);
        args.add(ARG_CNT);
        args.add(ARG_INDUSTRY);
        args.add(ARG_FRESHNESS);
        return args;
    }
}
