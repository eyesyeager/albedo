package com.eyes.albedo.common;

/**
 * 错误码常量（唯一来源：docs/api-spec.md §2.2 错误码登记表）。
 *
 * <p>🔴 纪律：新增错误码必须<b>先在 api-spec.md 登记</b>并广播 @前端 + @测试，然后才能在此新增常量并实现。
 *
 * <p>段位规则（框架 §14.2）：
 * <pre>
 *   0            成功（唯一成功码，禁止用 200）
 *   10000~19999  通用错误
 *   20000~20999  耶瞳 SSO 保留段 —— 业务严禁占用
 *   30000~39999  业务错误
 *   50000~59999  系统错误
 * </pre>
 *
 * <p>已废弃且禁止出现：10002（改用 20001/20002）、40001（改用 10001）。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    // ===== 成功 =====
    public static final int SUCCESS = 0;

    // ===== 通用错误 10000~19999 =====
    /** 参数校验失败（含分页越界、缺少 Idempotency-Key）。 */
    public static final int VALIDATION_FAILED = 10001;
    /** 已登录但业务权限不足（含成员 disabled、租户内角色不足）。 */
    public static final int PERMISSION_DENIED = 10003;
    /** 当前租户上下文中资源不存在（含跨租户 / 跨用户 ID）。 */
    public static final int RESOURCE_NOT_FOUND = 10004;
    /** 请求频率超限（M3）。 */
    public static final int RATE_LIMITED = 10005;

    // ===== 耶瞳 SSO 保留段 20000~20999（业务严禁占用） =====
    public static final int AUTH_FORBIDDEN = 20000;
    public static final int AUTH_TOKEN_INVALID = 20001;
    public static final int AUTH_TOKEN_EXPIRED = 20002;
    public static final int AUTH_ACCOUNT_FROZEN = 20003;
    public static final int AUTH_ACCOUNT_NOT_FOUND = 20004;
    public static final int AUTH_ROLE_ILLEGAL = 20005;
    public static final int AUTH_PARAM_ILLEGAL = 20008;
    /** 耶瞳保留段下界（含）。 */
    public static final int AUTH_SEGMENT_MIN = 20000;
    /** 耶瞳保留段上界（含）。 */
    public static final int AUTH_SEGMENT_MAX = 20999;

    // ===== 业务错误 30000~39999 =====
    /** BusinessException 默认兜底码，正式接口不得直接返回。 */
    public static final int BUSINESS_ERROR = 30001;
    public static final int TENANT_NOT_FOUND = 30010;
    public static final int TENANT_SUSPENDED = 30011;
    public static final int TENANT_CONFIG_UNAVAILABLE = 30012;
    public static final int TENANT_CONTEXT_MISSING = 30013;
    public static final int VERSION_CONFLICT = 30020;
    public static final int PUBLISH_VALIDATE_FAILED = 30021;
    public static final int AGENT_UNAVAILABLE = 30030;
    public static final int AGENT_DISABLED = 30031;
    public static final int CONVERSATION_READONLY = 30040;
    public static final int MESSAGE_TOO_LONG = 30041;
    public static final int TOOL_DENIED = 30050;
    public static final int TOOL_TIMEOUT = 30051;
    public static final int MCP_UNAVAILABLE = 30052;
    /** 工具入参不符合已注册 JSON Schema（本地 Tool 校验失败 / MCP JSON-RPC -32602）。 */
    public static final int TOOL_ARGS_INVALID = 30053;
    /** 单次生成的工具调用轮次超过 sys_config: tool.max_rounds。 */
    public static final int TOOL_LOOP_LIMIT_EXCEEDED = 30054;
    /** 同一 toolCallId 提交与既有决定相反的 decision（api-spec §7.8.2 状态机）。 */
    public static final int TOOL_CONFIRM_CONFLICT = 30055;
    /** 非幂等工具结果未知，禁止自动重试（EX-019 / AC-TOL-003）。 */
    public static final int TOOL_RETRY_BLOCKED = 30056;
    /** 工具执行返回业务失败（非超时、非鉴权、非参数错误）。 */
    public static final int TOOL_EXECUTION_FAILED = 30057;
    /**
     * Agent/Skill/MCP/Tool 配置或引用链非法（独立校验入口与运行时兜底共用）。
     *
     * <p>返回时 {@code data.violations[]} 必须给出字段级失败；🔴 禁止 NPE / 未分类 500 / 白屏。
     */
    public static final int RUNTIME_CONFIG_INVALID = 30060;
    /**
     * 缓存失效部分或全部失败。
     *
     * <p>🔴 {@code data.incompleteScopes[]} 必须列出未完成作用域，禁止伪报成功。
     */
    public static final int CACHE_INVALIDATION_FAILED = 30061;
    /**
     * 🔴 <b>V1.2.5 新增（ADR-020 ④，子段 30070~30079「用量与额度」）</b>：
     * 当前租户当前用户的<b>今日对话额度已用尽</b>（判定发生在<b>模型调用之前</b>）。
     *
     * <p>🔴 {@code data} <b>必须</b>是额度快照（{@code QuotaSnapshotDTO}，恰 9 键，
     * {@code remaining=0} / {@code status=exhausted}）—— 让前端"用尽那一刻零延迟进入用尽态"，
     * 无需再拉一次接口。
     *
     * <p>🔴 <b>禁止携带 {@code retryAfterSeconds}</b>：它不是秒级可恢复的频率限制，
     * 携带即会被前端 {@code rateLimitStore} 误表现为倒计时（真实恢复条件是"明日租户零点"）。
     *
     * <p>🔴 <b>禁止复用 {@link #RATE_LIMITED}</b>（PRD §8.11.3 已产品裁决）：两者恢复条件相差
     * 5 个数量级（等 N 秒 vs 等到明日租户零点），前端引导与状态机完全不同。
     */
    public static final int DAILY_QUOTA_EXHAUSTED = 30070;

    // ===== 系统错误 50000~59999 =====
    /** 数据库异常（对外语义等同系统错误，仅用于内部映射与排障）。 */
    public static final int DATABASE_ERROR = 50001;
    /** 上游不可用（eyesUser Thrift / 模型服务 / MCP）。 */
    public static final int UPSTREAM_UNAVAILABLE = 50002;
    /** 未分类系统错误 / 系统繁忙。 */
    public static final int INTERNAL_ERROR = 50003;

    /**
     * 判断 code 是否落在耶瞳 SSO 保留段。
     */
    public static boolean isAuthSegment(int code) {
        return code >= AUTH_SEGMENT_MIN && code <= AUTH_SEGMENT_MAX;
    }

    /**
     * 全部<b>已在 api-spec §2.2 登记</b>的错误码（🔴 唯一白名单）。
     *
     * <p>用途：埋点上报的 {@code errorCode} 必须落在本集合内，否则置空（api-spec §7.10.1）。
     * 🔴 <b>为什么要严格</b>：埋点里的 {@code errorCode} 会被用于错误分布统计，
     * 混入前端自造的码（如 {@code -1} / {@code 500}）会让口径失效并掩盖真实错误分布；
     * 而"未登记码"本身就是 §2.2 明令禁止的东西，不该被我们默默存下来。
     *
     * <p>🔴 纪律：新增码时必须同步本集合（否则新码的埋点会被静默置空，排查成本极高）。
     */
    private static final java.util.Set<Integer> REGISTERED = java.util.Set.of(
            SUCCESS, VALIDATION_FAILED, PERMISSION_DENIED, RESOURCE_NOT_FOUND, RATE_LIMITED,
            AUTH_FORBIDDEN, AUTH_TOKEN_INVALID, AUTH_TOKEN_EXPIRED, AUTH_ACCOUNT_FROZEN,
            AUTH_ACCOUNT_NOT_FOUND, AUTH_ROLE_ILLEGAL, AUTH_PARAM_ILLEGAL,
            BUSINESS_ERROR, TENANT_NOT_FOUND, TENANT_SUSPENDED, TENANT_CONFIG_UNAVAILABLE,
            TENANT_CONTEXT_MISSING, VERSION_CONFLICT, PUBLISH_VALIDATE_FAILED,
            AGENT_UNAVAILABLE, AGENT_DISABLED, CONVERSATION_READONLY, MESSAGE_TOO_LONG,
            TOOL_DENIED, TOOL_TIMEOUT, MCP_UNAVAILABLE, TOOL_ARGS_INVALID,
            TOOL_LOOP_LIMIT_EXCEEDED, TOOL_CONFIRM_CONFLICT, TOOL_RETRY_BLOCKED,
            TOOL_EXECUTION_FAILED, RUNTIME_CONFIG_INVALID, CACHE_INVALIDATION_FAILED,
            // 🔴 V1.2.5（ADR-020 ④）：漏加这一项会让 30070 在埋点里被**静默置空**
            DAILY_QUOTA_EXHAUSTED,
            DATABASE_ERROR, UPSTREAM_UNAVAILABLE, INTERNAL_ERROR);

    /**
     * 该 code 是否已在 api-spec §2.2 登记。
     */
    public static boolean isRegistered(int code) {
        return REGISTERED.contains(code);
    }

    /**
     * 已登记错误码集合（只读视图，供测试断言"登记表与常量类一致"）。
     */
    public static java.util.Set<Integer> registeredCodes() {
        return REGISTERED;
    }

    /**
     * 错误码的诊断语义（非面向终端用户的最终文案）。
     *
     * <p>面向用户的措辞由 @UI 在 {@code frontend/src/locales} 或租户配置中决定；
     * 此处仅保证接口 message 字段有确定、可排障的语义。
     */
    public static String defaultMessage(int code) {
        return switch (code) {
            case SUCCESS -> "success";
            case VALIDATION_FAILED -> "参数校验失败";
            case PERMISSION_DENIED -> "权限不足";
            case RESOURCE_NOT_FOUND -> "资源不存在";
            case RATE_LIMITED -> "请求频率超限";
            case AUTH_FORBIDDEN -> "权限不足";
            case AUTH_TOKEN_INVALID -> "身份凭据无效";
            case AUTH_TOKEN_EXPIRED -> "身份凭据已过期";
            case AUTH_ACCOUNT_FROZEN -> "账号已被冻结";
            case AUTH_ACCOUNT_NOT_FOUND -> "账户不存在";
            case AUTH_ROLE_ILLEGAL -> "非法角色";
            case AUTH_PARAM_ILLEGAL -> "鉴权参数非法";
            case BUSINESS_ERROR -> "业务处理失败";
            case TENANT_NOT_FOUND -> "站点不存在";
            case TENANT_SUSPENDED -> "站点暂停服务";
            case TENANT_CONFIG_UNAVAILABLE -> "站点配置不可用";
            case TENANT_CONTEXT_MISSING -> "缺少租户上下文";
            case VERSION_CONFLICT -> "数据已被他人更新";
            case PUBLISH_VALIDATE_FAILED -> "发布校验失败";
            case AGENT_UNAVAILABLE -> "暂无可用助手";
            case AGENT_DISABLED -> "助手已停用";
            case CONVERSATION_READONLY -> "会话只读";
            case MESSAGE_TOO_LONG -> "消息为空或超出长度限制";
            case TOOL_DENIED -> "工具调用被拒绝";
            case TOOL_TIMEOUT -> "工具执行超时";
            case MCP_UNAVAILABLE -> "MCP 服务不可用";
            case TOOL_ARGS_INVALID -> "工具入参不符合约定";
            case TOOL_LOOP_LIMIT_EXCEEDED -> "已达工具调用上限";
            case TOOL_CONFIRM_CONFLICT -> "确认决定与服务端记录冲突";
            case TOOL_RETRY_BLOCKED -> "工具结果待确认，不可自动重试";
            case TOOL_EXECUTION_FAILED -> "工具执行失败";
            case RUNTIME_CONFIG_INVALID -> "配置校验失败";
            case CACHE_INVALIDATION_FAILED -> "缓存失效未全部完成，请重试未完成作用域";
            // 🔴 诊断语义；面向用户的最终文案在前端 locales（含"明日租户零点重置 + 联系管理员"）
            case DAILY_QUOTA_EXHAUSTED -> "今日对话额度已用尽";
            case DATABASE_ERROR, INTERNAL_ERROR -> "系统繁忙，请稍后重试";
            case UPSTREAM_UNAVAILABLE -> "依赖服务暂不可用";
            default -> "未知错误";
        };
    }
}
