package com.eyes.albedo.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 启动自检的必备配置清单测试（AR-012 / architecture.md §13.6 落地纪律第 2 条）。
 *
 * <p>🔴 为什么必须自动化：api-spec §7.1.2 的 <b>25</b> 个键若漏纳入 {@code REQUIRED_CONFIG}，
 * 故障会变成"某个用户调用工具时才 30060/50003"的零散形式，排障成本极高；
 * 埋点两键漏纳入更隐蔽 —— 读取侧是 fail-closed，缺键表现为"埋点静默停摆"。
 * 本用例把"缺键即启动失败"的前提（清单完整）钉死在编译产物上。
 */
class StartupCheckerRequiredConfigTest {

    /** 🔴 字面量直接照抄 api-spec §7.1.2 / architecture.md §13.6 的 22 键（已删除确认/风险 3 键）。 */
    private static final List<String> M3_KEYS = List.of(
            "tool.max_rounds",
            "tool.default_timeout_seconds",
            "tool.max_timeout_seconds",
            "tool.result_max_bytes",
            "tool.args_summary_max_chars",
            "tool.result_summary_max_chars",
            "mcp.require_https",
            "mcp.connect_timeout_seconds",
            "mcp.call_timeout_seconds",
            "mcp.discover_timeout_seconds",
            "mcp.blocked_ip_cidrs",
            "mcp.allowed_internal_cidrs",
            "mcp.transport_preferred",
            "mcp.max_tools_per_server",
            "skill.instruction_max_chars",
            "skill.max_variables",
            "observability.analytics_batch_max",
            "observability.analytics_allowed_events",
            "observability.analytics_anonymous_enabled",
            // 🔴 V1.1.3 / V1.3.2：system 消息总长预算
            "chat.system_prompt_max_chars",
            // 🔴 V1.1.4 #1：埋点总开关与采样率（读取侧 fail-closed）
            "observability.analytics_enabled",
            "observability.analytics_sample_rate");

    @Test
    @DisplayName("🔴 api-spec §7.1.2 的 22 个 sys_config 键全部纳入 StartupChecker（缺键即启动失败）")
    void allM3KeysAreRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        for (String key : M3_KEYS) {
            assertTrue(required.contains(key), "未纳入启动校验的配置键：" + key);
        }
        assertTrue(M3_KEYS.size() == 22, "契约要求恰好 22 键，实际：" + M3_KEYS.size());
    }

    @Test
    @DisplayName("🔴 V1.4.0 ADR-016 两键已纳入启动校验（缺键即启动失败，读取侧 fail-closed 只是第二道兜底）")
    void sseLegacyKeysAreRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        // 🔴 sse_legacy_enabled 缺失时读取侧按 false → 表现为"异步形态上游永远 30052"，
        //    若不在启动拦下，运维会以为"代码不支持该上游"而白排查；
        //    sse_stream_max_bytes 缺失则每次 sse 调用 50003（BusinessConfig 无默认值兜底）。
        for (String key : List.of("mcp.sse_legacy_enabled", "mcp.sse_stream_max_bytes")) {
            assertTrue(required.contains(key), "未纳入启动校验的配置键：" + key);
        }
    }

    @Test
    @DisplayName("M1 既有必备键未被回退删除")
    void m1KeysStillRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        for (String key : List.of("business.page_size_default", "business.page_size_max",
                "chat.message_max_chars", "chat.stream_heartbeat_seconds",
                "ratelimit.message_per_minute", "model.providers")) {
            assertTrue(required.contains(key), "M1 必备键被移除：" + key);
        }
    }

    @Test
    @DisplayName("🔴 V1.1.9 多轮上下文两键已纳入启动校验（缺键即启动失败）")
    void contextBudgetKeysAreRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        // 🔴 context_max_chars 缺失会让窗口退回"只按条数"——
        //    20 条 × 20000 字符可击穿上游窗口，且该会话每轮都复现（永久不可用）。
        //    因此它必须与其它业务参数同规格：缺键即启动失败，禁止代码默认值兜底。
        for (String key : List.of("chat.context_max_chars",
                "chat.context_summary_item_max_chars")) {
            assertTrue(required.contains(key), "未纳入启动校验的配置键：" + key);
        }
    }

    @Test
    @DisplayName("🔴 V1.2.2 ADR-017/018 三键已纳入启动校验（键总数 29 → 32，缺键即启动失败）")
    void generationBudgetAndGuidelineKeysAreRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        // 🔴 前两键缺失 → SseEmitter 无法确定连接寿命，只能退回 BUG-MCP-002 的错误量纲
        //    （拿 Agent 单轮模型超时当整流寿命）；
        // 🔴 纪律段缺失 → 模型失去"只传必填参数 + 服务器当前时间"的引导，
        //    BUG-MCP-001 的另一半修复静默失效。本键**没有关闭开关**（要弱化引导请改文案）。
        for (String key : List.of("chat.generation_deadline_seconds",
                "chat.deadline_grace_seconds", "chat.tool_usage_guideline")) {
            assertTrue(required.contains(key), "未纳入启动校验的配置键：" + key);
        }
    }

    @Test
    @DisplayName("🔴 V1.2.5 ADR-020 三键已纳入启动校验（键总数 32 → 35，缺键即启动失败）")
    void quotaKeysAreRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        // 🔴 三者都是**平台默认值**：缺失时既不能按 true/false 猜开关，也不能按 3/50 猜阈值
        //    （反硬编码红线优先于"拦截型开关可 fail-open"这条一般原则）。
        for (String key : List.of("ratelimit.qpm_enabled", "ratelimit.daily_quota_enabled",
                "ratelimit.daily_quota_limit")) {
            assertTrue(required.contains(key), "未纳入启动校验的配置键：" + key);
        }
    }

    @Test
    @DisplayName("🔴 V1.2.5：ratelimit.message_per_hour 已从必备集**移除**（小时窗业务规则废除）")
    void hourlyKeyIsNoLongerRequired() {
        List<String> required = StartupChecker.requiredConfigKeys();
        // 🔴 处置顺序不可颠倒（§13.6 纪律 10）：先上线"移除读取点 + 移出必备集 + 删常量"，
        //    再 DELETE 库内该行；若必备集仍含该键而库内行已删，服务会启动即失败。
        assertTrue(!required.contains("ratelimit.message_per_hour"),
                "🔴 必备集仍含已废弃键：删库行后会导致启动失败");
        // 🔴 反向守护：M1 键集的 25 键子集断言不得被借机改动（message_per_minute 必须仍在）
        assertTrue(required.contains("ratelimit.message_per_minute"),
                "🔴 QPM 键仍是必备键（只是阈值来源上移为策略入参，键本身不变）");
    }
}
