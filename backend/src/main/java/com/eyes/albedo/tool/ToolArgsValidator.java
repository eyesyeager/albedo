package com.eyes.albedo.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具入参 JSON Schema 校验（🔴 <b>ADR-014</b>，api-spec §7.7.3 / §7.6.3 第 5 步）。
 *
 * <p><b>REQ-TOL-002 · AC-CHAT-007</b>
 *
 * <p>🔴 <b>这是安全边界，不是"顺手做个格式校验"</b>：
 * 放过的非法入参可能触发危险的下游操作（退款、导出、工单）。
 * 因此 ADR-014 明确否决了"手写子集校验器"与"只做浅校验"——
 * 子集实现的<b>漏判 = 校验通过但参数非法</b>，属安全缺陷。
 * 一期采用 {@code com.networknt:json-schema-validator}（draft 2020-12）。
 *
 * <p>🔴 <b>失败即 30053，且不发起任何执行</b>（api-spec §7.7.3）：
 * <ul>
 *   <li>只回<b>字段路径</b>（如 {@code /orderId}），🔴 <b>绝不回显值</b>
 *       —— 值可能是手机号、订单号、金额</li>
 *   <li>前端动作是"展示参数错误，<b>不重试同参</b>"（同参重试必然再次失败，纯浪费额度）</li>
 * </ul>
 *
 * <p>🔴 <b>Schema 自身非法 → 30060（不是 30053）</b>（ADR-014 第 4 条）：
 * 且必须在<b>构造工具清单阶段</b>就失败，不能等到模型请求调用时才暴露 ——
 * 否则错误会以"某用户偶发工具失败"的形式零散出现。
 *
 * <p>🔴 <b>一期不缓存编译后的 Schema</b>（ADR-014 第 3 条）：工具调用频率低，
 * 编译成本相对网络往返可忽略；避免引入新的进程内共享缓存及其准入例外与失效语义，
 * 也避免为缓存上限自造 {@code sys_config} 键。
 */
@Slf4j
@Component
public class ToolArgsValidator {

    private final ObjectMapper objectMapper;

    public ToolArgsValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验入参。
     *
     * @param toolKey       工具标识（仅用于日志，不进异常消息）
     * @param inputSchema   已注册的 JSON Schema（draft 2020-12）
     * @param argumentsJson 模型给出的入参 JSON
     * @throws BusinessException 30053 入参不符 Schema（{@code data.fields} 只含路径）；
     *                           30060 Schema 自身非法 / 入参不是合法 JSON 对象
     */
    public void validate(String toolKey, String inputSchema, String argumentsJson) {
        JsonSchema schema = compile(toolKey, inputSchema);
        if (schema == null) {
            // 无 Schema 视为"无参数约束"：不做校验（上游可能确实无入参）
            return;
        }
        JsonNode arguments = parseArguments(argumentsJson);
        Set<ValidationMessage> messages = schema.validate(arguments);
        if (messages.isEmpty()) {
            return;
        }
        List<String> fields = new ArrayList<>();
        for (ValidationMessage message : messages) {
            // 🔴 只取 instanceLocation（字段路径），绝不取 message（可能含入参值）
            String path = message.getInstanceLocation() == null
                    ? "/" : message.getInstanceLocation().toString();
            String normalized = path.isEmpty() ? "/" : path;
            if (!fields.contains(normalized)) {
                fields.add(normalized);
            }
        }
        // 🔴 日志也只记路径，不记入参
        log.warn("工具入参校验失败，拒绝执行：toolKey={} fields={}", toolKey, fields);
        throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID,
                ErrorCode.defaultMessage(ErrorCode.TOOL_ARGS_INVALID),
                Map.of("fields", fields));
    }

    /**
     * 编译 Schema（🔴 Schema 自身非法 → {@code 30060}）。
     *
     * @return {@code null} 表示未注册 Schema（无参数约束）
     */
    public JsonSchema compile(String toolKey, String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return null;
        }
        JsonNode schemaNode;
        try {
            schemaNode = objectMapper.readTree(inputSchema);
        } catch (Exception e) {
            throw schemaInvalid("入参 Schema 必须是合法 JSON");
        }
        if (!schemaNode.isObject()) {
            throw schemaInvalid("入参 Schema 根节点必须是对象");
        }
        try {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(schemaNode);
        } catch (Exception e) {
            log.warn("工具 Schema 无法编译：toolKey={}", toolKey);
            throw schemaInvalid("入参 Schema 无法编译（需符合 JSON Schema draft 2020-12）");
        }
    }

    /**
     * 校验 Schema 可用性（供清单构造阶段提前失败，ADR-014 第 4 条）。
     *
     * @param requireObjectRoot 本地 Tool 必须为 {@code object} 根类型（api-spec §7.7.1）
     * @throws BusinessException 30060
     */
    public void requireUsableSchema(String toolKey, String inputSchema, boolean requireObjectRoot) {
        JsonSchema schema = compile(toolKey, inputSchema);
        if (schema == null) {
            if (requireObjectRoot) {
                throw schemaInvalid("入参 Schema 不能为空");
            }
            return;
        }
        if (!requireObjectRoot) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(inputSchema);
            JsonNode type = node.get("type");
            if (type == null || !"object".equals(type.asText())) {
                throw schemaInvalid("入参 Schema 的根类型必须是 object");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw schemaInvalid("入参 Schema 必须是合法 JSON");
        }
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            // 模型给出的不是合法 JSON → 属入参问题（30053），不是配置问题
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID,
                    ErrorCode.defaultMessage(ErrorCode.TOOL_ARGS_INVALID),
                    Map.of("fields", List.of("/")));
        }
    }

    private BusinessException schemaInvalid(String message) {
        return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, message);
    }
}
