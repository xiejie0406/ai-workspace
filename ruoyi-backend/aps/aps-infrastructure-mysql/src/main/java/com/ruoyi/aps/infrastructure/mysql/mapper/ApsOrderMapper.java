package com.ruoyi.aps.infrastructure.mysql.mapper;

import java.util.List;
import java.util.Map;
import com.ruoyi.aps.application.order.OrderCatalog.MaterialDemand;
import com.ruoyi.aps.application.order.OrderCatalog.OrderLine;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionLot;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionOrder;
import com.ruoyi.aps.application.order.OrderCatalog.Task;
import com.ruoyi.aps.application.order.OrderCatalog.TaskDependency;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

public interface ApsOrderMapper
{
    @Select("SELECT COUNT(*) FROM aps_route_version v WHERE v.id=#{id} AND (#{allScope}=1 OR " +
            "(EXISTS (SELECT 1 FROM aps_route_node n JOIN aps_operation_phase p ON p.operation_spec_id=n.operation_spec_id " +
            "JOIN aps_resource_requirement rr ON rr.operation_phase_id=p.id WHERE n.route_version_id=v.id AND rr.optional_flag=0) " +
            "AND NOT EXISTS (SELECT 1 FROM aps_route_node n JOIN aps_operation_phase p ON p.operation_spec_id=n.operation_spec_id " +
            "JOIN aps_resource_requirement rr ON rr.operation_phase_id=p.id " +
            "LEFT JOIN aps_resource r ON r.id=rr.fixed_resource_id LEFT JOIN aps_work_center c ON c.id=COALESCE(rr.work_center_id,r.work_center_id) " +
            "LEFT JOIN aps_workshop w ON w.id=c.workshop_id WHERE n.route_version_id=v.id " +
            "AND (w.manager_user_id IS NULL OR w.manager_user_id<>#{userId}))))")
    int isRouteInScope(@Param("userId") String userId, @Param("allScope") boolean allScope, @Param("id") String id);

    @Select("SELECT COUNT(*) FROM aps_order o WHERE o.id=#{id} AND (#{allScope}=1 OR NOT EXISTS " +
            "(SELECT 1 FROM aps_order_line l JOIN aps_production_lot b ON b.order_line_id=l.id " +
            "JOIN aps_task t ON t.production_lot_id=b.id LEFT JOIN aps_work_center c ON c.id=t.work_center_id " +
            "LEFT JOIN aps_workshop w ON w.id=c.workshop_id WHERE l.order_id=o.id " +
            "AND t.work_center_id IS NOT NULL AND (w.manager_user_id IS NULL OR w.manager_user_id<>#{userId})))")
    int isOrderTasksInScope(@Param("userId") String userId, @Param("allScope") boolean allScope, @Param("id") String id);
    @Select("SELECT * FROM aps_order ORDER BY created_at DESC,order_no")
    List<Map<String, Object>> listOrders();

    @Select("SELECT * FROM aps_order WHERE id=#{id}")
    Map<String, Object> findOrder(@Param("id") String id);

    @Select("SELECT * FROM aps_order WHERE id=#{id} FOR UPDATE")
    Map<String, Object> findOrderForUpdate(@Param("id") String id);

    @Update("UPDATE aps_order SET customer_code=#{v.customerCode},customer_name=#{v.customerName},priority=#{v.priority},"
            + "promised_at=#{v.promisedAt},earliest_start_at=#{v.earliestStartAt},remark=#{v.remark},"
            + "updated_by=#{actor},row_version=row_version+1 WHERE id=#{v.id} AND status='DRAFT' AND row_version=#{v.rowVersion}")
    int updateDraftOrder(@Param("v") ProductionOrder value, @Param("actor") String actor);

    @Delete("DELETE FROM aps_order_line WHERE order_id=#{orderId}")
    void deleteDraftLines(@Param("orderId") String orderId);

