package com.eyes.albedo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.eyes.eyesAuth.constant.AuthConfigConstant;

/**
 * Web 层配置。
 *
 * <p>说明：
 * <ul>
 *   <li>本项目<b>不引入 Spring Security</b>，准入完全由 {@code @Permission} / {@code @TenantRole}
 *       切面承担，避免两套准入规则冲突</li>
 *   <li>CORS 仅按 {@code app.cors.allowed-origin-patterns} 放开（开发用）；生产同源部署应为空</li>
 *   <li>必须暴露响应头 {@code authorization}，否则浏览器读不到续期 token（auth-type=1 会失效）</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class WebMvcConfig implements WebMvcConfigurer {

    private final AppProperties appProperties;

    public WebMvcConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        String[] patterns = appProperties.getCors().getAllowedOriginPatterns();
        if (patterns == null || patterns.length == 0) {
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(patterns)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 🔴 前端需要读取续期 token 与请求链路 ID
                .exposedHeaders(AuthConfigConstant.HEADER_TOKEN, "X-Request-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
