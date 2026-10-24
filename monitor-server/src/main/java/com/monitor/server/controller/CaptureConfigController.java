package com.monitor.server.controller;

import com.monitor.server.entity.ApmCaptureConfig;
import com.monitor.server.repository.ApmCaptureConfigRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 采集策略管理 API —— 用户自定义 Agent 拦截目标和方式
 */
@RestController
@RequestMapping("/api/v1/capture-configs")
@RequiredArgsConstructor
public class CaptureConfigController {

    private final ApmCaptureConfigRepo repo;

    @GetMapping
    public List<ApmCaptureConfig> list() {
        return repo.selectList(null);
    }

    @GetMapping("/{id}")
    public ApmCaptureConfig get(@PathVariable Long id) {
        return repo.selectById(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody ApmCaptureConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        if (config.getEnabled() == null) config.setEnabled(true);
        if (config.getMethodMatch() == null || config.getMethodMatch().isEmpty()) {
            config.setMethodMatch("%");
        }
        repo.insert(config);
        return Map.of("code", 200, "id", config.getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ApmCaptureConfig config) {
        config.setId(id);
        config.setUpdatedAt(LocalDateTime.now());
        repo.updateById(config);
        return Map.of("code", 200);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        repo.deleteById(id);
        return Map.of("code", 200);
    }

    @PutMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        ApmCaptureConfig config = repo.selectById(id);
        if (config != null) {
            config.setEnabled(!Boolean.TRUE.equals(config.getEnabled()));
            config.setUpdatedAt(LocalDateTime.now());
            repo.updateById(config);
            return Map.of("code", 200, "enabled", config.getEnabled());
        }
        return Map.of("code", 404, "message", "策略不存在");
    }
}
