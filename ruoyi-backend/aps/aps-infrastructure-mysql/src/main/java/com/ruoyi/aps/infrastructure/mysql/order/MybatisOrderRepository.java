package com.ruoyi.aps.infrastructure.mysql.order;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.aps.application.order.OrderCatalog.ComponentSpec;
import com.ruoyi.aps.application.order.OrderCatalog.DemandType;
import com.ruoyi.aps.application.order.OrderCatalog.LineDependencySpec;
import com.ruoyi.aps.application.order.OrderCatalog.LotType;
import com.ruoyi.aps.application.order.OrderCatalog.MaterialDemand;
import com.ruoyi.aps.application.order.OrderCatalog.OrderLine;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionLot;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionOrder;
import com.ruoyi.aps.application.order.OrderCatalog.Task;
import com.ruoyi.aps.application.order.OrderCatalog.TaskDependency;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.domain.routing.DependencyType;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsOrderMapper;
import com.ruoyi.aps.infrastructure.mysql.support.ApsRowMapperSupport;

public final class MybatisOrderRepository extends ApsRowMapperSupport implements OrderRepository
{
    private static final TypeReference<List<ResourceRequirement>> REQUIREMENTS = new TypeReference<>() { };
    private final ApsOrderMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    public MybatisOrderRepository(ApsOrderMapper mapper) { this.mapper = mapper; }

