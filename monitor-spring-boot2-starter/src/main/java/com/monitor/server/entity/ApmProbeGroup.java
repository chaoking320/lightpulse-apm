package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接口巡检校验组 (统一配置与共享 Token / 鉴权中心)
 */
@Data
@TableName("apm_probe_group")
public class ApmProbeGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 校验组名称 (如: KMS外部接口校验组) */
    private String groupName;

    /** 所属应用 (如: jk-kms-backend) */
    private String appName;

    /** 认证类型: NONE / STATIC / DYNAMIC_LOGIN */
    private String authType;

    /** 动态登录 URL */
    private String loginUrl;

    /** 动态登录请求方法: POST / GET */
    private String loginMethod;

    /** 动态登录 Headers (JSON) */
    private String loginHeaders;

    /** 动态登录 Body (JSON 如: {"workNo": "WB-4830"}) */
    private String loginBody;

    /** Token 提取路径 (如: result.accessToken 或 data.token) */
    private String tokenPath;

    /** 注入业务接口 Header 键名 (如: accessToken 或 Authorization) */
    private String headerName;

    /** Token 前缀 (如 "" 或 "Bearer ") */
    private String tokenPrefix;

    /** 静态 Header 键名 */
    private String staticHeaderName;

    /** 静态 Header 键值 */
    private String staticHeaderValue;

    /** Token 缓存有效期 (秒), 默认 14400 (4小时) */
    private Integer expiresInSeconds;

    /** 提前刷新缓冲时间 (秒), 默认 300 (5分钟) */
    private Integer refreshBufferSeconds;

    /** 组默认接口调用周期 (秒), 默认 3600 (1小时) */
    private Integer defaultIntervalSeconds;

    /** 组默认超时时间 (毫秒), 默认 5000 */
    private Integer defaultTimeoutMs;

    /** 是否启用: 1 启用, 0 禁用 */
    private Integer enabled;

    /** 调度策略: CLUSTER_SINGLE (默认集群单节点防重) / ALL_NODES (全节点广播) */
    private String dispatchMode;

    /** 校验组描述说明 */
    private String description;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 辅助统计字段: 组内关联探针总数 */
    @TableField(exist = false)
    private Integer probeCount;
}
