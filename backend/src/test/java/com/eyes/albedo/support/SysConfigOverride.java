package com.eyes.albedo.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eyes.albedo.sysconfig.ConfigService;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试用 {@code sys_config} 临时覆盖器（🔴 仅 {@code src/test}）。
 *
 * <p><b>为什么需要它</b>：本项目没有独立测试库（{@code src/test/resources/application.yml} 已说明），
 * {@code sys_config} 是<b>与开发环境共享</b>的平台表。而 api-spec §7.13 要求
 * {@code test} 场景把 {@code mcp.require_https} 置为 {@code false}、
 * {@code mcp.allowed_internal_cidrs} 置为 {@code ["127.0.0.1/32"]} 才能访问环回 Mock MCP。
 *
 * <p>🔴 若直接改库不还原，等于<b>永久削弱</b>共享库里的 SSRF 策略
 * （dev 环境从此可以打内网）—— 因此本类强制"用完即还原"：
 * {@link #restore()} 必须在 {@code @AfterEach} 中调用，
 * 并在覆盖与还原<b>两侧</b>都失效 {@link ConfigService} 的 L1 + L2 缓存
 * （否则改了库但读到旧值，就是 M1 的 D-003/D-006 缺陷复现）。
 *
 * <p>🔴 生产侧的兜底是 {@code StartupChecker.checkProductionBlockers()}：
 * prod profile 下这两项若不是 {@code true} / {@code []}，应用<b>启动即失败</b>。
 */
public final class SysConfigOverride {

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    /** 键（group.key）→ 原值（null 表示原本不存在）。 */
    private final Map<String, String> original = new LinkedHashMap<>();

    public SysConfigOverride(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    /**
     * 覆盖一项配置（记录原值以便还原）。
     */
    public SysConfigOverride set(String group, String key, String value) {
        String mapKey = group + "." + key;
        if (!original.containsKey(mapKey)) {
            original.put(mapKey, currentValue(group, key));
        }
        jdbcTemplate.update("UPDATE sys_config SET config_value = ? "
                + "WHERE config_group = ? AND config_key = ?", value, group, key);
        configService.evict(group, key);
        return this;
    }

    /**
     * 还原全部被覆盖项（🔴 必须在 {@code @AfterEach} 调用）。
     */
    public void restore() {
        original.forEach((mapKey, value) -> {
            int dot = mapKey.indexOf('.');
            String group = mapKey.substring(0, dot);
            String key = mapKey.substring(dot + 1);
            if (value != null) {
                jdbcTemplate.update("UPDATE sys_config SET config_value = ? "
                        + "WHERE config_group = ? AND config_key = ?", value, group, key);
            }
            configService.evict(group, key);
        });
        original.clear();
    }

    private String currentValue(String group, String key) {
        return jdbcTemplate.query("SELECT config_value FROM sys_config "
                        + "WHERE config_group = ? AND config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null, group, key);
    }
}
