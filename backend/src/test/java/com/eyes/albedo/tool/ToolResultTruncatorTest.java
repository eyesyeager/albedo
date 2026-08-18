package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 结果截断单测（🔴 ADR-011 第 3 / 第 5 条）。
 *
 * <p>核心断言：
 * <ul>
 *   <li>🔴 <b>字节</b>截断（回灌体）与<b>字符</b>截断（摘要）互不混用</li>
 *   <li>🔴 字节截断按 UTF-8 码点边界切，绝不切出半个汉字（否则落库与下发都是乱码）</li>
 *   <li>阈值全部来自 {@code sys_config}，代码中无字面量</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolResultTruncatorTest {

    @Mock
    private BusinessConfig businessConfig;

    private ToolResultTruncator truncator;

    @BeforeEach
    void setUp() {
        truncator = new ToolResultTruncator(businessConfig);
    }

    @Test
    @DisplayName("🔴 字节截断必须落在 UTF-8 码点边界（不得切出半个汉字）")
    void bytesTruncationRespectsCodePoints() {
        // "壹" = 3 字节；上限 7 字节 → 只能放 2 个字（6 字节）
        String content = "壹".repeat(10);
        ToolResultTruncator.Truncation truncation = truncator.truncateBytes(content, 7);

        assertTrue(truncation.truncated());
        String kept = truncation.content().replace(ToolResultTruncator.TRUNCATION_MARKER, "");
        assertEquals(2, kept.length(), "应保留 2 个完整汉字：" + kept);
        assertFalse(kept.contains("\uFFFD"), "🔴 出现替换字符说明切坏了多字节字符");
        assertEquals(6, kept.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("未超限 → 原样返回且 truncated=false（不追加任何标记）")
    void withinLimitUnchanged() {
        ToolResultTruncator.Truncation truncation = truncator.truncateBytes("hello", 1024);
        assertFalse(truncation.truncated());
        assertEquals("hello", truncation.content());
    }

    @Test
    @DisplayName("🔴 截断后回灌体带确定性标记（模型侧提示常量，非用户可见文案，不入 sys_config）")
    void truncationMarkerAppended() {
        ToolResultTruncator.Truncation truncation = truncator.truncateBytes("abcdefghij", 4);
        assertTrue(truncation.content().endsWith(ToolResultTruncator.TRUNCATION_MARKER));
        assertEquals("abcd", truncation.content()
                .replace(ToolResultTruncator.TRUNCATION_MARKER, ""));
    }

    @Test
    @DisplayName("回灌体上限取 sys_config: tool.result_max_bytes（代码中无 1048576 字面量）")
    void modelLimitFromConfig() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_MAX_BYTES))).thenReturn(8);

        ToolResultTruncator.Truncation truncation = truncator.truncateForModel("0123456789");
        assertTrue(truncation.truncated());
        assertEquals("01234567", truncation.content()
                .replace(ToolResultTruncator.TRUNCATION_MARKER, ""));
    }

    @Test
    @DisplayName("🔴 摘要按【字符】截断并以 … 结尾（阈值取 sys_config，与字节上限互不混用）")
    void summaryTruncatedByChars() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_SUMMARY_MAX_CHARS))).thenReturn(5);
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_ARGS_SUMMARY_MAX_CHARS))).thenReturn(3);

        assertEquals("壹壹壹壹" + ToolResultTruncator.ELLIPSIS,
                truncator.truncateResultSummary("壹".repeat(10)));
        assertEquals("ab" + ToolResultTruncator.ELLIPSIS,
                truncator.truncateArgsSummary("abcdef"));
    }

    @Test
    @DisplayName("🔴 两类截断不可混用：1MB 字节上限不会把摘要放过 500 字符")
    void byteAndCharLimitsAreIndependent() {
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_MAX_BYTES))).thenReturn(1024 * 1024);
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_TOOL),
                eq(ConfigKeys.TOOL_RESULT_SUMMARY_MAX_CHARS))).thenReturn(500);

        String content = "x".repeat(2000);
        // 回灌体未超字节上限 → 不截断
        assertFalse(truncator.truncateForModel(content).truncated());
        // 摘要按字符上限截断
        assertEquals(500, truncator.truncateResultSummary(content)
                .codePointCount(0, truncator.truncateResultSummary(content).length()));
    }

    @Test
    @DisplayName("空值安全（null / 空串不产生 NPE、不产生假截断）")
    void nullSafe() {
        assertEquals("", truncator.truncateBytes(null, 10).content());
        assertFalse(truncator.truncateBytes(null, 10).truncated());
        assertEquals("", truncator.truncateChars(null, 10));
    }
}
