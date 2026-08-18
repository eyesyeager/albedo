package com.eyes.albedo.chat.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息（{@code scope=tenant}）。
 *
 * <p>关键设计：
 * <ul>
 *   <li><b>尝试模型</b>：重新生成不覆盖历史，而是新增 {@code attemptNo+1} 的 assistant 消息，
 *       旧尝试 {@code isCurrent=0} 保留（AC-CHAT-003），{@code supersedesMessageId} 指向被取代的尝试</li>
 *   <li><b>幂等</b>：用户消息携带 {@code idempotencyKey}，唯一键
 *       {@code uk_tenant_idem(tenant_id, uid, idempotency_key)} 在数据库层兜底防重（EX-013）；
 *       assistant 消息该列为 {@code NULL}（MySQL 唯一索引允许多个 NULL）</li>
 *   <li><b>不可见角色</b>：{@code system} / {@code tool} 消息🔴 不得返回给终端用户（PRD §8.8）</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "messages")
public class Message extends BaseTenantEntity {

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "uid", nullable = false)
    private Long uid;

    /** user / assistant / system / tool。 */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "longtext")
    private String content = "";

    /**
     * 推理型模型的思考过程（仅 {@code assistant}）。
     *
     * <p>🔴 <b>与 {@code content} 严格分离，不得合并</b>：{@code content} 是答案正文，
     * 参与上下文回灌、会话标题生成与「复制回答」；思考过程只用于展示。
     * 二者混存会同时污染这三条链路（api-spec §5.2 {@code delta.reasoning}）。
     *
     * <p>🔴 <b>可空语义</b>：{@code NULL} = 无思考过程（user 消息 / 非推理模型 /
     * V1.1.7 之前的历史消息），与「思考过程为空串」区分开。
     */
    @Column(name = "reasoning", columnDefinition = "longtext")
    private String reasoning;

    /**
     * 按轮次切分的「思考 / 正文」段落（JSON 数组，仅 {@code assistant}）。
     *
     * <p>🔴 <b>为什么需要它</b>：模型在多轮工具编排里的真实时序是
     * 「思考① → 工具① → 思考② → 正文」。{@link #content} 与 {@link #reasoning}
     * 是<b>全量拼接</b>的扁平文本，一旦落库就再也分不出「哪段思考发生在工具调用之前」，
     * 历史回显只能把工具节点整块堆到末尾 —— 因果关系被破坏。
     * 本列保存 {@code [{round, reasoning, text}, …]}，配合
     * {@code tool_calls.round} 即可还原「段落 → 该轮工具 → 下一段落」的真实顺序。
     *
     * <p>🔴 <b>它是渲染投影，不是事实来源</b>：{@code content} / {@code reasoning}
     * 仍是唯一权威（上下文回灌、标题生成、复制回答只认它们）。
     * 本列缺失或解析失败时，前端<b>降级为旧版布局</b>（全部思考 → 全部工具 → 全部正文），
     * 🔴 只损失排版、绝不丢内容 —— 这是刻意为之：绝不让一个排版字段有能力破坏正文。
     *
     * <p>🔴 符合 architecture §13.2 纪律 7：JSON 列只放<b>不可变快照/摘要</b>，
     * 本列不参与任何查询条件。
     */
    @Column(name = "segments", columnDefinition = "json")
    private String segments;

    /** pending / sent / queued / streaming / completed / stopped / failed。 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    @Column(name = "is_current", nullable = false, columnDefinition = "tinyint")
    private Integer isCurrent = 1;

    @Column(name = "supersedes_message_id")
    private Long supersedesMessageId;

    @Column(name = "model", nullable = false, length = 128)
    private String model = "";

    @Column(name = "agent_version", nullable = false)
    private Long agentVersion = 0L;

    /** JSON：{@code {"promptTokens":0,"completionTokens":0,"totalTokens":0}}。 */
    @Column(name = "token_usage", columnDefinition = "json")
    private String tokenUsage;

    /** stop / length / stopped / failed / tool_denied / timeout。 */
    @Column(name = "finish_reason", nullable = false, length = 32)
    private String finishReason = "";

    /** 失败时记录已登记的业务码（便于排障与前端语义还原）。 */
    @Column(name = "error_code")
    private Integer errorCode;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // ===== 角色 =====
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    // ===== 状态 =====
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_STREAMING = "streaming";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_FAILED = "failed";

    // ===== finishReason =====
    public static final String FINISH_STOP = "stop";
    public static final String FINISH_LENGTH = "length";
    public static final String FINISH_STOPPED = "stopped";
    public static final String FINISH_FAILED = "failed";
    public static final String FINISH_TIMEOUT = "timeout";
    /**
     * 🔴 M3：工具调用被拒绝（用户拒绝 / 未授权 / 确认超时）且模型无法在无工具结果下继续
     * （api-spec §5.2 {@code done.finishReason} 枚举 / §7.8.1 ⑤）。
     */
    public static final String FINISH_TOOL_DENIED = "tool_denied";

    /**
     * 是否已进入终态（终态不得再被流式写入覆盖）。
     */
    public boolean terminal() {
        return STATUS_COMPLETED.equals(status) || STATUS_STOPPED.equals(status)
                || STATUS_FAILED.equals(status);
    }

    public boolean current() {
        return isCurrent != null && isCurrent == 1;
    }
}
