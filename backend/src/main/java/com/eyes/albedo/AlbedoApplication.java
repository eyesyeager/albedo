package com.eyes.albedo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Albedo 多租户 AI 问答系统 —— 后端单体服务入口。
 *
 * <p>架构红线（详见 docs/architecture.md）：
 * <ul>
 *   <li>单体单一 JAR：禁止 Gateway / 多服务 / 注册中心 / 消息中间件 / WebFlux</li>
 *   <li>鉴权只接入耶瞳 SSO：禁止自建登录 / 注册 / 登出 / 刷新令牌接口</li>
 *   <li>反硬编码：业务参数入 sys_config，租户品牌配置入 site_config_versions</li>
 * </ul>
 *
 * <p>注意：组件扫描根为 {@code com.eyes.albedo}，因此 starter 包 {@code com.eyes.eyesAuth}
 * 下的 {@code PermissionAdvice}（javax.servlet 版本）不会被注册，鉴权由
 * {@link com.eyes.albedo.auth.PermissionAspect} 承担（ADR-002）。
 */
@EnableAsync
@ConfigurationPropertiesScan
@SpringBootApplication
public class AlbedoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlbedoApplication.class, args);
    }
}
