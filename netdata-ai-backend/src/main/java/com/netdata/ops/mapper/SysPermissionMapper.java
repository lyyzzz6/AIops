package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    @Select("SELECT p.* FROM sys_permission p " +
            "INNER JOIN role_permission rp ON p.id = rp.permission_id " +
            "WHERE rp.role_id = #{roleId}")
    List<SysPermission> selectPermissionsByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT DISTINCT p.* FROM sys_permission p " +
            "INNER JOIN role_permission rp ON p.id = rp.permission_id " +
            "INNER JOIN user_role ur ON rp.role_id = ur.role_id " +
            "WHERE ur.user_id = #{userId} " +
            "AND (ur.expires_at IS NULL OR ur.expires_at > NOW())")
    List<SysPermission> selectPermissionsByUserId(@Param("userId") Long userId);
}
