package com.eyes.albedo.common;

import java.util.Map;
import java.util.stream.Collectors;

import com.eyes.albedo.auth.AuthCodeException;
import com.eyes.albedo.chat.ai.AiStreamException;
import com.eyes.eyesAuth.exception.EyesAuthException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器 —— 保证 {@code /api/v1/**} 任何情况下都返回 <b>HTTP 200 + 业务码</b>（框架 §14.1）。
 *
 * <p>映射规则：
 * <pre>
 *   BusinessException                → 其自带 code（已登记的业务码）
 *   EyesAuthException / AuthCode     → 20000~20005（耶瞳保留段，前端据此清 token 跳 SSO）
 *   参数类异常                        → 10001
 *   找不到处理器 / 静态资源            → 10004
 *   乐观锁冲突                        → 30020
 *   数据库 / 未分类异常                → 50003
 * </pre>
 *
 * <p>🔴 <b>传输层不变量（ADR-021 / api-spec §1.2.1，V1.4.8 新增，本类是唯一实现处）</b>：
 * <pre>
 * ① 请求的 `Accept` 头**不得**改变 /api/v1/** 的响应形态；
 *    Accept: 通配 / application/json / text/event-stream 三者的建流前失败响应必须逐字节可比。
 * ② HTTP 状态码口径**不二分**：SSE 端点的**建流前**失败同样恒 HTTP 200 + application/json。
 * </pre>
 * 🔴 实现手段只有一条（见 {@link #json(Result)}）：每个"有响应体"的处理方法返回
 * {@code ResponseEntity<Result<T>>} 并<b>显式</b>预设 {@code Content-Type: application/json}，
 * 从而<b>整段跳过</b> Spring 的内容协商。
 * 🔴 <b>禁止</b>的替代路径（ADR-021 逐条否决）：按端点白名单特判、给 SSE 端点声明 {@code produces}、
 * 升 Boot 用 {@code @ExceptionHandler(produces=)}、注册能把 {@code Result} 写成
 * {@code text/event-stream} 的转换器、把限流/额度改走 SSE {@code error} 帧。
 *
 * <p>🔴 站点级非 200 响应只允许出现在 {@code GET /site/status}（由 Controller 显式设置状态码，
 * 不经过本处理器）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** MDC 中的请求追踪键（与 {@code TenantFilter} / {@code ChatStreamRunner} 同名）。 */
    private static final String MDC_REQUEST_ID = "requestId";

    /**
     * 🔴 <b>本类唯一允许的响应体写出方式</b>（ADR-021 (d2)，承重实现）。
     *
     * <p><b>机制</b>：{@code AbstractMessageConverterMethodProcessor.writeWithMessageConverters}
     * 的第一步是 {@code contentType = outputMessage.getHeaders().getContentType()}；
     * 当它是**具体**类型时即视为"预设"，直接 {@code selectedMediaType = contentType} 并
     * <b>整段跳过内容协商</b>（{@code isContentTypePreset} 分支）。
     *
     * <p>🔴 <b>不这样写会怎样</b>（BUG-QUOTA-001 实测）：直接返回 {@code Result} 时，
     * 写出阶段执行内容协商 —— {@code acceptable={text/event-stream}}（真实浏览器的 SSE 请求）
     * ∩ {@code producible={application/json,…}} <b>= ∅</b> →
     * {@code HttpMediaTypeNotAcceptableException}（发生在异常处理链<b>内部</b>，
     * {@code ExceptionHandlerExceptionResolver} 只记一条 WARN 并返回 null）→ 解析链耗尽 →
     * 原始异常抛回容器 → Tomcat 转 {@code /error} → {@code BasicErrorController} 对同一个
     * {@code Accept} <b>二次协商同样失败</b> → 最终报文 = <b>HTTP 500 + Content-Length: 0</b>。
     * 🔴 影响面是全局的：{@code 10005}/{@code 30070}/{@code 10001}/{@code 10004}/{@code 10003}/
     * {@code 20001~20005}/{@code 50003} —— 所有在 {@code meta} flush 之前抛出的业务异常
     * （其中鉴权码退化为 500 空体会让前端<b>拿不到 code、无法跳 SSO</b>）。
     *
     * <p>🔴 <b>纪律</b>：新增/修改任何"有响应体"的处理方法<b>必须</b>经由本方法构造，
     * 禁止逐个方法手写 {@code ResponseEntity.ok().contentType(...)}（手写必然漏，
     * 而漏掉的那一个只在带非 JSON {@code Accept} 的真实客户端上才暴露）。
     */
    private static <T> ResponseEntity<Result<T>> json(Result<T> body) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    // ===================== 上游模型调用异常 =====================

    /**
     * 上游模型调用失败（限流 / 不可用 / 超时）。
     *
     * <p>🔴 必须显式处理，🔴 严禁落入 {@link #handleUnexpected}：否则 {@code 10005 限流码}会被吞成
     * {@code 50003}，前端无法进入限流等待、也无法在错误块插值 {@remaining}。
     *
     * <p>限流（{@code RATE_LIMITED=10005}）时把 {@code retryAfterSeconds} 透传进
     * {@code data}，🔴 这是服务端给出的重试窗口（平台侧统一估算或上游下发），
     * 前端只读取、绝不硬编码。非限流异常不携带该字段。
     */
    @ExceptionHandler(AiStreamException.class)
    public ResponseEntity<Result<Object>> handleAiStream(AiStreamException e) {
        if (e.getRetryAfterSeconds() != null) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("retryAfterSeconds", e.getRetryAfterSeconds());
            log.warn("上游模型异常 code={} retryAfterSeconds={} message={}",
                    e.getCode(), e.getRetryAfterSeconds(), e.getMessage());
            return json(Result.error(e.getCode(), e.getMessage(), data));
        }
        log.warn("上游模型异常 code={} message={}", e.getCode(), e.getMessage());
        return json(Result.error(e.getCode(), e.getMessage()));
    }

    // ===================== 业务异常 =====================

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusiness(BusinessException e) {
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return json(Result.error(e.getCode(), e.getMessage(), e.getPayload()));
    }

    // ===================== 鉴权异常（耶瞳保留段） =====================

    /**
     * 鉴权失败。code 由 {@link AuthCodeException} 透传 eyesUser 的原始码；
     * 非法或缺失时兜底为 20001，确保前端拦截器行为确定。
     *
     * <p>🔴 本方法是 ADR-021 影响面里<b>最危险</b>的一个：若响应退化为 500 空体，
     * 前端拿不到 {@code 20001~20005} → 不会清 token、不会跳 SSO → 用户卡在"永远失败"的页面。
     */
    @ExceptionHandler(EyesAuthException.class)
    public ResponseEntity<Result<Void>> handleAuth(EyesAuthException e) {
        int code = ErrorCode.AUTH_TOKEN_INVALID;
        if (e instanceof AuthCodeException authCodeException
                && ErrorCode.isAuthSegment(authCodeException.getCode())) {
            code = authCodeException.getCode();
        }
        log.warn("鉴权失败 code={} message={}", code, e.getMessage());
        return json(Result.error(code, e.getMessage()));
    }

    // ===================== 参数校验类 → 10001 =====================

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败：{}", detail);
        return json(Result.error(ErrorCode.VALIDATION_FAILED,
                detail.isBlank() ? ErrorCode.defaultMessage(ErrorCode.VALIDATION_FAILED) : detail));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败：{}", detail);
        return json(Result.error(ErrorCode.VALIDATION_FAILED,
                detail.isBlank() ? ErrorCode.defaultMessage(ErrorCode.VALIDATION_FAILED) : detail));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败：{}", detail);
        return json(Result.error(ErrorCode.VALIDATION_FAILED, detail));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException e) {
        log.warn("方法参数校验失败：{}", e.getMessage());
        return json(Result.error(ErrorCode.VALIDATION_FAILED));
    }

    /**
     * 请求本身不合法 → {@code 10001}。
     *
     * <p>🔴 <b>两个内容协商异常并入本方法是安全网</b>（ADR-021）：
     * {@link HttpMediaTypeNotAcceptableException} 正是 BUG-QUOTA-001 的异常类型本身，
     * {@link HttpMediaTypeNotSupportedException} 是其请求侧对偶（{@code Content-Type} 不支持）。
     * 它们若落进 {@link #handleUnexpected} 会被报成 {@code 50003}（"系统繁忙"），
     * 把一个"客户端头不对"的问题伪装成服务端故障；更重要的是：本方法自身经 {@link #json}
     * 写出，因此即使真的发生协商失败，客户端也仍能拿到 <b>HTTP 200 + JSON</b> 而非 500 空体。
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotAcceptableException.class,
            HttpMediaTypeNotSupportedException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求非法：{} - {}", e.getClass().getSimpleName(), e.getMessage());
        return json(Result.error(ErrorCode.VALIDATION_FAILED));
    }

    // ===================== 资源不存在 → 10004 =====================

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        log.warn("资源不存在：{}", e.getMessage());
        return json(Result.error(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ===================== 并发与数据层 =====================

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Result<Void>> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("乐观锁冲突：{}", e.getMessage());
        return json(Result.error(ErrorCode.VERSION_CONFLICT));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Result<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        // 不回显数据库原始信息，避免泄露表结构与其他租户信息
        log.error("数据完整性冲突", e);
        return json(Result.error(ErrorCode.VALIDATION_FAILED, "数据冲突或校验失败"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Result<Void>> handleDataAccess(DataAccessException e) {
        log.error("数据库异常（内部映射 {}）", ErrorCode.DATABASE_ERROR, e);
        return json(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    // ===================== 异步 / SSE 生命周期（🔴 单列，绝不落 catch-all） =====================

    /**
     * 🔴 <b>Spring MVC 异步请求超时</b>（ADR-017 ⑤，V1.4.2 新增）。
     *
     * <p>🔴 <b>不返回任何响应体</b>（方法返回 {@code void}）：响应早已提交为
     * {@code text/event-stream}，再写 JSON {@code Result} 只会产生 "No converter for Result"
     * 噪声，且此时客户端已断开、这个 {@code 50003} 无人接收。
     * 🔴 <b>ADR-021 / §9.3.1 反向纪律 ③ 追认本方法必须保持 {@code void}</b>：
     * 本次改造把"有响应体"的方法统一改为 {@code ResponseEntity<Result<T>>}，
     * 🔴 但本方法与 {@link #handleAsyncNotUsable} <b>不在其列</b> ——
     * 响应已提交，写 JSON 会把垃圾字节插进 SSE 流（L6 ⓒ / J3 的既有断言不变）。
     *
     * <p>🔴 <b>级别 WARN</b>（不是 ERROR、也不是 DEBUG）：在 ADR-017 的预算模型下它
     * <b>不应发生</b> —— 业务侧应在 {@code remaining ≤ chat.deadline_grace_seconds} 时主动收敛。
     * 因此它是"预算不等式被打破，或存在未按 {@code remaining} 收敛的阻塞点"的<b>唯一信号</b>：
     * 降到 DEBUG 等于把这个信号丢掉，升到 ERROR 则会与真实故障混淆（AR-022）。
     *
     * <p>🔴 <b>绝不放宽 catch-all</b>：只精确匹配本类型，其余异常仍走 {@code 50003} ——
     * 严禁改成"凡 SSE 请求的异常都降级"，那会掩盖真实的 {@code 50003}（客户端仍在的场景）。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e) {
        log.warn("[DEADLINE] 🔴 异步请求（SSE）在传输层超时，本不应发生：requestId={}；"
                        + "请核对 chat.generation_deadline_seconds + chat.deadline_grace_seconds "
                        + "≤ spring.mvc.async.request-timeout，以及是否存在未按 remaining "
                        + "收敛的阻塞点（ADR-017 ⑤ / AR-022）",
                MDC.get(MDC_REQUEST_ID));
    }

    /**
     * 🔴 <b>客户端断开后写出失败</b>（ADR-017 ⑤）：关页面 / 切网属<b>正常</b>场景 ——
     * {@code SseWriter} 已 {@code markBroken}，无需告警，故 <b>DEBUG</b> + 无响应体。
     *
     * <p>🔴 同上：本方法必须<b>保持 {@code void}</b>（ADR-021 反向纪律 ③ / L6 ⓒ）。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncNotUsable(AsyncRequestNotUsableException e) {
        log.debug("异步请求已不可用（客户端断开后写出失败），无需处理：requestId={}",
                MDC.get(MDC_REQUEST_ID));
    }

    // ===================== 兜底 → 50003 =====================

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return json(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
