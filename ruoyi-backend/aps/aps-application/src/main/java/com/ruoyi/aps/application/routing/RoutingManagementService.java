package com.ruoyi.aps.application.routing;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.foundation.ApsValidationException;
import com.ruoyi.aps.application.foundation.ApsValidationIssue;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationMode;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.PhaseType;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.SegmentResourcePolicy;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdge;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdgeDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraph;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraphDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNode;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNodeDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteValidation;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;
import com.ruoyi.aps.domain.routing.DependencyType;
import com.ruoyi.aps.domain.routing.RouteGraphValidator;

/** 产品、工艺和版本化路线用例。 */
public final class RoutingManagementService
{
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,63}$");
    private static final Pattern UOM = Pattern.compile("^[A-Z][A-Z0-9_]{0,15}$");

    private final RoutingRepository repository;
    private final ApsTransactionOperations transactions;
    private final RouteGraphValidator graphValidator = new RouteGraphValidator();

    public RoutingManagementService(RoutingRepository repository, ApsTransactionOperations transactions)
    {
        this.repository = repository;
        this.transactions = transactions;
    }

    public List<Item> listItems() { return repository.listItems(); }
    public List<OperationSpec> listOperations() { return repository.listOperations(); }
    public List<RouteVersion> listRoutes(String itemId) { return repository.listRoutes(itemId); }
    public RouteGraph getRouteGraph(String id) { requireRoute(id); return repository.getRouteGraph(id); }

    public Item saveItem(Item input, String actor)
    {
        requireCode(input.code(), "物料编码");
        requireText(input.name(), "物料名称");
        if (input.type() == null || !UOM.matcher(normalize(input.baseUomCode())).matches())
            throw invalid("产品/物料类型和基础单位必须有效");
        String status = defaultValue(input.status(), "ACTIVE");
        if (!Set.of("ACTIVE", "INACTIVE").contains(status)) throw invalid("物料状态无效");
        if (input.id() == null)
        {
            Item created = new Item(newId(), normalize(input.code()), input.name().trim(), input.type(),
                    trim(input.specification()), normalize(input.baseUomCode()), status, trim(input.remark()), 0);
            return transactions.required(() -> { repository.insertItem(created, actor); return created; });
        }
        requireItem(input.id());
        Item updated = new Item(input.id(), normalize(input.code()), input.name().trim(), input.type(),
                trim(input.specification()), normalize(input.baseUomCode()), status, trim(input.remark()), input.rowVersion());
        return transactions.required(() -> {
            if (repository.updateItem(updated, actor) != 1) throw stale("物料已被其他人修改");
            return withVersion(updated, updated.rowVersion() + 1);
        });
    }

    public OperationSpec saveOperation(OperationSpec input, String actor)
    {
        requireCode(input.code(), "工序编码");
        requireText(input.name(), "工序名称");
        if (input.id() == null)
        {
            String id = newId();
            OperationSpec created = normalizeOperation(input, id, 0);
            validateOperation(created);
            return transactions.required(() -> {
                repository.insertOperation(created, actor);
                repository.replaceOperationChildren(created, actor);
                return created;
            });
        }
        OperationSpec current = requireOperation(input.id());
        if (!"DRAFT".equals(current.status())) throw new ApsBusinessException(ApsErrorCode.CONFLICT, "已生效工序不可原位修改，请新建工序版本");
        OperationSpec updated = normalizeOperation(input, input.id(), input.rowVersion());
        validateOperation(updated);
        return transactions.required(() -> {
            if (repository.updateOperation(updated, actor) != 1) throw stale("工序已被其他人修改");
            repository.replaceOperationChildren(updated, actor);
            return normalizeOperation(updated, updated.id(), updated.rowVersion() + 1);
        });
    }

    public OperationSpec activateOperation(String id, long rowVersion, String actor)
    {
        OperationSpec current = requireOperation(id);
        if ("ACTIVE".equals(current.status())) return current;
        validateOperation(current);
        OperationSpec updated = new OperationSpec(current.id(), current.code(), current.name(), current.mode(),
                current.outputItemId(), current.interruptible(), current.qualityGateRequired(), current.batchCapacity(),
                current.batchUomCode(), current.compatibilityRuleJson(), "ACTIVE", current.remark(), current.phases(), rowVersion);
        return transactions.required(() -> {
            if (repository.updateOperation(updated, actor) != 1) throw stale("工序已被其他人修改");
            return new OperationSpec(updated.id(), updated.code(), updated.name(), updated.mode(), updated.outputItemId(),
                    updated.interruptible(), updated.qualityGateRequired(), updated.batchCapacity(), updated.batchUomCode(),
                    updated.compatibilityRuleJson(), updated.status(), updated.remark(), updated.phases(), rowVersion + 1);
        });
    }

    public RouteVersion createRoute(RouteVersion input, String actor)
    {
        requireItem(input.itemId());
        requireCode(input.routeCode(), "路线编码");
        requireText(input.versionNo(), "路线版本号");
        if (input.effectiveFrom() != null && input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom()))
            throw invalid("路线失效时间必须晚于生效时间");
        RouteVersion created = new RouteVersion(newId(), input.itemId(), normalize(input.routeCode()), input.versionNo().trim(),
                "DRAFT", input.effectiveFrom(), input.effectiveTo(), trim(input.changeNote()), null, null, 0);
        return transactions.required(() -> { repository.insertRoute(created, actor); return created; });
    }

    public RouteGraph saveGraph(String routeVersionId, RouteGraphDraft draft, String actor)
    {
        return saveGraph(routeVersionId,null,draft,actor);
    }
    public RouteGraph saveGraph(String routeVersionId,Long expectedRowVersion,RouteGraphDraft draft,String actor)
    {
        return transactions.required(() -> {
        RouteVersion route = requireDraftRoute(routeVersionId);
        if(expectedRowVersion!=null && expectedRowVersion!=route.rowVersion())throw stale("路线图已被其他人修改，请回查后重试");
        List<RouteNode> nodes = normalizeNodes(routeVersionId, draft.nodes());
        Map<String, RouteNode> byCode = new HashMap<>();
        nodes.forEach(node -> byCode.put(node.nodeCode(), node));
        List<RouteEdge> edges = normalizeEdges(routeVersionId, draft.edges(), byCode);
        RouteGraph graph = new RouteGraph(route, nodes, edges);
        List<ApsValidationIssue> structural = validate(graph, false).issues();
        if (!structural.isEmpty()) throw validation(ApsErrorCode.INVALID_REQUEST, "路线图结构无效", structural);
            if(repository.advanceDraftRouteRevision(routeVersionId,route.rowVersion(),actor)!=1)throw stale("路线已生效或版本已变化，不能覆盖");
            repository.replaceRouteGraph(routeVersionId, nodes, edges, actor);
            return new RouteGraph(new RouteVersion(route.id(),route.itemId(),route.routeCode(),route.versionNo(),route.status(),route.effectiveFrom(),route.effectiveTo(),route.changeNote(),route.approvedBy(),route.approvedAt(),route.rowVersion()+1),nodes,edges);
        });
    }

    public RouteValidation validateRoute(String routeVersionId)
    {
        return validate(repository.getRouteGraph(requireRoute(routeVersionId).id()), true);
    }

    public RouteVersion publishRoute(String routeVersionId, long rowVersion, String actor)
    {
        RouteVersion route = requireDraftRoute(routeVersionId);
        RouteValidation validation = validateRoute(routeVersionId);
        if (!validation.publishable())
        {
            ApsErrorCode code = validation.issues().stream().anyMatch(i -> "UNSUPPORTED_SYNC_RULE".equals(i.code()))
                    ? ApsErrorCode.UNSUPPORTED_SYNC_RULE : ApsErrorCode.INVALID_REQUEST;
            throw validation(code, "路线未通过发布校验", validation.issues());
        }
        Instant approvedAt = Instant.now();
        return transactions.required(() -> {
            if (repository.updateRouteStatus(routeVersionId, rowVersion, "ACTIVE", actor, approvedAt) != 1)
                throw stale("路线版本已被其他人修改");
            return new RouteVersion(route.id(), route.itemId(), route.routeCode(), route.versionNo(), "ACTIVE",
                    route.effectiveFrom(), route.effectiveTo(), route.changeNote(), actor, approvedAt, rowVersion + 1);
        });
    }

    public RouteGraph copyRoute(String sourceId, String versionNo, String changeNote, String actor)
    {
        RouteGraph source = repository.getRouteGraph(requireRoute(sourceId).id());
        requireText(versionNo, "路线版本号");
        RouteVersion target = new RouteVersion(newId(), source.route().itemId(), source.route().routeCode(), versionNo.trim(),
                "DRAFT", source.route().effectiveFrom(), source.route().effectiveTo(), trim(changeNote), null, null, 0);
        Map<String, String> idMap = new HashMap<>();
        List<RouteNode> nodes = source.nodes().stream().map(node -> {
            String id = newId();
            idMap.put(node.id(), id);
            return new RouteNode(id, target.id(), node.operationSpecId(), node.nodeCode(), node.nodeName(),
                    node.displayOrder(), node.quantityMultiplier(), node.terminal(), 0);
        }).toList();
        List<RouteEdge> edges = source.edges().stream().map(edge -> new RouteEdge(
                stableId(target.id(), idMap.get(edge.predecessorNodeId()), idMap.get(edge.successorNodeId())), target.id(),
                idMap.get(edge.predecessorNodeId()), idMap.get(edge.successorNodeId()), edge.dependencyType(),
                edge.thresholdQty(), edge.thresholdRatio(), edge.transferBatchQty(), edge.lagSeconds(), edge.consumesOutput(), 0)).toList();
        return transactions.required(() -> {
            repository.insertRoute(target, actor);
            repository.replaceRouteGraph(target.id(), nodes, edges, actor);
            return new RouteGraph(target, nodes, edges);
        });
    }

    private RouteValidation validate(RouteGraph graph, boolean publish)
    {
        List<ApsValidationIssue> issues = new ArrayList<>();
        List<RouteGraphValidator.Node> nodes = graph.nodes().stream().map(node -> {
            OperationSpec operation = repository.findOperation(node.operationSpecId()).orElse(null);
            boolean duration = operation != null && operation.phases().stream().allMatch(this::hasValidDuration);
            boolean requirements = operation != null && operation.phases().stream()
                    .filter(phase -> phase.phaseType() != RoutingCatalog.PhaseType.WAIT)
                    .allMatch(phase -> phase.requirements().stream().anyMatch(value -> !value.optional()));
            boolean references = operation != null && operation.phases().stream().flatMap(phase -> phase.requirements().stream())
                    .allMatch(repository::requirementTargetExists);
            if (publish && (operation == null || !"ACTIVE".equals(operation.status())))
            {
                issues.add(new ApsValidationIssue("INACTIVE_OPERATION", "OPERATION_SPEC", node.operationSpecId(), "status",
                        "路线节点必须引用已生效工序"));
            }
            return new RouteGraphValidator.Node(node.id(), node.nodeCode(), duration, requirements, references);
        }).toList();
        List<RouteGraphValidator.Edge> edges = graph.edges().stream().map(edge -> new RouteGraphValidator.Edge(edge.id(),
                edge.predecessorNodeId(), edge.successorNodeId(), edge.dependencyType(), edge.thresholdQty(),
                edge.thresholdRatio(), edge.transferBatchQty(), edge.lagSeconds(), edge.consumesOutput())).toList();
        graphValidator.validate(new RouteGraphValidator.Graph(graph.route().id(), nodes, edges), publish).forEach(issue ->
                issues.add(new ApsValidationIssue(issue.code(), issue.objectType(), issue.objectId(), issue.field(), issue.message())));
        if (publish && !graph.nodes().isEmpty())
        {
            Set<String> predecessorIds = graph.edges().stream().map(RouteEdge::predecessorNodeId).collect(java.util.stream.Collectors.toSet());
            if (graph.nodes().stream().noneMatch(RouteNode::terminal))
            {
                issues.add(new ApsValidationIssue("NO_TERMINAL_NODE", "ROUTE_VERSION", graph.route().id(),
                        "nodes", "路线至少需要一个明确的终点节点"));
            }
            graph.nodes().forEach(node -> {
                if (node.terminal() && predecessorIds.contains(node.id()))
                {
                    issues.add(new ApsValidationIssue("INVALID_TERMINAL_NODE", "ROUTE_NODE", node.id(),
                            "terminal", "终点节点不能再作为其他节点的前置工序"));
                }
                if (!node.terminal() && !predecessorIds.contains(node.id()))
                {
                    issues.add(new ApsValidationIssue("UNMARKED_TERMINAL_NODE", "ROUTE_NODE", node.id(),
                            "terminal", "没有后继任务的路线节点必须标记为终点"));
                }
            });
        }
        return new RouteValidation(graph.route().id(), issues.isEmpty(), issues);
    }

    private List<RouteNode> normalizeNodes(String routeId, List<RouteNodeDraft> drafts)
    {
        Set<String> codes = new HashSet<>();
        return drafts.stream().map(value -> {
            requireCode(value.nodeCode(), "节点编码");
            requireText(value.nodeName(), "节点名称");
            if (!codes.add(normalize(value.nodeCode()))) throw invalid("路线节点编码重复: " + value.nodeCode());
            requireOperation(value.operationSpecId());
            if (value.quantityMultiplier() == null || value.quantityMultiplier().signum() <= 0) throw invalid("节点数量倍率必须大于零");
            String id = value.id() == null ? stableId(routeId, normalize(value.nodeCode())) : value.id();
            return new RouteNode(id, routeId, value.operationSpecId(), normalize(value.nodeCode()), value.nodeName().trim(),
                    value.displayOrder(), value.quantityMultiplier().stripTrailingZeros(), value.terminal(), 0);
        }).sorted(Comparator.comparingInt(RouteNode::displayOrder).thenComparing(RouteNode::nodeCode)).toList();
    }

    private List<RouteEdge> normalizeEdges(String routeId, List<RouteEdgeDraft> drafts, Map<String, RouteNode> nodes)
    {
        return drafts.stream().map(value -> {
            String predecessorCode = normalize(value.predecessorNodeCode());
            String successorCode = normalize(value.successorNodeCode());
            RouteNode predecessor = nodes.get(predecessorCode);
            RouteNode successor = nodes.get(successorCode);
            String id = value.id() == null ? stableId(routeId, predecessorCode, successorCode) : value.id();
            return new RouteEdge(id, routeId, predecessor == null ? predecessorCode : predecessor.id(),
                    successor == null ? successorCode : successor.id(), Objects.requireNonNull(value.dependencyType(), "dependencyType"),
                    value.thresholdQty(), value.thresholdRatio(), value.transferBatchQty(), value.lagSeconds(), value.consumesOutput(), 0);
        }).toList();
    }

    private void validateOperation(OperationSpec value)
    {
        requireCode(value.code(), "工序编码");
        requireText(value.name(), "工序名称");
        if (value.mode() == null || value.phases().isEmpty()) throw invalid("工序模式和阶段不能为空");
        if (value.outputItemId() != null) requireItem(value.outputItemId());
        if (value.mode() == OperationMode.BATCH)
        {
            if (value.batchCapacity() == null || value.batchCapacity().signum() <= 0 || !UOM.matcher(normalize(value.batchUomCode())).matches())
                throw invalid("批处理工序必须设置正数批容量和单位");
        }
        else if (value.batchCapacity() != null || value.batchUomCode() != null)
            throw invalid("非批处理工序不能设置批容量");
        Set<Integer> phaseNos = new HashSet<>();
        boolean segmentableRun = false;
        for (OperationPhase phase : value.phases())
        {
            if (phase.phaseType() == null || phase.resourceHoldPolicy() == null || phase.name() == null || phase.name().isBlank()
                    || phase.segmentResourcePolicy() == null || !phaseNos.add(phase.phaseNo())
                    || phase.phaseNo() <= 0 || !hasValidDuration(phase))
                throw validation(ApsErrorCode.INVALID_REQUEST, "阶段工时模型无效", List.of(
                        new ApsValidationIssue("MISSING_DURATION", "OPERATION_PHASE", phase.id(),
                                "durationModel", "阶段序号必须唯一且工时模型必须可复算")));
            boolean segmentable = value.interruptible() && phase.phaseType() == PhaseType.RUN;
            if (segmentable)
            {
                if (phase.maxSegments() < 2 || phase.minSegmentSeconds() <= 0
                        || phase.resumeSetupSeconds() < 0)
                    throw invalid("可中断运行阶段必须设置至少 2 段、正数最小分段秒数和非负恢复准备秒数");
                segmentableRun = true;
            }
            else if (phase.maxSegments() != 1 || phase.minSegmentSeconds() != 0
                    || phase.resumeSetupSeconds() != 0)
                throw invalid("不可中断阶段必须使用 maxSegments=1，且最小分段与恢复准备均为 0");
            Set<Integer> requirementNos = new HashSet<>();
            for (ResourceRequirement requirement : phase.requirements())
            {
                if (requirement.resourceType() == null || !requirementNos.add(requirement.requirementNo())
                        || requirement.requirementNo() <= 0 || requirement.seatCount() <= 0)
                    throw invalid("资源要求序号必须唯一且席位数大于零");
                if ((requirement.requiredSkillCode() == null) != (requirement.minimumSkillLevel() == null)
                        || (requirement.minimumSkillLevel() != null && (requirement.minimumSkillLevel() < 1 || requirement.minimumSkillLevel() > 10)))
                    throw invalid("技能编码和最低等级必须成对出现且等级为 1 到 10");
                if (requirement.holdOnPause() && (!segmentable
                        || phase.segmentResourcePolicy() != SegmentResourcePolicy.SAME_RESOURCES))
                    throw invalid("暂停保留资源只允许用于采用 SAME_RESOURCES 的可中断运行阶段");
                if (!repository.requirementTargetExists(requirement))
                    throw validation(ApsErrorCode.INVALID_REQUEST, "资源要求引用无效", List.of(
                            new ApsValidationIssue("UNKNOWN_RESOURCE_REQUIREMENT", "RESOURCE_REQUIREMENT",
                                    requirement.id(), "fixedResourceId", "资源要求引用了不存在或类型不符的工作中心/资源")));
            }
        }
        if (value.interruptible() && !segmentableRun)
            throw invalid("可中断工序必须包含一个配置了分段规则的运行阶段");
    }

    private boolean hasValidDuration(OperationPhase phase)
    {
        if (phase.durationModel() == null || phase.fixedSeconds() < 0 || phase.secondsPerUnit() == null || phase.secondsPerUnit().signum() < 0) return false;
        return switch (phase.durationModel())
        {
            case FIXED -> phase.fixedSeconds() > 0 && phase.secondsPerUnit().signum() == 0;
            case PER_UNIT -> phase.fixedSeconds() == 0 && phase.secondsPerUnit().signum() > 0;
            case FIXED_PLUS_UNIT -> phase.fixedSeconds() > 0 && phase.secondsPerUnit().signum() > 0;
        };
    }

    private OperationSpec normalizeOperation(OperationSpec input, String id, long version)
    {
        List<OperationPhase> phases = input.phases().stream().map(phase -> {
            String phaseId = phase.id() == null ? stableId(id, "phase", String.valueOf(phase.phaseNo())) : phase.id();
            List<ResourceRequirement> requirements = phase.requirements().stream().map(requirement ->
                    new ResourceRequirement(requirement.id() == null ? stableId(phaseId, "requirement", String.valueOf(requirement.requirementNo())) : requirement.id(),
                            phaseId, requirement.requirementNo(), requirement.resourceType(), requirement.workCenterId(),
                            requirement.fixedResourceId(), requirement.seatCount(), normalizeNullable(requirement.requiredSkillCode()),
                            requirement.minimumSkillLevel(), trim(requirement.capabilityRuleJson()), requirement.optional(),
                            requirement.holdOnPause(), 0)).toList();
            return new OperationPhase(phaseId, id, phase.phaseNo(), phase.phaseType(),
                    phase.name() == null ? "" : phase.name().trim(), phase.durationModel(),
                    phase.fixedSeconds(), phase.secondsPerUnit().stripTrailingZeros(), phase.resourceHoldPolicy(),
                    phase.maxSegments(), phase.minSegmentSeconds(), phase.resumeSetupSeconds(),
                    phase.segmentResourcePolicy(), requirements, 0);
        }).toList();
        return new OperationSpec(id, normalize(input.code()), input.name().trim(), input.mode(), input.outputItemId(),
                input.interruptible(), input.qualityGateRequired(), input.batchCapacity(), normalizeNullable(input.batchUomCode()),
                trim(input.compatibilityRuleJson()), "DRAFT", trim(input.remark()), phases, version);
    }

    private Item requireItem(String id) { return repository.findItem(id).orElseThrow(() -> new ApsBusinessException(ApsErrorCode.NOT_FOUND, "产品或物料不存在")); }
    private OperationSpec requireOperation(String id) { return repository.findOperation(id).orElseThrow(() -> new ApsBusinessException(ApsErrorCode.NOT_FOUND, "工序不存在")); }
    private RouteVersion requireRoute(String id) { return repository.findRoute(id).orElseThrow(() -> new ApsBusinessException(ApsErrorCode.NOT_FOUND, "路线版本不存在")); }
    private RouteVersion requireDraftRoute(String id) { RouteVersion route = requireRoute(id); if (!"DRAFT".equals(route.status())) throw new ApsBusinessException(ApsErrorCode.CONFLICT, "仅草稿路线允许编辑或发布"); return route; }
    private ApsBusinessException invalid(String message) { return new ApsBusinessException(ApsErrorCode.INVALID_REQUEST, message); }
    private ApsBusinessException stale(String message) { return new ApsBusinessException(ApsErrorCode.STALE_VERSION, message); }
    private ApsValidationException validation(ApsErrorCode code, String message, List<ApsValidationIssue> issues) { return new ApsValidationException(code, message, issues); }
    private void requireCode(String value, String label) { if (value == null || !CODE.matcher(normalize(value)).matches()) throw invalid(label + "必须是 1 至 64 位大写编码"); }
    private void requireText(String value, String label) { if (value == null || value.isBlank()) throw invalid(label + "不能为空"); }
    private String newId() { return UUID.randomUUID().toString(); }
    private String stableId(String... parts) { return UUID.nameUUIDFromBytes(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private String normalizeNullable(String value) { return value == null || value.isBlank() ? null : normalize(value); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String defaultValue(String value, String fallback) { return value == null || value.isBlank() ? fallback : normalize(value); }
    private Item withVersion(Item value, long version) { return new Item(value.id(), value.code(), value.name(), value.type(), value.specification(), value.baseUomCode(), value.status(), value.remark(), version); }
}
