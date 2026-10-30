package com.monitor.server.controller;

import com.monitor.server.entity.ApmUser;
import com.monitor.server.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 企业多用户与权限中心 (RBAC) 控制器 API
 */
@Slf4j
@RestController
@RequestMapping({"/api/v1/users", "/apm/api/v1/users"})
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;

    /**
     * 查询所有用户列表
     */
    @GetMapping
    public Map<String, Object> listUsers() {
        List<ApmUser> list = authService.listUsers();
        return Map.of("code", 200, "data", list);
    }

    /**
     * 新建用户
     */
    @PostMapping
    public Map<String, Object> createUser(@RequestBody Map<String, String> body) {
        try {
            ApmUser user = new ApmUser();
            user.setUsername(body.get("username"));
            user.setRole(body.get("role"));
            user.setRemark(body.get("remark"));
            String rawPassword = body.get("password");
            ApmUser created = authService.createUser(user, rawPassword);
            return Map.of("code", 200, "data", created, "message", "创建用户成功");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        } catch (Exception e) {
            log.error("创建用户异常", e);
            return Map.of("code", 500, "message", "创建失败: " + e.getMessage());
        }
    }

    /**
     * 更新用户信息 (角色、状态、备注、密码)
     */
    @PutMapping("/{id}")
    public Map<String, Object> updateUser(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        try {
            String role = body.get("role") != null ? String.valueOf(body.get("role")) : null;
            Integer status = body.get("status") instanceof Number ? ((Number) body.get("status")).intValue() : null;
            String remark = body.get("remark") != null ? String.valueOf(body.get("remark")) : null;
            String newPassword = body.get("password") != null ? String.valueOf(body.get("password")) : null;

            authService.updateUser(id, role, status, remark, newPassword);
            return Map.of("code", 200, "message", "更新用户成功");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        } catch (Exception e) {
            log.error("更新用户异常", e);
            return Map.of("code", 500, "message", "更新失败: " + e.getMessage());
        }
    }

    /**
     * 快速切换用户启用/停用状态
     */
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggleUser(@PathVariable("id") Long id) {
        try {
            authService.toggleUser(id);
            return Map.of("code", 200, "message", "状态切换成功");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("code", 500, "message", e.getMessage());
        }
    }

    /**
     * 重置用户密码
     */
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        try {
            String newPassword = body.get("newPassword");
            if (newPassword == null || newPassword.trim().length() < 5) {
                return Map.of("code", 400, "message", "新密码长度不能少于 5 位");
            }
            authService.updateUser(id, null, null, null, newPassword.trim());
            return Map.of("code", 200, "message", "重置密码成功");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        } catch (Exception e) {
            log.error("重置用户密码异常", e);
            return Map.of("code", 500, "message", "重置失败: " + e.getMessage());
        }
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable("id") Long id) {
        try {
            authService.deleteUser(id);
            return Map.of("code", 200, "message", "用户已删除");
        } catch (IllegalArgumentException e) {
            return Map.of("code", 400, "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("code", 500, "message", e.getMessage());
        }
    }
}
