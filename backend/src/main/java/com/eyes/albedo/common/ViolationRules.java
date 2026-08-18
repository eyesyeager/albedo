package com.eyes.albedo.common;

import java.util.List;
import java.util.Map;

/**
 * {@code 30060} 的 {@code data.violations[].rule} 字面量中，<b>跨模块共享</b>的那几项
 * （api-spec §7.3.1 / §7.5.2 ① / §7.6.5 ⑥ / G10 收尾裁决）。
 *
 * <p>🔴 <b>为什么放在 {@code common} 而不是 {@code configcheck.dto}</b>：
 * 这四条规则的<b>判定点有两处</b>，且分处不同模块：
 * <pre>
 * ① 运行时（fail-closed，抛 BusinessException）：
 *    systemPromptBudgetExceeded → chat/ContextAssembler
 *    functionNameTooLong / functionNameCollision → tool/ToolCatalogService
 * ② 聚合校验入口（收集 violations，不抛）：
 *    configcheck/RuntimeConfigValidator（🔴 契约要求"用同一实现做同样判定"）
 * </pre>
 * 若把字面量只留在 {@code configcheck.dto.ConfigViolationDTO}，{@code chat} / {@code tool}
 * 就必须反向依赖聚合层（{@code configcheck} 依赖 {@code tool}/{@code skill}，会成环，
 * architecture.md §5.1.2）；若各自复制一份字面量，两处判定就会在某次改名后<b>静默漂移</b>
 * —— 而 @测试 正是按 {@code rule} 字面量断言的，漂移表现为"用例莫名查不到 violation"。
 * {@code common} 是 L0 基础层，所有模块都可依赖，是唯一无环的落点。
 *
 * <p>🔴 纪律：新增 rule 必须先回写 api-spec，再在此登记，并同步
 * {@code configcheck.dto.ConfigViolationDTO} 的常量引用（后者直接引用本类，不复制字面量）。
 */
public final class ViolationRules {

    private ViolationRules() {
    }

    /**
     * system 消息总长（码点）超过 {@code sys_config: chat.system_prompt_max_chars}
     * （api-spec §7.5.2 ①）。
     *
     * <p>🔴 该判定<b>只在聚合层面可见</b>：单个 Skill 已受 {@code skill.instruction_max_chars}
     * 约束，"多 Skill 叠加后总长无上限"才是 fail-open 缺口。
     */
    public static final String SYSTEM_PROMPT_BUDGET_EXCEEDED = "systemPromptBudgetExceeded";

    /** 归一化后的模型函数名超过 64 字符（api-spec §7.6.5，🔴 拒绝不截断）。 */
    public static final String FUNCTION_NAME_TOO_LONG = "functionNameTooLong";

    /**
     * 两个不同 {@code toolKey} 归一化后同名（api-spec §7.6.5，🔴 fail-closed）。
     *
     * <p>不能静默少下发一个工具：那会表现为"AI 说它没有这个能力"，DBA 无从发现原因。
     */
    public static final String FUNCTION_NAME_COLLISION = "functionNameCollision";

    /**
     * 引用链自引用 / 成环（api-spec §7.3.1 G10 收尾）。
     *
     * <p>🔴 当前数据模型不可能成环，本规则是<b>防御性</b>约束：防二期新增引用类型时被静默递归爆栈。
     */
    public static final String CIRCULAR_REFERENCE = "circularReference";

    /**
     * 构造 {@code 30060} 的 {@code data} 载荷（形状与 api-spec §7.3.1 失败响应一致）。
     *
     * <p><b>🔴 谁能看到这段 data（api-spec §7.3.1 载荷形状裁定，V1.1.4 #2 —— 判据是
     * "调用者身份"，不是"错误码"）</b>：
     * <pre>
     * A 类 **管理端**（@Permission(ADMIN) / @TenantRole 保护：§7.3.1 校验入口、§7.4.2、§7.4.3）
     *   → 必须给完整 {objectType,objectId,valid,checkedObjects,violations[],warnings[]}
     * B 类 **终端用户路径**（SSE error 事件、/conversations/**、/messages/**）
     *   → 🔴 **仅 code + message**（data=null）：violations[] 含内部对象 ID 与配置拓扑，
     *     下发即把租户配置结构泄露给任意登录用户。
     * </pre>
     * 🔴 因此运行时抛出的 {@code BusinessException} 虽然<b>携带</b>本载荷（供 A 类校验入口复用
     * 同一实现），但 B 类出口（{@code chat/sse/SseWriter.error}）🔴 <b>只写 code + message</b>，
     * 🔴 <b>禁止</b>未来"补齐 violations"的反向改动。
     * 诊断不丢失的方式见 {@link #diagnostic(Object)}（ERROR 日志 + {@code requestId} 反查）。
     *
     * <p>🔴 {@code message} 禁含：正文片段、密钥、内部 IP/端口、堆栈、其他租户存在性
     * （api-spec §7.3.1 末尾）。
     */
    public static Map<String, Object> payload(String objectType, String objectId, String field,
                                              String rule, String message) {
        return Map.of(
                "objectType", objectType,
                "objectId", objectId == null ? "" : objectId,
                "valid", false,
                "checkedObjects", 1,
                "violations", List.of(Map.of(
                        "objectType", objectType,
                        "objectId", objectId == null ? "" : objectId,
                        "field", field,
                        "rule", rule,
                        "message", message)),
                "warnings", List.of());
    }

    /**
     * 从 {@link #payload} 载荷中提取<b>可安全写日志</b>的定位串
     * （{@code rule=… objectType:objectId=…}）。
     *
     * <p>🔴 <b>用途与硬约束（api-spec §7.3.1 载荷形状裁定 / §8.3 G-5，V1.1.4 #2）</b>：
     * <pre>
     * 运行时（SSE error 事件 / 面向终端用户的 JSON）的 30060 🔴 **只允许 code + message**：
     *   violations[] 含内部对象 ID 与配置拓扑，下发即把租户配置结构泄露给任意登录用户。
     * 但 🔴 **诊断不得丢失**：同一次失败必须留一条 ERROR 日志，含
     *   requestId + tenantId + agentVersion + rule + objectType:objectId，
     *   DBA 凭用户提供的 requestId 即可反查（字段级出口唯一 = §7.3.1 管理端校验入口）。
     * 👉 本方法只输出**规则名与对象标识**这类结构化标识，🔴 不输出 message / field 之外的正文，
     *    调用方仍须再过一层脱敏（audit/AuditSanitizer 或等价实现）。
     * </pre>
     *
     * @param payload {@code BusinessException.getPayload()}（形状可能不匹配，容错返回空串）
     * @return 形如 {@code rule=functionNameCollision objectType=mcpTool objectId=12}；无法解析返回 {@code ""}
     */
    public static String diagnostic(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return "";
        }
        Object violations = map.get("violations");
        if (!(violations instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> violation)) {
            return "";
        }
        return "rule=" + text(violation.get("rule"))
                + " field=" + text(violation.get("field"))
                + " objectType=" + text(violation.get("objectType"))
                + " objectId=" + text(violation.get("objectId"));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
