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

        // 2. Agent 专用通道：校验 X-Agent-Token
        if (uri.startsWith("/api/v1/agent/")) {
            String configuredToken = authService.getAuthConfig().getAgentSecretToken();
            if (configuredToken == null || configuredToken.isBlank()) {
                return true; // 若未配置秘钥则兼容放行
            }
            String agentToken = request.getHeader("X-Agent-Token");
            if (agentToken == null || agentToken.isBlank()) {
                String authHdr = request.getHeader("Authorization");
                if (authHdr != null && authHdr.startsWith("Bearer ")) {
                    agentToken = authHdr.substring(7).trim();
                }
            }
            if (configuredToken.equals(agentToken)) {
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
        if (uri.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"接口拒绝访问：未提供有效 Token 或登录凭据已过期\"}");
            return false;
        } else {
            // 浏览器页面访问重定向至登录页
            String redirectUrl = request.getContextPath() + "/login.html";
            response.sendRedirect(redirectUrl);
            return false;
        }
    }

    private boolean isPublicResource(String uri) {
        if (uri == null) return false;
        return uri.equals("/login.html")
                || uri.startsWith("/api/auth/login")
                || uri.startsWith("/api/auth/captcha")
                || uri.startsWith("/api/auth/status")
                || uri.equals("/api/setup/status")
                || uri.equals("/api/setup/health")
                || uri.equals("/api/health")
                || uri.equals("/favicon.ico")
                || uri.startsWith("/assets/")
                || uri.equals("/error");
    }
}
