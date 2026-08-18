package com.eyes.albedo.chat.service;

/**
 * 一次生成的<b>业务预算快照</b>（🔴 ADR-017 的 L2/L3，architecture.md §9.5.4 不变量 2/4）。
 *
 * <p><b>为什么必须有这个对象</b>（BUG-MCP-002 的直接病灶）：整流耗时 =
 * Σ(每轮模型时间) + Σ(工具执行) + Σ(等待用户确认)，而 Agent 的
 * {@code requestTimeoutSeconds} 只是 <b>单轮模型调用</b>的上限 —— 把后者当成整条流的寿命，
 * 连接必然在第一次确认等待期间被传输层掐断，此后写的 {@code done} 会被
 * {@code SseWriter.markBroken} 静默丢弃（"done 必发"在物理上不可能成立）。
 *
 * <p>🔴 <b>三层 deadline 的层间硬序</b>（本类承载 L2/L3）：
 * <pre>
 * L1 传输层（连接寿命）  = chat.generation_deadline_seconds + chat.deadline_grace_seconds
 *                        ← SseEmitter 的 timeout（ChatController）
 * L2 业务层（生成预算）  = chat.generation_deadline_seconds
 *                        ← 本类：入口 start(...) 一次，全程只取 remaining
 * L3 子步骤（每轮/每次）= min(该步骤自身上限, remaining − grace)
 *                        ← {@link #budgetFor(long)} / {@link #allows(long)}
 * 🔴 不变式：L3 ≤ L2 &lt; L1 ≤ spring.mvc.async.request-timeout
 * </pre>
 *
 * <p>🔴 <b>纪律</b>：
 * <ul>
 *   <li>只在<b>异步段</b>创建与使用（{@code ChatStreamRunner} 入口），🔴 不读 ThreadLocal、
 *       不读 Servlet 请求 —— 与 §9.5.2 的"快照纪律"一致</li>
 *   <li>🔴 <b>不可变</b>：只有 {@code System.nanoTime()} 在动，因此可安全地在同一生成线程内
 *       任意次读取；{@link #startedAtNanos()} 用 {@code nanoTime} 而非 {@code currentTimeMillis}，
 *       避免系统时钟被回拨时预算突然变长/变短</li>
 *   <li>🔴 "会阻塞的步骤必须在开始前确认自己能在预算内结束"——不能确认时的正确动作是
 *       <b>不开始</b>（{@link #allows(long)} 返回 false），而不是"开始了再指望被中断"</li>
 * </ul>
 *
 * @param startedAtNanos  预算起点（{@code System.nanoTime()}）
 * @param deadlineSeconds 业务总预算（{@code sys_config: chat.generation_deadline_seconds}）
 * @param graceSeconds    收尾宽限（{@code sys_config: chat.deadline_grace_seconds}）
 */
public record GenerationDeadline(long startedAtNanos, long deadlineSeconds, long graceSeconds) {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * 在<b>异步段入口</b>开始计时（🔴 预算值必须来自 {@code sys_config}，禁止代码默认值）。
     */
    public static GenerationDeadline start(long deadlineSeconds, long graceSeconds) {
        return new GenerationDeadline(System.nanoTime(), deadlineSeconds, graceSeconds);
    }

    /** 已消耗秒数（向下取整，仅用于日志）。 */
    public long elapsedSeconds() {
        return Math.floorDiv(System.nanoTime() - startedAtNanos, NANOS_PER_SECOND);
    }

    /**
     * 剩余预算秒数（🔴 <b>向下取整、可为负</b>）。
     *
     * <p>向下取整是刻意的保守取舍：宁可少算 1 秒也不允许"算多了一点点"导致
     * 业务收敛晚于传输层死亡。
     */
    public long remainingSeconds() {
        long remainingNanos = deadlineSeconds * NANOS_PER_SECOND
                - (System.nanoTime() - startedAtNanos);
        return Math.floorDiv(remainingNanos, NANOS_PER_SECOND);
    }

    /**
     * 可用于"开启新工作"的秒数 = {@code remaining − grace}（🔴 可为负）。
     *
     * <p>宽限必须留给"落库终态 + 写 {@code error} + 写 {@code done}"，
     * 因此任何新工作都只能用这部分预算。
     */
    public long usableSeconds() {
        return remainingSeconds() - graceSeconds;
    }

    /**
     * 预算是否已耗尽（🔴 判据 = {@code remaining ≤ grace}，§9.5.4 不变量 4 ④）。
     *
     * <p>命中即必须立刻按超时收敛（{@code error(50002)} + {@code done(timeout)}），
     * 🔴 不得再请求模型、不得再发确认卡、不得再发起工具执行。
     */
    public boolean exhausted() {
        return usableSeconds() <= 0L;
    }

    /**
     * 某个"会阻塞的步骤"是否允许开始（🔴 §9.5.4 不变量 4 ③ 的准入判定）。
     *
     * @param stepSeconds 该步骤自身的有效超时（如工具的 {@code timeout_seconds}）
     */
    public boolean allows(long stepSeconds) {
        return usableSeconds() >= stepSeconds;
    }

    /**
     * 子步骤的有效预算 = {@code min(自身上限, remaining − grace)}，🔴 下界 1 秒。
     *
     * <p>下界为 1 而不是 0：调用方必须先用 {@link #exhausted()} 判过"要不要开始"，
     * 走到这里说明预算尚有剩余；返回 0 会让上游把它当成"无超时"（更危险）。
     */
    public long budgetFor(long ownLimitSeconds) {
        return Math.max(1L, Math.min(ownLimitSeconds, usableSeconds()));
    }
}
