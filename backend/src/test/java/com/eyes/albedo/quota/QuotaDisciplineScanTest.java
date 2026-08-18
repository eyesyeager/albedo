package com.eyes.albedo.quota;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 额度域的<b>反硬编码与纪律静态扫描</b>（🔴 ADR-020 的红线守护，做法参照
 * {@code McpThreadDisciplineScanTest} 的静态扫描先例）。
 *
 * <p>🔴 <b>为什么必须是静态扫描</b>：这些红线的违反<b>不会让任何功能测试变红</b> ——
 * 代码里写一个 {@code 50} 兜底，在库内配置正确时行为完全一致，只有等到"配置缺失/被改"
 * 那天才会以"改了库却毫无效果"或"额度悄悄按 50 算"的形式暴露，而那时已无从追溯。
 *
 * <p>扫描规则（逐条对应 ADR-020 的明文禁止）：
 * <ol>
 *   <li>🔴 额度路径代码中不得出现 {@code 3} / {@code 50} 数值字面量（阈值兜底）</li>
 *   <li>🔴 不得出现 {@code 86400}（日窗口必须按日历规则计算，DST 日为 23 / 25 小时）</li>
 *   <li>🔴 不得出现 {@code DECR}（严禁为"回退 QPM"实现计数回拨）</li>
 *   <li>🔴 窗口计算路径不得出现 {@code Instant.now()} / {@code ZonedDateTime.now()}
 *       （时刻必须来自注入的 {@code Clock}）</li>
 *   <li>🔴 不得自造 {@code quota.*} 缓存 TTL 配置键（本增量零新增缓存键、零 TTL 键）</li>
 *   <li>🔴 不得出现 {@code requireIntForTenant} 之类的"租户维度 BusinessConfig 入口"</li>
 * </ol>
 *
 * <p>🔴 扫描前<b>剔除注释与字符串字面量</b>：本项目的 Javadoc 大量引用被禁止的写法作为
 * 契约说明（如"库中默认 {@code 50}"），连注释一起扫会产生假阳性，
 * 而假阳性会促使后来者放宽规则 —— 那才是真正的风险。
 */
class QuotaDisciplineScanTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final Path QUOTA_PACKAGE =
            SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", "quota"));

    /** 🔴 额度判定链上的 chat 侧文件（阈值与结算的另一半实现）。 */
    private static final List<Path> CHAT_SIDE_FILES = List.of(
            SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", "chat", "service",
                    "MessageRateLimiter.java")),
            SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", "chat", "service",
                    "GenerationAdmission.java")),
            SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", "chat", "service",
                    "QuotaSettlement.java")));

    /** 🔴 被禁止的阈值字面量（作为独立数值出现即违规）。 */
    private static final List<String> FORBIDDEN_NUMBERS = List.of("3", "50", "86400");

    @Test
    @DisplayName("🔴 反硬编码：额度路径代码中不得出现 3 / 50 / 86400 数值字面量")
    void forbidThresholdLiterals() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String code = readCodeOnly(file);
            for (String number : FORBIDDEN_NUMBERS) {
                Matcher matcher = Pattern.compile("(?<![\\w.])" + number + "(?![\\w.])")
                        .matcher(code);
                if (matcher.find()) {
                    violations.add(file.getFileName() + " 出现数值字面量 " + number
                            + "（阈值必须来自 sys_config / 策略表，日窗口必须按日历规则计算）");
                }
            }
        }
        assertTrue(violations.isEmpty(), "存在反硬编码红线违规：" + violations);
    }

    @Test
    @DisplayName("🔴 严禁为\"回退 QPM\"实现 DECR（会让被限流期间重试不延长封禁窗口的性质失效）")
    void forbidCounterRollback() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String code = readCodeOnly(file);
            if (code.contains("DECR") || code.contains("decrement")) {
                violations.add(file.getFileName() + " 出现计数回拨（DECR / decrement）");
            }
        }
        assertTrue(violations.isEmpty(), "存在计数回拨实现：" + violations);
    }

    @Test
    @DisplayName("🔴 窗口计算路径不得出现 Instant.now() / ZonedDateTime.now()（时刻必须来自注入 Clock）")
    void forbidAmbientClockInWindowPaths() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : List.of(
                QUOTA_PACKAGE.resolve(Paths.get("service", "QuotaWindowResolver.java")),
                SOURCE_ROOT.resolve(Paths.get("com", "eyes", "albedo", "chat", "service",
                        "MessageRateLimiter.java")))) {
            String code = readCodeOnly(file);
            if (code.contains("Instant.now()") || code.contains("ZonedDateTime.now()")
                    || code.contains("LocalDate.now()") || code.contains("System.currentTimeMillis")) {
                violations.add(file.getFileName() + " 使用了环境时钟（窗口标识是业务判定，必须可确定性验证）");
            }
        }
        assertTrue(violations.isEmpty(), "窗口计算路径存在环境时钟：" + violations);
    }

    @Test
    @DisplayName("🔴 零新增缓存键 / TTL 键：不得自造 quota.*_cache_ttl_seconds 之类的配置键")
    void forbidSelfInventedCacheKeys() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : scannedFiles()) {
            String code = readCodeOnly(file);
            if (code.contains("policy_cache_ttl") || code.contains("POLICY_CACHE_TTL")
                    || code.contains("usage_retention_days")) {
                violations.add(file.getFileName() + " 自造了额度域缓存 / 保留期配置键"
                        + "（一期直读 MySQL：零缓存键、零 TTL 键、零失效逻辑）");
            }
        }
        assertTrue(violations.isEmpty(), "存在自造配置键：" + violations);
    }

    @Test
    @DisplayName("🔴 租户覆盖的唯一入口是 QuotaPolicyResolver：禁止 BusinessConfig 出现租户维度方法")
    void forbidTenantScopedBusinessConfigEntry() throws IOException {
        String businessConfig = readCodeOnly(SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "sysconfig", "BusinessConfig.java")));

        assertTrue(!businessConfig.contains("ForTenant") && !businessConfig.contains("tenantId"),
                "🔴 BusinessConfig 是 sys_config（平台作用域）的读取器，"
                        + "塞进租户维度会让 §7 的配置域边界失守（ADR-020 ① 明确禁止 requireIntForTenant）");
    }

    @Test
    @DisplayName("🔴 message_per_hour 常量已删除（禁止留一个无人读的常量诱导复用）")
    void forbidDeprecatedHourlyKey() throws IOException {
        String configKeys = readCodeOnly(SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "sysconfig", "ConfigKeys.java")));

        assertTrue(!configKeys.contains("MESSAGE_PER_HOUR"),
                "🔴 ratelimit.message_per_hour 已废弃：常量必须一并删除（§13.6 纪律 10 第 ③ 步）");
        assertTrue(!configKeys.contains("message_per_hour"),
                "🔴 键名字面量也不得残留");
    }

    private List<Path> scannedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(QUOTA_PACKAGE)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(files::add);
        }
        files.addAll(CHAT_SIDE_FILES);
        assertTrue(files.size() > CHAT_SIDE_FILES.size(),
                "前置条件：quota 包必须存在且含实现文件");
        // 🔴 防"扫描空转"：剔除注释/字符串之后必须仍然看得见真实代码，
        //    否则本类会变成一个永远通过的假防线
        String sample = readCodeOnly(QUOTA_PACKAGE.resolve(
                Paths.get("service", "QuotaPolicyResolver.java")));
        assertTrue(sample.contains("class QuotaPolicyResolver") && sample.contains("MIN_LIMIT"),
                "前置条件：剔除注释与字符串后必须仍保留代码正文（防扫描空转）");
        return files;
    }

    /**
     * 读取源码并<b>剔除块注释、行注释与字符串字面量</b>（避免契约说明造成假阳性）。
     */
    private String readCodeOnly(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLineComments = withoutBlockComments.replaceAll("(?m)//.*$", " ");
        // 先剔除三引号文本块（Lua 脚本 / SQL），再剔除普通字符串
        String withoutTextBlocks = withoutLineComments.replaceAll("(?s)\"\"\".*?\"\"\"", " ");
        return withoutTextBlocks.replaceAll("\"(\\\\.|[^\"\\\\])*\"", " ");
    }
}
