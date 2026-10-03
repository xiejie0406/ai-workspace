package com.ruoyi.aps.infrastructure.mysql.routing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ruoyi.aps.application.routing.RoutingCatalog.DurationModel;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.ItemType;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationMode;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.PhaseType;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceHoldPolicy;
import com.ruoyi.aps.application.routing.RoutingCatalog.SegmentResourcePolicy;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdge;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraph;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNode;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;
import com.ruoyi.aps.application.routing.RoutingRepository;
import com.ruoyi.aps.domain.resource.ResourceType;
import com.ruoyi.aps.domain.routing.DependencyType;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsRoutingMapper;
import com.ruoyi.aps.infrastructure.mysql.support.ApsRowMapperSupport;

public final class MybatisRoutingRepository extends ApsRowMapperSupport implements RoutingRepository
{
    private final ApsRoutingMapper mapper;

    public MybatisRoutingRepository(ApsRoutingMapper mapper) { this.mapper = mapper; }

    @Override public List<Item> listItems() { return mapper.listItems().stream().map(this::item).toList(); }
    @Override public Optional<Item> findItem(String id) { return Optional.ofNullable(mapper.findItem(id)).map(this::item); }
    @Override public void insertItem(Item value, String actor) { mapper.insertItem(value, actor); }
    @Override public int updateItem(Item value, String actor) { return mapper.updateItem(value, actor); }
    @Override public List<OperationSpec> listOperations() { return mapper.listOperations().stream().map(this::operation).toList(); }
    @Override public Optional<OperationSpec> findOperation(String id) { return Optional.ofNullable(mapper.findOperation(id)).map(this::operation); }
    @Override public void insertOperation(OperationSpec value, String actor) { mapper.insertOperation(value, actor); }
    @Override public int updateOperation(OperationSpec value, String actor) { return mapper.updateOperation(value, actor); }

    @Override
    public void replaceOperationChildren(OperationSpec value, String actor)
    {
        mapper.deleteRequirements(value.id());
        mapper.deletePhases(value.id());
        value.phases().forEach(phase -> {
            mapper.insertPhase(phase, actor);
            phase.requirements().forEach(requirement -> mapper.insertRequirement(requirement, actor));
        });
    }

    @Override public boolean requirementTargetExists(ResourceRequirement value) { return Boolean.TRUE.equals(mapper.requirementTargetExists(value)); }
    @Override public List<RouteVersion> listRoutes(String itemId) { return mapper.listRoutes(itemId).stream().map(this::route).toList(); }
    @Override public Optional<RouteVersion> findRoute(String id) { return Optional.ofNullable(mapper.findRoute(id)).map(this::route); }
    @Override public RouteGraph getRouteGraph(String id) { return new RouteGraph(findRoute(id).orElseThrow(), mapper.listRouteNodes(id).stream().map(this::node).toList(), mapper.listRouteEdges(id).stream().map(this::edge).toList()); }
    @Override public void insertRoute(RouteVersion value, String actor) { mapper.insertRoute(value, actor); }
    @Override public int updateRouteStatus(String id, long rowVersion, String status, String actor, Instant approvedAt) { return mapper.updateRouteStatus(id, rowVersion, status, actor, approvedAt); }
    @Override public int advanceDraftRouteRevision(String id,long rowVersion,String actor) {return mapper.advanceDraftRouteRevision(id,rowVersion,actor);}

    @Override
    public void replaceRouteGraph(String routeVersionId, List<RouteNode> nodes, List<RouteEdge> edges, String actor)
    {
        mapper.deleteRouteEdges(routeVersionId);
        mapper.deleteRouteNodes(routeVersionId);
        nodes.forEach(node -> mapper.insertRouteNode(node, actor));
        edges.forEach(edge -> mapper.insertRouteEdge(edge, actor));
    }

    private Item item(Map<String, Object> row)
    {
        return new Item(text(row, "id"), text(row, "item_code"), text(row, "item_name"),
                ItemType.valueOf(text(row, "item_type")), nullable(row, "specification"), text(row, "base_uom_code"),
                text(row, "status"), nullable(row, "remark"), number(row, "row_version").longValue());
    }

    private OperationSpec operation(Map<String, Object> row)
    {
        String id = text(row, "id");
        List<OperationPhase> phases = mapper.listPhases(id).stream().map(this::phase).toList();
        return new OperationSpec(id, text(row, "operation_code"), text(row, "operation_name"),
                OperationMode.valueOf(text(row, "operation_mode")), nullable(row, "output_item_id"),
                bool(row, "interruptible"), bool(row, "quality_gate_required"), nullableDecimal(row, "batch_capacity"),
                nullable(row, "batch_uom_code"), nullable(row, "compatibility_rule_json"), text(row, "status"),
                nullable(row, "remark"), phases, number(row, "row_version").longValue());
    }

    private OperationPhase phase(Map<String, Object> row)
    {
        String id = text(row, "id");
        return new OperationPhase(id, text(row, "operation_spec_id"), number(row, "phase_no").intValue(),
                PhaseType.valueOf(text(row, "phase_type")), text(row, "phase_name"),
                DurationModel.valueOf(text(row, "duration_model")), number(row, "fixed_seconds").intValue(),
                decimal(row, "seconds_per_unit"), ResourceHoldPolicy.valueOf(text(row, "resource_hold_policy")),
                number(row, "max_segments").intValue(), number(row, "min_segment_seconds").intValue(),
                number(row, "resume_setup_seconds").intValue(),
                SegmentResourcePolicy.valueOf(text(row, "segment_resource_policy")),
                mapper.listRequirements(id).stream().map(this::requirement).toList(), number(row, "row_version").longValue());
    }

    private ResourceRequirement requirement(Map<String, Object> row)
    {
        return new ResourceRequirement(text(row, "id"), text(row, "operation_phase_id"),
                number(row, "requirement_no").intValue(), ResourceType.valueOf(text(row, "resource_type")),
                nullable(row, "work_center_id"), nullable(row, "fixed_resource_id"), number(row, "seat_count").intValue(),
                nullable(row, "required_skill_code"), row.get("min_skill_level") == null ? null : number(row, "min_skill_level").intValue(),
                nullable(row, "capability_rule_json"), bool(row, "optional_flag"), bool(row, "hold_on_pause"),
                number(row, "row_version").longValue());
    }

    private RouteVersion route(Map<String, Object> row)
    {
        return new RouteVersion(text(row, "id"), text(row, "item_id"), text(row, "route_code"), text(row, "version_no"),
                text(row, "status"), instant(row.get("effective_from")), instant(row.get("effective_to")), nullable(row, "change_note"),
                nullable(row, "approved_by"), instant(row.get("approved_at")), number(row, "row_version").longValue());
    }

    private RouteNode node(Map<String, Object> row)
    {
        return new RouteNode(text(row, "id"), text(row, "route_version_id"), text(row, "operation_spec_id"),
                text(row, "node_code"), text(row, "node_name"), number(row, "display_order").intValue(),
                decimal(row, "quantity_multiplier"), bool(row, "terminal_flag"), number(row, "row_version").longValue());
    }

    private RouteEdge edge(Map<String, Object> row)
    {
        return new RouteEdge(text(row, "id"), text(row, "route_version_id"), text(row, "predecessor_node_id"),
                text(row, "successor_node_id"), DependencyType.valueOf(text(row, "dependency_type")),
                nullableDecimal(row, "threshold_qty"), nullableDecimal(row, "threshold_ratio"),
                nullableDecimal(row, "transfer_batch_qty"), number(row, "lag_seconds").intValue(),
                bool(row, "consumes_output"), number(row, "row_version").longValue());
    }
}
