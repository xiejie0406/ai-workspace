package com.ruoyi.aps.infrastructure.mysql.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdge;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNode;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ApsRoutingMapper
{
    @Select("SELECT * FROM aps_item ORDER BY item_code")
    List<Map<String, Object>> listItems();

    @Select("SELECT * FROM aps_item WHERE id=#{id}")
    Map<String, Object> findItem(@Param("id") String id);

    @Insert("INSERT INTO aps_item(id,item_code,item_name,item_type,specification,base_uom_code,status,remark,created_by,updated_by) "
            + "VALUES(#{v.id},#{v.code},#{v.name},#{v.type},#{v.specification},#{v.baseUomCode},#{v.status},#{v.remark},#{actor},#{actor})")
    void insertItem(@Param("v") Item value, @Param("actor") String actor);

    @Update("UPDATE aps_item SET item_code=#{v.code},item_name=#{v.name},item_type=#{v.type},specification=#{v.specification},"
            + "base_uom_code=#{v.baseUomCode},status=#{v.status},remark=#{v.remark},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{v.id} AND row_version=#{v.rowVersion}")
    int updateItem(@Param("v") Item value, @Param("actor") String actor);

    @Select("SELECT * FROM aps_operation_spec ORDER BY operation_code")
    List<Map<String, Object>> listOperations();

    @Select("SELECT * FROM aps_operation_spec WHERE id=#{id}")
    Map<String, Object> findOperation(@Param("id") String id);

    @Select("SELECT * FROM aps_operation_phase WHERE operation_spec_id=#{operationId} ORDER BY phase_no")
    List<Map<String, Object>> listPhases(@Param("operationId") String operationId);

    @Select("SELECT * FROM aps_resource_requirement WHERE operation_phase_id=#{phaseId} ORDER BY requirement_no")
    List<Map<String, Object>> listRequirements(@Param("phaseId") String phaseId);

    @Insert("INSERT INTO aps_operation_spec(id,operation_code,operation_name,operation_mode,output_item_id,interruptible,"
            + "quality_gate_required,batch_capacity,batch_uom_code,compatibility_rule_json,status,remark,created_by,updated_by) "
            + "VALUES(#{v.id},#{v.code},#{v.name},#{v.mode},#{v.outputItemId},#{v.interruptible},#{v.qualityGateRequired},"
            + "#{v.batchCapacity},#{v.batchUomCode},#{v.compatibilityRuleJson},#{v.status},#{v.remark},#{actor},#{actor})")
    void insertOperation(@Param("v") OperationSpec value, @Param("actor") String actor);

    @Update("UPDATE aps_operation_spec SET operation_code=#{v.code},operation_name=#{v.name},operation_mode=#{v.mode},"
            + "output_item_id=#{v.outputItemId},interruptible=#{v.interruptible},quality_gate_required=#{v.qualityGateRequired},"
            + "batch_capacity=#{v.batchCapacity},batch_uom_code=#{v.batchUomCode},compatibility_rule_json=#{v.compatibilityRuleJson},"
            + "status=#{v.status},remark=#{v.remark},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{v.id} AND row_version=#{v.rowVersion}")
    int updateOperation(@Param("v") OperationSpec value, @Param("actor") String actor);

    @Delete("DELETE r FROM aps_resource_requirement r JOIN aps_operation_phase p ON p.id=r.operation_phase_id WHERE p.operation_spec_id=#{operationId}")
    void deleteRequirements(@Param("operationId") String operationId);

    @Delete("DELETE FROM aps_operation_phase WHERE operation_spec_id=#{operationId}")
    void deletePhases(@Param("operationId") String operationId);

    @Insert("INSERT INTO aps_operation_phase(id,operation_spec_id,phase_no,phase_type,phase_name,duration_model,fixed_seconds,"
            + "seconds_per_unit,resource_hold_policy,max_segments,min_segment_seconds,resume_setup_seconds,segment_resource_policy,"
            + "created_by,updated_by) VALUES(#{v.id},#{v.operationSpecId},#{v.phaseNo},#{v.phaseType},#{v.name},#{v.durationModel},"
            + "#{v.fixedSeconds},#{v.secondsPerUnit},#{v.resourceHoldPolicy},#{v.maxSegments},#{v.minSegmentSeconds},"
            + "#{v.resumeSetupSeconds},#{v.segmentResourcePolicy},#{actor},#{actor})")
    void insertPhase(@Param("v") OperationPhase value, @Param("actor") String actor);

    @Insert("INSERT INTO aps_resource_requirement(id,operation_phase_id,work_center_id,fixed_resource_id,requirement_no,resource_type,"
            + "seat_count,required_skill_code,min_skill_level,capability_rule_json,optional_flag,hold_on_pause,created_by,updated_by) VALUES(#{v.id},"
            + "#{v.phaseId},#{v.workCenterId},#{v.fixedResourceId},#{v.requirementNo},#{v.resourceType},#{v.seatCount},"
            + "#{v.requiredSkillCode},#{v.minimumSkillLevel},#{v.capabilityRuleJson},#{v.optional},#{v.holdOnPause},#{actor},#{actor})")
    void insertRequirement(@Param("v") ResourceRequirement value, @Param("actor") String actor);

    @Select("SELECT ((#{v.workCenterId} IS NULL OR EXISTS(SELECT 1 FROM aps_work_center c WHERE c.id=#{v.workCenterId})) "
            + "AND (#{v.fixedResourceId} IS NULL OR EXISTS(SELECT 1 FROM aps_resource r WHERE r.id=#{v.fixedResourceId} "
            + "AND r.resource_type=#{v.resourceType} AND (#{v.workCenterId} IS NULL OR r.work_center_id=#{v.workCenterId}))))")
    Boolean requirementTargetExists(@Param("v") ResourceRequirement value);

    @Select({"<script>", "SELECT * FROM aps_route_version",
            "<if test='itemId != null'> WHERE item_id=#{itemId}</if>", " ORDER BY route_code,version_no", "</script>"})
    List<Map<String, Object>> listRoutes(@Param("itemId") String itemId);

    @Select("SELECT * FROM aps_route_version WHERE id=#{id}")
    Map<String, Object> findRoute(@Param("id") String id);

    @Select("SELECT * FROM aps_route_node WHERE route_version_id=#{routeId} ORDER BY display_order,node_code")
    List<Map<String, Object>> listRouteNodes(@Param("routeId") String routeId);

    @Select("SELECT * FROM aps_route_edge WHERE route_version_id=#{routeId} ORDER BY predecessor_node_id,successor_node_id")
    List<Map<String, Object>> listRouteEdges(@Param("routeId") String routeId);

    @Insert("INSERT INTO aps_route_version(id,item_id,route_code,version_no,status,effective_from,effective_to,change_note,"
            + "approved_by,approved_at,created_by,updated_by) VALUES(#{v.id},#{v.itemId},#{v.routeCode},#{v.versionNo},#{v.status},"
            + "#{v.effectiveFrom},#{v.effectiveTo},#{v.changeNote},#{v.approvedBy},#{v.approvedAt},#{actor},#{actor})")
    void insertRoute(@Param("v") RouteVersion value, @Param("actor") String actor);

    @Update("UPDATE aps_route_version SET status=#{status},approved_by=#{actor},approved_at=#{approvedAt},updated_by=#{actor},"
            + "row_version=row_version+1 WHERE id=#{id} AND row_version=#{rowVersion}")
    int updateRouteStatus(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("status") String status, @Param("actor") String actor, @Param("approvedAt") Instant approvedAt);
    @Update("UPDATE aps_route_version SET row_version=row_version+1,updated_by=#{actor} WHERE id=#{id} AND row_version=#{rowVersion} AND status='DRAFT'")
    int advanceDraftRouteRevision(@Param("id") String id,@Param("rowVersion") long rowVersion,@Param("actor") String actor);

    @Delete("DELETE FROM aps_route_edge WHERE route_version_id=#{routeId}")
    void deleteRouteEdges(@Param("routeId") String routeId);

    @Delete("DELETE FROM aps_route_node WHERE route_version_id=#{routeId}")
    void deleteRouteNodes(@Param("routeId") String routeId);

    @Insert("INSERT INTO aps_route_node(id,route_version_id,operation_spec_id,node_code,node_name,display_order,quantity_multiplier,"
            + "terminal_flag,created_by,updated_by) VALUES(#{v.id},#{v.routeVersionId},#{v.operationSpecId},#{v.nodeCode},#{v.nodeName},"
            + "#{v.displayOrder},#{v.quantityMultiplier},#{v.terminal},#{actor},#{actor})")
    void insertRouteNode(@Param("v") RouteNode value, @Param("actor") String actor);

    @Insert("INSERT INTO aps_route_edge(id,route_version_id,predecessor_node_id,successor_node_id,dependency_type,threshold_qty,"
            + "threshold_ratio,transfer_batch_qty,lag_seconds,consumes_output,created_by,updated_by) VALUES(#{v.id},#{v.routeVersionId},"
            + "#{v.predecessorNodeId},#{v.successorNodeId},#{v.dependencyType},#{v.thresholdQty},#{v.thresholdRatio},"
            + "#{v.transferBatchQty},#{v.lagSeconds},#{v.consumesOutput},#{actor},#{actor})")
    void insertRouteEdge(@Param("v") RouteEdge value, @Param("actor") String actor);
}
