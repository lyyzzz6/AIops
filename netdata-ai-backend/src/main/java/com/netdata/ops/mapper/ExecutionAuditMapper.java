package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.ExecutionAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExecutionAuditMapper extends BaseMapper<ExecutionAudit> {
}
