package com.monitor.server.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 请求追踪过滤器：精准打印所有 Agent 上报与非 GET 请求，彻底消除协议/路径黑盒
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestDebugFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        if (servletRequest instanceof HttpServletRequest req) {
            String method = req.getMethod();
            String uri = req.getRequestURI();
            if ("PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                    || uri.contains("/agent") || uri.contains("/system-metrics")) {
                log.info("📡 [HTTP 请求接入] method={}, URI={}, clientIP={}", method, uri, req.getRemoteAddr());
            }
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }
}
