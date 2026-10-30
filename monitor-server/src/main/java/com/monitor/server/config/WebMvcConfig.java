package com.monitor.server.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 拦截器注册与全局路由规则 (兼容根路径与 /apm 访问)
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**", "/apm/**", "/api/**")
                .excludePathPatterns(
                        "/login.html",
                        "/apm/login.html",
                        "/api/auth/login",
                        "/api/v1/auth/login",
                        "/apm/api/auth/login",
                        "/apm/api/v1/auth/login",
                        "/api/auth/captcha",
                        "/api/v1/auth/captcha",
                        "/apm/api/auth/captcha",
                        "/apm/api/v1/auth/captcha",
                        "/api/auth/status",
                        "/api/v1/auth/status",
                        "/apm/api/auth/status",
                        "/apm/api/v1/auth/status",
                        "/api/setup/status",
                        "/api/v1/setup/status",
                        "/apm/api/setup/status",
                        "/apm/api/v1/setup/status",
                        "/api/setup/health",
                        "/api/v1/setup/health",
                        "/apm/api/setup/health",
                        "/apm/api/v1/setup/health",
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
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/apm").setViewName("forward:/apm/index.html");
        registry.addViewController("/apm/").setViewName("forward:/apm/index.html");
    }
}
