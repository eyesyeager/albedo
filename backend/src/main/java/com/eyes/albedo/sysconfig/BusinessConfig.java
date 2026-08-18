package com.eyes.albedo.sysconfig;

import java.util.Optional;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业务参数强制读取器（反硬编码红线的落地工具）。
 *
 * <p>与 {@link ConfigService} 的分工：
 * <ul>
 *   <li>{@link ConfigService} 带 {@code defaultValue}，仅允许用于<b>基础设施自举</b>
 *       （如缓存 TTL、Host 信任开关——这些在配置缺失时必须能启动）</li>
 *   <li>本类<b>不接受默认值</b>：业务阈值缺失或非法一律抛 {@code 50003}，
 *       杜绝「配置没插进去，代码悄悄用了魔法值」（docs/architecture.md §7.3）</li>
 * </ul>
 *
 * <p>🔴 业务代码中出现的任何阈值、上限、开关都必须经由本类读取，禁止 Java 字面量。
 */
@Slf4j
@Component
public class BusinessConfig {

    private final ConfigService configService;

    public BusinessConfig(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 必需的整型业务参数。
     *
     * @throws BusinessException 50003 配置缺失或非法
     */
    public int requireInt(String group, String key) {
        String raw = requireRaw(group, key);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw missing(group, key, "期望 NUMBER");
        }
    }

    /**
     * 必需的长整型业务参数。
     *
     * @throws BusinessException 50003 配置缺失或非法
     */
    public long requireLong(String group, String key) {
        String raw = requireRaw(group, key);
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw missing(group, key, "期望 NUMBER");
        }
    }

    /**
     * 必需的布尔业务参数。
     *
     * @throws BusinessException 50003 配置缺失或非法
     */
    public boolean requireBoolean(String group, String key) {
        String raw = requireRaw(group, key).trim();
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw)) {
            return false;
        }
        throw missing(group, key, "期望 BOOLEAN");
    }

    /**
     * 必需的字符串业务参数。
     *
     * @throws BusinessException 50003 配置缺失
     */
    public String requireString(String group, String key) {
        return requireRaw(group, key);
    }

    private String requireRaw(String group, String key) {
        Optional<String> raw = configService.find(group, key);
        if (raw.isEmpty() || raw.get().isBlank()) {
            throw missing(group, key, "配置缺失");
        }
        return raw.get();
    }

    private BusinessException missing(String group, String key, String reason) {
        // 只记录键名与原因，不回显配置值（可能含内部地址）
        log.error("业务配置不可用：sys_config[{}.{}] {}", group, key, reason);
        return new BusinessException(ErrorCode.INTERNAL_ERROR);
    }
}
