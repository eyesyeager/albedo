package com.eyes.albedo.tool.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.LocalToolHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内置本地 Tool 单测（api-spec §7.7.1 / ADR-015）。
 *
 * <p>🔴 {@code calculator} 是本地 Tool 侧最重要的安全评审对象（ADR-015「后果与风险」），
 * 因此逐条覆盖：超长表达式、非法字符、深嵌套括号、除零、{@code 1/3} 无限小数、
 * <b>以及"表达式里塞 Java/JS 代码片段"必须被挡住</b>。
 */
class BuiltinLocalToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CalculatorHandler calculator = new CalculatorHandler(objectMapper);
    private final DateTimeNowHandler datetime = new DateTimeNowHandler(objectMapper);

    /** 与库内 {@code local_tools.input_schema} 一致的阈值注解（🔴 阈值来源唯一）。 */
    private static final String CALC_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["expression"],
             "properties":{"expression":{"type":"string","minLength":1,"maxLength":200,
             "pattern":"^[0-9+*/(). -]+$"}},
             "x-limits":{"maxParenDepth":16,"maxDigits":30,"divisionScale":20}}""";

    // ===================== calculator =====================

    @Test
    @DisplayName("四则运算与优先级、括号、负号正确")
    void arithmetic() {
        assertEquals("7", calcResult("1+2*3"));
        assertEquals("9", calcResult("(1+2)*3"));
        assertEquals("-1", calcResult("2-3"));
        assertEquals("5", calcResult("-(-5)"));
        assertEquals("2.5", calcResult("5/2"));
        assertEquals("1", calcResult(" 0.5 + 0.5 "));
    }

    @Test
    @DisplayName("🔴 1/3 无限小数按 input_schema 的 divisionScale 收敛（不抛 ArithmeticException）")
    void repeatingDecimalConverges() {
        String result = calcResult("1/3");
        assertTrue(result.startsWith("0.3333333333"), result);
        // divisionScale=20 → 小数位不超过 20
        assertTrue(result.length() <= "0.".length() + 20, "小数位必须被 divisionScale 收敛：" + result);
    }

    @Test
    @DisplayName("🔴 除零 → 30057（工具执行业务失败，不是未分类异常）")
    void divisionByZero() {
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("1/0"));
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("1/(2-2)"));
    }

    @Test
    @DisplayName("🔴 括号不匹配 / 无法解析 → 30057")
    void malformedExpression() {
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("(1+2"));
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("1+2)"));
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("1 2"));
        assertEquals(ErrorCode.TOOL_EXECUTION_FAILED, calcError("*"));
    }

    @Test
    @DisplayName("🔴 深嵌套括号越界 → 30053（入口阈值取自 input_schema.x-limits，不是 Java 常量）")
    void parenDepthExceeded() {
        String deep = "(".repeat(20) + "1" + ")".repeat(20);
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, calcError(deep));
        // 深度 16 恰好可用（边界不多不少）
        String ok = "(".repeat(16) + "1" + ")".repeat(16);
        assertEquals("1", calcResult(ok));
    }

    @Test
    @DisplayName("🔴 单个数字过长 → 30053（防 BigDecimal 计算量放大）")
    void digitsExceeded() {
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, calcError("1".repeat(31)));
        assertTrue(calcResult("1".repeat(30)).length() == 30);
    }

    @Test
    @DisplayName("🔴 严禁 eval：塞入 Java / JS 代码片段一律被 30053 或 30057 挡住，绝不执行")
    void codeInjectionRejected() {
        // 这些字符本应先被 input_schema.pattern（30053）挡住；
        // 即便有人放宽了 Schema，解析器也必须拒绝（30057），这里断言"两道都不放行"
        for (String payload : new String[]{
                "Runtime.getRuntime().exec('rm -rf /')",
                "java.lang.System.exit(1)",
                "1;DROP TABLE tool_calls",
                "${1+1}", "#{2*2}", "2**10", "2^10", "eval('1+1')"}) {
            int code = calcError(payload);
            assertTrue(code == ErrorCode.TOOL_ARGS_INVALID || code == ErrorCode.TOOL_EXECUTION_FAILED,
                    "🔴 危险表达式必须被拒绝：" + payload + " 实际码=" + code);
        }
    }

    @Test
    @DisplayName("🔴 不支持幂运算（CPU 放大攻击面）：2^999999999 必须被拒绝而不是开始计算")
    void exponentiationUnsupported() {
        long start = System.currentTimeMillis();
        int code = calcError("2^999999999");
        assertTrue(code == ErrorCode.TOOL_ARGS_INVALID || code == ErrorCode.TOOL_EXECUTION_FAILED);
        assertTrue(System.currentTimeMillis() - start < 1000, "必须立即拒绝，不得进入计算");
    }

    @Test
    @DisplayName("缺少 expression / 入参非法 JSON → 30053")
    void missingExpression() {
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, errorOf(calculator, "{}"));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, errorOf(calculator, "not-json"));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, errorOf(calculator, "{\"expression\":\"  \"}"));
    }

    // ===================== datetime_now =====================

    @Test
    @DisplayName("🔴 缺省时区为 UTC（不是租户时区：保持纯函数、不新增 tool → platform 依赖边）")
    void defaultZoneIsUtc() throws Exception {
        String json = datetime.execute(invocation(datetime, "{}", null));
        assertEquals("UTC", objectMapper.readTree(json).get("timezone").asText());
        assertTrue(objectMapper.readTree(json).get("epochMillis").isNumber());
    }

    @Test
    @DisplayName("显式时区生效（模型经 Skill 内置变量 {{timezone}} 得知租户时区后传入）")
    void explicitZone() throws Exception {
        String json = datetime.execute(
                invocation(datetime, "{\"timezone\":\"Asia/Shanghai\"}", null));
        assertEquals("Asia/Shanghai", objectMapper.readTree(json).get("timezone").asText());
        assertTrue(objectMapper.readTree(json).get("iso8601").asText().contains("+08:00"));
    }

    @Test
    @DisplayName("🔴 Schema 通过但 IANA 无法解析的时区 → 30053（不执行、不回显内部异常）")
    void invalidZoneRejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                datetime.execute(invocation(datetime, "{\"timezone\":\"Asia/Atlantis\"}", null)));
        assertEquals(ErrorCode.TOOL_ARGS_INVALID, ex.getCode());
    }

    // ===================== 辅助 =====================

    private String calcResult(String expression) {
        try {
            String json = calculator.execute(invocation(calculator, args(expression), CALC_SCHEMA));
            return objectMapper.readTree(json).get("result").asText();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private int calcError(String expression) {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                calculator.execute(invocation(calculator, args(expression), CALC_SCHEMA)));
        return ex.getCode();
    }

    private int errorOf(LocalToolHandler handler, String argumentsJson) {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                handler.execute(invocation(handler, argumentsJson, CALC_SCHEMA)));
        return ex.getCode();
    }

    private String args(String expression) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("expression", expression));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private LocalToolHandler.LocalToolInvocation invocation(LocalToolHandler handler,
                                                            String argumentsJson, String schema) {
        return new LocalToolHandler.LocalToolInvocation("gift", 10086L, handler.toolKey(),
                argumentsJson, null, schema);
    }
}
