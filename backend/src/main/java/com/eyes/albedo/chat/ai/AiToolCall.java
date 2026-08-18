package com.eyes.albedo.chat.ai;

/**
 * 模型请求的一次工具调用（OpenAI 兼容 {@code tool_calls[]} 的最小投影）。
 *
 * <p>🔴 <b>按不可信输入处理</b>：{@code name} 可能是模型幻觉出的、我们从未下发过的函数名；
 * {@code argumentsJson} 可能不是合法 JSON、也可能不符合已注册 Schema。
 * 因此二者必须分别经过<b>授权判定</b>（{@code 30050}）与
 * <b>JSON Schema 校验</b>（{@code 30053}）才允许进入执行（api-spec §7.6.3）。
 *
 * @param id            上游给出的 {@code tool_call.id}（🔴 回灌 {@code role=tool} 消息时必须原样带回，
 *                      否则上游会因"tool_call_id 不匹配"直接报错）
 * @param name          模型选择的函数名（= {@code ToolDefinition.functionName()}）
 * @param argumentsJson 入参 JSON 文本（流式下由多个分片拼接而成）
 */
public record AiToolCall(String id, String name, String argumentsJson) {

    public AiToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }

    public boolean usable() {
        return !id.isEmpty() && !name.isEmpty();
    }
}
