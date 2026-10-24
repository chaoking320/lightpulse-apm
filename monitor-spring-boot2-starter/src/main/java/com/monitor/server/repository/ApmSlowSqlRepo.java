package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmSlowSql;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmSlowSqlRepo extends BaseMapper<ApmSlowSql> {
}
