package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmAlertRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmAlertRecordRepo extends BaseMapper<ApmAlertRecord> {
}
