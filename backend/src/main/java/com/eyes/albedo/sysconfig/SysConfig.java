package com.eyes.albedo.sysconfig;

import com.eyes.albedo.common.BaseAuditEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 平台级系统配置（{@code scope=platform}，🔴 不继承 BaseTenantEntity）。
 *
 * <p>承载「反硬编码」的<b>平台基础设施参数</b>：分页默认值、限流阈值、输入长度上限、
 * Host 信任开关、dev 映射、模型 provider 清单、埋点开关等。
 *
 * <p>🔴 边界（docs/architecture.md §7）：租户品牌与站点文案属于<b>租户级</b>配置，
 * 必须走 {@code site_config_versions} 的草稿→校验→发布流程，禁止写入本表。
 */
@Getter
@Setter
@Entity
@Table(name = "sys_config")
public class SysConfig extends BaseAuditEntity {

    @Column(name = "config_group", nullable = false, length = 100)
    private String configGroup;

    @Column(name = "config_key", nullable = false, length = 200)
    private String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    private String configValue;

    /** STRING / NUMBER / BOOLEAN / JSON。 */
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType = ValueType.STRING.name();

    @Column(name = "description", nullable = false, length = 500)
    private String description = "";

    /**
     * 是否下发前端（0 否 / 1 是）。
     *
     * <p>⚠️ 必须声明 {@code columnDefinition = "tinyint"}：架构 §13.3 的 DDL 用 TINYINT，
     * 而 Hibernate 对 {@code Integer} 的默认期望类型是 {@code integer}，
     * {@code ddl-auto: validate} 会因类型不符直接启动失败。
     */
    @Column(name = "is_frontend", nullable = false, columnDefinition = "tinyint")
    private Integer isFrontend = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    public boolean frontendVisible() {
        return isFrontend != null && isFrontend == 1;
    }

    /**
     * 配置值类型。
     */
    public enum ValueType {
        STRING, NUMBER, BOOLEAN, JSON;

        public static ValueType of(String raw) {
            if (raw == null || raw.isBlank()) {
                return STRING;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return STRING;
            }
        }
    }
}
