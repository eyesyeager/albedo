package com.eyes.albedo.tool;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.ViolationRules;

/**
 * 模型函数名归一化（🔴 api-spec V1.1.3 §7.6.5 的<b>唯一实现</b>，本地 Tool 与 MCP 工具共用）。
 *
 * <p><b>背景</b>：MCP 的 {@code toolKey} = {@code {mcpKey}:{toolName}} 含 {@code :}，
 * 而 OpenAI 兼容接口（混元）要求 {@code tools[].function.name} 匹配
 * {@code ^[a-zA-Z0-9_-]{1,64}$}。因此<b>下发给模型的函数名</b>与<b>契约中的 {@code toolKey}</b>
 * 必然是两个不同的标识符。
 *
 * <p><b>🔴 归一化算法（逐字符映射，不可自行"优化"）</b>：
 * <pre>
 * [a-zA-Z0-9_-] 原样保留；🔴 其余任何字符（含 : . 空格 中文 非 ASCII）一律替换为 _
 * 🔴 不做大小写转换、不做去重压缩：a::b → a__b，**不得**压成 a_b（压缩更易碰撞）
 * 示例：crm:lookup → crm_lookup；crm:lookup.v2 → crm_lookup_v2
 *      本地 Tool datetime_now → datetime_now（tool_key 受 ^[a-z][a-z0-9_]{1,63}$ 约束，恒等映射）
 * </pre>
 *
 * <p><b>🔴 为什么超长与碰撞都 fail-closed（{@code 30060}）而不是"静默少下发一个工具"</b>：
 * <ol>
 *   <li>静默少下发 = 模型看不到该工具 → 表现为"AI 说它没有这个能力"，
 *       DBA 无法从任何地方发现原因，属最难排查的静默降级
 *       （与 §7.5.2 ⑤ 否决"截断最低优先级片段"同一条理由）；</li>
 *   <li>{@code 30060} 是<b>配置非法</b>语义，且 §7.3.1 的校验入口用<b>同一实现</b>做同样判定 ——
 *       DBA 可在用户对话<b>之前</b>发现冲突（这正是 AC-CFG-003 的价值）；</li>
 *   <li>🔴 "截断 + 追加 hash 后缀"方案<b>已被否决</b>：会产出人类不可读的函数名
 *       （如 {@code crm_look_9f2a}），排障时无法把日志里的函数名对回 {@code toolKey}。</li>
 * </ol>
 *
 * <p>🔴 <b>反向解析必须靠映射表</b>（{@code chat/ChatStreamRunner} 持有
 * {@code Map<functionName, 定义>}）：{@code _ → :} <b>不可逆</b> ——
 * {@code a_b} 无法判断原文是 {@code a:b} 还是 {@code a_b}，猜错等于<b>执行了用户没批准的工具</b>。
 * 🔴 全代码库禁止出现 {@code replace("_", ":")} 一类的字符串还原。
 *
 * <p>🔴 <b>不新增错误码、不新增 {@code sys_config} 键</b>：字符集与 64 长度上限来自
 * <b>上游接口协议</b>（非业务参数），写在契约里即为基线，禁止入库、也禁止另设可调开关。
 */
public final class ToolFunctionNames {

    private ToolFunctionNames() {
    }

    /**
     * 上游协议对 {@code function.name} 的长度上限（🔴 协议常量，非业务阈值，故不入 {@code sys_config}）。
     */
    public static final int MAX_LENGTH = 64;

    /**
     * 归一化（🔴 不截断 —— 超长由 {@link #requireWithinLength} 判为 {@code 30060}）。
     */
    public static String normalize(String toolKey) {
        if (toolKey == null || toolKey.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(toolKey.length());
        for (int i = 0; i < toolKey.length(); i++) {
            char c = toolKey.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(legal ? c : '_');
        }
        return sb.toString();
    }

    /** 归一化后是否超出上游长度上限。 */
    public static boolean tooLong(String functionName) {
        return functionName != null && functionName.length() > MAX_LENGTH;
    }

    /**
     * 长度强制校验（🔴 拒绝，<b>不截断</b>）。
     *
     * @param objectType {@code violations[].objectType}（MCP 工具为 {@code mcpTool}，本地为 {@code localTool}）
     * @throws BusinessException 30060 {@code rule=functionNameTooLong}
     */
    public static void requireWithinLength(String toolKey, String functionName, String objectType,
                                           Long objectId) {
        if (!tooLong(functionName)) {
            return;
        }
        throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                "工具函数名超出长度上限（" + MAX_LENGTH + " 字符），请缩短 mcpKey 或工具名",
                ViolationRules.payload(objectType,
                        objectId == null ? null : String.valueOf(objectId),
                        "toolKey", ViolationRules.FUNCTION_NAME_TOO_LONG,
                        "工具函数名超出长度上限，请缩短 mcpKey 或工具名"));
    }

    /**
     * 碰撞拒绝（🔴 fail-closed）。
     *
     * <p>🔴 {@code message} 只回"工具函数名冲突，请调整 mcpKey 或工具名"，
     * <b>不回显</b>另一方的完整 {@code toolKey}（同租户内可回显 {@code mcpKey}，
     * 🔴 但跨租户信息一律禁回 —— 清单构造天然同租户，此处仍按最小披露处理）。
     *
     * @throws BusinessException 30060 {@code rule=functionNameCollision}
     */
    public static BusinessException collision(String objectType, Long objectId) {
        return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                "工具函数名冲突，请调整 mcpKey 或工具名",
                ViolationRules.payload(objectType,
                        objectId == null ? null : String.valueOf(objectId),
                        "toolKey", ViolationRules.FUNCTION_NAME_COLLISION,
                        "工具函数名冲突，请调整 mcpKey 或工具名"));
    }
}
