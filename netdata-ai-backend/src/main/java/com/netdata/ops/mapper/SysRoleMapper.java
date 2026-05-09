package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    @Select("SELECT r.* FROM sys_role r " +
            "INNER JOIN user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.status = 1 " +
            "AND (ur.expires_at IS NULL OR ur.expires_at > NOW())")
    List<SysRole> selectRolesByUserId(@Param("userId") Long userId);
}
