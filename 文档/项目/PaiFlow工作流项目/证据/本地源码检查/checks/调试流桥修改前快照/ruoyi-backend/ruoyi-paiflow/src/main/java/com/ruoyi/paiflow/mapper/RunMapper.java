package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.RunRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RunMapper {
    RunRecord find(@Param("id") String id);
    RunRecord lock(@Param("id") String id);
    RunRecord byRequest(@Param("ownerUserId") long ownerUserId, @Param("idempotencyKey") String key);
    int insert(RunRecord run);
    int update(RunRecord run);
    List<RunRecord> queued(@Param("engineProfile") String engine, @Param("limit") int limit);
    List<RunRecord> expired(@Param("engineProfile") String engine, @Param("now") java.time.LocalDateTime now, @Param("limit") int limit);
    String event(@Param("runId") String id, @Param("seq") long seq);
    int insertEvent(@Param("runId") String id, @Param("seq") long seq, @Param("eventJson") String eventJson);
    List<String> events(@Param("runId") String id, @Param("after") long after, @Param("limit") int limit);
}
