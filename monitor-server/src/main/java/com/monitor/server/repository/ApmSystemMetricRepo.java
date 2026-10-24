package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmSystemMetric;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmSystemMetricRepo extends BaseMapper<ApmSystemMetric> {

    /** 获取各个主机的最新一条监控指标（按应用名 + 主机名 + IP 维度唯一区分节点，防止多台默认主机名相互覆盖） */
    @Select("""
        SELECT m.* FROM apm_system_metric m
        INNER JOIN (
            SELECT app_name, host_name, ip_address, MAX(id) AS max_id
            FROM apm_system_metric
            GROUP BY app_name, host_name, ip_address
        ) latest ON m.id = latest.max_id
        ORDER BY m.created_at DESC
    """)
    List<ApmSystemMetric> selectLatestForEachHost();

    /** 获取指定主机最近 N 分钟内的历史指标数据 */
    @Select("""
        SELECT * FROM apm_system_metric
        WHERE (host_name = #{hostName} OR app_name = #{hostName} OR ip_address = #{hostName})
          AND created_at >= DATE_SUB(NOW(), INTERVAL #{minutes} MINUTE)
        ORDER BY created_at ASC
    """)
    List<ApmSystemMetric> selectRecentMetrics(@Param("hostName") String hostName, @Param("minutes") int minutes);
}
