package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmCaptureConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmCaptureConfigRepo extends BaseMapper<ApmCaptureConfig> {

    /** 查询指定应用的启用采集策略 */
    @Select("SELECT * FROM apm_capture_config WHERE enabled = 1 AND (app_name = '*' OR app_name = #{appName})")
    List<ApmCaptureConfig> findEnabled(@Param("appName") String appName);
}
