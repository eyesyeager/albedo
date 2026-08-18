package com.eyes.albedo.conversation.entity;

import java.time.Instant;

import com.eyes.albedo.tenant.BaseTenantEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 会话（{@code scope=tenant}）。
 *
 * <p>隔离要求：查询<b>必须</b>同时限定 {@code tenant_id}（Hibernate 自动）与 {@code uid}（业务显式），
 * 其他租户或其他用户的会话 ID 统一按「资源不存在」处理（AC-TEN-004 / AC-CON-003）。
 *
 * <p>{@code agentId + agentVersion} 在创建时固定，🔴 Agent 升级后<b>不静默切版</b>（RISK-005）。
 *
 * <p>{@code version} 为 JPA 乐观锁，用于多标签页并发重命名 → {@code 30020}（EX-023）。
 */
@Getter
@Setter
@Entity
@Table(name = "conversations")
public class Conversation extends BaseTenantEntity {

    @Column(name = "uid", nullable = false)
    private Long uid;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** 创建时绑定的 Agent 已发布版本号。 */
    @Column(name = "agent_version", nullable = false)
    private Long agentVersion;

    @Column(name = "title", nullable = false, length = 120)
    private String title = "";

    /** auto / model / manual；🔴 manual 之后自动标题不得覆盖（AC-CON-004）。 */
    @Column(name = "title_source", nullable = false, length = 16)
    private String titleSource = TITLE_SOURCE_AUTO;

    /** active / readOnly / deleted。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_READ_ONLY = "readOnly";
    public static final String STATUS_DELETED = "deleted";

    public static final String TITLE_SOURCE_AUTO = "auto";
    public static final String TITLE_SOURCE_MODEL = "model";
    public static final String TITLE_SOURCE_MANUAL = "manual";

    public boolean deleted() {
        return STATUS_DELETED.equals(status) || deletedAt != null;
    }

    public boolean manualTitle() {
        return TITLE_SOURCE_MANUAL.equals(titleSource);
    }
}
