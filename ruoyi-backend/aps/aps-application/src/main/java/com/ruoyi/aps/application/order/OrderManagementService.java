package com.ruoyi.aps.application.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.foundation.ApsValidationException;
import com.ruoyi.aps.application.foundation.ApsValidationIssue;
import com.ruoyi.aps.application.order.OrderCatalog.ComponentSpec;
import com.ruoyi.aps.application.order.OrderCatalog.DemandType;
import com.ruoyi.aps.application.order.OrderCatalog.ExpansionResult;
import com.ruoyi.aps.application.order.OrderCatalog.LineDependencySpec;
import com.ruoyi.aps.application.order.OrderCatalog.LotType;
import com.ruoyi.aps.application.order.OrderCatalog.MaterialDemand;
import com.ruoyi.aps.application.order.OrderCatalog.MigrationDiff;
import com.ruoyi.aps.application.order.OrderCatalog.OrderLine;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionLot;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionOrder;
import com.ruoyi.aps.application.order.OrderCatalog.Task;
import com.ruoyi.aps.application.order.OrderCatalog.TaskDependency;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdge;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraph;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNode;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;
import com.ruoyi.aps.application.routing.RoutingRepository;
import com.ruoyi.aps.domain.routing.DependencyType;
import com.ruoyi.aps.domain.routing.TaskDurationCalculator;
import com.ruoyi.aps.domain.resource.ResourceType;

