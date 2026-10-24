package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_capture_config")
public class ApmCaptureConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 应用名(*=全局) */
    private String appName;

    /** 策略名称 */
    private String configName;

    /** 匹配类型: annotation / class_name / package_name */
    private String matchType;

    /** 类匹配值（注解全名 / 类名模式 / 包名） */
    private String classMatch;

    /** 方法匹配值（注解全名 / 方法名模式, %=全部） */
    private String methodMatch;

    /** 是否启用 */
    private Boolean enabled;

    /** 策略描述 */
    private String description;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
