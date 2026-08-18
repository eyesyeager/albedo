package com.eyes.albedo.chat.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 流式 {@code tool_calls} 分片累积的确定性回归测试（🔴 不依赖真实上游，故进常规 {@code mvn test}）。
 *
 * <p>锁定的缺陷：混元在 {@code finish_reason=tool_calls} 之前会连发两个分片 ——
 * 首片带 {@code id + function.name}、次片把 {@code name} 回传为 <b>空字符串</b>并携带
 * {@code arguments} 增量。旧实现以「字段存在即赋值」处理，导致次片的 {@code ""}
 * <b>覆盖</b>首片已拿到的函数名，{@code AiToolCall.usable()} 随即为 false，
 * 整次工具调用被丢弃 —— 终端表现为「问『现在是几号』模型毫无响应」。
 *
 * <p>🔴 因此本测试的断言不是"实现细节"，而是<b>上游兼容性契约</b>：
 * 空串不得覆盖已累积的 id / name。
 */
class ToolCallAccumulationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiChatClient client = new AiChatClient(null, null, objectMapper, null);

    /** 反射调用私有累积方法（保持生产代码可见性不为测试放宽）。 */
    private void accumulate(Map<Integer, Object> buffer, String json) throws Exception {
        Method method = AiChatClient.class.getDeclaredMethod("accumulateToolCalls",
                JsonNode.class, Map.class);
        method.setAccessible(true);
        method.invoke(client, objectMapper.readTree(json), buffer);
    }

    @SuppressWarnings("unchecked")
    private List<AiToolCall> finish(Map<Integer, Object> buffer) throws Exception {
        Method method = AiChatClient.class.getDeclaredMethod("finishToolCalls", Map.class);
        method.setAccessible(true);
        return (List<AiToolCall>) method.invoke(client, buffer);
    }

    @Test
    @DisplayName("混元真实分片序列：次片的空 name 不得覆盖首片函数名（回归 datetime_now 无响应）")
    void hunyuanEmptyNameMustNotOverride() throws Exception {
        Map<Integer, Object> buffer = new LinkedHashMap<>();

        // 以下两帧为混元 hunyuan-a13b 的实际返回（已抓包核对，仅省略无关字段）
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"id":"call_da0n2pk2c3mcmj6ghdb0","index":0,"type":"function",
                   "function":{"name":"datetime_now","arguments":""}}]}}]}
                """);
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"id":"call_da0n2pk2c3mcmj6ghdb0","index":0,"type":"function",
                   "function":{"name":"","arguments":"{}"}}]}}]}
                """);

        List<AiToolCall> calls = finish(buffer);

        assertEquals(1, calls.size(), "两个同 index 分片必须聚合为一次调用，且不得被丢弃");
        AiToolCall call = calls.get(0);
        assertEquals("datetime_now", call.name(), "🔴 空串不得覆盖已累积的函数名");
        assertEquals("call_da0n2pk2c3mcmj6ghdb0", call.id(), "id 必须保留以回灌 tool_call_id");
        assertEquals("{}", call.argumentsJson());
        assertTrue(call.usable());
    }

    @Test
    @DisplayName("参数增量按到达顺序拼接为完整 JSON")
    void argumentsAreConcatenatedInOrder() throws Exception {
        Map<Integer, Object> buffer = new LinkedHashMap<>();
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"id":"call_1","index":0,"function":{"name":"calculator","arguments":""}}]}}]}
                """);
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"index":0,"function":{"name":"","arguments":"{\\"expr"}}]}}]}
                """);
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"index":0,"function":{"name":"","arguments":"ession\\":\\"1+1\\"}"}}]}}]}
                """);

        List<AiToolCall> calls = finish(buffer);

        assertEquals(1, calls.size());
        assertEquals("calculator", calls.get(0).name());
        assertEquals("{\"expression\":\"1+1\"}", calls.get(0).argumentsJson(),
                "分片必须按到达顺序拼成完整 JSON");
    }

    @Test
    @DisplayName("多个并行工具调用按 index 各自聚合，互不串扰")
    void multipleToolCallsAggregateByIndex() throws Exception {
        Map<Integer, Object> buffer = new LinkedHashMap<>();
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"id":"call_a","index":0,"function":{"name":"datetime_now","arguments":"{}"}},
                  {"id":"call_b","index":1,"function":{"name":"calculator","arguments":""}}]}}]}
                """);
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"index":1,"function":{"name":"","arguments":"{\\"expression\\":\\"2*3\\"}"}}]}}]}
                """);

        List<AiToolCall> calls = finish(buffer);

        assertEquals(2, calls.size());
        assertEquals("datetime_now", calls.get(0).name());
        assertEquals("{}", calls.get(0).argumentsJson());
        assertEquals("calculator", calls.get(1).name());
        assertEquals("{\"expression\":\"2*3\"}", calls.get(1).argumentsJson());
    }

    @Test
    @DisplayName("真正缺 name 的分片仍必须丢弃（不得因放宽覆盖规则而回到 fail-open）")
    void trulyMissingNameIsStillDiscarded() throws Exception {
        Map<Integer, Object> buffer = new LinkedHashMap<>();
        accumulate(buffer, """
                {"choices":[{"index":0,"delta":{"tool_calls":[
                  {"id":"call_x","index":0,"function":{"arguments":"{}"}}]}}]}
                """);

        List<AiToolCall> calls = finish(buffer);

        assertTrue(calls.isEmpty(), "从未出现过函数名的调用无法执行，必须丢弃");
    }

    @Test
    @DisplayName("非流式 message.tool_calls 一次性返回也能解析")
    void nonStreamingShapeIsSupported() throws Exception {
        Map<Integer, Object> buffer = new LinkedHashMap<>();
        accumulate(buffer, """
                {"choices":[{"index":0,"message":{"tool_calls":[
                  {"id":"call_z","index":0,"function":{"name":"datetime_now","arguments":"{}"}}]}}]}
                """);

        List<AiToolCall> calls = finish(buffer);

        assertEquals(1, calls.size());
        assertEquals("datetime_now", calls.get(0).name());
        assertFalse(calls.get(0).id().isEmpty());
    }
}
