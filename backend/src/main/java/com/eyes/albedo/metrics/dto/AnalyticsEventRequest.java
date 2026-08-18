package com.eyes.albedo.metrics.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * 单条埋点事件入参（🔴 <b>字段白名单即本类的字段清单</b>，api-spec §7.10.1）。
 *
 * <p>🔴 <b>为什么用固定字段的类而不是 {@code Map<String,Object>}</b>：
 * 白名单必须是"结构上不可能装进未列出字段"，而不是"代码里记得过滤"。
 * 用 Map 承载会让任何一次遗漏过滤都直接变成<b>正文/凭据落库</b>（不可逆的安全事故）。
 *
 * <p>🔴 但仅靠字段白名单<b>不够</b>：客户端可能把消息正文塞进
 * 白名单内的自由文本字段（如 {@code source} / {@code pagePath} / {@code toolKey}）。
 * 因此 {@code AnalyticsEventService} 还要做<b>值级</b>拦截（键名子串 + 手机号/邮箱/长数字正则）。
 *
 * <p>{@link #extras} 收集<b>未列出的字段</b>：🔴 不落库，只用于「禁止字段命中即整条丢弃」判定 ——
 * 若直接丢弃未知字段，客户端把 {@code messageContent} 传上来就会被<b>静默接受</b>
 * （事件照常入库，只是少了那个字段），我们也就永远发现不了前端在往上传正文。
 *
 * @param clientEventId 去重键，{@code ^[A-Za-z0-9_-]{8,64}$}
 * @param eventName     必须命中 {@code observability.analytics_allowed_events} 白名单
 * @param occurredAt    ISO-8601 UTC；与服务端偏差 &gt;24h → 整条 discarded
 * @param loginState    {@code anonymous} / {@code logged_in}
 * @param configVersion ≥0
 * @param conversationId 🔴 必须属当前租户 + 当前 uid，否则<b>置空</b>（不报错、不泄露存在性）
 * @param agentId       同租户校验，不通过则置空
 * @param agentVersion  ≥0
 * @param toolType      {@code local} / {@code mcp}
 * @param toolKey       ≤64
 * @param status        ≤32，须为已定义枚举字面量
 * @param result        {@code success} / {@code failed} / {@code denied}
 * @param errorCode     🔴 必须是 api-spec §2.2 已登记码，否则置空
 * @param durationMs    0 ~ 600000
 * @param latencyMs     0 ~ 600000
 * @param charCount     ≥0；🔴 只允许长度，禁止正文
 * @param tokenUsage    {@code {promptTokens, completionTokens, totalTokens}}
 * @param source        ≤32
 * @param pagePath      🔴 仅站内 path，服务端强制去除 query 与 hash（AC-AUTH-002）
 * @param action        ≤32
 */
public final class AnalyticsEventRequest {

    private String clientEventId;
    private String eventName;
    private String occurredAt;
    private String loginState;
    private Long configVersion;
    private String conversationId;
    private String agentId;
    private Long agentVersion;
    private String toolType;
    private String toolKey;
    private String status;
    private String result;
    private Integer errorCode;
    private Integer durationMs;
    private Integer latencyMs;
    private Integer charCount;
    private TokenUsagePayload tokenUsage;
    private String source;
    private String pagePath;
    private String action;

    /** 🔴 未列入白名单的字段（不落库，仅用于"禁止字段命中即整条丢弃"判定）。 */
    private final Map<String, Object> extras = new java.util.LinkedHashMap<>();

    @JsonAnySetter
    public void putExtra(String key, Object value) {
        if (extras.size() < EXTRA_KEYS_MAX) {
            // 🔴 只留键名与"是否为字符串"，不长期持有值以外的引用；值仍需参与正则判定
            extras.put(key, value);
        }
    }

    /**
     * {@link #extras} 上限：防止构造超大 JSON 撑爆内存（埋点是<b>匿名可访问</b>端点）。
     *
     * <p>🔴 这是<b>攻击面控制</b>而非业务阈值，故不入 {@code sys_config}：
     * 白名单只有 20 个字段，正常客户端不会出现几十个未知键；命中上限本身就说明请求异常。
     */
    private static final int EXTRA_KEYS_MAX = 32;

    /** token 用量（🔴 只有三个数值，禁止承载其它内容）。 */
    public record TokenUsagePayload(Integer promptTokens, Integer completionTokens,
                                   Integer totalTokens) {
    }

    public Map<String, Object> extras() {
        return extras;
    }

    // ===== getters / setters（Jackson 反序列化用） =====

    public String getClientEventId() {
        return clientEventId;
    }

    public void setClientEventId(String clientEventId) {
        this.clientEventId = clientEventId;
    }

    public String getEventName() {
        return eventName;
    }

    public void setEventName(String eventName) {
        this.eventName = eventName;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(String occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getLoginState() {
        return loginState;
    }

    public void setLoginState(String loginState) {
        this.loginState = loginState;
    }

    public Long getConfigVersion() {
        return configVersion;
    }

    public void setConfigVersion(Long configVersion) {
        this.configVersion = configVersion;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Long getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Long agentVersion) {
        this.agentVersion = agentVersion;
    }

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public String getToolKey() {
        return toolKey;
    }

    public void setToolKey(String toolKey) {
        this.toolKey = toolKey;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(Integer errorCode) {
        this.errorCode = errorCode;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public TokenUsagePayload getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsagePayload tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPagePath() {
        return pagePath;
    }

    public void setPagePath(String pagePath) {
        this.pagePath = pagePath;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }
}
