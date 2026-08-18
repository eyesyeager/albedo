package com.eyes.albedo.sysconfig;

import java.util.Map;

import com.eyes.albedo.common.Result;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台配置下发接口（反硬编码主链路）。
 *
 * <p>契约：{@code docs/api-spec.md §4.1.1}
 * <ul>
 *   <li>路径为平台级白名单，不需要租户上下文（前端启动即可拉取）</li>
 *   <li>只下发 {@code is_frontend=1} 的配置项</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sys-config")
public class SysConfigController {

    private final ConfigService configService;

    public SysConfigController(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * 获取前端可用平台配置。
     *
     * @param group 可选分组，缺省返回全部 {@code is_frontend=1} 项
     */
    @Permission(PermissionEnum.NO)
    @GetMapping
    public Result<Map<String, ?>> frontendConfig(@RequestParam(required = false) String group) {
        if (group == null || group.isBlank()) {
            return Result.success(configService.frontendConfig());
        }
        return Result.success(Map.of(group, configService.frontendConfig(group)));
    }
}
