package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmEndpointMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ApmEndpointMetricRepo extends BaseMapper<ApmEndpointMetric> {
    @Select("SELECT app_name, SUM(total_calls) total, SUM(error_calls) errors, " +
            "COALESCE(SUM(total_cost_ms)/NULLIF(SUM(total_calls),0),0) avg_cost " +
            "FROM apm_endpoint_metric WHERE bucket_minute >= #{since} GROUP BY app_name")
    List<Map<String,Object>> summaryByApp(@Param("since") LocalDateTime since);

    @Select("SELECT class_name,method_name,http_method,http_path," +
            "SUM(total_calls) calls,SUM(error_calls) errors," +
            "COALESCE(SUM(total_cost_ms)/NULLIF(SUM(total_calls),0),0) avg_cost," +
            "MAX(max_cost_ms) max_cost " +
            "FROM apm_endpoint_metric WHERE app_name=#{app} AND bucket_minute>=#{since} " +
            "GROUP BY class_name,method_name,http_method,http_path " +
            "ORDER BY calls DESC")
    List<Map<String,Object>> endpointList(@Param("app") String app, @Param("since") LocalDateTime since);

    @Select("SELECT DATE_FORMAT(bucket_minute,'%H:%i') t," +
            "SUM(total_calls) calls,COALESCE(SUM(total_cost_ms)/NULLIF(SUM(total_calls),0),0) avg_cost " +
            "FROM apm_endpoint_metric WHERE app_name=#{app} AND class_name=#{cls} AND method_name=#{mtd} " +
            "AND bucket_minute>=#{since} GROUP BY bucket_minute ORDER BY bucket_minute")
    List<Map<String,Object>> endpointTrend(@Param("app") String app, @Param("cls") String cls,
                                            @Param("mtd") String method, @Param("since") LocalDateTime since);

    @Select("SELECT bucket_minute,SUM(total_calls) calls,SUM(error_calls) errors " +
            "FROM apm_endpoint_metric WHERE app_name=#{app} AND bucket_minute>=#{since} " +
            "GROUP BY bucket_minute ORDER BY bucket_minute")
    List<Map<String,Object>> appTrend(@Param("app") String app, @Param("since") LocalDateTime since);

    @Select("SELECT DISTINCT app_name FROM apm_endpoint_metric")
    List<String> listAppNames();
}
