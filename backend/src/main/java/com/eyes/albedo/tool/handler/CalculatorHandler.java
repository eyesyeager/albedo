package com.eyes.albedo.tool.handler;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.tool.LocalToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内置本地 Tool：十进制四则运算计算器（api-spec §7.7.1 / ADR-015 ①②）。
 *
 * <p><b>REQ-TOL-002 · AC-TOL-001</b>
 *
 * <p>🔴 <b>它是本地 Tool 侧最重要的安全评审对象</b>（ADR-015「后果与风险」）：
 * 唯一接受<b>模型自由文本</b>并做解析的内置工具。因此实现纪律逐条不可省：
 * <ol>
 *   <li>🔴 <b>自写递归下降解析器</b>；严禁 {@code ScriptEngine} / Nashorn / SpEL / JEXL / OGNL /
 *       任何 {@code eval} 类设施 —— 那等于把"模型输出"变成"可执行代码"（RCE）</li>
 *   <li>🔴 <b>一期不支持幂运算</b>（{@code ^} / {@code **}）、阶乘、位运算、变量与函数调用：
 *       指数运算是 CPU 放大攻击面（{@code 2^999999999} 能在纯函数里烧满一个 aiStreamExecutor 线程）。
 *       字符集本身已由 {@code input_schema} 的 {@code pattern} 限定为
 *       「数字 与 加号 减号 乘号 斜杠 圆括号 小数点 空格」，
 *       因此 {@code ^} / 字母一律在<b>参数校验阶段</b>（{@code 30053}）就被挡住</li>
 *   <li>数值一律 {@link BigDecimal}；除法按 {@code divisionScale} 保留小数并四舍五入</li>
 *   <li>🔴 <b>阈值不在 Java 里写死、也不新增 {@code sys_config} 键</b>（ADR-015 ②）：
 *       表达式长度与字符集由 {@code input_schema} 的 {@code maxLength} / {@code pattern} 约束；
 *       括号深度 / 单个数字位数 / 除法精度由 {@code input_schema} 的<b>注解式扩展关键字</b>
 *       {@code x-limits} 给出（JSON Schema 2020-12 允许未知关键字作为注解，
 *       校验器会忽略它，故不影响入参校验）。缺失时按 {@code maxLength} 自然上界推导，
 *       🔴 绝不落回硬编码常量</li>
 *   <li>解析失败 / 括号不匹配 / 除零 / 溢出 → {@code 30057}（工具执行业务失败）；
 *       深度或数字位数越界 → {@code 30053}（属入参约束，前端动作"不重试同参"）。
 *       🔴 两者都不回显堆栈、不把内部异常消息回灌模型</li>
 * </ol>
 */
@Slf4j
@Component
public class CalculatorHandler implements LocalToolHandler {

    /** 🔴 必须与 {@code local_tools.tool_key} 完全一致。 */
    public static final String TOOL_KEY = "calculator";

    /** 必填入参。 */
    private static final String ARG_EXPRESSION = "expression";

    /** {@code input_schema} 中的注解式限额（🔴 阈值唯一来源，见类注释第 4 条）。 */
    private static final String SCHEMA_LIMITS = "x-limits";
    private static final String LIMIT_MAX_PAREN_DEPTH = "maxParenDepth";
    private static final String LIMIT_MAX_DIGITS = "maxDigits";
    private static final String LIMIT_DIVISION_SCALE = "divisionScale";

    private static final String FIELD_EXPRESSION = "expression";
    private static final String FIELD_RESULT = "result";

    private final ObjectMapper objectMapper;

    public CalculatorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolKey() {
        return TOOL_KEY;
    }