    @Select("SELECT * FROM aps_order WHERE source_system=#{sourceSystem} AND external_id=#{externalId}")
    Map<String, Object> findByExternalId(@Param("sourceSystem") String sourceSystem, @Param("externalId") String externalId);

    @Select("SELECT * FROM aps_order_line WHERE order_id=#{orderId} ORDER BY line_no")
    List<Map<String, Object>> listOrderLines(@Param("orderId") String orderId);

    @Select("SELECT * FROM aps_order_line WHERE id=#{id}")
    Map<String, Object> findOrderLine(@Param("id") String id);

    @Insert("INSERT INTO aps_order(id,order_no,source_system,external_id,customer_code,customer_name,priority,promised_at,"
            + "earliest_start_at,status,remark,created_by,updated_by) VALUES(#{v.id},#{v.orderNo},#{v.sourceSystem},#{v.externalId},"
            + "#{v.customerCode},#{v.customerName},#{v.priority},#{v.promisedAt},#{v.earliestStartAt},#{v.status},#{v.remark},#{actor},#{actor})")
    void insertOrder(@Param("v") ProductionOrder order, @Param("actor") String actor);

    @Insert("INSERT INTO aps_order_line(id,order_id,line_no,item_id,route_version_id,demand_qty,uom_code,promised_at,earliest_start_at,"
            + "status,component_snapshot_json,created_by,updated_by,row_version) VALUES(#{v.id},#{v.orderId},#{v.lineNo},#{v.itemId},#{v.routeVersionId},"
            + "#{v.demandQty},#{v.uomCode},#{v.promisedAt},#{v.earliestStartAt},#{v.status},#{snapshotJson},#{actor},#{actor},#{v.rowVersion})")
    void insertOrderLine(@Param("v") OrderLine line, @Param("snapshotJson") String snapshotJson,
            @Param("actor") String actor);

    @Update("UPDATE aps_order SET status=#{status},updated_by=#{actor},row_version=row_version+1 WHERE id=#{id} AND row_version=#{rowVersion}")
    int updateOrderStatus(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("status") String status, @Param("actor") String actor);

    @Update("UPDATE aps_order_line SET status='RELEASED',updated_by=#{actor},row_version=row_version+1 "
            + "WHERE order_id=#{id} AND status='DRAFT'")
    void releaseOrderLines(@Param("id") String id, @Param("actor") String actor);

    @Update("UPDATE aps_production_lot l JOIN aps_order_line ol ON ol.id=l.order_line_id SET l.status='RELEASED',"
            + "l.updated_by=#{actor},l.row_version=l.row_version+1 WHERE ol.order_id=#{id} AND l.status='PLANNED'")
    void releaseOrderLots(@Param("id") String id, @Param("actor") String actor);

    @Update("UPDATE aps_order_line SET route_version_id=#{targetRouteVersionId},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND route_version_id=#{expectedRouteVersionId} AND row_version=#{rowVersion}")
    int updateOrderLineRoute(@Param("id") String id, @Param("expectedRouteVersionId") String expectedRouteVersionId,
            @Param("targetRouteVersionId") String targetRouteVersionId, @Param("rowVersion") long rowVersion,
            @Param("actor") String actor);

    @Select("SELECT l.* FROM aps_production_lot l JOIN aps_order_line ol ON ol.id=l.order_line_id "
            + "WHERE ol.order_id=#{orderId} ORDER BY ol.line_no,l.created_at,l.lot_no")
    List<Map<String, Object>> listLots(@Param("orderId") String orderId);

    @Select("SELECT * FROM aps_production_lot WHERE id=#{id}")
    Map<String, Object> findLot(@Param("id") String id);

    @Insert("INSERT INTO aps_production_lot(id,order_line_id,item_id,route_version_id,parent_lot_id,lot_no,lot_type,planned_qty,"
            + "uom_code,recovery_reason,status,created_by,updated_by) VALUES(#{v.id},#{v.orderLineId},#{v.itemId},#{v.routeVersionId},"
            + "#{v.parentLotId},#{v.lotNo},#{v.lotType},#{v.plannedQty},#{v.uomCode},#{v.recoveryReason},#{v.status},#{actor},#{actor})")
    void insertLot(@Param("v") ProductionLot lot, @Param("actor") String actor);

