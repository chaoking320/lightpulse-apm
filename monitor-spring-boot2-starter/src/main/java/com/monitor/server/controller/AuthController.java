package com.monitor.server.controller;

import com.monitor.server.service.AuthService;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 用户认证与权限管理控制器 (Spring Boot 2 / javax.servlet)
 */
@Slf4j
@RestController
@RequestMapping({"/api/auth", "/api/v1/auth", "/apm/api/v1/auth", "/apm/api/auth"})
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final com.monitor.server.service.CaptchaService captchaService;

    public static final String COOKIE_NAME = "LP_TOKEN";

    /**
     * 获取图形验证码
     */
    @GetMapping("/captcha")
    public Map<String, Object> getCaptcha() {
        return Map.of("code", 200, "data", captchaService.generateCaptcha());
    }

    /**
     * 用户登录 (内置验证码校验与连续失败防爆破锁定)
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body,
                                     HttpServletRequest request,
                                     HttpServletResponse response) {
        String clientIp = resolveClientIp(request);

        // 1. 检查 IP 是否被安全锁定
        long remainingLock = captchaService.getIpLockoutRemainingSeconds(clientIp);
        if (remainingLock > 0) {
            long mins = Math.max(1, (remainingLock + 59) / 60);
            return Map.of("code", 429, "message", "安全保护：连续登录失败过多，该 IP 已被临时锁定，请 " + mins + " 分钟后再试！");
        }

        // 2. 校验验证码
        String captchaKey = body.get("captchaKey");
        String captchaCode = body.get("captchaCode");
        if (!captchaService.validateCaptcha(captchaKey, captchaCode)) {
            return Map.of("code", 400, "message", "图形验证码错误或已失效，请重新输入");
        }

        String username = body.get("username");
        String password = body.get("password");

        // 3. 校验账号密码
        String token = authService.login(username, password);
        if (token == null) {
            int remaining = captchaService.recordLoginFailure(clientIp);
            String warnMsg = remaining > 0
                    ? "账号或密码错误（还可尝试 " + remaining + " 次，连续输错 5 次将锁定 15 分钟）"
                    : "连续输错密码已达 5 次，为了系统安全，该 IP 已被锁定 15 分钟！";
            return Map.of("code", 401, "message", warnMsg);
        }

        // 登录成功，重置失败计数
        captchaService.recordLoginSuccess(clientIp);

        // 设置 Cookie
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setPath("/");
        cookie.setMaxAge(7 * 24 * 3600);
        cookie.setHttpOnly(false); // 允许前端 JS 读取
        response.addCookie(cookie);

        return Map.of(
                "code", 200,
                "message", "登录成功",
                "data", Map.of(
                        "token", token,
                        "username", username != null ? username : "admin"
                )
        );
    }

    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            int idx = ip.indexOf(',');
            return idx != -1 ? ip.substring(0, idx).trim() : ip.trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        String token = extractToken(request);
        if (token != null) {
            authService.logout(token);
        }

        // 清理 Cookie
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        return Map.of("code", 200, "message", "已成功退出登录");
    }

    /**
     * 查询当前登录状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus(HttpServletRequest request) {
        String token = extractToken(request);
        boolean valid = authService.validateToken(token);
        if (valid) {
            return Map.of(
                    "code", 200,
                    "data", Map.of(
                            "authenticated", true,
                            "username", authService.getAuthConfig().getAdminUsername()
                    )
            );
        } else {
            return Map.of(
                    "code", 401,
                    "data", Map.of("authenticated", false),
                    "message", "未登录或登录已过期"
            );
        }
    }

    /**
     * 修改管理员密码
     */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        return Map.of("code", 400, "message", "生产多节点集群环境下已禁用在线修改密码，请在配置文件中通过 monitor.auth.password 统一指定！");
    }

    public static String extractToken(HttpServletRequest request) {
        // 1. 尝试从 Header 获取
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String xToken = request.getHeader("X-Token");
        if (xToken != null && !xToken.trim().isEmpty()) {
            return xToken.trim();
        }

        // 2. 尝试从 Cookie 获取
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (COOKIE_NAME.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }

        // 3. 尝试从 URL 参数获取
        String param = request.getParameter("token");
        if (param != null && !param.trim().isEmpty()) {
            return param.trim();
        }

        return null;
    }
}
