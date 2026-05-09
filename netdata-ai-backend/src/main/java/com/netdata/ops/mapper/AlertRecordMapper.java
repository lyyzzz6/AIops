package com.netdata.ops.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.netdata.ops.entity.AlertRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {

    @Select("SELECT severity, COUNT(*) as count FROM alert_record " +
            "WHERE status = 'firing' GROUP BY severity")
    List<Map<String, Object>> selectFiringStatsBySeverity();

    @Select("SELECT COUNT(*) FROM alert_record WHERE status = 'firing'")
    long countFiring();

    @Select("SELECT COUNT(*) FROM alert_record WHERE status = 'resolved' " +
            "AND resolved_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)")
    long countResolvedToday();
}
