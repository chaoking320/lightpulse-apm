package com.monitor.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("apm_probe_config")
public class ApmProbeConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String probeName;
    private String appName;
    private String targetUrl;
    private String httpMethod;
    private String requestHeaders;
    private String requestBody;
    private String authType;
    private String tokenConfig;
    private Integer assertStatusCode;
    private String assertBodyContains;
    private String assertBodyRegex;
    private Long groupId;
    private String dispatchMode; // CLUSTER_SINGLE (默认集群单节点防重) / ALL_NODES (全节点广播)
    private Integer intervalSeconds;
    private Integer timeoutMs;
    private Integer enabled;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 动态登录与认证展开辅助字段 (非数据库持久化列，用于前后端直通交互与解析)
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String loginUrl;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String loginMethod;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String loginBody;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String tokenPath;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String headerName;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String tokenPrefix;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String staticHeaderName;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String staticHeaderValue;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String groupName;
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String tokenKey;
}
