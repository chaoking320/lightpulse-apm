package com.monitor.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LightPulse APM 核心自动装配入口
 * 宿主项目引入 starter 后，自动扫描 APM 控制器、服务、Mapper 与定时调度任务
 */
@Configuration
@AutoConfigureAfter(name = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@ComponentScan(basePackages = "com.monitor.server")
@MapperScan(basePackages = "com.monitor.server.repository")
@EnableScheduling
public class MonitorAutoConfiguration {
}
