package com.eyes.albedo.tool.handler;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
 * 内置本地 Tool：当前时间（api-spec §7.7.1「一期平台内置本地 Tool 清单」/ ADR-015 ①）。
 *
 * <p><b>REQ-TOL-002 · AC-TOL-001 / AC-TOL-002</b>
 *
 * <p>🔴 <b>为什么它可以内置</b>（ADR-015 复核口径）：纯只读、无外部副作用、
 * <b>无网络、无数据库、无锁等待、无无界循环</b> —— 这正是
 * ADR-008 第 8 条「本地 Tool 超时只计时判定、不强制中断」得以成立的前提（AR-014 残余风险为零）。
 *
 * <p>🔴 <b>缺省时区为 UTC，不是租户时区</b>（api-spec §7.7.1 明确口径）：
 * 读 {@code tenants.timezone} 会引入一次数据库 IO，使本工具不再是纯函数，
 * 并新增 {@code tool → platform} 的依赖边（architecture.md §5.1.2 未授权该边）。
 * 👉 正确做法：租户时区经 Skill 内置变量 <code>{{timezone}}</code> 注入<b>系统提示</b>，
 * 由模型在调用时显式传 {@code timezone} 参数 —— 数据来源不变，工具保持纯函数。
 *
 * <p>🔴 <b>非法时区 → {@code 30053}</b>：Schema 的 {@code pattern} 只能挡住字符集，
 * 挡不住"格式合法但 IANA 不存在"（如 {@code Asia/Atlantis}）。
 * 这属<b>入参不合法</b>而非执行失败，故映射 {@code 30053}（前端动作：不重试同参）。
 * 🔴 异常消息只回固定措辞，不回显内部异常。
 */
@Slf4j
@Component
public class DateTimeNowHandler implements LocalToolHandler {

    /** 🔴 必须与 {@code local_tools.tool_key} 完全一致。 */
    public static final String TOOL_KEY = "datetime_now";

    /** 可选入参：IANA 时区 ID。 */
    private static final String ARG_TIMEZONE = "timezone";

    /** 输出字段（对外仍是"工具结果"，回灌模型前由调用方脱敏 + 截断）。 */
    private static final String FIELD_ISO = "iso8601";
    private static final String FIELD_EPOCH = "epochMillis";
    private static final String FIELD_TIMEZONE = "timezone";

    /**
     * 🔴 缺省时区（api-spec §7.7.1：缺省 <b>UTC</b>，不是租户时区）。
     *
     * <p>用 {@code ZoneId.of("UTC")} 而非 {@link ZoneOffset#UTC}：后者的 {@code getId()} 是
     * {@code "Z"}，回灌给模型时可读性差且容易被模型误读为"无时区"。
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

    private final ObjectMapper objectMapper;

    public DateTimeNowHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolKey() {
        return TOOL_KEY;
    }

    @Override
    public String execute(LocalToolInvocation invocation) {
        ZoneId zone = resolveZone(invocation.argumentsJson());
        Instant now = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(FIELD_ISO, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone)));
        result.put(FIELD_EPOCH, now.toEpochMilli());
        result.put(FIELD_TIMEZONE, zone.getId());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            // 序列化失败属实现缺陷而非入参问题 → 30057（工具执行业务失败）
            log.error("datetime_now 结果序列化失败", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }
    }

    /**
     * 解析时区（🔴 缺省 UTC；非法值 → {@code 30053}）。
     */
    private ZoneId resolveZone(String argumentsJson) {
        String raw = readTimezone(argumentsJson);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            // 🔴 只记键名不记值：时区本身不敏感，但保持"实现体不回显入参"的统一纪律
            log.warn("datetime_now 收到无法解析的时区参数，拒绝执行");
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "时区参数不是有效的 IANA 时区");
        }
    }

    private String readTimezone(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            if (!root.isObject()) {
                return null;
            }
            JsonNode node = root.get(ARG_TIMEZONE);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            // 入参不是合法 JSON：Schema 校验阶段本应已拦下（30053），此处兜底同码
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "入参不是合法 JSON 对象");
        }
    }
}
