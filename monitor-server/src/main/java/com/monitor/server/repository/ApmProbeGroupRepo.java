package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmProbeGroup;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmProbeGroupRepo extends BaseMapper<ApmProbeGroup> {
}