    @Update("UPDATE aps_production_lot SET planned_qty=#{quantity},updated_by=#{actor},row_version=row_version+1 "
            + "WHERE id=#{id} AND row_version=#{rowVersion}")
    int updateLotQuantity(@Param("id") String id, @Param("rowVersion") long rowVersion,
            @Param("quantity") java.math.BigDecimal quantity, @Param("actor") String actor);

    @Select("SELECT t.* FROM aps_task t JOIN aps_production_lot l ON l.id=t.production_lot_id "
            + "JOIN aps_order_line ol ON ol.id=l.order_line_id WHERE ol.order_id=#{orderId} ORDER BY ol.line_no,t.task_code")
    List<Map<String, Object>> listTasks(@Param("orderId") String orderId);

    @Select("SELECT * FROM aps_task WHERE id=#{taskId}")
    Map<String, Object> findTask(@Param("taskId") String taskId);

    @Select("SELECT * FROM aps_task WHERE production_lot_id=#{lotId} ORDER BY task_code")
    List<Map<String, Object>> listTasksByLot(@Param("lotId") String lotId);

    @Select("SELECT t.* FROM aps_task t JOIN aps_production_lot l ON l.id=t.production_lot_id "
            + "WHERE l.order_line_id=#{orderLineId} ORDER BY t.task_code")
    List<Map<String, Object>> listTasksByLine(@Param("orderLineId") String orderLineId);

    @Insert("INSERT INTO aps_task(id,production_lot_id,route_version_id,route_node_id,operation_spec_id,work_center_id,task_code,"
            + "task_name,task_qty,uom_code,setup_seconds,run_seconds,unload_seconds,wait_seconds,transport_seconds,interruptible,"
            + "required_skill_code,min_skill_level,requirement_snapshot_json,status,created_by,updated_by) VALUES(#{v.id},"
            + "#{v.productionLotId},#{v.routeVersionId},#{v.routeNodeId},#{v.operationSpecId},#{v.workCenterId},#{v.taskCode},"
            + "#{v.taskName},#{v.taskQty},#{v.uomCode},#{v.setupSeconds},#{v.runSeconds},#{v.unloadSeconds},#{v.waitSeconds},"
            + "#{v.transportSeconds},#{v.interruptible},#{v.requiredSkillCode},#{v.minimumSkillLevel},#{snapshotJson},#{v.status},#{actor},#{actor})")
    void insertTask(@Param("v") Task task, @Param("snapshotJson") String snapshotJson, @Param("actor") String actor);

    @Update("UPDATE aps_task SET task_qty=#{v.taskQty},setup_seconds=#{v.setupSeconds},run_seconds=#{v.runSeconds},"
            + "unload_seconds=#{v.unloadSeconds},wait_seconds=#{v.waitSeconds},transport_seconds=#{v.transportSeconds},"
            + "updated_by=#{actor},row_version=row_version+1 WHERE id=#{v.id} AND row_version=#{rowVersion}")
    int updateTaskQuantity(@Param("v") Task task, @Param("rowVersion") long rowVersion,
            @Param("actor") String actor);

    @Select("SELECT d.* FROM aps_task_dependency d JOIN aps_task t ON t.id=d.successor_task_id "
            + "JOIN aps_production_lot l ON l.id=t.production_lot_id JOIN aps_order_line ol ON ol.id=l.order_line_id "
            + "WHERE ol.order_id=#{orderId} ORDER BY d.predecessor_task_id,d.successor_task_id")
    List<Map<String, Object>> listDependencies(@Param("orderId") String orderId);

