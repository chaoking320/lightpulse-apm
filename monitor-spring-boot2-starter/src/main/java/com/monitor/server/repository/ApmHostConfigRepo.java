package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmHostConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmHostConfigRepo extends BaseMapper<ApmHostConfig> {
}
