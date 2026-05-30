package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    SysUser selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE email = #{email} AND deleted = 0")
    SysUser selectByEmail(@Param("email") String email);

    @Update("UPDATE sys_user SET deleted = 1, updated_at = NOW() WHERE id = #{id}")
    void deleteByIdPhysical(@Param("id") Long id);

    @Select("SELECT DISTINCT p.permission_code FROM sys_permission p " +
            "INNER JOIN role_permission rp ON p.id = rp.permission_id " +
            "INNER JOIN user_role ur ON rp.role_id = ur.role_id " +
            "WHERE ur.user_id = #{userId} " +
            "AND (ur.expires_at IS NULL OR ur.expires_at > NOW())")
    List<String> selectPermissionsByUserId(@Param("userId") Long userId);

    @Select("SELECT r.role_code FROM sys_role r " +
            "INNER JOIN user_role ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} " +
            "AND r.status = 1 " +
            "AND (ur.expires_at IS NULL OR ur.expires_at > NOW())")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
