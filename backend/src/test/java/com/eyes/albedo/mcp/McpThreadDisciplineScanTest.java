package com.eyes.albedo.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.eyes.albedo.mcp.entity.McpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code mcp} 包的<b>线程纪律静态扫描</b>（🔴 ADR-008 第 8 条 V1.4.0 补注 / ADR-016 ⑥ 的机械防线）。
 *
 * <p><b>为什么必须静态扫描</b>：ADR-016 允许 {@code sse} 传输使用 {@code sendAsync} +
 * {@code BodySubscriber}（复用 {@code HttpClient} <b>既有</b>内部 executor，不算新增线程池），
 * 这条"允许"极易被后续维护者误读为"异步随便用"。而一旦有人写下
 * {@code CompletableFuture.supplyAsync(...)}（落到 {@code ForkJoinPool.commonPool}）
 * 或 {@code Executors.newFixedThreadPool(...)}，功能测试<b>全绿</b>，
 * 代价只在生产上量后以"AI 首字变慢 / 线程数缓涨"的形态出现 —— 属"上线才发现"类型，
 * 必须在编译期之外加一道机械防线。
 *
 * <p>🔴 同时守住 {@code transport} 枚举<b>仍恰 2 值</b>（ADR-016 ①：不新增 {@code sse_legacy}）。
 */
class McpThreadDisciplineScanTest {

    private static final Path MCP_SOURCE_ROOT =
            Paths.get("src", "main", "java", "com", "eyes", "albedo", "mcp");

    /**
     * 🔴 字面量黑名单（ADR-008 第 8 条补注）：这些写法等价于"新建第二个受我们管理的线程池"，
     * 或"无超时等待"（潜在永久挂起）。
     *
     * <p>🔴 {@code orTimeout} / {@code completeOnTimeout} / {@code delayedExecutor} 为
     * <b>V1.4.1 补入</b>：三者都会启用 {@code CompletableFuture.Delayer} 内部那个
     * <b>静态 {@link java.util.concurrent.ScheduledThreadPoolExecutor}</b> ——
     * 实质就是"新增线程池"，只是它藏在 JDK 里、不出现在我们的代码里，
     * 因此既躲过 code review 也躲过原先的 {@code *Async} 黑名单。
     * 本包的超时一律由 {@code HttpRequest.timeout(remaining)} 与
     * {@code Future.get(remaining, unit)} 落实（Deadline 预算制）。
     */
    private static final List<String> FORBIDDEN_THREADING = List.of(
            "new Thread(", "Executors.new", "@Async", "ThreadPoolExecutor",
            "supplyAsync", "runAsync", "ForkJoinPool", ".join()",
            // 🔴 JDK 内部 Delayer 的 ScheduledThreadPoolExecutor（实质新增线程池）
            "orTimeout(", "completeOnTimeout(", "delayedExecutor");

    /**
     * 🔴 <b>正则</b>黑名单：任何 {@code xxxAsync(} 形式的组合子（{@code thenApplyAsync} /
     * {@code thenComposeAsync} / {@code handleAsync} / {@code thenCombineAsync} /
     * {@code completeAsync} / {@code whenCompleteAsync} …）。
     *
     * <p><b>为什么从穷举改为正则</b>：{@code CompletableFuture} 的 {@code *Async} 家族有近 20 个成员，
     * 穷举必漏（原先就漏了 {@code thenComposeAsync} / {@code handleAsync} /
     * {@code thenCombineAsync} / {@code completeAsync}）。它们的共同语义是
     * <b>把回调投递给某个 executor</b>（不带 executor 参数时落到
     * {@code ForkJoinPool.commonPool}）—— 一律禁止。
     *
     * <p>🔴 <b>白名单：非 async 组合子（如 {@code whenComplete} / {@code thenApply}）合规</b>
     * —— @架构师 已正式裁决：回调在<b>完成线程上内联执行</b>，不向任何 executor 投递任务，
     * 故不构成"新增线程池"。{@code SseTransport.openEventStream} 正是靠非 async 的
     * {@code whenComplete} 把连接层异常转交给 {@code statusFuture}
     * （否则会白等满预算并把 {@code connect_failed} 误判为 {@code timeout}）。
     * 本正则要求 {@code Async} 紧邻 {@code (}，因此 {@code whenComplete(} <b>不会</b>被匹配。
     */
    private static final Pattern FORBIDDEN_ASYNC_COMBINATOR = Pattern.compile("\\w+Async\\s*\\(");

    /**
     * 🔴 唯一被豁免的 {@code *Async} 调用：{@code httpClient.sendAsync(...)}。
     *
     * <p>ADR-016 ⑥ 的边界：它复用 {@code HttpClient} Bean <b>自带</b>的内部 executor，
     * 而 JDK17 的 {@code HttpClient.send(...)} 本身就是 {@code sendAsync(...)} + 阻塞等待的语法糖
     * —— 即今天的同步调用<b>已经在用</b>那个池，故显式使用不构成"新增线程池"。
     */
    private static final List<String> ALLOWED_ASYNC_CALLS = List.of("sendAsync");

