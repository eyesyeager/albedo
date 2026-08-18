package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工具入参 Schema 校验单测（🔴 ADR-014 / api-spec §7.7.3）。
 *
 * <p>🔴 这是<b>安全边界</b>：放过的非法入参可能触发危险的下游操作。
 * 因此这里刻意覆盖了"手写子集校验器最容易漏"的几类约束
 * （{@code required} / 类型 / 枚举 / 数值区间 / 嵌套对象 / {@code additionalProperties}）。
 */
class ToolArgsValidatorTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "orderId": {"type": "string", "minLength": 3},
                "amount": {"type": "number", "minimum": 0.01},
                "reason": {"type": "string", "enum": ["quality", "logistics"]},
                "operator": {
                  "type": "object",
                  "properties": {"uid": {"type": "string"}},
                  "required": ["uid"]
                }
              },
              "required": ["orderId", "amount"],
              "additionalProperties": false
            }
            """;

    private final ToolArgsValidator validator = new ToolArgsValidator(new ObjectMapper());

    @Test
    @DisplayName("合法入参 → 通过")
    void validArguments() {
        validator.validate("order_refund", SCHEMA,
                "{\"orderId\":\"A12345\",\"amount\":9.9,\"reason\":\"quality\"}");
    }

    @Test
    @DisplayName("🔴 缺必填字段 → 30053，且 data.fields 只含字段路径、绝不回显值")
    void missingRequiredField() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate("order_refund", SCHEMA,
                        "{\"orderId\":\"A12345\"}"));

        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.getCode());
        List<String> fields = fields(ex);
        assertFalse(fields.isEmpty());
        // 🔴 不得出现入参值
        assertFalse(String.valueOf(ex.getPayload()).contains("A12345"),
                String.valueOf(ex.getPayload()));
    }

    @Test
    @DisplayName("🔴 类型不符 → 30053（字段路径形如 /amount）")
    void typeMismatch() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate("order_refund", SCHEMA,
                        "{\"orderId\":\"A12345\",\"amount\":\"9.9\"}"));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.getCode());
        assertTrue(fields(ex).stream().anyMatch(field -> field.contains("amount")),
                String.valueOf(fields(ex)));
    }

    @Test
    @DisplayName("🔴 枚举越界 / 数值下界 / 字符串长度 —— 子集校验器最易漏的三类")
    void constraintViolations() {
        assertThrows(BusinessException.class, () -> validator.validate("t", SCHEMA,
                "{\"orderId\":\"A1\",\"amount\":1}"), "minLength 必须被校验");
        assertThrows(BusinessException.class, () -> validator.validate("t", SCHEMA,
                "{\"orderId\":\"A123\",\"amount\":0}"), "minimum 必须被校验");
        assertThrows(BusinessException.class, () -> validator.validate("t", SCHEMA,
                "{\"orderId\":\"A123\",\"amount\":1,\"reason\":\"other\"}"),
                "enum 必须被校验");
    }

    @Test
    @DisplayName("🔴 嵌套对象的必填字段 / additionalProperties=false 必须被校验")
    void nestedAndAdditionalProperties() {
        assertThrows(BusinessException.class, () -> validator.validate("t", SCHEMA,
                "{\"orderId\":\"A123\",\"amount\":1,\"operator\":{}}"),
                "嵌套 required 必须被校验");
        assertThrows(BusinessException.class, () -> validator.validate("t", SCHEMA,
                "{\"orderId\":\"A123\",\"amount\":1,\"evil\":\"x\"}"),
                "additionalProperties=false 必须被校验");
    }

    @Test
    @DisplayName("模型给出非法 JSON → 30053（属入参问题，不是配置问题）")
    void malformedArgumentsJson() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validate("t", SCHEMA, "not-json"));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.getCode());
    }

    @Test
    @DisplayName("🔴 Schema 自身非法 → 30060（配置问题，不是 30053）")
    void invalidSchemaIsConfigError() {
        BusinessException notJson = assertThrows(BusinessException.class,
                () -> validator.validate("t", "{oops", "{}"));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, notJson.getCode());

        BusinessException notObject = assertThrows(BusinessException.class,
                () -> validator.validate("t", "[1,2]", "{}"));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, notObject.getCode());
    }

    @Test
    @DisplayName("🔴 本地 Tool 的 Schema 根类型必须是 object → 否则 30060（清单构造阶段失败）")
    void localToolRequiresObjectRoot() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.requireUsableSchema("t", "{\"type\":\"array\"}", true));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());

        BusinessException empty = assertThrows(BusinessException.class,
                () -> validator.requireUsableSchema("t", null, true));
        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, empty.getCode());

        // MCP 工具宽松：无 Schema 视为无参数约束
        validator.requireUsableSchema("t", null, false);
    }

    @Test
    @DisplayName("无 Schema → 不做校验（上游可能确实无入参）")
    void noSchemaSkipsValidation() {
        validator.validate("t", null, "{\"anything\":1}");
        validator.validate("t", "", "{\"anything\":1}");
    }

    @SuppressWarnings("unchecked")
    private List<String> fields(BusinessException ex) {
        Map<String, Object> payload = (Map<String, Object>) ex.getPayload();
        return (List<String>) payload.get("fields");
    }
}
