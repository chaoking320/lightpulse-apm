package com.monitor.server.filter;

import com.monitor.server.service.ApmTrafficRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 请求追踪与流量记录过滤器：精准追踪真实业务 QPS/RPM 与 Agent 上报，零存储零数据库负担
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestDebugFilter implements Filter {

    private final ApmTrafficRecorder trafficRecorder;

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        long startNanos = System.nanoTime();
        int statusCode = 200;
        try {
            if (servletRequest instanceof HttpServletRequest req) {
                String method = req.getMethod();
                String uri = req.getRequestURI();
                if ("PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)
                        || uri.contains("/agent") || uri.contains("/system-metrics")) {
                    log.info("📡 [HTTP 请求接入] method={}, URI={}, clientIP={}", method, uri, req.getRemoteAddr());
                }
            }
            filterChain.doFilter(servletRequest, servletResponse);
            if (servletResponse instanceof HttpServletResponse resp) {
                statusCode = resp.getStatus();
            }
        } catch (IOException | ServletException | RuntimeException e) {
            statusCode = 500;
            throw e;
        } finally {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            if (trafficRecorder != null && servletRequest instanceof HttpServletRequest req) {
                trafficRecorder.recordRequest(req.getRequestURI(), costMs, statusCode);
            }
        }
    }
}
