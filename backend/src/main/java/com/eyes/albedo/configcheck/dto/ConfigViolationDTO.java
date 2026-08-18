package com.eyes.albedo.configcheck.dto;

/**
 * 配置校验违规项（{@code 30060} 的 {@code data.violations[]} 元素，api-spec §7.3.1）。
 *
 * <p>🔴 {@code message} <b>禁止</b>包含：
 * 密钥/凭据明文或片段、内部 IP/域名/端口、堆栈、其他租户资源存在性。
 * 例如 {@code endpoint} 非法只回"不在允许范围内"，<b>不得</b>回显解析出的 IP。
 *
 * @param objectType 出问题的对象类型（如 {@code skillVersion} / {@code mcp}）
 * @param objectId   对象 ID（string，ADR-004）
 * @param field      字段名（对外 camelCase）
 * @param rule       规则分类（如 {@code undeclaredVariable} / {@code ssrfRejected} / {@code invalidCipher}）
 * @param message    可展示说明（🔴 见上方禁含清单）
 */
public record ConfigViolationDTO(String objectType,
                                 String objectId,
                                 String field,
                                 String rule,
                                 String message) {

    // ===== 规则分类常量（@测试 据此断言，🔴 新增规则必须在此登记） =====
    /** 必填项缺失。 */
    public static final String RULE_REQUIRED = "required";
    /** 长度越界。 */
    public static final String RULE_LENGTH = "length";
    /** 枚举取值非法。 */
    public static final String RULE_ENUM = "invalidEnum";
    /** 格式非法（key 命名、JSON 结构等）。 */
    public static final String RULE_FORMAT = "invalidFormat";
    /** 阈值越界（超时、数量上限等，边界取自 sys_config）。 */
    public static final String RULE_RANGE = "outOfRange";
    /** 被引用资源不存在。 */
    public static final String RULE_REF_NOT_FOUND = "refNotFound";
    /** 被引用资源不可用（停用 / 归档）。 */
    public static final String RULE_REF_UNAVAILABLE = "refUnavailable";
    /** 🔴 跨租户引用。 */
    public static final String RULE_CROSS_TENANT = "crossTenantReference";
    /** 指令引用了未声明变量。 */
    public static final String RULE_UNDECLARED_VARIABLE = "undeclaredVariable";
    /** 变量名占用平台保留名。 */
    public static final String RULE_RESERVED_VARIABLE = "reservedVariable";
    /** 声明但未使用（仅 warning，不阻断）。 */
    public static final String RULE_UNUSED_VARIABLE = "unusedVariable";
    /** endpoint 未通过 HTTPS / SSRF 校验。 */
    public static final String RULE_SSRF_REJECTED = "ssrfRejected";
    /** 凭据密文格式非法或密钥版本不受支持。 */
    public static final String RULE_INVALID_CIPHER = "invalidCipher";
    /** 凭据缺失（authType 需要凭据但未配置）。 */
    public static final String RULE_CREDENTIAL_MISSING = "credentialMissing";
    /** 配置中出现脚本 / 表达式 / 可执行片段。 */
    public static final String RULE_EXECUTABLE_CONFIG = "executableConfig";
    /** 传输方式不向租户开放（stdio）。 */
    public static final String RULE_TRANSPORT_FORBIDDEN = "transportForbidden";
    /** 主体表指针与版本表不一致（如 currentVersion 指向不存在的已发布版本）。 */
    public static final String RULE_POINTER_BROKEN = "brokenVersionPointer";
    /**
     * 🔴 引用链<b>未完全递归</b>（仅 warning，不阻断）。
     *
     * <p>api-spec V1.1.2 §7.3.1 G10 裁决：{@code objectType=agentVersion} 的
     * {@code agentVersion → 绑定 → Skill/MCP/Tool} 递归校验<b>允许分阶段</b>，
     * 但在补齐前 🔴 <b>禁止把结果报成"全量校验通过"</b> ——
     * 必须以本 rule 在 {@code warnings[]} 明示，避免"伪报校验通过"。
     * 📋 收尾期限 = M3 签署前（@架构师 签署条件之一）。
     */
    public static final String RULE_REFERENCES_NOT_FULLY_CHECKED = "referencesNotFullyChecked";

    // ===== 🔴 跨模块共享规则（字面量唯一定义在 common/ViolationRules，禁止复制） =====
    //
    // 🔴 为什么引用而不是复制：这三条的判定点<b>有两处</b>——运行时（chat/tool，fail-closed 抛异常）
    //    与本聚合入口（收集 violations）。api-spec §7.3.1 G10 明确要求「用同一实现做同样判定」，
    //    复制字面量会在某次改名后静默漂移，表现为 @测试 用例"莫名查不到 violation"。
    /** system 提示总长超预算（api-spec §7.5.2 ①，判定实现在 {@code chat/ContextAssembler}）。 */
    public static final String RULE_SYSTEM_PROMPT_BUDGET_EXCEEDED =
            com.eyes.albedo.common.ViolationRules.SYSTEM_PROMPT_BUDGET_EXCEEDED;
    /** 归一化后的模型函数名超 64 字符（api-spec §7.6.5，判定实现在 {@code tool/ToolFunctionNames}）。 */
    public static final String RULE_FUNCTION_NAME_TOO_LONG =
            com.eyes.albedo.common.ViolationRules.FUNCTION_NAME_TOO_LONG;
    /** 两个 {@code toolKey} 归一化后同名（api-spec §7.6.5，判定实现在 {@code tool/ToolCatalogService}）。 */
    public static final String RULE_FUNCTION_NAME_COLLISION =
            com.eyes.albedo.common.ViolationRules.FUNCTION_NAME_COLLISION;
    /** 引用链自引用 / 成环（api-spec §7.3.1 G10；🔴 fail-closed，禁止靠栈深度兜底）。 */
    public static final String RULE_CIRCULAR_REFERENCE =
            com.eyes.albedo.common.ViolationRules.CIRCULAR_REFERENCE;

    public static ConfigViolationDTO of(String objectType, Long objectId, String field,
                                        String rule, String message) {
        return new ConfigViolationDTO(objectType,
                objectId == null ? null : String.valueOf(objectId), field, rule, message);
    }
}
