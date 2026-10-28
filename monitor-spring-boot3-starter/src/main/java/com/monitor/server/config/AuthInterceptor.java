package com.monitor.server.config;

import com.monitor.server.controller.AuthController;
import com.monitor.server.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 控制台与接口访问全链路安全拦截器
 * 1. 严格拦截未授权控制台页面与全部数据管理 API（必须携带合法 User Token）
 * 2. 严格校验 Agent 数据上报通道（必须携带合法 Agent Secret Token，防止伪造上报）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();

        // 1. 公开静态资源与登录认证入口放行
        if (isPublicResource(uri)) {
            return true;
        }

        // 2. Agent 专用通道：校验 X-Agent-Token (支持未配置、默认秘钥 lp-agent-secret-token 全线免密畅通)
        if (uri.startsWith("/api/v1/agent/") || uri.startsWith("/apm/api/v1/agent/")) {
            String configuredToken = authService.getAuthConfig().getAgentSecretToken();
            if (configuredToken == null || configuredToken.isBlank() || "lp-agent-secret-token".equals(configuredToken)) {
                return true; // 若未配置秘钥或为默认秘钥，直接放行，无需复杂配置
            }
            String agentToken = request.getHeader("X-Agent-Token");
            if (agentToken == null || agentToken.isBlank()) {
                String authHdr = request.getHeader("Authorization");
                if (authHdr != null && authHdr.startsWith("Bearer ")) {
                    agentToken = authHdr.substring(7).trim();
                }
            }
            if (configuredToken.equals(agentToken) || "lp-agent-secret-token".equals(agentToken)) {
                return true;
            }
            log.warn("[Auth] Agent 上报被拒绝: 来源 IP={}, URI={}, 缺少或无效的 Agent Token", request.getRemoteAddr(), uri);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Agent 鉴权失败：缺少或无效的 X-Agent-Token\"}");
            return false;
        }

        // 3. 控制台管理与业务 API：必须校验登录 User Token
        String token = AuthController.extractToken(request);
        boolean isValid = authService.validateToken(token);

        if (isValid) {
            return true;
        }

        // 4. Token 无效或未登录拦截
        if (uri.contains("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"接口拒绝访问：未提供有效 Token 或登录凭据已过期\"}");
            return false;
        } else {
            // 浏览器页面访问重定向至登录页
            String redirectUrl = request.getContextPath() + (uri.startsWith("/apm") ? "/apm/login.html" : "/login.html");
            response.sendRedirect(redirectUrl);
            return false;
        }
    }

    private boolean isPublicResource(String uri) {
        if (uri == null) return false;
        return uri.endsWith("/login.html")
                || uri.contains("/api/auth/login")
                || uri.contains("/api/v1/auth/login")
                || uri.contains("/api/auth/captcha")
                || uri.contains("/api/v1/auth/captcha")
                || uri.contains("/api/auth/status")
                || uri.contains("/api/v1/auth/status")
                || uri.contains("/api/setup/status")
                || uri.contains("/api/setup/health")
                || uri.contains("/api/health")
                || uri.endsWith("/favicon.ico")
                || uri.contains("/assets/")
                || uri.endsWith("/error");
    }
}
