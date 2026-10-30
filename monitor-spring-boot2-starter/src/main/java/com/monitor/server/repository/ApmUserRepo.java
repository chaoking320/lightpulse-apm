package com.monitor.server.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.monitor.server.entity.ApmUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApmUserRepo extends BaseMapper<ApmUser> {
}
