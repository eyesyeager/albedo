package com.eyes.albedo.tenant;

import java.util.Arrays;

import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产环境安全守卫：dev Host 映射<b>必须关闭</b>（EX-028 / AC-TEN-007）。
 *
 * <p>命中即抛异常终止启动 —— 这是上线阻断项，不允许以"日志告警"了事，
 * 否则租户识别可被 Host 伪造绕过（RISK-008）。
 */
@Slf4j
@Component
public class DevHostMappingGuard {

    private static final String PROD_PROFILE = "prod";

    private final Environment environment;
    private final ConfigService configService;

    public DevHostMappingGuard(Environment environment, ConfigService configService) {
        this.environment = environment;
        this.configService = configService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
        boolean devMappingEnabled = configService.getBoolean(ConfigKeys.GROUP_TENANT,
                ConfigKeys.DEV_HOST_MAPPING_ENABLED, false);

        if (prod && devMappingEnabled) {
            throw new IllegalStateException(
                    "生产环境禁止启用 dev Host 映射：请将 sys_config[tenant.dev_host_mapping_enabled] 置为 false");
        }
        if (devMappingEnabled) {
            log.warn("dev Host 映射已启用（仅允许非生产环境）：sys_config[{}.{}]=true",
                    ConfigKeys.GROUP_TENANT, ConfigKeys.DEV_HOST_MAPPING_ENABLED);
        }
    }
}
