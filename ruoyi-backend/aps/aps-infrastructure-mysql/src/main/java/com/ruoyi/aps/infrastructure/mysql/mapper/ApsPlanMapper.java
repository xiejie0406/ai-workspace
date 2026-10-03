package com.ruoyi.aps.infrastructure.mysql.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ApsPlanMapper
{
    @Select({"<script>",
            "SELECT p.id,p.base_version_id,p.version_no,p.version_name,p.request_id,",
            "p.definition_revision,p.execution_revision,p.input_hash,p.status,p.created_at,p.updated_at,p.row_version ",
            "FROM aps_plan_version p ",
            "<if test='!allWorkshops'>",
            "WHERE JSON_LENGTH(JSON_EXTRACT(p.input_snapshot_json,'$.scope.workshopIds')) &gt; 0 ",
            "AND NOT EXISTS (SELECT 1 FROM JSON_TABLE(p.input_snapshot_json, ",
            "'$.scope.workshopIds[*]' COLUMNS(workshop_id VARCHAR(36) PATH '$')) scope_workshop ",
            "WHERE scope_workshop.workshop_id NOT IN ",
            "<foreach collection='workshopIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>) ",
            "</if>",
            "ORDER BY p.version_no DESC,p.id DESC LIMIT 200",
            "</script>"})
    List<Map<String, Object>> listVisiblePlans(@Param("workshopIds") List<String> workshopIds,
            @Param("allWorkshops") boolean allWorkshops);

    @Select("SELECT * FROM aps_plan_version WHERE request_id=#{requestId} FOR UPDATE")
    Map<String, Object> findByRequestId(@Param("requestId") String requestId);

    @Select("SELECT request_fingerprint FROM aps_plan_version WHERE request_id=#{requestId}")
    String findRequestFingerprintByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM aps_plan_version WHERE request_id=#{requestId}")
    Map<String, Object> findRequestSnapshot(@Param("requestId") String requestId);

    @Select("SELECT * FROM aps_plan_version WHERE id=#{id} AND status='PUBLISHED' AND is_current=1")
    Map<String, Object> findCurrentPublishedBaseline(@Param("id") String id);

    @Select("SELECT * FROM aps_plan_version WHERE id=#{id}")
    Map<String, Object> findPlanVersionById(@Param("id") String id);

    @Select("SELECT * FROM aps_plan_version WHERE id=#{id} FOR UPDATE")
    Map<String, Object> findPlanVersionByIdForUpdate(@Param("id") String id);

    @Select("SELECT * FROM aps_plan_version WHERE status='PUBLISHED' AND is_current=1 FOR UPDATE")
    Map<String, Object> findCurrentPublishedForUpdate();

    @Select("SELECT MAX(f.updated_at) FROM ("
            + "SELECT updated_at FROM aps_workshop UNION ALL SELECT updated_at FROM aps_work_center "
            + "UNION ALL SELECT updated_at FROM aps_resource UNION ALL SELECT updated_at FROM aps_resource_skill "
            + "UNION ALL SELECT updated_at FROM aps_resource_availability UNION ALL SELECT updated_at FROM aps_item "
            + "UNION ALL SELECT updated_at FROM aps_operation_spec UNION ALL SELECT updated_at FROM aps_operation_phase "
            + "UNION ALL SELECT updated_at FROM aps_resource_requirement UNION ALL SELECT updated_at FROM aps_route_version "
            + "UNION ALL SELECT updated_at FROM aps_route_node UNION ALL SELECT updated_at FROM aps_route_edge "
            + "UNION ALL SELECT updated_at FROM aps_order UNION ALL SELECT updated_at FROM aps_order_line "
            + "UNION ALL SELECT updated_at FROM aps_production_lot UNION ALL SELECT updated_at FROM aps_task "
            + "UNION ALL SELECT updated_at FROM aps_task_dependency UNION ALL SELECT updated_at FROM aps_material_demand "
            + "UNION ALL SELECT updated_at FROM aps_execution_run UNION ALL SELECT updated_at FROM aps_actual_occupancy "
            + "UNION ALL SELECT updated_at FROM aps_production_report UNION ALL SELECT updated_at FROM aps_output_lot "
            + "UNION ALL SELECT created_at AS updated_at FROM aps_quantity_event) f")
    Instant latestPlanningFactUpdatedAt();

    @Select({"<script>",
            "SELECT DISTINCT m.task_id FROM aps_execution_run r ",
            "JOIN aps_plan_job_member m ON m.plan_job_id=r.plan_job_id ",
            "WHERE (r.actual_start_at IS NOT NULL OR r.status NOT IN ('READY','CANCELLED')) ",
            "AND m.task_id IN ",
            "<foreach collection='taskIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ",
            "ORDER BY m.task_id",
            "</script>"})
    List<String> findStartedTaskIds(@Param("taskIds") List<String> taskIds);

    @Update("UPDATE aps_plan_version SET status='SUPERSEDED',is_current=0,updated_at=#{publishedAt},"
            + "updated_by=#{actor},row_version=row_version+1 WHERE id=#{id} AND status='PUBLISHED' "
            + "AND is_current=1 AND row_version=#{rowVersion}")
    int supersedeCurrent(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("publishedAt") Instant publishedAt, @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET status='PUBLISHED',is_current=1,published_at=#{publishedAt},"
            + "change_note=#{reason},"
            + "published_by=#{actor},updated_at=#{publishedAt},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND status='FEASIBLE' AND row_version=#{rowVersion}")
    int publishCandidate(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("publishedAt") Instant publishedAt, @Param("reason") String reason,
            @Param("actor") String actor);

    @Update("UPDATE aps_plan_version candidate LEFT JOIN aps_plan_version baseline "
            + "ON baseline.daily_baseline_date=#{businessDate} "
            + "SET candidate.daily_baseline_date=#{businessDate},candidate.updated_at=#{assignedAt},"
            + "candidate.updated_by=#{actor},candidate.row_version=candidate.row_version+1 "
            + "WHERE candidate.id=#{id} AND candidate.status='PUBLISHED' "
            + "AND candidate.daily_baseline_date IS NULL AND baseline.id IS NULL")
    int assignDailyBaselineIfAbsent(@Param("id") String id, @Param("businessDate") LocalDate businessDate,
            @Param("assignedAt") Instant assignedAt, @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET status='CANCELLED',is_current=0,change_note=#{reason},"
            + "updated_at=#{discardedAt},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND status IN ('FEASIBLE','CONFLICT') AND row_version=#{rowVersion}")
    int discardCandidate(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("discardedAt") Instant discardedAt, @Param("reason") String reason,
            @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND row_version=#{rowVersion} AND status IN ('FEASIBLE','CONFLICT')")
    int advanceCandidateRevision(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("actor") String actor);

    @Select("SELECT j.*,m.id AS member_id,m.task_id,m.member_no,m.planned_qty AS member_planned_qty,"
            + "m.uom_code AS member_uom_code FROM aps_plan_job j "
            + "LEFT JOIN aps_plan_job_member m ON m.plan_job_id=j.id "
            + "WHERE j.plan_version_id=#{id} ORDER BY j.planned_start_at,j.id,m.member_no")
    List<Map<String, Object>> findPlanJobMembers(@Param("id") String id);

    @Select("SELECT s.* FROM aps_plan_segment s JOIN aps_plan_job j ON j.id=s.plan_job_id "
            + "WHERE j.plan_version_id=#{id} ORDER BY s.start_at,s.plan_job_id,s.segment_no")
    List<Map<String, Object>> findPlanSegments(@Param("id") String id);

    @Select("SELECT a.* FROM aps_plan_allocation a JOIN aps_plan_segment s ON s.id=a.plan_segment_id "
            + "JOIN aps_plan_job j ON j.id=s.plan_job_id WHERE j.plan_version_id=#{id} "
            + "ORDER BY a.plan_segment_id,a.allocation_role,a.seat_no,a.id")
    List<Map<String, Object>> findPlanAllocations(@Param("id") String id);

    @Select("SELECT j.id AS job_id,j.planned_start_at,j.planned_end_at,m.task_id "
            + "FROM aps_plan_job j JOIN aps_plan_job_member m ON m.plan_job_id=j.id "
            + "WHERE j.plan_version_id=#{id} ORDER BY j.id,m.member_no")
    List<Map<String, Object>> findBaselineJobMembers(@Param("id") String id);

    @Select("SELECT DISTINCT s.plan_job_id AS job_id,a.resource_id FROM aps_plan_segment s "
            + "JOIN aps_plan_allocation a ON a.plan_segment_id=s.id "
            + "JOIN aps_plan_job j ON j.id=s.plan_job_id WHERE j.plan_version_id=#{id} "
            + "ORDER BY s.plan_job_id,a.resource_id")
    List<Map<String, Object>> findBaselineJobResources(@Param("id") String id);

    @Select("SELECT l.id AS lock_id,l.plan_segment_id,l.plan_allocation_id,l.row_version,l.target_type,l.lock_type,l.locked_start_at,l.locked_end_at,"
            + "l.locked_resource_id,l.lock_reason,l.plan_job_id AS job_id,s.operation_phase_id AS phase_id,"
            + "s.segment_no,a.resource_requirement_id AS requirement_id,a.seat_no,"
            + "a.resource_id AS allocation_resource_id FROM aps_plan_lock l "
            + "LEFT JOIN aps_plan_segment s ON s.id=l.plan_segment_id "
            + "LEFT JOIN aps_plan_allocation a ON a.id=l.plan_allocation_id "
            + "WHERE l.plan_version_id=#{id} ORDER BY l.id")
    List<Map<String, Object>> findBaselineLocks(@Param("id") String id);

    @Insert("INSERT INTO aps_plan_lock(id,plan_version_id,plan_job_id,plan_segment_id,plan_allocation_id,"
            + "locked_resource_id,target_type,lock_type,locked_start_at,locked_end_at,lock_reason,created_by,updated_by) "
            + "VALUES(#{v.id},#{planVersionId},#{v.jobId},#{v.segmentId},#{v.allocationId},#{v.lockedResourceId},"
            + "#{v.targetType},#{v.lockType},#{v.lockedStartAt},#{v.lockedEndAt},#{v.reason},#{actor},#{actor})")
    void insertLock(@Param("v") com.ruoyi.aps.application.planning.PlanRepository.PlanLock lock,
            @Param("planVersionId") String planVersionId, @Param("actor") String actor);

    @org.apache.ibatis.annotations.Delete("DELETE FROM aps_plan_lock WHERE plan_version_id=#{planVersionId} "
            + "AND id=#{lockId} AND row_version=#{rowVersion}")
    int deleteLock(@Param("planVersionId") String planVersionId, @Param("lockId") String lockId,
            @Param("rowVersion") long rowVersion);

    @Select("SELECT COALESCE(MAX(version_no),0)+1 FROM aps_plan_version FOR UPDATE")
    long nextVersionNo();

    @Insert("INSERT INTO aps_plan_version(id,base_version_id,version_no,version_name,status,is_current,request_id,"
            + "request_fingerprint,definition_revision,execution_revision,input_hash,input_snapshot_json,change_note,"
            + "created_by,updated_by) VALUES(#{v.id},#{v.baseVersionId},#{v.versionNo},#{v.versionName},'DRAFT',0,"
            + "#{v.requestId},#{requestFingerprint},#{v.definitionRevision},#{v.executionRevision},#{v.inputHash},"
            + "#{inputJson},#{changeNote},#{actor},#{actor})")
    void insertDraft(@Param("v") com.ruoyi.aps.application.planning.PlanRepository.PlanRecord plan,
            @Param("inputJson") String inputJson, @Param("requestFingerprint") String requestFingerprint,
            @Param("changeNote") String changeNote, @Param("actor") String actor);

    @Select("SELECT * FROM aps_plan_version WHERE status='DRAFT' ORDER BY version_no LIMIT 1")
    Map<String, Object> findNextDraft();

    @Update("UPDATE aps_plan_version SET status='SOLVING',updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND status='DRAFT' AND row_version=#{rowVersion}")
    int claim(@Param("id") String id, @Param("rowVersion") long rowVersion, @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET status='FAILED',"
            + "solver_summary_json=JSON_OBJECT('solverStatus','UNKNOWN','resultKind',NULL,'summary',"
            + "JSON_OBJECT('stopReason','WORKER_ERROR','reasonCode','STALE_SOLVING_RECOVERED')),"
            + "validation_summary_json=JSON_OBJECT('problems',JSON_ARRAY("
            + "JSON_OBJECT('reasonCode','STALE_SOLVING_RECOVERED'))),"
            + "change_note=LEFT(CONCAT_WS('；',NULLIF(change_note,''),'STALE_SOLVING_RECOVERED：Worker 启动时回收超时求解'),500),"
            + "updated_by=#{actor},row_version=row_version+1 "
            + "WHERE status='SOLVING' AND updated_at < #{staleBefore}")
    int recoverStaleSolving(@Param("staleBefore") Instant staleBefore, @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET status='CANCELLED',updated_by=#{actor},row_version=row_version+1 "
            + "WHERE request_id=#{requestId} AND status IN ('DRAFT','SOLVING') AND row_version=#{rowVersion}")
    int cancel(@Param("requestId") String requestId, @Param("rowVersion") long rowVersion,
            @Param("actor") String actor);

    @Select("SELECT COUNT(*) FROM aps_plan_version WHERE id=#{id} AND status='CANCELLED'")
    int isCancelled(@Param("id") String id);

    @Insert("INSERT INTO aps_plan_job(id,plan_version_id,operation_spec_id,work_center_id,job_code,job_type,batch_code,"
            + "planned_qty,uom_code,capacity_value,capacity_uom_code,compatibility_key,carry_run_id,planned_start_at,planned_end_at,created_by,updated_by) "
            + "VALUES(#{v.id},#{v.planVersionId},#{v.operationSpecId},#{v.workCenterId},#{v.jobCode},#{v.jobType},#{v.batchCode},"
            + "#{v.plannedQty},#{v.uomCode},#{v.capacityValue},#{v.capacityUomCode},#{v.compatibilityKey},#{v.carryRunId},#{v.startAt},#{v.endAt},#{actor},#{actor})")
    void insertJob(@Param("v") JobRow value, @Param("actor") String actor);

    @Insert("INSERT INTO aps_plan_job_member(id,plan_job_id,task_id,operation_spec_id,member_no,planned_qty,uom_code,allocation_ratio,created_by,updated_by) "
            + "VALUES(#{v.id},#{v.planJobId},#{v.taskId},#{v.operationSpecId},#{v.memberNo},#{v.plannedQty},#{v.uomCode},#{v.allocationRatio},#{actor},#{actor})")
    void insertMember(@Param("v") MemberRow value, @Param("actor") String actor);

    @Insert("INSERT INTO aps_plan_segment(id,plan_job_id,operation_spec_id,operation_phase_id,segment_no,phase_type,start_at,end_at,"
            + "planned_qty,release_at,release_qty,uom_code,created_by,updated_by) VALUES(#{v.id},#{v.planJobId},#{v.operationSpecId},"
            + "#{v.operationPhaseId},#{v.segmentNo},#{v.phaseType},#{v.startAt},#{v.endAt},#{v.plannedQty},#{v.releaseAt},#{v.releaseQty},"
            + "#{v.uomCode},#{actor},#{actor})")
    void insertSegment(@Param("v") SegmentRow value, @Param("actor") String actor);

    @Insert("INSERT INTO aps_plan_allocation(id,plan_segment_id,operation_phase_id,resource_requirement_id,resource_id,allocation_role,"
            + "seat_no,capacity_used,created_by,updated_by) VALUES(#{v.id},#{v.planSegmentId},#{v.operationPhaseId},"
            + "#{v.requirementId},#{v.resourceId},#{v.allocationRole},#{v.seatNo},#{v.capacityUsed},#{actor},#{actor})")
    void insertAllocation(@Param("v") AllocationRow value, @Param("actor") String actor);

    @Update("UPDATE aps_plan_version SET status=#{status},solver_summary_json=#{solverSummaryJson},"
            + "validation_summary_json=#{validationSummaryJson},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND status='SOLVING' AND row_version=#{rowVersion}")
    int complete(@Param("id") String id, @Param("rowVersion") long rowVersion, @Param("status") String status,
            @Param("solverSummaryJson") String solverSummaryJson,
            @Param("validationSummaryJson") String validationSummaryJson, @Param("actor") String actor);

    record JobRow(String id, String planVersionId, String operationSpecId, String workCenterId, String jobCode,
            String jobType, String batchCode, BigDecimal plannedQty, String uomCode, BigDecimal capacityValue,
            String capacityUomCode, String compatibilityKey, String carryRunId, Instant startAt, Instant endAt) { }
    record MemberRow(String id, String planJobId, String taskId, String operationSpecId, int memberNo,
            BigDecimal plannedQty, String uomCode, BigDecimal allocationRatio) { }
    record SegmentRow(String id, String planJobId, String operationSpecId, String operationPhaseId, int segmentNo,
            String phaseType, Instant startAt, Instant endAt, BigDecimal plannedQty, Instant releaseAt,
            BigDecimal releaseQty, String uomCode) { }
    record AllocationRow(String id, String planSegmentId, String operationPhaseId, String requirementId,
            String resourceId, String allocationRole, int seatNo, BigDecimal capacityUsed) { }
}