    @Test
    @DisplayName("🔴 mcp 包禁止新增线程池 / 禁止无超时等待（白名单只有 sendAsync + BodySubscriber + get(timeout)）")
    void mcpPackageIntroducesNoThreadPool() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            for (String forbidden : FORBIDDEN_THREADING) {
                if (content.contains(forbidden)) {
                    violations.add(file.getFileName() + " 出现 " + forbidden);
                }
            }
            for (String method : asyncCombinators(content)) {
                if (ALLOWED_ASYNC_CALLS.contains(method)) {
                    continue;
                }
                violations.add(file.getFileName() + " 出现 async 组合子 " + method + "(");
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 违反 ADR-008 第 8 条「不新增线程池」的边界定义：" + violations);
    }

    @Test
    @DisplayName("🔴 R2 自检：正则精确命中 *Async 家族，且绝不误伤非 async 组合子（whenComplete/thenApply）")
    void asyncRegexMatchesOnlyAsyncCombinators() {
        // 🔴 必须命中：原穷举漏掉的四个 + 已有的三个 + 带空格的变体
        for (String forbidden : List.of("f.thenComposeAsync(x)", "f.handleAsync(x)",
                "f.thenCombineAsync(x)", "f.completeAsync(x)", "f.thenApplyAsync(x)",
                "f.thenAcceptAsync(x)", "f.whenCompleteAsync(x)", "f.exceptionallyAsync(x)",
                "f.thenRunAsync (x)")) {
            assertTrue(!asyncCombinators(forbidden).isEmpty(),
                    "🔴 正则漏掉 *Async 家族成员：" + forbidden);
        }
        // 🔴 绝不允许命中：非 async 组合子（回调在完成线程内联执行，架构师已裁决合规）
        for (String allowed : List.of("responseFuture.whenComplete((response, error) -> {",
                "future.thenApply(node -> node)", "future.thenAccept(node -> {})",
                "future.handle((v, e) -> v)", "future.thenCompose(v -> other)",
                "future.exceptionally(e -> null)", "future.complete(value)",
                "future.get(remainingMillis, TimeUnit.MILLISECONDS)")) {
            assertTrue(asyncCombinators(allowed).isEmpty(),
                    "🔴 正则误伤非 async 组合子（whenComplete 等为白名单）：" + allowed
                            + " → 命中 " + asyncCombinators(allowed));
        }
        // 🔴 sendAsync 会被正则命中，只能靠显式白名单豁免（否则 SseTransport 无法通过扫描）
        assertEquals(List.of("sendAsync"), asyncCombinators("httpClient.sendAsync(req, handler)"));
        assertTrue(ALLOWED_ASYNC_CALLS.contains("sendAsync"),
                "🔴 sendAsync 必须留在白名单，否则 SseTransport 无法通过扫描");
    }

    /** 提取文本中所有 {@code xxxAsync(} 的方法名（🔴 已剔除注释的源码请先经 {@link #read}）。 */
    private List<String> asyncCombinators(String content) {
        List<String> found = new ArrayList<>();
        Matcher matcher = FORBIDDEN_ASYNC_COMBINATOR.matcher(content);
        while (matcher.find()) {
            String hit = matcher.group();
            found.add(hit.substring(0, hit.indexOf('(')).trim());
        }
        return found;
    }

    @Test
    @DisplayName("🔴 异步等待必须带超时（get(...) 至少两个参数：timeout + unit）")
    void everyFutureWaitHasTimeout() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            // 无参 get() 只允许出现在 Optional 语境；本包的 Future 等待一律带超时单位
            if (content.contains("Future") && content.contains(".get()")) {
                violations.add(file.getFileName() + " 出现无参 get()（无超时等待 = 潜在永久挂起）");
            }
        }
        assertTrue(violations.isEmpty(), "🔴 等待必须收敛在 deadline 预算内：" + violations);
    }

    @Test
    @DisplayName("🔴 SseTransport 必须 finally 关流（try-with-resources）且不持有任何会话状态字段")
    void sseTransportHoldsNoSessionState() throws IOException {
        String content = read(MCP_SOURCE_ROOT.resolve("SseTransport.java"));

        assertTrue(content.contains("try (SseSessionStream"),
                "🔴 GET 流必须由 try-with-resources 托管（finally 强制 cancel 订阅 + 响应 Future）");
        for (String forbidden : List.of("sessionId;", "private URI session",
                "static SseSessionStream", "redis", "Redis", "cache", "Cache")) {
            assertTrue(!content.contains(forbidden),
                    "🔴 禁止跨调用复用 session（不得写入字段 / 静态变量 / Redis / 缓存）：出现 "
                            + forbidden);
        }
    }

    @Test
    @DisplayName("🔴 ADR-016 ①：transport 合法值仍恰 2 个（未偷偷新增 sse_legacy 枚举）")
    void transportEnumStillHasExactlyTwoLegalValues() {
        assertEquals("streamable_http", McpServer.TRANSPORT_STREAMABLE_HTTP);
        assertEquals("sse", McpServer.TRANSPORT_SSE);
        assertEquals("stdio", McpServer.TRANSPORT_STDIO);
        assertEquals(3, java.util.Arrays.stream(McpServer.class.getDeclaredFields())
                .filter(field -> field.getName().startsWith("TRANSPORT_")).count(),
                "🔴 传输常量恰 3 个（2 个合法 + stdio 恒拒绝）：新增 sse_legacy 即违反 ADR-016 ①");
    }

    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(MCP_SOURCE_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 读取源码并剔除注释（本项目大量 Javadoc 会把被禁写法作为反面示例列出）。
     */
    private String read(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlockComments.replaceAll("(?m)^\\s*//.*$", " ");
    }
}
