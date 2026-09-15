package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.ViolationRules;
import com.eyes.albedo.tool.dto.ToolDefinition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型函数名归一化与回映射（api-spec V1.1.3 §7.6.5 ⑥ 裁决）。
 *
 * <p>🔴 本用例守护三件事：
 * <ol>
 *   <li>归一化算法<b>逐字符映射且不压缩</b>（压缩会显著提高碰撞概率）</li>
 *   <li>超长与碰撞一律 {@code 30060} + 对应 {@code rule}（🔴 拒绝，不截断、不静默少下发）</li>
 *   <li>🔴 <b>全代码库不存在字符串还原</b>（{@code _ → :} 不可逆；猜错等于执行用户没批准的工具）</li>
 * </ol>
 */
class ToolFunctionNamesTest {

    @Test
    @DisplayName("🔴 §7.6.5：逐字符映射，crm:lookup → crm_lookup；不做大小写转换")
    void normalizesIllegalCharacters() {
        assertEquals("crm_lookup", ToolFunctionNames.normalize("crm:lookup"));
        assertEquals("crm_lookup_v2", ToolFunctionNames.normalize("crm:lookup.v2"));
        assertEquals("CRM_Lookup", ToolFunctionNames.normalize("CRM:Lookup"),
                "🔴 不得做大小写转换（会与另一个只差大小写的 toolKey 撞名）");
        assertEquals("a_b_c", ToolFunctionNames.normalize("a b/c"));
        assertEquals("_", ToolFunctionNames.normalize("中"),
                "非 ASCII 字符逐个替换为下划线");
    }

    @Test
    @DisplayName("🔴 §7.6.5：连续非法字符**不压缩**（a::b → a__b，压缩更易碰撞）")
    void doesNotCollapseConsecutiveUnderscores() {
        assertEquals("a__b", ToolFunctionNames.normalize("a::b"));
        assertEquals("a___b", ToolFunctionNames.normalize("a:.:b"));
    }

    @Test
    @DisplayName("本地 tool_key 天然合规 → 恒等映射（datetime_now / calculator）")
    void localToolKeysAreIdentityMapped() {
        assertEquals("datetime_now", ToolFunctionNames.normalize("datetime_now"));
        assertEquals("calculator", ToolFunctionNames.normalize("calculator"));
        assertEquals("a-b_c9", ToolFunctionNames.normalize("a-b_c9"));
    }

    @Test
    @DisplayName("🔴 归一化后 >64 字符 → 30060 rule=functionNameTooLong（拒绝，不截断）")
    void tooLongIsRejectedNotTruncated() {
        String toolKey = "m".repeat(60) + ":" + "t".repeat(10);
        String functionName = ToolFunctionNames.normalize(toolKey);
        assertEquals(71, functionName.length(), "🔴 归一化本身不得截断");
        assertTrue(ToolFunctionNames.tooLong(functionName));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                ToolFunctionNames.requireWithinLength(toolKey, functionName, "mcpTool", 7L));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload())
                        .contains(ViolationRules.FUNCTION_NAME_TOO_LONG),
                "🔴 violations[].rule 必须是 functionNameTooLong：" + ex.getPayload());
        assertFalse(ex.getMessage().contains(toolKey), "🔴 message 不得回显完整 toolKey");
    }

    @Test
    @DisplayName("恰好 64 字符 → 放行（边界值，不得 off-by-one 误杀）")
    void exactlyMaxLengthIsAccepted() {
        String functionName = "x".repeat(ToolFunctionNames.MAX_LENGTH);
        assertFalse(ToolFunctionNames.tooLong(functionName));
        ToolFunctionNames.requireWithinLength(functionName, functionName, "mcpTool", 1L);
    }

    @Test
    @DisplayName("🔴 碰撞异常携带 rule=functionNameCollision，且不回显另一方 toolKey")
    void collisionCarriesRule() {
        BusinessException ex = ToolFunctionNames.collision("mcpTool", 12L);
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload())
                .contains(ViolationRules.FUNCTION_NAME_COLLISION), String.valueOf(ex.getPayload()));
    }

    @Test
    @DisplayName("🔴 ToolDefinition.functionName 由 toolKey 派生，且 toolKey 保持原样（对外契约标识符）")
    void definitionCarriesBothIdentifiers() {
        ToolDefinition definition = ToolDefinition.of(ToolDefinition.TYPE_MCP, "crm:lookup_user",
                "lookup_user", "", null, "", false, 30, 1L, null);

        assertEquals("crm:lookup_user", definition.toolKey(),
                "🔴 toolKey 是对外契约标识符（SSE / tool_calls / 审计都记它），不得被归一化污染");
        assertEquals("crm_lookup_user", definition.functionName());
    }
}
