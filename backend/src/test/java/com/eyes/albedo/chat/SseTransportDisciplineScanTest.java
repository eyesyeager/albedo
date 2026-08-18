package com.eyes.albedo.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.eyes.albedo.common.GlobalExceptionHandler;
import com.eyes.albedo.testsupport.SseRequests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 🔴🔴 <b>SSE 传输层纪律的机械化守护</b>（api-spec §8.3 <b>L5 / L6</b>，architecture ADR-021 / §9.3.1）。
 *
 * <p><b>为什么必须是"反射 + 静态扫描"而不是功能测试</b>：BUG-QUOTA-001 的教训不是
 * "限流坏了"，而是 🔴 <b>一个全局传输层约束在 1166 个用例全绿的情况下被悄悄打破</b>。
 * 功能测试只能覆盖<b>已经写过的</b>端点；本类覆盖的是"<b>以后新增的</b>端点会不会复发"：
 * <ul>
 *   <li><b>L5 ⓐ</b>：新增一个返回 {@code SseEmitter} 的端点但忘了纳入 L1/L2 类用例 → 🔴 测试红</li>
 *   <li><b>L6 ⓐ</b>：给 SSE 端点补上 {@code produces} → 🔴 测试红
 *       （它会让 {@code Accept: application/json} 在 handler mapping 阶段直接 406，违反 §1.2）</li>
 *   <li><b>L6 ⓑ</b>：任何地方写下 {@code SseEmitter.completeWithError(} → 🔴 测试红
 *       （错误分派会把 JSON 写进已提交的 SSE 流）</li>
 *   <li><b>L6 ⓒ</b>：把两个异步生命周期处理方法从 {@code void} 改成有响应体 → 🔴 测试红</li>
 *   <li>🔴 <b>本轮修复本身的守护</b>：{@code GlobalExceptionHandler} 里出现一个
 *       直接返回 {@code Result} 的处理方法（= 绕过 {@link GlobalExceptionHandler} 的
 *       {@code json(...)} 承重路径）→ 🔴 测试红</li>
 * </ul>
 *
 * <p>🔴 扫描前<b>剔除注释与字符串字面量</b>（做法与 {@code QuotaDisciplineScanTest} 一致）：
 * 本项目的 Javadoc 大量把被禁写法作为契约说明引用，连注释一起扫必然假阳性，
 * 而假阳性会促使后来者放宽规则 —— 那才是真正的风险。
 */
class SseTransportDisciplineScanTest {

    private static final Path SOURCE_MAIN = Paths.get("src", "main", "java");
    private static final Path SOURCE_TEST = Paths.get("src", "test", "java");
    private static final Path CLASSES_ROOT = Paths.get("target", "classes");

    private static final Path EXCEPTION_HANDLER = SOURCE_MAIN.resolve(
            Paths.get("com", "eyes", "albedo", "common", "GlobalExceptionHandler.java"));

    // ===================== L5 ⓐ：SSE 端点集合 ⊆ 已覆盖集合 =====================

    @Test
    @DisplayName("🔴 L5ⓐ：所有返回 SseEmitter 的 handler 必须已被 SseRequests 登记（新增端点未覆盖即红）")
    void everySseHandlerIsCoveredByTheSingleRequestHelper() throws Exception {
        List<Method> handlers = sseHandlers();
        assertFalse(handlers.isEmpty(),
                "🔴 前置条件：必须扫描到至少一个 SSE handler，否则本类是永远通过的假防线");

        Set<String> declared = new LinkedHashSet<>();
        for (Method handler : handlers) {
            declared.addAll(mappedPaths(handler));
        }
        Set<String> covered = new LinkedHashSet<>(SseRequests.COVERED_PATTERNS);

        List<String> uncovered = declared.stream().filter(path -> !covered.contains(path)).toList();
        assertTrue(uncovered.isEmpty(),
                "🔴 存在未被 L1/L2 类用例覆盖的 SSE 端点：" + uncovered
                        + "；请在 SseRequests.COVERED_PATTERNS 登记，并为其补建流前失败的"
                        + "「200 + application/json」用例（ADR-021 是全局约束，不是限流专属）");
        // 反向：登记表不得堆积已删除的端点（否则覆盖集合会虚假膨胀）
        List<String> stale = covered.stream().filter(path -> !declared.contains(path)).toList();
        assertTrue(stale.isEmpty(), "🔴 SseRequests.COVERED_PATTERNS 存在已不存在的端点：" + stale);
    }

    // ===================== L6 ⓐ：SSE 端点不得声明 produces =====================

    @Test
    @DisplayName("🔴 L6ⓐ：SSE 端点不得声明 produces（声明即让 Accept: application/json 在映射阶段 406）")
    void sseEndpointsNeverDeclareProduces() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Method handler : sseHandlers()) {
            PostMapping post = handler.getAnnotation(PostMapping.class);
            if (post != null && post.produces().length > 0) {
                violations.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName()
                        + " 声明了 produces=" + List.of(post.produces()));
            }
            RequestMapping request = handler.getAnnotation(RequestMapping.class);
            if (request != null && request.produces().length > 0) {
                violations.add(handler.getDeclaringClass().getSimpleName() + "#" + handler.getName()
                        + " 声明了 produces=" + List.of(request.produces()));
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 ADR-021 备选 (c) 已被否决（既治不了内容协商，又会造成 406）：" + violations);
    }

    // ===================== L6 ⓑ：全库禁止 completeWithError =====================

    @Test
    @DisplayName("🔴 L6ⓑ：全库（main + test）不得出现 SseEmitter.completeWithError(")
    void forbidCompleteWithErrorAnywhere() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaSources(SOURCE_MAIN, SOURCE_TEST)) {
            if (readCodeOnly(file).contains("completeWithError(")) {
                violations.add(file.toString());
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 completeWithError 会触发错误分派，把 JSON 写进已提交的 text/event-stream 流"
                        + "（§9.3.1 明文永久禁止，SseWriter.markBroken 已选用 complete()）：" + violations);
    }

    // ===================== L6 ⓒ：两个异步处理方法必须保持 void =====================

    @Test
    @DisplayName("🔴 L6ⓒ：AsyncRequestTimeout / AsyncRequestNotUsable 两个处理方法必须仍为 void（J3 不变）")
    void asyncLifecycleHandlersStayVoid() throws Exception {
        assertEquals(void.class,
                GlobalExceptionHandler.class
                        .getDeclaredMethod("handleAsyncTimeout", AsyncRequestTimeoutException.class)
                        .getReturnType(),
                "🔴 响应已提交为 text/event-stream，写 JSON 会把垃圾字节插进 SSE 流（§9.3.1 反向纪律 ③）");
        assertEquals(void.class,
                GlobalExceptionHandler.class
                        .getDeclaredMethod("handleAsyncNotUsable", AsyncRequestNotUsableException.class)
                        .getReturnType(),
                "🔴 同上：客户端已断开，这个响应体无人接收（J3 的既有断言不得因本次改造被改动）");
    }

    // ===================== 本轮修复自身的守护：不得绕过 json(...) =====================

    @Test
    @DisplayName("🔴 ADR-021 (d2)：GlobalExceptionHandler 的处理方法只能返回 ResponseEntity<Result> 或 void")
    void everyExceptionHandlerWritesThroughPresetJson() {
        List<String> violations = new ArrayList<>();
        for (Method method : GlobalExceptionHandler.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class)
                    == null) {
                continue;
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class || returnType == ResponseEntity.class) {
                continue;
            }
            violations.add(method.getName() + " 返回 " + returnType.getSimpleName());
        }
        assertTrue(violations.isEmpty(),
                "🔴 直接返回 Result 会走内容协商 → 带 Accept: text/event-stream 的请求必得 500 空体"
                        + "（BUG-QUOTA-001 根因）；必须经 json(...) 预设 application/json：" + violations);
    }

    @Test
    @DisplayName("🔴 承重实现不得被悄悄拿掉：json(...) 必须显式预设 APPLICATION_JSON")
    void presetContentTypeIsStillThere() throws IOException {
        String code = readCodeOnly(EXCEPTION_HANDLER);
        assertTrue(code.contains("contentType(MediaType.APPLICATION_JSON)"),
                "🔴 这一行就是 ADR-021 的全部技术内容（isContentTypePreset 分支 → 跳过内容协商），"
                        + "删掉它缺陷立即复发");
    }

    // ===================== 扫描基建 =====================

    /**
     * 反射取出全部返回 {@code SseEmitter} / {@code ResponseEntity<SseEmitter>} 的 handler 方法。
     *
     * <p>🔴 用 {@code Class.forName(name, false, loader)}（<b>不初始化</b>）：本类是纯扫描测试，
     * 不加载 Spring 上下文，也不应触发任何静态初始化副作用。
     */
    private List<Method> sseHandlers() throws Exception {
        List<Method> handlers = new ArrayList<>();
        ClassLoader loader = getClass().getClassLoader();
        for (String className : compiledClassNames()) {
            Class<?> type;
            try {
                type = Class.forName(className, false, loader);
            } catch (Throwable ignored) {
                continue;
            }
            if (type.getAnnotation(RestController.class) == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (returnsSseEmitter(method)) {
                    handlers.add(method);
                }
            }
        }
        return handlers;
    }

    private boolean returnsSseEmitter(Method method) {
        if (SseEmitter.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }
        if (!ResponseEntity.class.isAssignableFrom(method.getReturnType())) {
            return false;
        }
        Type generic = method.getGenericReturnType();
        if (!(generic instanceof ParameterizedType parameterized)) {
            return false;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        return arguments.length == 1 && arguments[0] instanceof Class<?> argument
                && SseEmitter.class.isAssignableFrom(argument);
    }

    /** 取该 handler 的映射路径（类级 {@code @RequestMapping} 前缀 + 方法级路径）。 */
    private List<String> mappedPaths(Method handler) {
        List<String> prefixes = new ArrayList<>();
        RequestMapping typeMapping = handler.getDeclaringClass().getAnnotation(RequestMapping.class);
        if (typeMapping != null && typeMapping.value().length > 0) {
            prefixes.addAll(List.of(typeMapping.value()));
        } else {
            prefixes.add("");
        }
        List<String> methodPaths = new ArrayList<>();
        PostMapping post = handler.getAnnotation(PostMapping.class);
        if (post != null) {
            methodPaths.addAll(List.of(post.value()));
        }
        RequestMapping request = handler.getAnnotation(RequestMapping.class);
        if (request != null) {
            methodPaths.addAll(List.of(request.value()));
        }
        List<String> paths = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String methodPath : methodPaths) {
                paths.add(prefix + methodPath);
            }
        }
        return paths;
    }

    private List<String> compiledClassNames() throws IOException {
        assertTrue(Files.isDirectory(CLASSES_ROOT),
                "前置条件：target/classes 必须存在（本类依赖已编译产物做反射扫描）");
        try (Stream<Path> stream = Files.walk(CLASSES_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.toString().contains("$"))
                    .map(path -> CLASSES_ROOT.relativize(path).toString()
                            .replace(".class", "")
                            .replace('/', '.')
                            .replace('\\', '.'))
                    .toList();
        }
    }

    private List<Path> javaSources(Path... roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            assertTrue(Files.isDirectory(root), "前置条件：源码根目录必须存在：" + root);
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(files::add);
            }
        }
        assertTrue(files.size() > 100, "前置条件：扫描范围不得为空（防扫描空转），实际=" + files.size());
        return files;
    }

    /** 读取源码并剔除块注释、行注释、文本块与字符串字面量（避免契约说明造成假阳性）。 */
    private String readCodeOnly(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLineComments = withoutBlockComments.replaceAll("(?m)//.*$", " ");
        String withoutTextBlocks = withoutLineComments.replaceAll("(?s)\"\"\".*?\"\"\"", " ");
        return withoutTextBlocks.replaceAll("\"(\\\\.|[^\"\\\\])*\"", " ");
    }
}
