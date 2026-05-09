package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
