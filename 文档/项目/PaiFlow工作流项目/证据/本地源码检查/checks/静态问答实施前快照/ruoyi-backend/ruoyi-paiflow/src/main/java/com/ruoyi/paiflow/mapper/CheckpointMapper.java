package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.CheckpointRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CheckpointMapper {
    CheckpointRecord find(@Param("id") String id);
    CheckpointRecord lock(@Param("id") String id);
    CheckpointRecord byRequest(@Param("runId") String runId,@Param("attempt") int attempt,@Param("key") String key);
    CheckpointRecord byPosition(@Param("runId") String runId,@Param("attempt") int attempt,@Param("nodeId") String nodeId,@Param("iterationId") String iterationId,@Param("ordinal") int ordinal);
    int insert(CheckpointRecord record);
    int update(CheckpointRecord record);
    List<CheckpointRecord> ended(@Param("now") LocalDateTime now,@Param("limit") int limit);
}
