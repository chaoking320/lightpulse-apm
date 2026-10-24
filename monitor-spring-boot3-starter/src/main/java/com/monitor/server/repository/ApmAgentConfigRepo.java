package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmAgentConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmAgentConfigRepo extends BaseMapper<ApmAgentConfig> {
}
