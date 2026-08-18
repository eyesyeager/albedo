package com.eyes.albedo.sysconfig;

import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * 平台级配置读取服务（反硬编码主链路）。
 *
 * <p>实现要求（{@link ConfigServiceImpl}）：Redis 缓存 + <b>写后主动失效</b>，做到"改完即生效、无需重启"。
 *
 * <p>🔴 纪律：
 * <ul>
 *   <li>{@code defaultValue} 仅允许用于<b>基础设施自举</b>（如缓存 TTL 自身）；
 *       业务参数缺失必须暴露为错误（30012 / 50003），禁止用代码默认值静默兜底</li>
 *   <li>禁止在 Java 代码中出现业务魔法值；键名统一取自 {@link ConfigKeys}</li>
 *   <li>租户品牌/站点文案不在此服务范围内（见 site 模块）</li>
 * </ul>
 */
public interface ConfigService {

    Optional<String> find(String group, String key);

    String getString(String group, String key, String defaultValue);

    int getInt(String group, String key, int defaultValue);

    long getLong(String group, String key, long defaultValue);

    boolean getBoolean(String group, String key, boolean defaultValue);

    <T> T getJson(String group, String key, TypeReference<T> type, T defaultValue);

    /**
     * 全部 {@code is_frontend=1} 配置，按 group 聚合，value 已按 {@code value_type} 转型，
     * key 转为 camelCase。用于 {@code GET /api/v1/sys-config}。
     */
    Map<String, Map<String, Object>> frontendConfig();

    /**
     * 指定分组的前端配置。
     */
    Map<String, Object> frontendConfig(String group);

    /**
     * 失效单项缓存（管理端写入后必须调用）。
     */
    void evict(String group, String key);

    /**
     * 失效全部配置缓存（含前端聚合）。
     */
    void evictAll();

    /**
     * 失效单项缓存并<b>回报实际条目数</b>（L1 + L2 分别计数）。
     *
     * <p>为什么需要这个变体：api-spec §7.2.1 的缓存失效接口必须返回各作用域<b>实际失效条目数</b>，
     * 并在部分失败时返回 {@code 30061} + {@code incompleteScopes[]}，🔴 禁止伪报成功。
     * 原有 {@link #evict(String, String)} 返回 void 且吞掉 Redis 故障（M1 语义：不让缓存故障
     * 升级为业务失败），两种语义不能混用，故additively 提供本方法：
     * <b>失败必须抛出</b>，由调用方计入未完成作用域。
     */
    com.eyes.albedo.tenant.CacheEviction evictAndCount(String group, String key);

    /**
     * 失效全部配置缓存并回报实际条目数（语义同 {@link #evictAndCount(String, String)}）。
     *
     * @param group 限定分组；为空则全部
     */
    com.eyes.albedo.tenant.CacheEviction evictAllAndCount(String group);
}
