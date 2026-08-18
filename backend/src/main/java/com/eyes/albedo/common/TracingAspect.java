package com.eyes.albedo.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;

/**
 * 全量调用日志切面。
 *
 * <p>对 {@code @RestController} 与 {@code @Service} 的所有方法做环绕拦截，
 * 统一打印：方法签名、入参、出参（或异常）、耗时。一次性为整站提供"大量日志"，
 * 配合 logback 滚动文件落盘，便于本地排查。
 *
 * <p>注意：切面仅记录 INFO 级别；生产可通过 {@code logging.level.com.eyes.albedo.common.TracingAspect=OFF}
 * 或在 application.yml 将其设为 WARN 关闭。敏感字段（如 authorization token）不建议出现在入参日志中，
 * 因此这里对名称包含 token/secret/password/authorization 的参数做脱敏。
 */
@Slf4j
@Aspect
@Order(10)
@Component
public class TracingAspect {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 入参/出参 JSON 序列化上限，避免超大对象（如文件流、长文本）撑爆日志 */
    private static final int MAX_PAYLOAD_CHARS = 2000;

    /** 需要脱敏的参数名关键词（小写匹配） */
    private static final String[] SENSITIVE_KEYWORDS = {
            "token", "secret", "password", "password", "authorization", "credential", "cookie"
    };

    @Around("(@within(org.springframework.web.bind.annotation.RestController)" +
            " || @within(org.springframework.stereotype.Service)" +
            " || @within(org.springframework.stereotype.Repository))" +
            " && !within(com.eyes.albedo.common.TracingAspect)")
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String className = signature.getDeclaringType().getSimpleName();
        String methodName = signature.getName();
        String fullMethod = className + "." + methodName;

        Object[] args = pjp.getArgs();
        String argStr = safeSerializeArgs(signature.getParameterNames(), args);

        long start = System.nanoTime();
        log.info("[TRACE] ▶ {}({})", fullMethod, argStr);

        try {
            Object result = pjp.proceed();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            String resultStr = safeSerialize(result);
            log.info("[TRACE] ◀ {} 完成 耗时={}ms 返回={}", fullMethod, elapsedMs, resultStr);
            return result;
        } catch (Throwable t) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[TRACE] ✖ {} 异常 耗时={}ms {}: {}",
                    fullMethod, elapsedMs, t.getClass().getSimpleName(), t.getMessage(), t);
            throw t;
        }
    }

    private String safeSerializeArgs(String[] paramNames, Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < args.length; i++) {
            String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : ("arg" + i);
            Object value = args[i];
            if (isSensitive(name)) {
                joiner.add(name + "=***");
            } else {
                joiner.add(name + "=" + safeSerialize(value));
            }
        }
        return joiner.toString();
    }

    private boolean isSensitive(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        for (String kw : SENSITIVE_KEYWORDS) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private String safeSerialize(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_PAYLOAD_CHARS) {
                return json.substring(0, MAX_PAYLOAD_CHARS) + "...(truncated,len=" + json.length() + ")";
            }
            return json;
        } catch (JsonProcessingException e) {
            return value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
        }
    }
}