    @Insert("INSERT INTO aps_task_dependency(id,predecessor_task_id,successor_task_id,dependency_type,threshold_qty,threshold_ratio,"
            + "transfer_batch_qty,uom_code,lag_seconds,consumes_output,created_by,updated_by) VALUES(#{v.id},#{v.predecessorTaskId},"
            + "#{v.successorTaskId},#{v.dependencyType},#{v.thresholdQty},#{v.thresholdRatio},#{v.transferBatchQty},#{v.uomCode},"
            + "#{v.lagSeconds},#{v.consumesOutput},#{actor},#{actor})")
    void insertDependency(@Param("v") TaskDependency dependency, @Param("actor") String actor);

    @Select("SELECT d.* FROM aps_material_demand d JOIN aps_task t ON t.id=d.target_task_id "
            + "JOIN aps_production_lot l ON l.id=t.production_lot_id JOIN aps_order_line ol ON ol.id=l.order_line_id "
            + "WHERE ol.order_id=#{orderId} ORDER BY t.task_code,d.demand_no")
    List<Map<String, Object>> listMaterialDemands(@Param("orderId") String orderId);

    @Insert("INSERT INTO aps_material_demand(id,target_task_id,demand_no,item_id,source_task_id,demand_type,required_qty,uom_code,"
            + "transfer_batch_qty,material_status,ready_at,created_by,updated_by) VALUES(#{v.id},#{v.targetTaskId},#{v.demandNo},"
            + "#{v.itemId},#{v.sourceTaskId},#{v.demandType},#{v.requiredQty},#{v.uomCode},#{v.transferBatchQty},"
            + "#{v.materialStatus},#{v.readyAt},#{actor},#{actor})")
    void insertMaterialDemand(@Param("v") MaterialDemand demand, @Param("actor") String actor);

