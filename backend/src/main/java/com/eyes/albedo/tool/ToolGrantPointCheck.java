package com.eyes.albedo.tool;

import com.eyes.albedo.mcp.repository.McpToolRepository;
import com.eyes.albedo.tool.dto.ToolDefinition;
import com.eyes.albedo.tool.repository.TenantToolGrantRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 🔴 <b>每次工具执行前的授权点查</b>（api-spec §7.6.3 契约表 / architecture.md §9.5.1 #4 补注、
 * AC-MCP-004 / AC-CFG-004）。
 *
 * <p><b>🔴 为什么"清单构造时校验过"还必须再查一次</b>（V1.1.4 #4 裁决认定为
 * <b>订正实现缺口</b>，不是新增要求 —— §7.6.3 的标题自 V1.1 起就是「<b>每次调用前</b>」，
 * 其第 3 步本身即 {@code granted} 点查）：
 * <pre>
 * 清单构造（ToolCatalogService.buildCatalog）→ 工具执行之间横跨**整轮生成**：
 *   · 高风险确认等待最长 tool.confirm_wait_seconds（默认 120s）
 *   · 多轮循环最多 tool.max_rounds 轮
 * 👉 最坏可达**数分钟**。若不复查，"DBA 紧急撤销授权后工具还能被执行数分钟"，
 *    直接违反明文 AC-MCP-004「取消授权后运行时仍须拒绝」。
 * </pre>
 *
 * <p><b>🔴 三条硬约束（写错任何一条都等于把这道防线关掉）</b>：
 * <ol>
 *   <li>🔴 <b>≤1 次查询</b>：MCP 走 {@code mcp_tools JOIN mcp_servers} 单行点查，
 *       本地 Tool 走 {@code tenant_tool_grants JOIN local_tools} 单行点查；
 *       🔴 禁止拆成"先查 A 再查 B"（那是 2 次，会被 @测试 的 G-4 断言判缺陷）；</li>
 *   <li>🔴 <b>禁止缓存</b>（含 Redis / 进程内 / {@code @Cacheable}）：缓存即回到 fail-open，
 *       "改库即生效"随缓存 TTL 一起失效；</li>
 *   <li>🔴 <b>fail-closed</b>：查询本身异常（DB 抖动）也判"未授权"——
 *       宁可拒绝一次工具调用，也不在授权状态未知时放行。</li>
 * </ol>
 *
 * <p><b>🔴 位置与性能纪律（不违反 D-004 / D-006）</b>：本点查发生在
 * <b>异步段、首个可见帧之后</b>（{@code tool}(pending) 帧已下发），
 * 既不在"租户识别 + 配置读取 ≤20ms"的热路径内，也不受首字 P95 ≤5s 约束；
 * 相对 MCP 的一次网络往返（≤ {@code mcp.call_timeout_seconds}，默认 30s）完全可忽略。
 * 🔴 严禁把本调用挪进 {@code ChatController}（Servlet 线程）或租户识别链路。
 *
 * <p><b>🔴 事务纪律（ADR-010）</b>：本类<b>不加 {@code @Transactional}</b>——
 * 单条 count 查询在无事务下由连接自动提交即可，加事务只会在"事务外那一段"
 * （确认等待与工具执行之间）多开一个事务并延长连接占用。
 * 拒绝时的 {@code tool_calls → denied} + 审计 {@code tool.grant_denied} 由
 * {@link ToolCallRecorder#markTerminal} 在<b>同一个独立短事务</b>内写入。
 *
 * <p><b>🔴 残余窗口（AR-017，已登记为契约，@测试 不得判缺陷）</b>：点查通过 →
 * {@code invoke} 返回之间仍有 TOCTOU 窗口，大小 = <b>单次工具执行时长</b>
 * （本地 Tool ≈ 毫秒；MCP ≤ {@code mcp.call_timeout_seconds}）。
 * 判据：撤授权后<b>新发起</b>的执行必须 {@code 30050}；<b>已进入</b> {@code invoke} 的允许完成。
 * 🔴 <b>不</b>实现"执行中撤授权即中断"——那需要第二个线程 / 中断机制，违反 ADR-008 第 8 条。
 */
@Slf4j
@Service
public class ToolGrantPointCheck {

    private final McpToolRepository mcpToolRepository;
    private final TenantToolGrantRepository grantRepository;

    /**
     * 已执行的点查次数（🔴 <b>供测试断言"每次执行 ≤1 次查询"</b>，api-spec §7.1.2 查询次数表第 3 行）。
     *
     * <p>🔴 为什么把计数暴露出来（与 {@code RuntimeConfigValidator.validateWithQueryCount} 同一理由）：
     * "≤1 次"这条纪律必须<b>可机械验证</b> —— 否则"顺手多查一次"只能靠人肉 review，
     * 而 review 恰恰最容易放过它。计数器只增不减、无业务语义，不影响任何对外契约。
     */
    private final java.util.concurrent.atomic.AtomicLong queries =
            new java.util.concurrent.atomic.AtomicLong();

    public ToolGrantPointCheck(McpToolRepository mcpToolRepository,
                               TenantToolGrantRepository grantRepository) {
        this.mcpToolRepository = mcpToolRepository;
        this.grantRepository = grantRepository;
    }

    /** 累计点查次数（测试断言用）。 */
    public long queryCount() {
        return queries.get();
    }

    /**
     * 该工具在<b>此刻</b>是否仍被授权执行。
     *
     * <p>🔴 <b>复查范围（api-spec V1.1.5 G-2 裁决框 / AR-019 追认，注释即契约）</b>：
     * <pre>
     * ✅ 授权列 / 启用列：granted、status（工具行）、mcp_servers.status、
     *    mcp_servers.deleted_at IS NULL（🔴 只判 mcp_servers 这一张 —— G-1：
     *    mcp_tools / tenant_tool_grants / local_tools **无** deleted_at 列，不得加列）
     * ❌ 🔴 <b>不含绑定</b>：agent_capability_bindings **不复查**（绑定是本轮生成期快照，
     *    生成中解绑只在「下一次提问」生效，AR-019；@测试 据此断言而非判缺陷）
     * 🔴 预算 ≤1 次查询、🔴 禁缓存、🔴 fail-closed；严禁改写为"顺手复查绑定"
     *    （既破坏生成期清单自洽性，又必然把预算推到 2 次而被 G-4 断言判缺陷）
     * </pre>
     *
     * @param tenantId   租户号（🔴 仅用于日志；租户过滤由 Hibernate discriminator 保证）
     * @param definition 本次要执行的工具定义（来自本次生成的清单）
     * @return {@code true} 仍有效；{@code false} 已被撤销 / 已停用 / 判定失败（fail-closed）
     */
    public boolean stillGranted(String tenantId, ToolDefinition definition) {
        if (definition == null) {
            return false;
        }
        try {
            // 🔴 恰好一条查询（两个分支各自都是单条 join 点查）
            queries.incrementAndGet();
            long hit = definition.mcp()
                    ? mcpGrant(definition)
                    // 🔴 本地 Tool：一次 join 判 grant + 平台注册行状态
                    : grantRepository.countExecutableGrant(definition.toolKey());
            if (hit > 0) {
                return true;
            }
            log.warn("[SECURITY] 执行前授权点查未通过（授权已撤销 / 已停用），拒绝本次执行："
                            + "tenantId={} toolType={} toolKey={}",
                    tenantId, definition.toolType(), definition.toolKey());
            return false;
        } catch (RuntimeException e) {
            // 🔴 fail-closed：授权状态未知时一律拒绝（宁可少执行一次，也不越权执行一次）
            log.error("[SECURITY] 执行前授权点查失败，按未授权处理：tenantId={} toolKey={}",
                    tenantId, definition.toolKey(), e);
            return false;
        }
    }

    /**
     * MCP 工具点查（🔴 {@code mcpId} 缺失属配置非法，同样 fail-closed）。
     */
    private long mcpGrant(ToolDefinition definition) {
        if (definition.mcpId() == null) {
            log.error("[SECURITY] MCP 工具定义缺少 mcpId，无法点查授权，按未授权处理：toolKey={}",
                    definition.toolKey());
            return 0L;
        }
        return mcpToolRepository.countExecutableGrant(definition.mcpId(), definition.toolKey());
    }
}
