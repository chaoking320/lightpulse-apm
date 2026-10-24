package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmMonitorRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmMonitorRuleRepo extends BaseMapper<ApmMonitorRule> {

    /** 查询启用的规则（全局或指定应用） */
    @Select("SELECT * FROM apm_monitor_rule WHERE enabled = 1 AND (app_name = '*' OR app_name = #{appName})")
    List<ApmMonitorRule> findEnabled(@Param("appName") String appName);
}