    @Override public List<ProductionOrder> listOrders() { return mapper.listOrders().stream().map(this::order).toList(); }
    @Override public boolean isRouteInScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        return mapper.isRouteInScope(scope.actorUserId(), scope.allWorkshops(), id) > 0;
    }
    @Override public boolean isOrderInScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        if (!scope.allWorkshops()) {
            var order = findOrder(id);
            if (order.isEmpty() || order.get().lines().isEmpty()) return false;
            if (order.get().lines().stream().anyMatch(line -> !isRouteInScope(scope, line.routeVersionId()))) return false;
        }
        return mapper.isOrderTasksInScope(scope.actorUserId(), scope.allWorkshops(), id) > 0;
    }
    @Override public Optional<ProductionOrder> findOrder(String id) { return Optional.ofNullable(mapper.findOrder(id)).map(this::order); }
    @Override public Optional<ProductionOrder> findOrderForUpdate(String id) { return Optional.ofNullable(mapper.findOrderForUpdate(id)).map(this::order); }
    @Override public int updateDraftOrder(ProductionOrder order, String actor) { return mapper.updateDraftOrder(order, actor); }
    @Override public void deleteDraftLines(String orderId) { mapper.deleteDraftLines(orderId); }
    @Override public Optional<ProductionOrder> findByExternalId(String sourceSystem, String externalId) { return Optional.ofNullable(mapper.findByExternalId(sourceSystem, externalId)).map(this::order); }
    @Override public Optional<OrderLine> findOrderLine(String id) { return Optional.ofNullable(mapper.findOrderLine(id)).map(this::line); }
    @Override public void insertOrder(ProductionOrder order, String actor) { mapper.insertOrder(order, actor); }
    @Override public void insertOrderLine(OrderLine line, String actor) { mapper.insertOrderLine(line, write(new LineSnapshot(line.components(), line.dependencies())), actor); }
    @Override public int updateOrderStatus(String id, long rowVersion, String status, String actor) { return mapper.updateOrderStatus(id, rowVersion, status, actor); }
    @Override public void releaseOrderChildren(String id, String actor) { mapper.releaseOrderLines(id, actor); mapper.releaseOrderLots(id, actor); }
    @Override public int updateOrderLineRoute(String id, String expectedRouteVersionId, String targetRouteVersionId, long rowVersion, String actor) { return mapper.updateOrderLineRoute(id, expectedRouteVersionId, targetRouteVersionId, rowVersion, actor); }
    @Override public List<ProductionLot> listLots(String orderId) { return mapper.listLots(orderId).stream().map(this::lot).toList(); }
    @Override public Optional<ProductionLot> findLot(String id) { return Optional.ofNullable(mapper.findLot(id)).map(this::lot); }
    @Override public void insertLot(ProductionLot lot, String actor) { mapper.insertLot(lot, actor); }
    @Override public int updateLotQuantity(String id, long rowVersion, java.math.BigDecimal quantity, String actor) { return mapper.updateLotQuantity(id, rowVersion, quantity, actor); }
    @Override public List<Task> listTasks(String orderId) { return mapper.listTasks(orderId).stream().map(this::task).toList(); }
    @Override public Optional<Task> findTask(String id) { return Optional.ofNullable(mapper.findTask(id)).map(this::task); }
    @Override public boolean isTaskInScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        return findTask(id).flatMap(task -> findLot(task.productionLotId()))
                .flatMap(lot -> findOrderLine(lot.orderLineId())).map(line -> isOrderInScope(scope, line.orderId())).orElse(false);
    }
    @Override public List<Task> listTasksByLot(String lotId) { return mapper.listTasksByLot(lotId).stream().map(this::task).toList(); }
    @Override public List<Task> listTasksByLine(String orderLineId) { return mapper.listTasksByLine(orderLineId).stream().map(this::task).toList(); }
    @Override public void insertTask(Task task, String actor) { mapper.insertTask(task, write(task.requirementSnapshot()), actor); }
    @Override public int updateTaskQuantity(Task task, long rowVersion, String actor) { return mapper.updateTaskQuantity(task, rowVersion, actor); }
    @Override public List<TaskDependency> listDependencies(String orderId) { return mapper.listDependencies(orderId).stream().map(this::dependency).toList(); }
    @Override public void insertDependency(TaskDependency dependency, String actor) { mapper.insertDependency(dependency, actor); }
    @Override public List<MaterialDemand> listMaterialDemands(String orderId) { return mapper.listMaterialDemands(orderId).stream().map(this::demand).toList(); }
    @Override public void insertMaterialDemand(MaterialDemand demand, String actor) { mapper.insertMaterialDemand(demand, actor); }
    @Override public void recomputeExecutionStatuses(String taskId, String actor)
    {
        mapper.recomputeTaskStatus(taskId, actor);
        mapper.recomputeLotStatus(taskId, actor);
        mapper.recomputeLineStatus(taskId, actor);
        mapper.recomputeOrderStatus(taskId, actor);
    }

    private ProductionOrder order(Map<String, Object> row)
    {
        String id = text(row, "id");
        return new ProductionOrder(id, text(row, "order_no"), text(row, "source_system"), nullable(row, "external_id"),
                nullable(row, "customer_code"), nullable(row, "customer_name"), number(row, "priority").intValue(),
                instant(row.get("promised_at")), instant(row.get("earliest_start_at")), text(row, "status"),
                nullable(row, "remark"), mapper.listOrderLines(id).stream().map(this::line).toList(),
                number(row, "row_version").longValue());
    }

    private OrderLine line(Map<String, Object> row)
    {
        LineSnapshot snapshot = readSnapshot(nullable(row, "component_snapshot_json"));
        return new OrderLine(text(row, "id"), text(row, "order_id"), number(row, "line_no").intValue(),
                text(row, "item_id"), text(row, "route_version_id"), decimal(row, "demand_qty"), text(row, "uom_code"),
                instant(row.get("promised_at")), instant(row.get("earliest_start_at")), text(row, "status"),
                snapshot.components(), snapshot.dependencies(), number(row, "row_version").longValue());
    }

    private ProductionLot lot(Map<String, Object> row)
    {
        return new ProductionLot(text(row, "id"), text(row, "order_line_id"), text(row, "item_id"),
                text(row, "route_version_id"), nullable(row, "parent_lot_id"), text(row, "lot_no"),
                LotType.valueOf(text(row, "lot_type")), decimal(row, "planned_qty"), text(row, "uom_code"),
                nullable(row, "recovery_reason"), text(row, "status"), number(row, "row_version").longValue());
    }

    private Task task(Map<String, Object> row)
    {
        List<ResourceRequirement> requirements = readRequirements(nullable(row, "requirement_snapshot_json"));
        return new Task(text(row, "id"), text(row, "production_lot_id"), text(row, "route_version_id"),
                text(row, "route_node_id"), text(row, "operation_spec_id"), nullable(row, "work_center_id"),
                text(row, "task_code"), text(row, "task_name"), decimal(row, "task_qty"), text(row, "uom_code"),
                number(row, "setup_seconds").intValue(), number(row, "run_seconds").intValue(),
                number(row, "unload_seconds").intValue(), number(row, "wait_seconds").intValue(),
                number(row, "transport_seconds").intValue(), bool(row, "interruptible"),
                nullable(row, "required_skill_code"), row.get("min_skill_level") == null ? null : number(row, "min_skill_level").intValue(),
                requirements, text(row, "status"), number(row, "row_version").longValue());
    }

    private TaskDependency dependency(Map<String, Object> row)
    {
        return new TaskDependency(text(row, "id"), text(row, "predecessor_task_id"), text(row, "successor_task_id"),
                DependencyType.valueOf(text(row, "dependency_type")), nullableDecimal(row, "threshold_qty"),
                nullableDecimal(row, "threshold_ratio"), nullableDecimal(row, "transfer_batch_qty"), nullable(row, "uom_code"),
                number(row, "lag_seconds").intValue(), bool(row, "consumes_output"), number(row, "row_version").longValue());
    }

    private MaterialDemand demand(Map<String, Object> row)
    {
        return new MaterialDemand(text(row, "id"), text(row, "target_task_id"), number(row, "demand_no").intValue(),
                text(row, "item_id"), nullable(row, "source_task_id"), DemandType.valueOf(text(row, "demand_type")),
                decimal(row, "required_qty"), text(row, "uom_code"), nullableDecimal(row, "transfer_batch_qty"),
                text(row, "material_status"), instant(row.get("ready_at")), number(row, "row_version").longValue());
    }

    private String write(Object value)
    {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("订单快照序列化失败", exception); }
    }

    private LineSnapshot readSnapshot(String value)
    {
        if (value == null || value.isBlank()) return new LineSnapshot(List.of(), List.of());
        try { return json.readValue(value, LineSnapshot.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("订单行快照损坏", exception); }
    }

    private List<ResourceRequirement> readRequirements(String value)
    {
        if (value == null || value.isBlank()) return List.of();
        try { return json.readValue(value, REQUIREMENTS); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("任务资源快照损坏", exception); }
    }

    public record LineSnapshot(List<ComponentSpec> components, List<LineDependencySpec> dependencies)
    {
        public LineSnapshot
        {
            components = components == null ? List.of() : List.copyOf(components);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }
}
