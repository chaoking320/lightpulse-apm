package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApmApplicationRepo extends BaseMapper<ApmApplication> {

    /** 更新心跳时间，将应用标记为在线 */
    @Update("UPDATE apm_application SET last_heartbeat=CURRENT_TIMESTAMP, status='online' WHERE instance_id=#{instanceId}")
    int heartbeat(@Param("instanceId") String instanceId);

    /** 查询最后心跳早于指定时间且当前状态为 online 的失联应用 */
    @org.apache.ibatis.annotations.Select("SELECT * FROM apm_application WHERE last_heartbeat < #{threshold} AND status='online'")
    java.util.List<ApmApplication> findStaleOnlineApps(@Param("threshold") java.time.LocalDateTime threshold);

    /** 将最后心跳早于指定时间的应用标记为离线 */
    @Update("UPDATE apm_application SET status='offline' WHERE last_heartbeat < #{threshold} AND status='online'")
    int markOffline(@Param("threshold") java.time.LocalDateTime threshold);
}
