package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmLogRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmLogRecordRepo extends BaseMapper<ApmLogRecord> {
}
