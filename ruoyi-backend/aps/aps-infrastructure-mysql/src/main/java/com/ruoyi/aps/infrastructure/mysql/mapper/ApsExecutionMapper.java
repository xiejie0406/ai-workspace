package com.ruoyi.aps.infrastructure.mysql.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ActualOccupancy;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ApsExecutionMapper
{
    String COMPLETE_PLAN_SCOPE=" AND (#{allScope}=1 OR (JSON_TYPE(JSON_EXTRACT(v.input_snapshot_json,'$.scope.workshopIds'))='ARRAY' " +
            "AND JSON_LENGTH(JSON_EXTRACT(v.input_snapshot_json,'$.scope.workshopIds'))>0 " +
            "AND NOT EXISTS (SELECT 1 FROM JSON_TABLE(v.input_snapshot_json,'$.scope.workshopIds[*]' " +
            "COLUMNS(workshop_id VARCHAR(36) PATH '$')) sw LEFT JOIN aps_workshop sw_w ON sw_w.id=sw.workshop_id " +
            "WHERE sw_w.manager_user_id IS NULL OR sw_w.manager_user_id<>#{userId})))";
    @Select("SELECT m.id,m.member_no,m.task_id,t.task_name,m.planned_qty,m.uom_code FROM aps_plan_job_member m " +
            "JOIN aps_task t ON t.id=m.task_id WHERE m.plan_job_id=#{id} ORDER BY m.member_no,m.id")
    List<Map<String,Object>> listReportMembers(@Param("id") String id);
    @Select("SELECT r.*,j.job_code," +
            "(SELECT GROUP_CONCAT(DISTINCT po.order_no ORDER BY po.order_no SEPARATOR '、') FROM aps_plan_job_member m " +
            "JOIN aps_task t ON t.id=m.task_id JOIN aps_production_lot b ON b.id=t.production_lot_id " +
            "JOIN aps_order_line l ON l.id=b.order_line_id JOIN aps_order po ON po.id=l.order_id WHERE m.plan_job_id=j.id) order_numbers," +
            "(SELECT GROUP_CONCAT(DISTINCT t.task_name ORDER BY t.task_name SEPARATOR '、') FROM aps_plan_job_member m " +
            "JOIN aps_task t ON t.id=m.task_id WHERE m.plan_job_id=j.id) task_names " +
            "FROM aps_execution_run r JOIN aps_plan_job j ON j.id=r.plan_job_id " +
            "JOIN aps_plan_version v ON v.id=r.plan_version_id " +
            "WHERE (#{status}='' OR r.status=#{status}) AND (#{resourceId}='' OR EXISTS " +
            "(SELECT 1 FROM aps_actual_occupancy o WHERE o.execution_run_id=r.id AND o.resource_id=#{resourceId})) " +
            COMPLETE_PLAN_SCOPE + " " +
            "AND (#{keyword}='' OR LOCATE(#{keyword},j.job_code)>0 OR EXISTS (SELECT 1 FROM aps_plan_job_member m " +
            "JOIN aps_task t ON t.id=m.task_id JOIN aps_production_lot b ON b.id=t.production_lot_id " +
            "JOIN aps_order_line l ON l.id=b.order_line_id JOIN aps_order po ON po.id=l.order_id " +
            "WHERE m.plan_job_id=j.id AND (LOCATE(#{keyword},po.order_no)>0 OR LOCATE(#{keyword},t.task_name)>0))) " +
            "ORDER BY r.created_at DESC,r.id DESC LIMIT 200")
    List<Map<String,Object>> listRuns(@Param("userId") String userId,@Param("allScope") boolean allScope,
            @Param("keyword") String keyword,@Param("status") String status,@Param("resourceId") String resourceId);
    @Select("SELECT j.plan_version_id,j.id AS plan_job_id,j.planned_qty,j.uom_code," +
            "(v.status='PUBLISHED' AND v.is_current=1) AS current_published,o.quality_gate_required " +
            "FROM aps_plan_job j JOIN aps_plan_version v ON v.id=j.plan_version_id " +
            "JOIN aps_operation_spec o ON o.id=j.operation_spec_id " +
            "LEFT JOIN aps_work_center c ON c.id=j.work_center_id " +
            "LEFT JOIN aps_workshop w ON w.id=c.workshop_id " +
            "WHERE j.plan_version_id=#{planVersionId} AND j.id=#{planJobId} " +
            "AND v.status='PUBLISHED' AND v.is_current=1 " +
            "AND (#{allScope}=1 OR w.manager_user_id=#{userId})" + COMPLETE_PLAN_SCOPE + " FOR UPDATE")
    Map<String, Object> findPublishedJobForDispatch(@Param("userId") String userId,
            @Param("allScope") boolean allScope, @Param("planVersionId") String planVersionId,
            @Param("planJobId") String planJobId);

    @Select("SELECT * FROM aps_execution_run WHERE id=#{id}")
    Map<String, Object> findRun(@Param("id") String id);

    @Select("SELECT * FROM aps_execution_run WHERE id=#{id} FOR UPDATE")
    Map<String, Object> findRunForUpdate(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM aps_execution_run r JOIN aps_plan_job j ON j.id=r.plan_job_id " +
            "JOIN aps_plan_version v ON v.id=r.plan_version_id " +
            "LEFT JOIN aps_work_center c ON c.id=j.work_center_id LEFT JOIN aps_workshop w ON w.id=c.workshop_id " +
            "WHERE r.id=#{id} AND (#{allScope}=1 OR w.manager_user_id=#{userId})" + COMPLETE_PLAN_SCOPE)
    int isRunInScope(@Param("userId") String userId, @Param("allScope") boolean allScope,
            @Param("id") String id);

    @Select("SELECT COUNT(*) FROM aps_execution_run r JOIN aps_plan_job_member m ON m.plan_job_id=r.plan_job_id " +
            "WHERE r.id=#{executionRunId} AND m.task_id=#{taskId}")
    int isRunForTask(@Param("executionRunId") String executionRunId, @Param("taskId") String taskId);

    @Select("SELECT * FROM aps_execution_run WHERE request_id=#{requestId} FOR UPDATE")
    Map<String, Object> findRunByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM aps_execution_run WHERE plan_job_id=#{planJobId} " +
            "AND status NOT IN ('COMPLETED','CANCELLED') ORDER BY run_no LIMIT 1 FOR UPDATE")
    Map<String, Object> findNonTerminalRunByJob(@Param("planJobId") String planJobId);

    @Select("SELECT GREATEST(j.planned_qty-COALESCE(SUM(CASE WHEN r.status='CANCELLED' " +
            "THEN COALESCE(p.processed_qty,0) ELSE r.assigned_qty END),0),0) " +
            "FROM aps_plan_job j LEFT JOIN aps_execution_run r ON r.plan_job_id=j.id " +
            "LEFT JOIN (SELECT execution_run_id,SUM(processed_qty) processed_qty FROM aps_production_report " +
            "WHERE report_type<>'CORRECTION' GROUP BY execution_run_id) p ON p.execution_run_id=r.id " +
            "WHERE j.id=#{planJobId} GROUP BY j.id,j.planned_qty")
    java.math.BigDecimal remainingAssignableQuantity(@Param("planJobId") String planJobId);

    @Select("SELECT COALESCE(MAX(run_no),0)+1 FROM aps_execution_run WHERE plan_job_id=#{planJobId}")
    int nextRunNo(@Param("planJobId") String planJobId);

    @Select("SELECT COUNT(*) FROM aps_plan_job_member m JOIN aps_task_dependency d " +
            "ON (d.predecessor_task_id=m.task_id OR d.successor_task_id=m.task_id) " +
            "WHERE m.plan_job_id=#{planJobId} AND d.dependency_type='SAME_START'")
    int hasUnsupportedSameStart(@Param("planJobId") String planJobId);

    @Select("SELECT s.id AS plan_segment_id,s.segment_no,a.id AS source_plan_allocation_id,a.resource_id," +
            "CASE WHEN s.phase_type IN ('SETUP','RUN','UNLOAD') THEN s.phase_type ELSE 'WAIT_HOLD' END activity_type," +
            "r.hold_on_pause FROM aps_plan_segment s JOIN aps_plan_allocation a ON a.plan_segment_id=s.id " +
            "JOIN aps_resource_requirement r ON r.id=a.resource_requirement_id " +
            "WHERE s.plan_job_id=#{planJobId} ORDER BY s.segment_no,a.resource_id")
    List<Map<String, Object>> listPlannedResources(@Param("planJobId") String planJobId);

    @Select("SELECT o.plan_segment_id,s.segment_no,o.source_plan_allocation_id,o.resource_id," +
            "CASE WHEN s.phase_type IN ('SETUP','RUN','UNLOAD') THEN s.phase_type ELSE 'WAIT_HOLD' END activity_type," +
            "rr.hold_on_pause FROM aps_actual_occupancy o " +
            "JOIN aps_plan_segment s ON s.id=o.plan_segment_id " +
            "JOIN aps_plan_allocation a ON a.id=o.source_plan_allocation_id " +
            "AND a.plan_segment_id=o.plan_segment_id " +
            "JOIN aps_resource_requirement rr ON rr.id=a.resource_requirement_id " +
            "WHERE o.execution_run_id=#{executionRunId} AND o.activity_type<>'PAUSE_HOLD' " +
            "AND o.plan_segment_id=(SELECT o2.plan_segment_id FROM aps_actual_occupancy o2 " +
            "WHERE o2.execution_run_id=#{executionRunId} AND o2.plan_segment_id IS NOT NULL " +
            "AND o2.activity_type<>'PAUSE_HOLD' " +
            "ORDER BY o2.start_at DESC,o2.created_at DESC,o2.id DESC LIMIT 1) " +
            "AND NOT EXISTS (SELECT 1 FROM aps_actual_occupancy later " +
            "WHERE later.execution_run_id=o.execution_run_id " +
            "AND later.source_plan_allocation_id=o.source_plan_allocation_id " +
            "AND later.activity_type<>'PAUSE_HOLD' " +
            "AND (later.start_at>o.start_at OR (later.start_at=o.start_at AND later.created_at>o.created_at) " +
            "OR (later.start_at=o.start_at AND later.created_at=o.created_at AND later.id>o.id))) " +
            "ORDER BY s.segment_no,o.source_plan_allocation_id")
    List<Map<String, Object>> listResumeResources(@Param("executionRunId") String executionRunId);

    @Select("SELECT task_id FROM aps_plan_job_member WHERE plan_job_id=#{planJobId} ORDER BY member_no")
    List<String> listJobTaskIds(@Param("planJobId") String planJobId);

    @Select("SELECT COUNT(*) FROM aps_resource r WHERE r.id=#{resourceId} AND r.status='ACTIVE' " +
            "AND EXISTS (SELECT 1 FROM aps_resource_availability a WHERE a.resource_id=r.id " +
            "AND a.window_type IN ('AVAILABLE','OVERTIME') AND a.start_at<=#{at} AND a.end_at>#{at} " +
            "AND a.capacity_ratio>0) AND NOT EXISTS (SELECT 1 FROM aps_resource_availability a " +
            "WHERE a.resource_id=r.id AND a.window_type IN ('UNAVAILABLE','LEAVE','MAINTENANCE') " +
            "AND a.start_at<=#{at} AND a.end_at>#{at})")
    int isResourceReady(@Param("resourceId") String resourceId, @Param("at") Instant at);

    @Select("SELECT COUNT(*) FROM aps_actual_occupancy WHERE resource_id=#{resourceId} AND status='ACTIVE' " +
            "AND start_at<=#{at} AND execution_run_id<>#{excludedRunId}")
    int hasResourceConflict(@Param("resourceId") String resourceId, @Param("at") Instant at,
            @Param("excludedRunId") String excludedRunId);

    @Select("SELECT COUNT(*) FROM aps_resource replacement JOIN aps_resource original ON original.id=#{oldResourceId} " +
            "WHERE replacement.id=#{replacementResourceId} AND replacement.status='ACTIVE' " +
            "AND replacement.resource_type=original.resource_type " +
            "AND replacement.workshop_id=original.workshop_id " +
            "AND replacement.work_center_id=original.work_center_id " +
            "AND NOT EXISTS (SELECT 1 FROM aps_actual_occupancy o JOIN aps_plan_segment s ON s.id=o.plan_segment_id " +
            "JOIN aps_plan_allocation a ON a.id=o.source_plan_allocation_id " +
            "AND a.plan_segment_id=o.plan_segment_id " +
            "JOIN aps_resource_requirement rr ON rr.id=a.resource_requirement_id " +
            "WHERE o.execution_run_id=#{executionRunId} AND o.id=#{occupancyId} " +
            "AND rr.required_skill_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM aps_resource_skill sk " +
            "WHERE sk.resource_id=replacement.id AND sk.status='ACTIVE' AND sk.skill_code=rr.required_skill_code " +
            "AND sk.skill_level>=COALESCE(rr.min_skill_level,1) " +
            "AND (sk.valid_from IS NULL OR sk.valid_from<=#{at}) AND (sk.valid_to IS NULL OR sk.valid_to>#{at})))")
    int isReplacementCompatible(@Param("executionRunId") String executionRunId,
            @Param("occupancyId") String occupancyId, @Param("oldResourceId") String oldResourceId,
            @Param("replacementResourceId") String replacementResourceId, @Param("at") Instant at);

    @Select("SELECT * FROM aps_actual_occupancy WHERE execution_run_id=#{executionRunId} " +
            "AND resource_id=#{resourceId} AND status='ACTIVE' ORDER BY start_at DESC LIMIT 1 FOR UPDATE")
    Map<String, Object> findActiveOccupancy(@Param("executionRunId") String executionRunId,
            @Param("resourceId") String resourceId);

    @Select("SELECT * FROM aps_actual_occupancy WHERE id=#{id}")
    Map<String, Object> findOccupancy(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM aps_actual_occupancy WHERE execution_run_id=#{executionRunId} " +
            "AND resource_id=#{resourceId} AND status='COMPLETED' AND end_at=#{endAt}")
    int hasCompletedOccupancyEndingAt(@Param("executionRunId") String executionRunId,
            @Param("resourceId") String resourceId, @Param("endAt") Instant endAt);

    @Select("SELECT * FROM aps_actual_occupancy WHERE execution_run_id=#{executionRunId} ORDER BY start_at,id")
    List<Map<String, Object>> listOccupancies(@Param("executionRunId") String executionRunId);

    @Select("SELECT * FROM aps_actual_occupancy WHERE execution_run_id=#{executionRunId} " +
            "AND status='ACTIVE' ORDER BY start_at,id FOR UPDATE")
    List<Map<String, Object>> listActiveOccupanciesForUpdate(@Param("executionRunId") String executionRunId);

    @Select("SELECT COUNT(*) FROM aps_production_report WHERE execution_run_id=#{executionRunId} " +
            "AND report_type<>'CORRECTION' AND processed_qty>0")
    int hasProducedQuantity(@Param("executionRunId") String executionRunId);

    @Select("SELECT COUNT(*) FROM aps_output_lot l JOIN aps_production_report p ON p.id=l.source_report_id " +
            "WHERE p.execution_run_id=#{executionRunId} AND l.quality_status<>'CLOSED' " +
            "AND l.total_qty>(l.available_qty+l.reserved_qty+l.consumed_qty+l.scrapped_qty+" +
            "COALESCE((SELECT SUM(CASE WHEN e.to_bucket='TRANSFERRED' THEN e.quantity " +
            "WHEN e.from_bucket='TRANSFERRED' THEN -e.quantity ELSE 0 END) FROM aps_quantity_event e " +
            "WHERE e.output_lot_id=l.id),0))")
    int hasUnresolvedOutput(@Param("executionRunId") String executionRunId);

    @Insert("INSERT INTO aps_execution_run(id,plan_version_id,plan_job_id,run_no,status,assigned_qty,uom_code," +
            "request_id,created_by,updated_by) VALUES(#{v.id},#{v.planVersionId},#{v.planJobId},#{v.runNo}," +
            "#{status},#{v.assignedQty},#{v.uomCode},#{v.requestId},#{actor},#{actor})")
    void insertRun(@Param("v") ExecutionRun run, @Param("status") String status, @Param("actor") String actor);

    @Update("UPDATE aps_execution_run SET status=#{targetStatus},actual_start_at=#{actualStartAt}," +
            "actual_end_at=#{actualEndAt},pause_reason=#{reason},updated_by=#{actor},row_version=row_version+1 " +
            "WHERE id=#{id} AND row_version=#{rowVersion} AND status=#{expectedStatus}")
    int transitionRun(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("expectedStatus") String expectedStatus, @Param("targetStatus") String targetStatus,
            @Param("actualStartAt") Instant actualStartAt, @Param("actualEndAt") Instant actualEndAt,
            @Param("reason") String reason, @Param("actor") String actor);

    @Update("UPDATE aps_execution_run SET updated_by=#{actor},row_version=row_version+1 " +
            "WHERE id=#{id} AND row_version=#{rowVersion}")
    int incrementRunRevision(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("actor") String actor);

    @Insert("INSERT INTO aps_actual_occupancy(id,plan_job_id,execution_run_id,plan_segment_id," +
            "source_plan_allocation_id,resource_id," +
            "activity_type,start_at,end_at,status,correction_of_id,reason,created_by,updated_by) " +
            "VALUES(#{v.id},#{v.planJobId},#{v.executionRunId},#{v.planSegmentId}," +
            "#{v.sourcePlanAllocationId},#{v.resourceId},#{activityType}," +
            "#{v.startAt},#{v.endAt},#{status},#{v.correctionOfId},#{v.reason},#{actor},#{actor})")
    void insertOccupancy(@Param("v") ActualOccupancy occupancy, @Param("activityType") String activityType,
            @Param("status") String status, @Param("actor") String actor);

    @Update("UPDATE aps_actual_occupancy SET end_at=#{endAt},status=#{status},updated_by=#{actor}," +
            "row_version=row_version+1 WHERE id=#{id} AND row_version=#{rowVersion} AND status='ACTIVE'")
    int closeOccupancy(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("endAt") Instant endAt, @Param("status") String status, @Param("actor") String actor);

    @Select("SELECT COALESCE(SUM(row_version+1),0) FROM aps_execution_run")
    long currentExecutionRevision();

    @Select({"<script>",
            "SELECT m.task_id,COALESCE(SUM(p.processed_qty),0) AS processed_qty ",
            "FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id ",
            "JOIN aps_production_report p ON p.execution_run_id=r.id AND p.plan_job_member_id=m.id ",
            "WHERE m.task_id IN ",
            "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ",
            "AND NOT EXISTS (SELECT 1 FROM aps_production_report c WHERE c.correction_of_id=p.id) ",
            "GROUP BY m.task_id ORDER BY m.task_id",
            "</script>"})
    List<Map<String, Object>> processedQuantityByTask(@Param("taskIds") List<String> taskIds);

    @Select({"<script>",
            "SELECT o.id,o.plan_job_id,o.execution_run_id,o.plan_segment_id,o.resource_id,o.activity_type,",
            "o.start_at,m.task_id,m.planned_qty AS member_planned_qty,m.uom_code,",
            "j.planned_qty AS job_planned_qty,r.assigned_qty,r.status AS run_status,rs.resource_type,",
            "(SELECT COUNT(*) FROM aps_plan_job_member mc WHERE mc.plan_job_id=j.id) AS member_count,",
            "j.operation_spec_id,s.operation_phase_id,s.phase_type,",
            "a.resource_requirement_id,a.seat_no,a.capacity_used,",
            "TIMESTAMPDIFF(SECOND,s.start_at,s.end_at) AS segment_seconds,",
            "COALESCE((SELECT SUM(p.processed_qty) FROM aps_production_report p ",
            "WHERE p.execution_run_id=r.id AND p.plan_job_member_id=m.id ",
            "AND NOT EXISTS (SELECT 1 FROM aps_production_report c WHERE c.correction_of_id=p.id)),0) AS processed_qty,",
            "COALESCE((SELECT SUM(p.processed_qty) FROM aps_production_report p ",
            "WHERE p.execution_run_id=r.id AND NOT EXISTS ",
            "(SELECT 1 FROM aps_production_report c WHERE c.correction_of_id=p.id)),0) AS run_processed_qty ",
            "FROM aps_actual_occupancy o JOIN aps_execution_run r ON r.id=o.execution_run_id ",
            "JOIN aps_plan_job j ON j.id=r.plan_job_id JOIN aps_plan_job_member m ON m.plan_job_id=j.id ",
            "JOIN aps_resource rs ON rs.id=o.resource_id LEFT JOIN aps_plan_segment s ON s.id=o.plan_segment_id ",
            "LEFT JOIN aps_plan_allocation a ON a.id=o.source_plan_allocation_id ",
            "WHERE o.status='ACTIVE' AND m.task_id IN ",
            "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ",
            "ORDER BY o.start_at,o.id,m.member_no",
            "</script>"})
    List<Map<String, Object>> listPlanningOccupancies(@Param("taskIds") List<String> taskIds);
}