/** 多产品订单导入、路线冻结和幂等任务展开。 */
public final class OrderManagementService
{
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,63}$");
    private static final Pattern SOURCE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");

    private final OrderRepository orders;
    private final RoutingRepository routings;
    private final ApsTransactionOperations transactions;
    private final TaskDurationCalculator durations = new TaskDurationCalculator();

    public OrderManagementService(OrderRepository orders, RoutingRepository routings,
            ApsTransactionOperations transactions)
    {
        this.orders = orders;
        this.routings = routings;
        this.transactions = transactions;
    }

    public List<ProductionOrder> listOrders() { return orders.listOrders(); }
    public List<ProductionOrder> listOrders(com.ruoyi.aps.application.resource.ResourceAccessScope scope) {
        return orders.listOrders().stream().filter(value -> orders.isOrderInScope(scope, value.id())).toList();
    }
    public void requireOrderScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        if (!orders.isOrderInScope(scope, id)) throw new com.ruoyi.aps.application.foundation.ApsBusinessException(ApsErrorCode.FORBIDDEN, "订单不存在或不在完整车间权限范围内");
    }
    public void requireInputScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, ProductionOrder input) {
        for (OrderLine line : input.lines()) requireRouteScope(scope, line.routeVersionId());
    }
    public void requireRouteScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        if (!orders.isRouteInScope(scope, id)) throw new com.ruoyi.aps.application.foundation.ApsBusinessException(ApsErrorCode.FORBIDDEN, "路线不存在或包含未授权/未归属的工作中心");
    }
    public void requireLineScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        requireOrderScope(scope, orders.findOrderLine(id).orElseThrow(() -> notFound("订单行不存在")).orderId());
    }
    public void requireLotScope(com.ruoyi.aps.application.resource.ResourceAccessScope scope, String id) {
        requireLineScope(scope, orders.findLot(id).orElseThrow(() -> notFound("生产批不存在")).orderLineId());
    }
    public ProductionOrder getOrder(String id) { return requireOrder(id); }
    public ExpansionResult getExpansion(String orderId) { return loadExpansion(requireOrder(orderId), true); }

    /** 相同来源和 externalId 的相同载荷返回原订单，不同载荷明确冲突。 */
    public ProductionOrder createOrImport(ProductionOrder input, String actor)
    {
        ProductionOrder normalized = normalizeAndValidate(input);
        if (normalized.externalId() != null)
        {
            ProductionOrder existing = orders.findByExternalId(normalized.sourceSystem(), normalized.externalId()).orElse(null);
            if (existing != null)
            {
                if (!samePayload(existing, normalized))
                    throw new ApsBusinessException(ApsErrorCode.IDEMPOTENCY_CONFLICT, "相同来源幂等键已用于不同订单载荷");
                return existing;
            }
        }
        return transactions.required(() -> {
            orders.insertOrder(normalized, actor);
            normalized.lines().forEach(line -> orders.insertOrderLine(line, actor));
            return normalized;
        });
    }

    /** 草稿头、产品行与展开串行化；已有冻结任务不得原位改写。 */
    public ProductionOrder updateDraft(String id, long rowVersion, ProductionOrder input, String actor)
    {
        return transactions.required(() -> {
            ProductionOrder current = orders.findOrderForUpdate(id).orElseThrow(() -> notFound("订单不存在"));
            if (current.rowVersion() != rowVersion) throw stale("订单已被其他人修改，请回查后再编辑");
            if (!"DRAFT".equals(current.status()) || !orders.listLots(id).isEmpty() || !orders.listTasks(id).isEmpty())
                throw new ApsBusinessException(ApsErrorCode.CONFLICT, "仅未展开的草稿订单可以编辑");
            if (!current.orderNo().equals(normalize(input.orderNo())) || !current.sourceSystem().equals(normalize(input.sourceSystem()))
                    || !Objects.equals(current.externalId(), trim(input.externalId())))
                throw invalid("订单号、来源和外部幂等键不能通过草稿编辑改变");
            ProductionOrder normalized = normalizeAndValidate(new ProductionOrder(id, input.orderNo(), input.sourceSystem(),
                    input.externalId(), input.customerCode(), input.customerName(), input.priority(), input.promisedAt(),
                    input.earliestStartAt(), "DRAFT", input.remark(), input.lines(), rowVersion));
            Map<Integer, OrderLine> existing = current.lines().stream().collect(Collectors.toMap(OrderLine::lineNo, value -> value));
            List<OrderLine> lines = normalized.lines().stream().map(line -> {
                OrderLine before = existing.get(line.lineNo());
                return new OrderLine(before == null ? line.id() : before.id(), id, line.lineNo(), line.itemId(), line.routeVersionId(), line.demandQty(),
                        line.uomCode(), line.promisedAt(), line.earliestStartAt(), "DRAFT", line.components(), line.dependencies(),
                        before == null ? 0 : before.rowVersion() + 1);
            }).toList();
            ProductionOrder update = new ProductionOrder(id, current.orderNo(), current.sourceSystem(), current.externalId(),
                    normalized.customerCode(), normalized.customerName(), normalized.priority(), normalized.promisedAt(),
                    normalized.earliestStartAt(), "DRAFT", normalized.remark(), lines, rowVersion);
            if (orders.updateDraftOrder(update, actor) != 1) throw stale("订单状态或版本已经变化");
            orders.deleteDraftLines(id);
            lines.forEach(line -> orders.insertOrderLine(line, actor));
            return new ProductionOrder(id, update.orderNo(), update.sourceSystem(), update.externalId(), update.customerCode(),
                    update.customerName(), update.priority(), update.promisedAt(), update.earliestStartAt(), update.status(),
                    update.remark(), lines, rowVersion + 1);
        });
    }

    /** 重复展开直接回读已冻结网络；首次展开在一个事务内完成 M15～M18。 */
    public ExpansionResult expandOrder(String orderId, String actor)
    {
        return transactions.required(() -> {
            ProductionOrder order = orders.findOrderForUpdate(orderId).orElseThrow(() -> notFound("订单不存在"));
            if (!orders.listLots(orderId).isEmpty()) return loadExpansion(order, true);
            Map<Integer, ProductionLot> lotsByLine = new LinkedHashMap<>();
            Map<Integer, Map<String, Task>> tasksByLineAndNode = new LinkedHashMap<>();
            List<Task> tasks = new ArrayList<>();
            List<TaskDependency> dependencies = new ArrayList<>();
            List<MaterialDemand> demands = new ArrayList<>();

            for (OrderLine line : order.lines().stream().sorted(Comparator.comparingInt(OrderLine::lineNo)).toList())
            {
                ProductionLot lot = normalLot(order, line);
                orders.insertLot(lot, actor);
                lotsByLine.put(line.lineNo(), lot);
                RouteGraph graph = routings.getRouteGraph(line.routeVersionId());
                Map<String, Task> byNode = expandLot(line, lot, graph, actor, tasks, dependencies);
                tasksByLineAndNode.put(line.lineNo(), byNode);
            }
            expandCrossLineDependencies(order, tasksByLineAndNode, dependencies, actor);
            expandMaterialDemands(order, tasksByLineAndNode, demands, actor);
            return new ExpansionResult(order, new ArrayList<>(lotsByLine.values()), tasks, dependencies, demands, false);
        });
    }

    public ProductionLot createDerivedLot(String parentLotId, LotType type, BigDecimal quantity,
            String reason, String actor)
    {
        ProductionLot parent = orders.findLot(parentLotId).orElseThrow(() -> notFound("父生产批不存在"));
        if (type == null || type == LotType.NORMAL) throw invalid("派生生产批类型必须是拆批、返工或补产");
        if (quantity == null || quantity.signum() <= 0) throw invalid("派生生产批数量必须大于零");
        OrderLine line = orders.findOrderLine(parent.orderLineId()).orElseThrow(() -> notFound("订单行不存在"));
        String id = newId();
        ProductionLot lot = new ProductionLot(id, line.id(), line.itemId(), line.routeVersionId(), parent.id(),
                boundedCode(parent.lotNo(), type.name(), id.substring(0, 8)), type,
                quantity(quantity), line.uomCode(), trim(reason), "PLANNED", 0);
        return transactions.required(() -> {
            ProductionLot currentParent = orders.findLot(parentLotId).orElseThrow(() -> notFound("父生产批不存在"));
            if (type == LotType.SPLIT) reduceParentForSplit(currentParent, line, lot.plannedQty(), actor, false);
            orders.insertLot(lot, actor);
            expandLot(line, lot, routings.getRouteGraph(line.routeVersionId()), actor,
                    new ArrayList<>(), new ArrayList<>());
            return lot;
        });
    }

    /**
     * 人工排程场景使用的幂等拆批入口。它允许尚未开工的 RELEASED 批次，并由调用方使用
     * requestId 派生稳定批次 ID；这样事务成功后的网络重试不会再次扣减父批数量。
     */
    public ProductionLot createPlanningSplitLot(String parentLotId, BigDecimal quantity, String reason,
            String derivedLotId, String actor)
    {
        if (derivedLotId == null || derivedLotId.isBlank()) throw invalid("拆批请求缺少稳定派生批次 ID");
        try { UUID.fromString(derivedLotId); }
        catch (IllegalArgumentException exception) { throw invalid("派生批次 ID 必须是 UUID"); }
        if (quantity == null || quantity.signum() <= 0) throw invalid("拆批数量必须大于零");
        String normalizedReason = trim(reason);
        if (normalizedReason == null) throw invalid("拆批原因不能为空");

        return transactions.required(() -> {
            ProductionLot existing = orders.findLot(derivedLotId).orElse(null);
            if (existing != null)
            {
                if (existing.lotType() != LotType.SPLIT || !Objects.equals(existing.parentLotId(), parentLotId)
                        || existing.plannedQty().compareTo(quantity(quantity)) != 0
                        || !Objects.equals(existing.recoveryReason(), normalizedReason))
                    throw new ApsBusinessException(ApsErrorCode.IDEMPOTENCY_CONFLICT,
                            "同一拆批请求已绑定不同批次意图");
                return existing;
            }
            ProductionLot parent = orders.findLot(parentLotId).orElseThrow(() -> notFound("父生产批不存在"));
            if (!Set.of("PLANNED", "RELEASED").contains(parent.status()))
                throw invalid("只有尚未开工的计划或已释放生产批可以拆批");
            OrderLine line = orders.findOrderLine(parent.orderLineId()).orElseThrow(() -> notFound("订单行不存在"));
            ProductionLot lot = new ProductionLot(derivedLotId, line.id(), line.itemId(), line.routeVersionId(),
                    parent.id(), boundedCode(parent.lotNo(), LotType.SPLIT.name(), derivedLotId.substring(0, 8)),
                    LotType.SPLIT, quantity(quantity), line.uomCode(), normalizedReason, parent.status(), 0);
            reduceParentForSplit(parent, line, lot.plannedQty(), actor, true);
            orders.insertLot(lot, actor);
            expandLot(line, lot, routings.getRouteGraph(line.routeVersionId()), actor,
                    new ArrayList<>(), new ArrayList<>());
            return lot;
        });
    }

    /**
     * 拆批是数量转移而不是新增产量：在同一事务内从父批及父任务扣减，再创建子批。
     * 首版只允许未释放批，并拒绝需要复制上游物料/跨批前置关系的情形，避免静默造出不完整网络。
     */
    private void reduceParentForSplit(ProductionLot parent, OrderLine line, BigDecimal splitQuantity, String actor,
            boolean allowReleased)
    {
        if (!("PLANNED".equals(parent.status()) || allowReleased && "RELEASED".equals(parent.status())))
            throw invalid(allowReleased ? "只有尚未开工的计划或已释放生产批可以拆批" : "只有未释放生产批可以拆批");
        BigDecimal remaining = parent.plannedQty().subtract(splitQuantity);
        if (remaining.signum() <= 0) throw invalid("拆批后父批必须保留正数量；全量转移请使用批次调整流程");
        List<Task> parentTasks = orders.listTasksByLot(parent.id());
        Set<String> parentTaskIds = parentTasks.stream().map(Task::id).collect(Collectors.toSet());
        List<TaskDependency> dependencies = orders.listDependencies(line.orderId());
        if (dependencies.stream().anyMatch(value -> allowReleased
                ? parentTaskIds.contains(value.successorTaskId()) != parentTaskIds.contains(value.predecessorTaskId())
                : parentTaskIds.contains(value.successorTaskId()) && !parentTaskIds.contains(value.predecessorTaskId())))
            throw invalid(allowReleased ? "当前拆批存在跨批依赖，必须先通过影响闭包重建关系后再拆分"
                    : "当前拆批存在跨批前置关系，必须先通过影响闭包重建关系后再拆分");
        if (orders.listMaterialDemands(line.orderId()).stream()
                .anyMatch(value -> parentTaskIds.contains(value.targetTaskId())
                        || allowReleased && parentTaskIds.contains(value.sourceTaskId())))
            throw invalid(allowReleased ? "当前拆批任务关联物料供需，必须先通过物料重分配流程拆分"
                    : "当前拆批任务带有物料需求，必须先通过物料重分配流程拆分");
        dependencies.stream().filter(value -> parentTaskIds.contains(value.predecessorTaskId())).forEach(value ->
                validateExpandedThreshold(value.dependencyType(), value.thresholdQty(), value.transferBatchQty(),
                        remaining, value.id()));
        if (orders.updateLotQuantity(parent.id(), parent.rowVersion(), remaining, actor) != 1)
            throw stale("父生产批已被其他人修改");

        RouteGraph graph = routings.getRouteGraph(parent.routeVersionId());
        Map<String, RouteNode> nodes = graph.nodes().stream().collect(Collectors.toMap(RouteNode::id, value -> value));
        for (Task task : parentTasks)
        {
            RouteNode node = nodes.get(task.routeNodeId());
            OperationSpec operation = routings.findOperation(task.operationSpecId()).orElse(null);
            if (node == null || operation == null) throw invalid("父批任务的冻结工艺快照不完整");
            Task adjusted = taskFrom(parent, node, operation, remaining);
            if (orders.updateTaskQuantity(adjusted, task.rowVersion(), actor) != 1)
                throw stale("父生产批任务已被其他人修改");
        }
    }

    /** 这里只完成订单结构释放，完整数据就绪仍由 IMP-05 承担。 */
    public ProductionOrder releaseOrder(String orderId, long rowVersion, String actor)
    {
        ProductionOrder order = requireOrder(orderId);
        ExpansionResult expansion = orders.listLots(orderId).isEmpty() ? expandOrder(orderId, actor) : loadExpansion(order, true);
        List<ApsValidationIssue> issues = new ArrayList<>();
        expansion.dependencies().stream().filter(value -> value.dependencyType() == DependencyType.SAME_START)
                .forEach(value -> issues.add(new ApsValidationIssue("UNSUPPORTED_SYNC_RULE", "DEPENDENCY", value.id(),
                        "dependencyType", "P0 保留 SAME_START 来源，但订单不能进入可执行状态")));
        order.lines().forEach(line -> {
            RouteVersion route = routings.findRoute(line.routeVersionId()).orElse(null);
            if (route == null || !"ACTIVE".equals(route.status()))
                issues.add(new ApsValidationIssue("NO_PUBLISHED_ROUTE", "ORDER_LINE", line.id(), "routeVersionId",
                        "订单释放前路线版本必须已发布"));
        });
        if (!issues.isEmpty())
        {
            ApsErrorCode code = issues.stream().anyMatch(i -> "UNSUPPORTED_SYNC_RULE".equals(i.code()))
                    ? ApsErrorCode.UNSUPPORTED_SYNC_RULE : ApsErrorCode.INVALID_REQUEST;
            throw new ApsValidationException(code, "订单未通过结构释放校验", issues);
        }
        return transactions.required(() -> {
            if (orders.updateOrderStatus(orderId, rowVersion, "RELEASED", actor) != 1) throw stale("订单已被其他人修改");
            orders.releaseOrderChildren(orderId, actor);
            List<OrderLine> releasedLines = order.lines().stream().map(line -> new OrderLine(line.id(), line.orderId(),
                    line.lineNo(), line.itemId(), line.routeVersionId(), line.demandQty(), line.uomCode(),
                    line.promisedAt(), line.earliestStartAt(), "RELEASED", line.components(), line.dependencies(),
                    line.rowVersion() + 1)).toList();
            return new ProductionOrder(order.id(), order.orderNo(), order.sourceSystem(), order.externalId(),
                    order.customerCode(), order.customerName(), order.priority(), order.promisedAt(), order.earliestStartAt(),
                    "RELEASED", order.remark(), releasedLines, rowVersion + 1);
        });
    }

    public MigrationDiff previewRouteMigration(String orderLineId, String targetRouteVersionId)
    {
        OrderLine line = orders.findOrderLine(orderLineId).orElseThrow(() -> notFound("订单行不存在"));
        RouteGraph current = routings.getRouteGraph(line.routeVersionId());
        RouteGraph target = routings.getRouteGraph(targetRouteVersionId);
        if (!line.itemId().equals(target.route().itemId()) || !"ACTIVE".equals(target.route().status()))
            throw invalid("目标路线必须是同一产品的已发布版本");
        Map<String, String> oldNodes = current.nodes().stream().collect(Collectors.toMap(RouteNode::nodeCode, RouteNode::operationSpecId));
        Map<String, String> newNodes = target.nodes().stream().collect(Collectors.toMap(RouteNode::nodeCode, RouteNode::operationSpecId));
        Set<String> oldEdges = edgeKeys(current);
        Set<String> newEdges = edgeKeys(target);
        return new MigrationDiff(line.id(), current.route().id(), target.route().id(),
                difference(newNodes.keySet(), oldNodes.keySet()), difference(oldNodes.keySet(), newNodes.keySet()),
                oldNodes.keySet().stream().filter(newNodes::containsKey)
                        .filter(code -> !Objects.equals(oldNodes.get(code), newNodes.get(code))).sorted().toList(),
                difference(newEdges, oldEdges), difference(oldEdges, newEdges), true);
    }

    public OrderLine confirmRouteMigration(String orderLineId, String expectedCurrentRouteVersionId,
            String targetRouteVersionId, long rowVersion, boolean confirmed, String actor)
    {
        if (!confirmed) throw new ApsBusinessException(ApsErrorCode.CONFLICT, "路线迁移必须显式确认差异");
        OrderLine line = orders.findOrderLine(orderLineId).orElseThrow(() -> notFound("订单行不存在"));
        if (!Objects.equals(line.routeVersionId(), expectedCurrentRouteVersionId)) throw stale("订单行路线版本已变化");
        if (!orders.listTasksByLine(line.id()).isEmpty()) throw new ApsBusinessException(ApsErrorCode.CONFLICT, "已展开任务的订单行不能原位迁移路线");
        previewRouteMigration(orderLineId, targetRouteVersionId);
        return transactions.required(() -> {
            if (orders.updateOrderLineRoute(line.id(), expectedCurrentRouteVersionId, targetRouteVersionId,
                    rowVersion, actor) != 1) throw stale("订单行已被其他人修改");
            return new OrderLine(line.id(), line.orderId(), line.lineNo(), line.itemId(), targetRouteVersionId,
                    line.demandQty(), line.uomCode(), line.promisedAt(), line.earliestStartAt(), line.status(),
                    line.components(), line.dependencies(), rowVersion + 1);
        });
    }

    private Map<String, Task> expandLot(OrderLine line, ProductionLot lot, RouteGraph graph, String actor,
            List<Task> taskSink, List<TaskDependency> dependencySink)
    {
        Map<String, Task> byNodeCode = new HashMap<>();
        Map<String, Task> byNodeId = new HashMap<>();
        for (RouteNode node : graph.nodes())
        {
            OperationSpec operation = routings.findOperation(node.operationSpecId())
                    .orElseThrow(() -> notFound("路线引用的工序不存在"));
            BigDecimal taskQty = quantity(lot.plannedQty().multiply(node.quantityMultiplier()));
            Task task = taskFrom(lot, node, operation, taskQty);
            orders.insertTask(task, actor);
            taskSink.add(task);
            byNodeCode.put(node.nodeCode(), task);
            byNodeId.put(node.id(), task);
        }
        for (RouteEdge edge : graph.edges())
        {
            Task predecessor = byNodeId.get(edge.predecessorNodeId());
            Task successor = byNodeId.get(edge.successorNodeId());
            if (predecessor == null || successor == null) throw invalid("路线依赖引用了缺失节点");
            validateExpandedThreshold(edge.dependencyType(), edge.thresholdQty(), edge.transferBatchQty(),
                    predecessor.taskQty(), edge.id());
            TaskDependency dependency = new TaskDependency(stableId(lot.id(), "edge", edge.id()),
                    predecessor.id(), successor.id(), edge.dependencyType(), edge.thresholdQty(), edge.thresholdRatio(),
                    edge.transferBatchQty(), edge.dependencyType() == DependencyType.QUANTITY ? predecessor.uomCode() : null,
                    edge.lagSeconds(), edge.consumesOutput(), 0);
            orders.insertDependency(dependency, actor);
            dependencySink.add(dependency);
        }
        return byNodeCode;
    }

    private Task taskFrom(ProductionLot lot, RouteNode node, OperationSpec operation, BigDecimal taskQty)
    {
        int setup = 0, run = 0, unload = 0, wait = 0, transport = 0;
        List<ResourceRequirement> requirements = new ArrayList<>();
        for (OperationPhase phase : operation.phases())
        {
            int seconds = durations.seconds(BigDecimal.valueOf(phase.fixedSeconds()), phase.secondsPerUnit(), taskQty);
            switch (phase.phaseType())
            {
                case SETUP -> setup = Math.addExact(setup, seconds);
                case RUN -> run = Math.addExact(run, seconds);
                case UNLOAD -> unload = Math.addExact(unload, seconds);
                case WAIT -> wait = Math.addExact(wait, seconds);
                case TRANSPORT -> transport = Math.addExact(transport, seconds);
            }
            requirements.addAll(phase.requirements());
        }
        ResourceRequirement primarySkill = requirements.stream().filter(value -> !value.optional()
                && value.resourceType() == ResourceType.PERSON && value.requiredSkillCode() != null).findFirst().orElse(null);
        Set<String> centers = requirements.stream().filter(value -> !value.optional() && value.workCenterId() != null)
                .map(ResourceRequirement::workCenterId).collect(Collectors.toSet());
        String center = centers.size() == 1 ? centers.iterator().next() : null;
        String id = stableId(lot.id(), "node", node.id());
        return new Task(id, lot.id(), lot.routeVersionId(), node.id(), operation.id(), center,
                boundedCode(lot.lotNo(), node.nodeCode()), node.nodeName(), taskQty, lot.uomCode(), setup, run, unload,
                wait, transport, operation.interruptible(), primarySkill == null ? null : primarySkill.requiredSkillCode(),
                primarySkill == null ? null : primarySkill.minimumSkillLevel(), requirements, "NOT_READY", 0);
    }

    private void expandCrossLineDependencies(ProductionOrder order, Map<Integer, Map<String, Task>> tasks,
            List<TaskDependency> sink, String actor)
    {
        for (OrderLine targetLine : order.lines())
        {
            for (LineDependencySpec spec : targetLine.dependencies())
            {
                Task predecessor = task(tasks, spec.predecessorLineNo(), spec.predecessorNodeCode());
                Task successor = task(tasks, targetLine.lineNo(), spec.successorNodeCode());
                validateExpandedThreshold(spec.dependencyType(), spec.thresholdQty(), spec.transferBatchQty(),
                        predecessor.taskQty(), predecessor.id());
                if (spec.dependencyType() == DependencyType.SAME_START && (spec.thresholdQty() != null
                        || spec.thresholdRatio() != null || spec.transferBatchQty() != null || spec.lagSeconds() != 0
                        || spec.consumesOutput())) throw invalid("SAME_START 不能携带数量、转移批、消耗或 lag");
                TaskDependency dependency = new TaskDependency(stableId(order.id(), "cross", predecessor.id(), successor.id()),
                        predecessor.id(), successor.id(), spec.dependencyType(), spec.thresholdQty(), spec.thresholdRatio(),
                        spec.transferBatchQty(), spec.dependencyType() == DependencyType.QUANTITY ? predecessor.uomCode() : null,
                        spec.lagSeconds(), spec.consumesOutput(), 0);
                orders.insertDependency(dependency, actor);
                sink.add(dependency);
            }
        }
    }

    private void expandMaterialDemands(ProductionOrder order, Map<Integer, Map<String, Task>> tasks,
            List<MaterialDemand> sink, String actor)
    {
        for (OrderLine line : order.lines())
        {
            for (ComponentSpec spec : line.components())
            {
                Task target = task(tasks, line.lineNo(), spec.targetNodeCode());
                Task source = spec.sourceLineNo() == null ? null : task(tasks, spec.sourceLineNo(), spec.sourceNodeCode());
                Item item = routings.findItem(spec.itemId()).orElseThrow(() -> notFound("组件物料不存在"));
                if (!item.baseUomCode().equals(normalize(spec.uomCode()))) throw invalid("组件需求单位与物料基础单位不一致");
                if (source != null && !source.uomCode().equals(normalize(spec.uomCode()))) throw invalid("转移需求单位与来源任务单位不一致");
                BigDecimal required = quantity(spec.requiredQtyPerUnit().multiply(line.demandQty()));
                MaterialDemand demand = new MaterialDemand(stableId(target.id(), "demand", String.valueOf(spec.demandNo())),
                        target.id(), spec.demandNo(), spec.itemId(), source == null ? null : source.id(), spec.demandType(),
                        required, normalize(spec.uomCode()), spec.transferBatchQty(), "UNAVAILABLE", null, 0);
                orders.insertMaterialDemand(demand, actor);
                sink.add(demand);
            }
        }
    }

    private ProductionOrder normalizeAndValidate(ProductionOrder input)
    {
        String orderNo = normalize(input.orderNo());
        String source = input.sourceSystem() == null || input.sourceSystem().isBlank() ? "LOCAL" : normalize(input.sourceSystem());
        if (!CODE.matcher(orderNo).matches() || !SOURCE.matcher(source).matches()) throw invalid("订单号或来源系统编码无效");
        if (input.priority() < 1 || input.priority() > 100 || input.lines().isEmpty()) throw invalid("优先级必须为 1 到 100，且订单至少包含一个产品行");
        if (!"LOCAL".equals(source) && (input.externalId() == null || input.externalId().isBlank())) throw invalid("外部订单必须提供 externalId 幂等键");
        String id = input.id() == null ? (input.externalId() == null ? newId() : stableId(source, input.externalId())) : input.id();
        Set<Integer> lineNos = new HashSet<>();
        List<OrderLine> lines = input.lines().stream().map(line -> {
            if (line.lineNo() <= 0 || !lineNos.add(line.lineNo()) || line.demandQty() == null || line.demandQty().signum() <= 0)
                throw invalid("订单行号必须唯一且需求数量大于零");
            String lineId = line.id() == null ? stableId(id, "line", String.valueOf(line.lineNo())) : line.id();
            Item item = routings.findItem(line.itemId()).orElseThrow(() -> notFound("订单行产品不存在"));
            RouteVersion route = routings.findRoute(line.routeVersionId()).orElseThrow(() -> notFound("订单行路线不存在"));
            String uom = normalize(line.uomCode());
            if (!"ACTIVE".equals(item.status()) || !item.id().equals(route.itemId()))
                throw invalid("订单行必须引用同一已启用产品的路线版本");
            if (!Set.of("DRAFT", "ACTIVE").contains(route.status())) throw invalid("订单行不能引用已退役路线");
            if (!item.baseUomCode().equals(uom)) throw objectValidation("UNIT_MISMATCH", "ORDER_LINE", lineId,
                    "uomCode", "订单行单位与产品基础单位不一致");
            validateSnapshots(line, input.lines(), lineId);
            List<ComponentSpec> components = line.components().stream().map(spec -> new ComponentSpec(spec.demandNo(),
                    normalize(spec.targetNodeCode()), spec.itemId(), spec.sourceLineNo(),
                    spec.sourceNodeCode() == null ? null : normalize(spec.sourceNodeCode()), spec.demandType(),
                    quantity(spec.requiredQtyPerUnit()), normalize(spec.uomCode()), nullableQuantity(spec.transferBatchQty()))).toList();
            List<LineDependencySpec> dependencies = line.dependencies().stream().map(spec -> new LineDependencySpec(
                    spec.predecessorLineNo(), normalize(spec.predecessorNodeCode()), normalize(spec.successorNodeCode()),
                    spec.dependencyType(), nullableQuantity(spec.thresholdQty()), nullableQuantity(spec.thresholdRatio()),
                    nullableQuantity(spec.transferBatchQty()), spec.lagSeconds(), spec.consumesOutput())).toList();
            return new OrderLine(lineId, id, line.lineNo(), item.id(), route.id(), quantity(line.demandQty()),
                    uom, line.promisedAt(), line.earliestStartAt(), "DRAFT", components, dependencies, 0);
        }).sorted(Comparator.comparingInt(OrderLine::lineNo)).toList();
        return new ProductionOrder(id, orderNo, source, trim(input.externalId()), trim(input.customerCode()), trim(input.customerName()),
                input.priority(), input.promisedAt(), input.earliestStartAt(), "DRAFT", trim(input.remark()), lines, 0);
    }

    private void validateSnapshots(OrderLine line, List<OrderLine> allLines, String lineId)
    {
        Set<Integer> demandNos = new HashSet<>();
        Set<Integer> knownLines = allLines.stream().map(OrderLine::lineNo).collect(Collectors.toSet());
        Map<Integer, OrderLine> lines = allLines.stream().collect(Collectors.toMap(OrderLine::lineNo, value -> value, (a, b) -> a));
        Set<String> targetNodes = routings.getRouteGraph(line.routeVersionId()).nodes().stream()
                .map(RouteNode::nodeCode).collect(Collectors.toSet());
        for (ComponentSpec spec : line.components())
        {
            if (spec.demandNo() <= 0 || !demandNos.add(spec.demandNo()) || spec.demandType() == null
                    || spec.requiredQtyPerUnit() == null || spec.requiredQtyPerUnit().signum() <= 0)
                throw invalid("组件需求序号必须唯一，且单位用量大于零");
            Item component = routings.findItem(spec.itemId()).orElseThrow(() -> notFound("组件物料不存在"));
            if (!component.baseUomCode().equals(normalize(spec.uomCode()))) throw objectValidation("UNIT_MISMATCH",
                    "ORDER_LINE", lineId, "components.uomCode", "组件单位与物料基础单位不一致");
            if (!targetNodes.contains(normalize(spec.targetNodeCode()))) throw objectValidation("MISSING_EDGE_NODE",
                    "ORDER_LINE", lineId, "components.targetNodeCode", "组件需求引用了目标路线中不存在的节点");
            if ((spec.demandType() == DemandType.TRANSFER) != (spec.sourceLineNo() != null))
                throw invalid("TRANSFER 组件必须显式引用来源产品行，其他需求不能引用来源行");
            if (spec.sourceLineNo() != null && (!knownLines.contains(spec.sourceLineNo()) || spec.sourceNodeCode() == null))
                throw invalid("组件来源产品行或节点不存在");
            if (spec.sourceLineNo() != null)
            {
                OrderLine sourceLine = lines.get(spec.sourceLineNo());
                Set<String> sourceNodes = routings.getRouteGraph(sourceLine.routeVersionId()).nodes().stream()
                        .map(RouteNode::nodeCode).collect(Collectors.toSet());
                if (!sourceNodes.contains(normalize(spec.sourceNodeCode()))) throw objectValidation("MISSING_EDGE_NODE",
                        "ORDER_LINE", lineId, "components.sourceNodeCode", "组件来源节点不在来源产品路线中");
                if (!normalize(sourceLine.uomCode()).equals(normalize(spec.uomCode()))) throw objectValidation("UNIT_MISMATCH",
                        "ORDER_LINE", lineId, "components.uomCode", "转移组件单位与来源产品行单位不一致");
            }
            if (spec.transferBatchQty() != null && spec.transferBatchQty().signum() <= 0) throw invalid("组件转移批必须大于零");
        }
        for (LineDependencySpec spec : line.dependencies())
        {
            if (!knownLines.contains(spec.predecessorLineNo()) || spec.predecessorNodeCode() == null
                    || spec.successorNodeCode() == null || spec.dependencyType() == null)
                throw invalid("跨产品依赖必须显式指定已有来源行和两端节点");
            if (spec.predecessorLineNo() == line.lineNo()) throw invalid("同一产品行内依赖必须在路线图维护，不能重复写成跨产品依赖");
            if (!targetNodes.contains(normalize(spec.successorNodeCode()))) throw objectValidation("MISSING_EDGE_NODE",
                    "ORDER_LINE", lineId, "dependencies.successorNodeCode", "跨产品依赖目标节点不在目标路线中");
            OrderLine sourceLine = lines.get(spec.predecessorLineNo());
            Set<String> sourceNodes = routings.getRouteGraph(sourceLine.routeVersionId()).nodes().stream()
                    .map(RouteNode::nodeCode).collect(Collectors.toSet());
            if (!sourceNodes.contains(normalize(spec.predecessorNodeCode()))) throw objectValidation("MISSING_EDGE_NODE",
                    "ORDER_LINE", lineId, "dependencies.predecessorNodeCode", "跨产品依赖来源节点不在来源路线中");
            if (spec.dependencyType() == DependencyType.QUANTITY)
            {
                boolean qty = spec.thresholdQty() != null && spec.thresholdQty().signum() > 0;
                boolean ratio = spec.thresholdRatio() != null && spec.thresholdRatio().signum() > 0
                        && spec.thresholdRatio().compareTo(BigDecimal.ONE) <= 0;
                if (qty == ratio) throw invalid("数量依赖必须且只能设置数量或比例门槛");
            }
            if (spec.dependencyType() == DependencyType.FINISH && (spec.thresholdQty() != null
                    || spec.thresholdRatio() != null || spec.transferBatchQty() != null))
                throw invalid("完成依赖不能携带数量门槛或转移批");
            if (spec.dependencyType() == DependencyType.SAME_START && (spec.thresholdQty() != null
                    || spec.thresholdRatio() != null || spec.transferBatchQty() != null || spec.lagSeconds() != 0
                    || spec.consumesOutput())) throw invalid("SAME_START 不能携带数量、转移批、消耗或 lag");
        }
    }

    private void validateExpandedThreshold(DependencyType type, BigDecimal qty, BigDecimal transfer,
            BigDecimal predecessorQty, String objectId)
    {
        if (type == DependencyType.QUANTITY)
        {
            if (qty != null && qty.compareTo(predecessorQty) > 0)
                throw objectValidation("THRESHOLD_EXCEEDED", "DEPENDENCY", objectId, "thresholdQty", "数量门槛超过前置任务冻结数量");
            if (transfer != null && transfer.compareTo(predecessorQty) > 0)
                throw objectValidation("TRANSFER_BATCH_EXCEEDED", "DEPENDENCY", objectId, "transferBatchQty", "转移批超过前置任务冻结数量");
        }
    }

    private ExpansionResult loadExpansion(ProductionOrder order, boolean reused)
    {
        return new ExpansionResult(order, orders.listLots(order.id()), orders.listTasks(order.id()),
                orders.listDependencies(order.id()), orders.listMaterialDemands(order.id()), reused);
    }

    private ProductionLot normalLot(ProductionOrder order, OrderLine line)
    {
        String id = stableId(line.id(), "lot", "NORMAL");
        return new ProductionLot(id, line.id(), line.itemId(), line.routeVersionId(), null,
                boundedCode(order.orderNo(), String.format("%03d", line.lineNo())), LotType.NORMAL,
                line.demandQty(), line.uomCode(), null, "PLANNED", 0);
    }

    private Task task(Map<Integer, Map<String, Task>> tasks, int lineNo, String nodeCode)
    {
        Map<String, Task> line = tasks.get(lineNo);
        Task task = line == null ? null : line.get(normalize(nodeCode));
        if (task == null) throw invalid("依赖或组件引用了不存在的产品行/路线节点: " + lineNo + "/" + nodeCode);
        return task;
    }

    private boolean samePayload(ProductionOrder left, ProductionOrder right)
    {
        if (!Objects.equals(left.orderNo(), right.orderNo()) || left.priority() != right.priority()
                || !Objects.equals(left.customerCode(), right.customerCode()) || !Objects.equals(left.customerName(), right.customerName())
                || !Objects.equals(left.promisedAt(), right.promisedAt()) || !Objects.equals(left.earliestStartAt(), right.earliestStartAt())
                || left.lines().size() != right.lines().size()) return false;
        for (int index = 0; index < left.lines().size(); index++)
        {
            OrderLine a = left.lines().get(index), b = right.lines().get(index);
            if (a.lineNo() != b.lineNo() || !a.itemId().equals(b.itemId()) || !a.routeVersionId().equals(b.routeVersionId())
                    || a.demandQty().compareTo(b.demandQty()) != 0 || !a.uomCode().equals(b.uomCode())
                    || !Objects.equals(a.promisedAt(), b.promisedAt()) || !Objects.equals(a.earliestStartAt(), b.earliestStartAt())
                    || !a.components().equals(b.components()) || !a.dependencies().equals(b.dependencies())) return false;
        }
        return true;
    }

    private Set<String> edgeKeys(RouteGraph graph)
    {
        Map<String, String> codes = graph.nodes().stream().collect(Collectors.toMap(RouteNode::id, RouteNode::nodeCode));
        return graph.edges().stream().map(edge -> codes.get(edge.predecessorNodeId()) + "->" + codes.get(edge.successorNodeId())
                + ":" + edge.dependencyType()).collect(Collectors.toSet());
    }

    private List<String> difference(Set<String> left, Set<String> right) { return left.stream().filter(value -> !right.contains(value)).sorted().toList(); }
    private ProductionOrder requireOrder(String id) { return orders.findOrder(id).orElseThrow(() -> notFound("订单不存在")); }
    private ApsBusinessException invalid(String message) { return new ApsBusinessException(ApsErrorCode.INVALID_REQUEST, message); }
    private ApsBusinessException notFound(String message) { return new ApsBusinessException(ApsErrorCode.NOT_FOUND, message); }
    private ApsBusinessException stale(String message) { return new ApsBusinessException(ApsErrorCode.STALE_VERSION, message); }
    private ApsValidationException objectValidation(String code, String objectType, String id, String field, String message) { return new ApsValidationException(ApsErrorCode.INVALID_REQUEST, message, List.of(new ApsValidationIssue(code, objectType, id, field, message))); }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String newId() { return UUID.randomUUID().toString(); }
    private String stableId(String... values) { return UUID.nameUUIDFromBytes(String.join("\u0000", values).getBytes(StandardCharsets.UTF_8)).toString(); }
    private String boundedCode(String... parts)
    {
        String value = normalize(String.join("-", parts));
        if (value.length() <= 64) return value;
        String suffix = stableId(value).replace("-", "").substring(0, 16).toUpperCase();
        return value.substring(0, 47) + "-" + suffix;
    }
    private BigDecimal quantity(BigDecimal value)
    {
        BigDecimal normalized = value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        int integerDigits = Math.max(0, normalized.precision() - normalized.scale());
        if (normalized.signum() <= 0 || integerDigits > 12) throw invalid("数量必须在 DECIMAL(18,6) 有效范围内");
        return normalized;
    }
    private BigDecimal nullableQuantity(BigDecimal value) { return value == null ? null : quantity(value); }
}
