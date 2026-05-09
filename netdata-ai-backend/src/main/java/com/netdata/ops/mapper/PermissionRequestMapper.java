package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.PermissionRequest;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionRequestMapper extends BaseMapper<PermissionRequest> {
}
