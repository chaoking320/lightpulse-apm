package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmProbeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ApmProbeRecordRepo extends BaseMapper<ApmProbeRecord> {

    /** 查询最新的探针巡检历史记录（支持分页或限制条数） */
    @Select("""
        SELECT * FROM apm_probe_record
        ORDER BY created_at DESC
        LIMIT #{limit}
    """)
    List<ApmProbeRecord> selectRecentRecords(@Param("limit") int limit);
}