    @Update("UPDATE aps_task t SET t.status=(CASE " +
            "WHEN COALESCE((SELECT SUM(p.processed_qty) FROM aps_plan_job_member m " +
            "JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "JOIN aps_production_report p ON p.execution_run_id=r.id AND p.plan_job_member_id=m.id " +
            "WHERE m.task_id=t.id AND NOT EXISTS (SELECT 1 FROM aps_production_report c WHERE c.correction_of_id=p.id)),0)>=t.task_qty " +
            "AND NOT EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "JOIN aps_production_report p ON p.execution_run_id=r.id AND p.plan_job_member_id=m.id " +
            "JOIN aps_output_lot ol ON ol.source_report_id=p.id WHERE m.task_id=t.id AND ol.quality_status<>'CLOSED' " +
            "AND ol.total_qty>(ol.available_qty+ol.reserved_qty+ol.consumed_qty+ol.scrapped_qty)) THEN 'COMPLETED' " +
            "WHEN EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "WHERE m.task_id=t.id AND r.status='WAIT_QUALITY') THEN 'WAIT_QUALITY' " +
            "WHEN EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "WHERE m.task_id=t.id AND r.status='RUNNING') THEN 'RUNNING' " +
            "WHEN EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "WHERE m.task_id=t.id AND r.status='PAUSED') THEN 'PAUSED' " +
            "WHEN EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "WHERE m.task_id=t.id AND r.status='READY') THEN 'DISPATCHED' " +
            "WHEN EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r ON r.plan_job_id=m.plan_job_id " +
            "WHERE m.task_id=t.id) AND NOT EXISTS (SELECT 1 FROM aps_plan_job_member m JOIN aps_execution_run r " +
            "ON r.plan_job_id=m.plan_job_id WHERE m.task_id=t.id AND r.status<>'CANCELLED') THEN 'CANCELLED' " +
            "ELSE t.status END),t.updated_by=#{actor},t.row_version=t.row_version+1 WHERE t.id=#{taskId}")
    int recomputeTaskStatus(@Param("taskId") String taskId, @Param("actor") String actor);

    @Update("UPDATE aps_production_lot l JOIN aps_task source ON source.production_lot_id=l.id " +
            "SET l.status=(CASE WHEN NOT EXISTS (SELECT 1 FROM aps_task t WHERE t.production_lot_id=l.id AND t.status<>'COMPLETED') " +
            "THEN 'COMPLETED' WHEN EXISTS (SELECT 1 FROM aps_task t WHERE t.production_lot_id=l.id " +
            "AND t.status IN ('DISPATCHED','RUNNING','PAUSED','WAIT_QUALITY','COMPLETED')) THEN 'IN_PRODUCTION' " +
            "WHEN NOT EXISTS (SELECT 1 FROM aps_task t WHERE t.production_lot_id=l.id AND t.status<>'CANCELLED') " +
            "THEN 'CANCELLED' ELSE l.status END),l.updated_by=#{actor},l.row_version=l.row_version+1 " +
            "WHERE source.id=#{taskId}")
    int recomputeLotStatus(@Param("taskId") String taskId, @Param("actor") String actor);

    @Update("UPDATE aps_order_line line JOIN aps_production_lot source_lot ON source_lot.order_line_id=line.id " +
            "JOIN aps_task source_task ON source_task.production_lot_id=source_lot.id " +
            "SET line.status=(CASE WHEN (SELECT COALESCE(SUM(ol.available_qty+ol.reserved_qty+ol.consumed_qty+" +
            "COALESCE((SELECT SUM(CASE WHEN e.to_bucket='TRANSFERRED' THEN e.quantity " +
            "WHEN e.from_bucket='TRANSFERRED' THEN -e.quantity ELSE 0 END) FROM aps_quantity_event e " +
            "WHERE e.output_lot_id=ol.id),0)),0) " +
            "FROM aps_output_lot ol JOIN aps_task terminal_task ON terminal_task.id=ol.source_task_id " +
            "JOIN aps_production_lot delivered_lot ON delivered_lot.id=terminal_task.production_lot_id " +
            "WHERE delivered_lot.order_line_id=line.id AND ol.quality_status<>'CLOSED' " +
            "AND NOT EXISTS (SELECT 1 FROM aps_task_dependency d WHERE d.predecessor_task_id=terminal_task.id))>=line.demand_qty " +
            "AND NOT EXISTS (SELECT 1 FROM aps_production_lot recovery WHERE recovery.order_line_id=line.id " +
            "AND recovery.lot_type IN ('REWORK','REPLENISH') AND recovery.status NOT IN ('COMPLETED','CANCELLED')) " +
            "THEN 'COMPLETED' WHEN EXISTS (SELECT 1 FROM aps_production_lot l WHERE l.order_line_id=line.id " +
            "AND l.status IN ('IN_PRODUCTION','COMPLETED')) THEN 'IN_PRODUCTION' ELSE line.status END)," +
            "line.updated_by=#{actor},line.row_version=line.row_version+1 WHERE source_task.id=#{taskId}")
    int recomputeLineStatus(@Param("taskId") String taskId, @Param("actor") String actor);

    @Update("UPDATE aps_order o JOIN aps_order_line source_line ON source_line.order_id=o.id " +
            "JOIN aps_production_lot source_lot ON source_lot.order_line_id=source_line.id " +
            "JOIN aps_task source_task ON source_task.production_lot_id=source_lot.id " +
            "SET o.status=(CASE WHEN NOT EXISTS (SELECT 1 FROM aps_order_line line WHERE line.order_id=o.id " +
            "AND line.status<>'COMPLETED') THEN 'COMPLETED' WHEN EXISTS (SELECT 1 FROM aps_order_line line " +
            "WHERE line.order_id=o.id AND line.status IN ('IN_PRODUCTION','COMPLETED')) THEN 'IN_PRODUCTION' " +
            "ELSE o.status END),o.updated_by=#{actor},o.row_version=o.row_version+1 WHERE source_task.id=#{taskId}")
    int recomputeOrderStatus(@Param("taskId") String taskId, @Param("actor") String actor);
}
