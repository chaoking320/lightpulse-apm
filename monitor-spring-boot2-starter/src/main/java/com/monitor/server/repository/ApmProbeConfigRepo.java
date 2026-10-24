package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmProbeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmProbeConfigRepo extends BaseMapper<ApmProbeConfig> {

    /** 查询指定应用或全局可用的已启用探针列表 (精准匹配应用名，支持 KMS 别名智能映射) */
    @Select("""
        SELECT * FROM apm_probe_config
        WHERE enabled = 1 AND (
            LOWER(TRIM(app_name)) = LOWER(TRIM(#{appName}))
                        OR app_name = '*'
            OR app_name IS NULL
            OR app_name = ''
        )
        ORDER BY id ASC
    """)
    List<ApmProbeConfig> selectActiveProbesForApp(@Param("appName") String appName);
}
