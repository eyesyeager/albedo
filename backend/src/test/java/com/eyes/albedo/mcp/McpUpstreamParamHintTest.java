package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.common.ErrorCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>上游参数诊断字段的流向纪律</b>（ADR-018 ③ / 落点表 6，V1.4.2 新增）。
 *
 * <p>被守护的三条硬约束：
 * <ul>
 *   <li>🔴 <b>仅</b> {@link McpFailure#INVALID_PARAMS}（JSON-RPC {@code -32602}）可携带上游文本 ——
 *       它是"上游<b>对参数</b>说的话"；其余分类（连接失败 / TLS / 401 / 协议不兼容 / 超时）
 *       是"我们<b>对基础设施</b>的诊断"，可能含 endpoint、内网地址、凭据线索</li>
 *   <li>🔴 它<b>绝不进入</b> {@link Exception#getMessage()} —— 否则会顺着既有的日志 / 审计 /
 *       连接测试诊断链路一路外泄（{@code message} 是被广泛记录的字段）</li>
 *   <li>🔴 约束写在<b>构造函数</b>里而非靠调用方自觉：让"给 30052 塞上游正文"这类回归
 *       在编译产物层面不可能发生</li>
 * </ul>
 */
class McpUpstreamParamHintTest {

    @Test
    @DisplayName("🔴 INVALID_PARAMS 携带上游 error.message，且 30053 错误码不变")
    void invalidParamsCarriesHint() {
        McpTransportException ex = new McpTransportException(McpFailure.INVALID_PARAMS,
                "Invalid params: Mode must be one of 0,1,2");

        assertEquals("Invalid params: Mode must be one of 0,1,2", ex.upstreamParamHint());
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.errorCode());
    }

    @Test
    @DisplayName("🔴 上游诊断绝不出现在 getMessage()（message 只允许分类名 + 固定措辞）")
    void hintNeverLeaksIntoMessage() {
        McpTransportException ex = new McpTransportException(McpFailure.INVALID_PARAMS,
                "Invalid params: token=Bearer abc, endpoint=http://127.0.0.1:9011");

        assertFalse(ex.getMessage().contains("Bearer"), ex.getMessage());
        assertFalse(ex.getMessage().contains("127.0.0.1"), ex.getMessage());
        assertFalse(ex.getMessage().contains("Invalid params"), ex.getMessage());
        assertTrue(ex.getMessage().contains(McpFailure.INVALID_PARAMS.name()),
                "message 仍必须点名分类，供本地排障：" + ex.getMessage());
    }

    @Test
    @DisplayName("🔴 非 INVALID_PARAMS 分类即便被传入文本也一律丢弃（结构性防误用）")
    void otherFailuresDropHint() {
        for (McpFailure failure : McpFailure.values()) {
            if (failure == McpFailure.INVALID_PARAMS) {
                continue;
            }
            McpTransportException ex = new McpTransportException(failure,
                    "connect failed to https://169.254.169.254/latest/meta-data");

            assertNull(ex.upstreamParamHint(),
                    "🔴 " + failure + " 属平台/传输侧诊断，绝不允许承载上游文本");
        }
    }

    @Test
    @DisplayName("空白诊断视为无诊断（避免回灌出一句空话占 token）")
    void blankHintIsNormalizedToNull() {
        assertNull(new McpTransportException(McpFailure.INVALID_PARAMS, "   ").upstreamParamHint());
        assertNull(new McpTransportException(McpFailure.INVALID_PARAMS).upstreamParamHint());
        assertNull(new McpTransportException(McpFailure.INVALID_PARAMS,
                new IllegalStateException("cause")).upstreamParamHint());
    }
}
