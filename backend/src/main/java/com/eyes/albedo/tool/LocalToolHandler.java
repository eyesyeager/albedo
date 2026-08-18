package com.eyes.albedo.tool;

/**
 * 本地 Tool 实现体契约（🔴 平台内置 Java 组件，api-spec §7.7.1）。
 *
 * <p>🔴 <b>禁止租户上传 / 写入任何可执行代码</b>（PRD 非范围项）：
 * {@code local_tools} 表只登记<b>声明式元数据</b>（Schema / 风险等级 / 超时 / 幂等性），
 * 实现体一律是<b>平台内置</b>的 Spring Bean，由 {@link LocalToolRegistry} 按
 * {@link #toolKey()} <b>静态注册</b>。注册表中有行但平台无对应实现 → {@code 30060}。
 *
 * <p>🔴 <b>实现者纪律</b>：
 * <ol>
 *   <li>{@link #execute} 必须是<b>短、纯、不阻塞</b>的操作：<b>无网络、无数据库、无锁等待、
 *       无无界循环</b>。🔴 这不是建议 —— 已裁决（ADR-008 第 8 条 G8 补注）本地 Tool 超时
 *       只做"计时判定 + 如实上报"，<b>不强制中断实现体</b>（强制中断需要第二个线程，
 *       与"不新增线程池"冲突）。实现体自身长阻塞会占住一个 {@code aiStreamExecutor} 线程（AR-014）</li>
 *   <li>🔴 <b>禁止在实现体内开启事务</b>（ADR-010）：工具执行发生在<b>事务外</b>，
 *       {@code tool_calls} 状态流转由 {@code ToolCallRecorder} 在短事务里做</li>
 *   <li>返回值按<b>不可信内容</b>处理：调用方会脱敏 + 截断后才回灌模型</li>
 *   <li>业务失败请抛 {@link com.eyes.albedo.common.BusinessException}（{@code 30053} 入参不合法 /
 *       {@code 30057} 执行业务失败）；🔴 不要自己吞掉异常返回"成功"，也不要抛未分类异常</li>
 *   <li>🔴 阈值一律写在 {@code local_tools.input_schema}（含注解式扩展关键字），
 *       <b>不在 Java 写死、不新增 {@code sys_config} 键</b>（ADR-015 ②）</li>
 * </ol>
 *
 * <p>🔴 <b>一期内置清单（ADR-015 ①，@架构师 已复核）</b>：{@code datetime_now} 与
 * {@code calculator}（无外部副作用、不会长阻塞）、{@code skill_load}（读 Skill 版本快照，
 * 见 {@code SkillLoadHandler} 的差异说明）、{@code skill_exec}（🔴 <b>唯一有真实副作用</b>的
 * 内置工具，{@code risk_level=high}，仅执行 {@code skill_resources} 表锁定的预置脚本快照，
 * 见 {@code SkillExecHandler} 的边界说明）。新增内置工具必须先回写 api-spec §7.7.1 清单
 * 并由 @架构师 复核，🔴 后端不得自行发明有真实副作用的工具（退款 / 导出 / 工单）——
 * {@code skill_exec} 之所以被允许突破"无外部副作用"红线，是因为它的"副作用"来源被
 * 严格限定为<b>平台/租户预置数据</b>，而非模型/用户运行时自由输入，威胁模型与
 * "退款/导出"类工具（任意业务副作用）本质不同。
 */
public interface LocalToolHandler {

    /**
     * 工具键（🔴 必须与 {@code local_tools.tool_key} 完全一致，格式 {@code ^[a-z][a-z0-9_]{1,63}$}）。
     */
    String toolKey();

    /**
     * 执行工具。
     *
     * @param invocation 已通过<b>授权判定 + JSON Schema 校验</b>的调用请求
     * @return 结果文本（JSON 或纯文本；调用方负责脱敏与截断）
     */
    String execute(LocalToolInvocation invocation);

    /**
     * 一次本地工具调用的入参（🔴 不含租户凭据、不含消息正文）。
     *
     * @param tenantId        租户号（显式传入：异步段禁止依赖 ThreadLocal，architecture.md §9.5.2）
     * @param uid             调用者 uid（快照值）
     * @param toolKey         工具键
     * @param argumentsJson   已校验的入参 JSON
     * @param configJson      {@code tenant_tool_grants.config}（非代码配置，可为空）
     * @param inputSchemaJson {@code local_tools.input_schema} 原文。🔴 存在的唯一理由：
     *                        ADR-015 ② 要求"表达式长度 / 括号深度 / 数字位数等阈值一律写在
     *                        {@code input_schema} 里，不在 Java 写死、也不新增 {@code sys_config} 键"，
     *                        因此实现体必须能读到自己的 Schema 注解（如 {@code x-limits}）
     * @param agentVersionId  🔴 本次改造新增：本次生成绑定的 Agent 版本 ID（快照，非"当前最新"）。
     *                        存在的唯一理由：{@code skill_load}（ADR-015 修订新增的第三个内置
     *                        本地 Tool）需要据此回查"当前 Agent 绑定了哪些 Skill"
     *                        （{@code SkillVersionService.resolveBindings} 的入参）。
     *                        {@code datetime_now} / {@code calculator} 不使用该字段
     */
    record LocalToolInvocation(String tenantId, Long uid, String toolKey, String argumentsJson,
                               String configJson, String inputSchemaJson, long agentVersionId) {

        /** 兼容构造（🔴 {@code agentVersionId} 缺省为 {@code 0}，供既有单测/无需感知的实现体使用）。 */
        public LocalToolInvocation(String tenantId, Long uid, String toolKey, String argumentsJson,
                                   String configJson, String inputSchemaJson) {
            this(tenantId, uid, toolKey, argumentsJson, configJson, inputSchemaJson, 0L);
        }
    }
}
