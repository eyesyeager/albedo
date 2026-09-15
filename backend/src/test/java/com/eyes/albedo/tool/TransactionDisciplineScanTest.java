package com.eyes.albedo.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事务纪律静态扫描（🔴 <b>ADR-010 的自动化防线</b>，与 {@code TenantIsolationScanTest} 同风格）。
 *
 * <p><b>为什么必须静态扫描而不是只靠评审</b>：
 * 把一次网络调用误包进 {@code @Transactional} 方法，功能测试<b>看起来完全正常</b>
 * （结果对、状态对、审计也写了），只有在以下三种情况才会爆：
 * <ol>
 *   <li>并发上量后连接池耗尽（生产事故）；</li>
 *   <li>🔴 高风险确认场景：生成线程持有 {@code tool_calls} 行锁等确认，
 *       confirm 接口又要 {@code SELECT … FOR UPDATE} 同一行 → <b>互等死锁</b>（ADR-010 致命自锁）；</li>
 *   <li>工具执行失败回滚时把已提交的审计一起回滚（审计必须不可篡改）。</li>
 * </ol>
 * 三者都是"上线才发现"的类型，因此必须在编译期之外加一道机械防线。
 *
 * <p>规则：
 * <ol>
 *   <li>🔴 {@code @Transactional} 方法体内禁止出现 {@code McpClient} / {@code HttpClient} /
 *       {@code SseEmitter} / {@code SseWriter} / {@code LocalToolHandler} / {@code ToolExecutor} 调用</li>
 *   <li>🔴 {@code ToolCallRecorder} 的每个公开写方法必须是 {@code REQUIRES_NEW}（独立短事务）</li>
 *   <li>🔴 {@code McpClient} 的实现与 {@code ToolExecutor} 的实现<b>整类</b>不得出现
 *       {@code @Transactional}（它们本身就是"事务外那一段"）</li>
 * </ol>
 */
class TransactionDisciplineScanTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** 🔴 禁止出现在事务方法体内的调用特征。 */
    private static final List<String> FORBIDDEN_IN_TRANSACTION = List.of(
            "mcpClient.", "McpClient.", "httpClient.", "HttpClient.newBuilder",
            "sseWriter.", "SseEmitter", "handler.execute(", "executor.execute(",
            "toolExecutor.", "executorRegistry.execute(", "InetAddress.getAllByName",
            "ssrfGuard.requireAllowed", "ssrfGuard.evaluate");

    /**
     * 🔴 整类禁止出现 {@code @Transactional} 的文件（它们是"事务外那一段"）。
     *
     * <p>M3 第三阶段新增的三类必须在列：
     * <ul>
     *   <li>{@code ToolOrchestrator} —— 编排层要在事务外做确认等待（最长 120s）与工具执行</li>
     *   <li>{@code ToolConfirmRegistry} —— 🔴 等待原语：等待期间<b>绝不允许</b>持有任何事务，
     *       否则与 confirm 的 {@code SELECT … FOR UPDATE} 互等死锁（ADR-010 致命自锁）</li>
     *   <li>{@code ToolConfirmService} —— 唤醒必须发生在 {@code ToolConfirmWriter} 短事务
     *       <b>提交之后</b>，因此它自身不得是事务方法</li>
     * </ul>
     * 本地 Tool 实现体（{@code handler/*}）同样在列：实现体跑在事务外（ADR-010）。
     */
    private static final Set<String> MUST_BE_TRANSACTION_FREE = Set.of(
            "McpJsonRpcClient.java", "StreamableHttpTransport.java", "SseTransport.java",
            "AbstractMcpTransport.java", "LocalToolExecutor.java", "McpToolExecutor.java",
            // 🔴 V1.4.0（ADR-016）新增的 sse 双形态辅助类：它们跑在"事务外那一段"，
            //    且 SseSessionStream 的回调在 HttpClient 共享 executor 上 ——
            //    一旦有人给它们加事务，等于在共享 IO 线程里开数据库事务（连接池瞬间见底）。
            "McpRpcMessages.java", "McpRpcRequest.java", "SseFrameParser.java",
            "SseSessionStream.java",
            "ToolExecutorRegistry.java", "McpConnectionTester.java", "McpDiscoveryService.java",
            "SsrfGuard.java",
            "ToolOrchestrator.java", "ToolConfirmRegistry.java", "ToolConfirmService.java",
            "DateTimeNowHandler.java", "CalculatorHandler.java",
            // ===== M3 第四阶段新增（本轮扩展覆盖） =====
            // 🔴 ToolFunctionNames / SystemPromptBudget / ViolationRules 是**纯函数工具**：
            //    它们被清单构造、上下文组装与聚合校验三处复用，一旦有人给它们加事务，
            //    就会在"事务外那一段"（工具执行前后）意外开启事务并放大连接占用。
            "ToolFunctionNames.java", "SystemPromptBudget.java", "ViolationRules.java",
            // 🔴 M3 收尾新增（V1.1.4 #4）：执行前授权点查发生在"事务外那一段"
            //    （确认等待与工具执行之间）。一旦有人给它加 @Transactional，
            //    就会在等待/执行窗口内额外占用一个事务与连接，且把只读点查卷入外层回滚语义。
            "ToolGrantPointCheck.java",
            // 🔴 埋点写入**绝不允许**参与任何外层事务（api-spec §7.10.1「永不影响主流程」）：
            //    共享事务下单条撞唯一键会把整批标记 rollback-only，
            //    "重复只计 duplicated、其余照常接受"的契约直接失效（见服务类注释）。
            "AnalyticsEventService.java", "AnalyticsFieldGuard.java",
            "AnalyticsEventController.java");

    /**
     * 🔴 <b>禁止字符串还原模型函数名</b>（api-spec §7.6.5 回映射规则）。
     *
     * <p>归一化把 {@code :} 等字符映射为 {@code _}，🔴 <b>不可逆</b>：
     * {@code a_b} 无法判断原文是 {@code a:b} 还是 {@code a_b}。
     * 若有人图省事写 {@code name.replace("_", ":")} 来"还原" toolKey，
     * 就会在存在同名候选时<b>猜错工具</b> —— 等于执行了用户没批准的工具（高风险确认被绕过）。
     * 因此唯一合法途径是 {@code Map<functionName, 定义>} 查表。
     */
    private static final List<String> FORBIDDEN_NAME_RESTORE = List.of(
            "replace(\"_\", \":\")", "replace('_', ':')", "replaceAll(\"_\", \":\")");

    @Test
    @DisplayName("🔴 ADR-010：@Transactional 方法体内禁止任何网络调用 / SSE 写出 / 工具执行")
    void noNetworkCallsInsideTransactions() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            if (!content.contains("@Transactional")) {
                continue;
            }
            for (String body : transactionalMethodBodies(content)) {
                for (String forbidden : FORBIDDEN_IN_TRANSACTION) {
                    if (body.contains(forbidden)) {
                        violations.add(file.getFileName() + " 的 @Transactional 方法内出现 "
                                + forbidden);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 违反 ADR-010 三段式事务纪律（长事务 + confirm 死锁风险）：" + violations);
    }

    @Test
    @DisplayName("🔴 传输 / 执行器 / SSRF 守卫整类不得出现 @Transactional（它们是事务外那一段）")
    void executorsAreTransactionFree() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (!MUST_BE_TRANSACTION_FREE.contains(name)) {
                continue;
            }
            String content = read(file);
            if (content.contains("@Transactional")) {
                violations.add(name + " 不得出现 @Transactional");
            }
        }
        assertTrue(violations.isEmpty(), "🔴 事务边界被破坏：" + violations);
    }

    @Test
    @DisplayName("🔴 ToolCallRecorder 的写方法必须是 REQUIRES_NEW（独立短事务）")
    void recorderUsesRequiresNew() throws IOException {
        Path recorder = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "tool", "ToolCallRecorder.java"));
        assertTrue(Files.exists(recorder), "ToolCallRecorder 必须存在（状态流转唯一入口）");
        String content = read(recorder);

        int transactional = countOccurrences(content, "@Transactional");
        int requiresNew = countOccurrences(content, "Propagation.REQUIRES_NEW");
        assertTrue(transactional > 0, "ToolCallRecorder 必须有事务方法");
        assertTrue(transactional == requiresNew,
                "🔴 每个 @Transactional 都必须显式声明 REQUIRES_NEW："
                        + transactional + " 个事务方法但只有 " + requiresNew + " 个 REQUIRES_NEW");
        assertTrue(content.contains("findByIdForUpdate"),
                "🔴 状态流转必须在行锁内进行（SELECT … FOR UPDATE 是唯一裁决点）");
    }

    @Test
    @DisplayName("🔴 §7.6.3 #4：执行前授权点查必须存在且不可缓存（ToolOrchestrator 置 running 前调用）")
    void grantPointCheckExistsAndIsNotCached() throws IOException {
        Path pointCheck = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "tool", "ToolGrantPointCheck.java"));
        assertTrue(Files.exists(pointCheck),
                "🔴 执行前授权点查是 AC-MCP-004 的落地点（撤授权即刻生效），不得删除");
        String content = read(pointCheck);
        assertTrue(content.contains("countExecutableGrant"),
                "🔴 点查必须走 join 单查（mcp_tools⋈mcp_servers / tenant_tool_grants⋈local_tools）");
        for (String forbidden : List.of("@Cacheable", "redis", "Redis", "cache")) {
            assertFalse(content.contains(forbidden),
                    "🔴 点查结果禁止缓存（缓存即回到 fail-open）：出现 " + forbidden);
        }

        Path orchestrator = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "tool", "ToolOrchestrator.java"));
        String orchestratorContent = read(orchestrator);
        int checkAt = orchestratorContent.indexOf("grantPointCheck.stillGranted");
        int runningAt = orchestratorContent.indexOf("recorder.markRunning");
        assertTrue(checkAt > 0, "🔴 ToolOrchestrator 必须调用执行前授权点查");
        assertTrue(runningAt > checkAt,
                "🔴 点查必须发生在 tool_calls → running **之前**（api-spec §7.6.3 契约表「时点」行）");
    }

    @Test
    @DisplayName("🔴 tool 包不得依赖 chat 包（否则 chat ↔ tool 成环，architecture.md §5.1.2）")
    void toolDoesNotDependOnChat() throws IOException {
        assertNoImport("tool", "com.eyes.albedo.chat");
    }

    @Test
    @DisplayName("🔴 §7.6.5：全代码库禁止用字符串还原模型函数名（_ → : 不可逆，猜错=执行未批准工具）")
    void noStringBasedFunctionNameRestore() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            for (String forbidden : FORBIDDEN_NAME_RESTORE) {
                if (content.contains(forbidden)) {
                    violations.add(file.getFileName() + " 出现 " + forbidden);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 必须靠 Map<functionName, 定义> 查表回映射，禁止字符串还原：" + violations);
    }

    @Test
    @DisplayName("🔴 §7.8.1 ③：执行期竞态必须收敛 denied+30050，禁止再向 30052 归一化")
    void noRaceNormalizationToMcpUnavailable() throws IOException {
        Path orchestrator = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "tool", "ToolOrchestrator.java"));
        String content = read(orchestrator);
        assertFalse(content.contains("normalizeExecutionStatus"),
                "🔴 running→denied 已是合法流转，禁止把 denied 归一化成 failed");
        assertFalse(content.contains("normalizeExecutionErrorCode"),
                "🔴 30052 仅保留连接/传输/协议/上游鉴权原义，禁止作为竞态兜底码");
        assertTrue(content.contains("deniedDuringExecution"),
                "🔴 必须存在执行期安全拒绝的专用分支（denied + 30050 + 审计路由）");
    }

    @Test
    @DisplayName("🔴 mcp 包不得依赖 tool 包（传输层不感知编排）")
    void mcpDoesNotDependOnTool() throws IOException {
        assertNoImport("mcp", "com.eyes.albedo.tool");
    }

    @Test
    @DisplayName("🔴 skill 包不得依赖 chat / tool 包（指令文本永不提权）")
    void skillDoesNotDependOnChatOrTool() throws IOException {
        assertNoImport("skill", "com.eyes.albedo.chat");
        assertNoImport("skill", "com.eyes.albedo.tool");
    }

    @Test
    @DisplayName("🔴 audit 包不得依赖任何业务包（审计是基础设施，只接收结构化入参）")
    void auditDoesNotDependOnBusiness() throws IOException {
        for (String business : List.of("mcp", "tool", "skill", "chat", "agent", "conversation",
                "metrics", "site", "membership", "platform", "configcheck")) {
            assertNoImport("audit", "com.eyes.albedo." + business);
        }
    }

    private void assertNoImport(String packageName, String forbiddenImport) throws IOException {
        Path root = SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", packageName));
        if (!Files.exists(root)) {
            return;
        }
        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java")).toList()) {
                if (read(file).contains("import " + forbiddenImport)) {
                    violations.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 " + packageName + " 包禁止依赖 " + forbiddenImport + "：" + violations);
    }

    /**
     * 粗粒度提取 {@code @Transactional} 方法体（按大括号配平）。
     */
    private List<String> transactionalMethodBodies(String content) {
        List<String> bodies = new ArrayList<>();
        Matcher matcher = Pattern.compile("@Transactional").matcher(content);
        while (matcher.find()) {
            int bodyStart = content.indexOf('{', matcher.end());
            if (bodyStart < 0) {
                continue;
            }
            int depth = 0;
            int i = bodyStart;
            while (i < content.length()) {
                char c = content.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                i++;
            }
            bodies.add(content.substring(bodyStart, Math.min(i + 1, content.length())));
        }
        return bodies;
    }

    private int countOccurrences(String content, String needle) {
        int count = 0;
        int at = content.indexOf(needle);
        while (at >= 0) {
            count++;
            at = content.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 读取源码并剔除注释（本项目大量 Javadoc 会引用被禁写法作为反面示例）。
     */
    private String read(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlockComments.replaceAll("(?m)^\\s*//.*$", " ");
    }
}
