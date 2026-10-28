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
                        "/api/auth/**",
                        "/api/v1/auth/**",
                        "/apm/api/auth/**",
                        "/apm/api/v1/auth/**",
                        "/api/setup/**",
                        "/api/v1/setup/**",
                        "/apm/api/setup/**",
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
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/apm").setViewName("forward:/apm/index.html");
        registry.addViewController("/apm/").setViewName("forward:/apm/index.html");
    }
}
