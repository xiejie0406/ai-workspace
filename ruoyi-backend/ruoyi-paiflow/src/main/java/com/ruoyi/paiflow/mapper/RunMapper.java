package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.RunRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface RunMapper {
    /** 水位内的实际数据库序号与原事件字节；不从JSON内容推导行序。 */
    record EventRow(long seq, String eventJson) {}
    RunRecord find(@Param("id") String id);
    RunRecord lock(@Param("id") String id);
    RunRecord byRequest(@Param("ownerUserId") long ownerUserId, @Param("idempotencyKey") String key);
    List<RunRecord> page(@Param("spaceId") long spaceId,@Param("workflowId") Long workflowId,@Param("state") String state,@Param("offset") long offset,@Param("limit") int limit);
    long count(@Param("spaceId") long spaceId,@Param("workflowId") Long workflowId,@Param("state") String state);
    List<java.util.Map<String,Object>> statistics(@Param("spaceId") long spaceId,@Param("workflowId") Long workflowId);
    int insert(RunRecord run);
    int update(RunRecord run);
    List<RunRecord> queued(@Param("engineProfile") String engine, @Param("limit") int limit);
    List<RunRecord> expired(@Param("engineProfile") String engine, @Param("now") java.time.LocalDateTime now, @Param("limit") int limit);
    String event(@Param("runId") String id, @Param("seq") long seq);
    int insertEvent(@Param("runId") String id, @Param("seq") long seq, @Param("eventJson") String eventJson);
    List<String> events(@Param("runId") String id, @Param("after") long after, @Param("limit") int limit);
    List<EventRow> eventsThrough(@Param("runId") String id, @Param("after") long after, @Param("through") long through, @Param("limit") int limit);
}
