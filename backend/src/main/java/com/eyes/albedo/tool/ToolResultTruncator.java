package com.eyes.albedo.tool;

import java.nio.charset.StandardCharsets;

import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.springframework.stereotype.Component;

/**
 * 工具结果截断（🔴 <b>ADR-011 第 3 条：两个"截断"必须严格区分，不得混用</b>）。
 *
 * <table border="1">
 *   <caption>两类截断的用途完全不同</caption>
 *   <tr><th>配置键</th><th>单位</th><th>截断对象</th><th>影响</th></tr>
 *   <tr>
 *     <td>{@code tool.result_max_bytes}</td><td><b>字节</b></td>
 *     <td>🔴 <b>回灌模型的结果体</b></td>
 *     <td>决定 SSE / {@code tool_calls} 的 {@code truncated} 字段（EX-017）</td>
 *   </tr>
 *   <tr>
 *     <td>{@code tool.result_summary_max_chars} / {@code tool.args_summary_max_chars}</td>
 *     <td><b>字符</b></td>
 *     <td>🔴 <b>摘要</b>（SSE / 落库 / 审计 / 查询接口四处共用同一份）</td>
 *     <td>只影响展示，不影响模型输入</td>
 *   </tr>
 * </table>
 *
 * <p>🔴 <b>为什么必须分开</b>：把摘要阈值（500 字符）用在回灌体上，模型会拿到被砍成 500 字的结果，
 * 后续推理直接失真；反过来把 1MB 用在摘要上，SSE 帧与 {@code tool_calls.result_summary}
 * 列会被撑爆（列宽 2048）。两者混用是"看起来能跑、实际两头都错"的典型缺陷。
 *
 * <p>🔴 <b>字节截断必须按 UTF-8 码点边界切</b>（ADR-011 第 5 条）：
 * 直接 {@code new String(bytes, 0, limit)} 会切出半个汉字，落库与下发都会出现乱码（U+FFFD）。
 */
@Component
public class ToolResultTruncator {

    /** 摘要被截断时的尾标（api-spec §5.2：被截断时以 {@code …} 结尾）。 */
    public static final String ELLIPSIS = "…";

    /**
     * 回灌模型时追加的确定性标记。
     *
     * <p>🔴 <b>非用户可见文案</b>（ADR-011 第 2 条）：它只进模型上下文，
     * 因此<b>不入</b> {@code sys_config}、<b>不入</b> i18n；前端展示一律用 {@code truncated} 字段。
     */
    public static final String TRUNCATION_MARKER = "\n[结果已截断]";

    private final BusinessConfig businessConfig;

    public ToolResultTruncator(BusinessConfig businessConfig) {
        this.businessConfig = businessConfig;
    }

    /**
     * 截断结果（含是否被截断的标记）。
     *
     * @param content   截断后的回灌内容
     * @param truncated 是否发生截断（决定 SSE / 落库的 {@code truncated} 字段）
     */
    public record Truncation(String content, boolean truncated) {
    }

    /**
     * 按<b>字节</b>截断回灌模型的结果体（{@code sys_config: tool.result_max_bytes}）。
     *
     * <p>🔴 按 UTF-8 码点边界切，绝不切出半个字符。
     */
    public Truncation truncateForModel(String content) {
        int maxBytes = businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_RESULT_MAX_BYTES);
        return truncateBytes(content, maxBytes);
    }

    /**
     * 按字节截断到指定上限（可测入口）。
     */
    public Truncation truncateBytes(String content, int maxBytes) {
        if (content == null || content.isEmpty()) {
            return new Truncation("", false);
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return new Truncation(content, false);
        }
        // 🔴 逐码点累加，保证不在多字节字符中间切断
        int consumed = 0;
        int endIndex = 0;
        int i = 0;
        while (i < content.length()) {
            int codePoint = content.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int size = utf8Length(codePoint);
            if (consumed + size > maxBytes) {
                break;
            }
            consumed += size;
            i += charCount;
            endIndex = i;
        }
        return new Truncation(content.substring(0, endIndex) + TRUNCATION_MARKER, true);
    }

    /**
     * 按<b>字符</b>截断入参摘要（{@code sys_config: tool.args_summary_max_chars}）。
     */
    public String truncateArgsSummary(String summary) {
        return truncateChars(summary, businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_ARGS_SUMMARY_MAX_CHARS));
    }

    /**
     * 按<b>字符</b>截断结果摘要（{@code sys_config: tool.result_summary_max_chars}）。
     */
    public String truncateResultSummary(String summary) {
        return truncateChars(summary, businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_RESULT_SUMMARY_MAX_CHARS));
    }

    /**
     * 按字符截断（超限以 {@link #ELLIPSIS} 结尾）。
     */
    public String truncateChars(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maxChars) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, Math.max(0, maxChars - 1));
        return value.substring(0, endIndex) + ELLIPSIS;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }
}