    @Override
    public String execute(LocalToolInvocation invocation) {
        String expression = readExpression(invocation.argumentsJson());
        Limits limits = Limits.from(invocation.inputSchemaJson(), objectMapper, expression.length());

        BigDecimal value = new Parser(expression, limits).parse();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_EXPRESSION, expression);
        // 🔴 result 以字符串返回：避免 JS 端的 number 精度丢失（api-spec §7.7.1）
        result.put(FIELD_RESULT, value.stripTrailingZeros().toPlainString());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("calculator 结果序列化失败", e);
            throw executionFailed("工具执行失败");
        }
    }

    private String readExpression(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw argsInvalid("缺少表达式参数");
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            JsonNode node = root.isObject() ? root.get(ARG_EXPRESSION) : null;
            if (node == null || node.isNull() || node.asText().isBlank()) {
                throw argsInvalid("缺少表达式参数");
            }
            return node.asText().trim();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw argsInvalid("入参不是合法 JSON 对象");
        }
    }

    private static BusinessException argsInvalid(String message) {
        return new BusinessException(ErrorCode.TOOL_ARGS_INVALID, message);
    }

    private static BusinessException executionFailed(String message) {
        return new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, message);
    }

    /**
     * 计算量上界（🔴 全部来自 {@code local_tools.input_schema}，无 Java 硬编码阈值）。
     *
     * @param maxParenDepth 括号最大嵌套深度
     * @param maxDigits     单个数字的最大字符数
     * @param divisionScale 除法保留的小数位数
     */
    record Limits(int maxParenDepth, int maxDigits, int divisionScale) {

        /**
         * 从 {@code input_schema} 读取；缺失时按<b>表达式长度自然上界</b>推导。
         *
         * <p>为什么可以这样兜底：表达式字符集与长度已由 Schema 限死（无幂运算、无函数），
         * 一个长度为 N 的表达式最多只能嵌套 N 层括号、最多只能有 N 位数字 ——
         * 用 N 作上界等价于"不额外收紧"，且<b>不引入任何字面量阈值</b>。
         */
        static Limits from(String inputSchemaJson, ObjectMapper mapper, int expressionLength) {
            int natural = Math.max(1, expressionLength);
            if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
                return new Limits(natural, natural, natural);
            }
            try {
                JsonNode limits = mapper.readTree(inputSchemaJson).path(SCHEMA_LIMITS);
                return new Limits(
                        positiveOr(limits.path(LIMIT_MAX_PAREN_DEPTH), natural),
                        positiveOr(limits.path(LIMIT_MAX_DIGITS), natural),
                        positiveOr(limits.path(LIMIT_DIVISION_SCALE), natural));
            } catch (Exception e) {
                // Schema 自身非法在清单构造阶段已以 30060 拦下；此处只做保守兜底
                return new Limits(natural, natural, natural);
            }
        }

        private static int positiveOr(JsonNode node, int fallback) {
            return node != null && node.isInt() && node.asInt() > 0 ? node.asInt() : fallback;
        }
    }

    /**
     * 递归下降解析器（🔴 无 eval、无反射、无脚本引擎）。
     *
     * <pre>
     * expr    := term (('+' | '-') term)*
     * term    := factor (('*' | '/') factor)*
     * factor  := ('+' | '-')? primary
     * primary := number | '(' expr ')'
     * </pre>
     */
    private static final class Parser {

        private final String source;
        private final Limits limits;
        private int cursor;
        private int depth;

        private Parser(String source, Limits limits) {
            this.source = source;
            this.limits = limits;
        }

        BigDecimal parse() {
            BigDecimal value = expression();
            skipSpaces();
            if (cursor != source.length()) {
                // 尾部有无法解析的残留（如 "1+2)" / "1 2"）
                throw executionFailed("表达式无法解析");
            }
            return value;
        }

        private BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipSpaces();
                char c = peek();
                if (c == '+') {
                    cursor++;
                    value = value.add(term());
                } else if (c == '-') {
                    cursor++;
                    value = value.subtract(term());
                } else {
                    return value;
                }
            }
        }

        private BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipSpaces();
                char c = peek();
                if (c == '*') {
                    cursor++;
                    value = value.multiply(factor());
                } else if (c == '/') {
                    cursor++;
                    BigDecimal divisor = factor();
                    if (divisor.signum() == 0) {
                        // 🔴 除零是业务失败，不是系统异常
                        throw executionFailed("表达式包含除以零");
                    }
                    value = value.divide(divisor, limits.divisionScale(), RoundingMode.HALF_UP);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal factor() {
            skipSpaces();
            char c = peek();
            if (c == '+') {
                cursor++;
                return factor();
            }
            if (c == '-') {
                cursor++;
                return factor().negate();
            }
            return primary();
        }

        private BigDecimal primary() {
            skipSpaces();
            char c = peek();
            if (c == '(') {
                cursor++;
                depth++;
                if (depth > limits.maxParenDepth()) {
                    // 🔴 入口约束越界 → 30053（把"防计算量放大"前移到参数层）
                    throw argsInvalid("表达式括号嵌套过深");
                }
                BigDecimal value = expression();
                skipSpaces();
                if (peek() != ')') {
                    throw executionFailed("表达式括号不匹配");
                }
                cursor++;
                depth--;
                return value;
            }
            return number();
        }

        private BigDecimal number() {
            int start = cursor;
            boolean dotSeen = false;
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (c >= '0' && c <= '9') {
                    cursor++;
                } else if (c == '.' && !dotSeen) {
                    dotSeen = true;
                    cursor++;
                } else {
                    break;
                }
            }
            int length = cursor - start;
            if (length == 0) {
                throw executionFailed("表达式无法解析");
            }
            if (length > limits.maxDigits()) {
                throw argsInvalid("表达式中的数字过长");
            }
            try {
                return new BigDecimal(source.substring(start, cursor));
            } catch (NumberFormatException e) {
                throw executionFailed("表达式无法解析");
            }
        }

        private void skipSpaces() {
            while (cursor < source.length() && source.charAt(cursor) == ' ') {
                cursor++;
            }
        }

        /** 越界返回 {@code '\0'}（不抛异常，由各产生式自行判定）。 */
        private char peek() {
            return cursor < source.length() ? source.charAt(cursor) : '\0';
        }
    }
}
