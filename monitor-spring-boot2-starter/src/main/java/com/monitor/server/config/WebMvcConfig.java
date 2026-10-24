package com.monitor.server.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 路由与安全拦截配置（精确匹配监控中心路径，不侵入宿主业务路由）
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**", "/apm/**")
                .excludePathPatterns(
                        "/login.html",
                        "/apm/login.html",
                        "/api/auth/login",
                        "/api/v1/auth/login",
                        "/apm/api/v1/auth/login",
                        "/api/auth/captcha",
                        "/api/v1/auth/captcha",
                        "/apm/api/auth/captcha",
                        "/apm/api/v1/auth/captcha",
                        "/api/auth/status",
                        "/api/v1/auth/status",
                        "/apm/api/v1/auth/status",
                        "/api/setup/**",
                        "/api/v1/setup/**",
                        "/apm/api/v1/setup/**",
                        "/api/health",
                        "/favicon.ico",
                        "/error",
                        "/assets/**"
                );
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/apm/**")
                .addResourceLocations("classpath:/static/apm/", "classpath:/static/");
    }
}
