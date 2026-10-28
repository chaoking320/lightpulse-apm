package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmLogConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmLogConfigRepo extends BaseMapper<ApmLogConfig> {

    @Select("SELECT * FROM apm_log_config WHERE enabled = 1 AND (app_name = #{appName} OR app_name = '*')")
    List<ApmLogConfig> selectActiveConfigsForApp(String appName);
}
