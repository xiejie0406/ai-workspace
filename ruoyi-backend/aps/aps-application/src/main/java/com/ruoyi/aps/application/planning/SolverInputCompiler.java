package com.ruoyi.aps.application.planning;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.foundation.ApsValidationException;
import com.ruoyi.aps.application.foundation.ApsValidationIssue;
import com.ruoyi.aps.application.order.OrderCatalog.OrderLine;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionOrder;
import com.ruoyi.aps.application.order.OrderCatalog.Task;
import com.ruoyi.aps.application.order.OrderCatalog.TaskDependency;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.execution.ExecutionRepository;
import com.ruoyi.aps.application.execution.QuantityRepository;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceCatalog.Resource;
import com.ruoyi.aps.application.resource.ResourceCatalog.Skill;
import com.ruoyi.aps.application.resource.ResourceCatalog.Workshop;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationMode;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingRepository;
import com.ruoyi.aps.domain.resource.ResourceCalendarService.NetWindow;
import com.ruoyi.aps.solver.contract.SolverInput;
import com.ruoyi.aps.solver.contract.SolverInputCodec;
import com.ruoyi.aps.solver.contract.ValidationResult;
import com.ruoyi.aps.validator.IndependentConstraintValidator;

/** 把 M01～M18 的数据库事实编译成排序稳定、带 JCS hash 的 SolverInput v1。 */
public final class SolverInputCompiler
{
    private final ResourceManagementService resources;
    private final OrderRepository orders;
    private final RoutingRepository routings;
    private final PlanRepository plans;
    private final ExecutionRepository executions;
    private final QuantityRepository quantities;
    private final String configuredSiteCode;
    private final String configuredZoneId;
    private final SolverInputCodec codec = new SolverInputCodec();
    private final IndependentConstraintValidator validator = new IndependentConstraintValidator();

    public SolverInputCompiler(ResourceManagementService resources, OrderRepository orders, RoutingRepository routings)
    {
        this(resources, orders, routings, null, null, null);
    }

    public SolverInputCompiler(ResourceManagementService resources, OrderRepository orders,
            RoutingRepository routings, PlanRepository plans)
    {
        this(resources, orders, routings, plans, null, null);
    }

    public SolverInputCompiler(ResourceManagementService resources, OrderRepository orders,
            RoutingRepository routings, PlanRepository plans, ExecutionRepository executions,
            QuantityRepository quantities)
    {
        this(resources,orders,routings,plans,executions,quantities,null,null);
    }
    public SolverInputCompiler(ResourceManagementService resources, OrderRepository orders,
            RoutingRepository routings, PlanRepository plans, ExecutionRepository executions,
            QuantityRepository quantities,String siteCode,String zoneId)
    {
        this.resources = resources;
        this.orders = orders;
        this.routings = routings;
        this.plans = plans;
        this.executions = executions;
        this.quantities = quantities;
        this.configuredSiteCode=siteCode;this.configuredZoneId=zoneId;
    }

    public Compilation compile(ResourceAccessScope access, CompileRequest request)
    {
        validateRequest(request);
        Set<String> requestedOrders = new LinkedHashSet<>(request.orderIds());
        List<ProductionOrder> selected = orders.listOrders().stream().filter(order -> requestedOrders.contains(order.id()))
                .filter(order -> orders.isOrderInScope(access, order.id()))
                .sorted(Comparator.comparing(ProductionOrder::id)).toList();
        List<ApsValidationIssue> selectionIssues = new ArrayList<>();
        requestedOrders.stream().filter(id -> selected.stream().noneMatch(order -> order.id().equals(id)))
                .forEach(id -> selectionIssues.add(new ApsValidationIssue("NOT_FOUND", "ORDER", id, null, "计划范围中的订单不存在")));
        selected.stream().filter(order -> !Set.of("RELEASED", "IN_PRODUCTION").contains(order.status())).forEach(order ->
                selectionIssues.add(new ApsValidationIssue("ORDER_NOT_RELEASED", "ORDER", order.id(), "status",
                        "只有已释放或生产中的订单可进入求解输入")));
        Map<String, OrderLine> lines = selected.stream().flatMap(order -> order.lines().stream())
                .collect(java.util.stream.Collectors.toMap(OrderLine::id, value -> value));
        List<Task> sourceTasks = selected.stream().flatMap(order -> orders.listTasks(order.id()).stream())
                .sorted(Comparator.comparing(Task::id)).toList();
        selected.stream().filter(order -> orders.listLots(order.id()).isEmpty() || orders.listTasks(order.id()).isEmpty())
                .forEach(order -> selectionIssues.add(new ApsValidationIssue("NO_ROUTE", "ORDER", order.id(), "lines", "订单尚未形成可追溯任务网络")));
        if (!selectionIssues.isEmpty())
            throw new ApsValidationException(ApsErrorCode.INVALID_REQUEST, "计划范围尚未通过数据就绪检查", selectionIssues);

        ResourceCompilation resourceCompilation = compileResources(access, request);
        Map<String, String> lineByTaskId = new HashMap<>();
        for (OrderLine line : lines.values())
            orders.listTasksByLine(line.id()).forEach(task -> lineByTaskId.put(task.id(), line.id()));
        List<String> sourceTaskIds = sourceTasks.stream().map(Task::id).toList();
        Map<String, BigDecimal> processedByTask = executions == null ? Map.of()
                : executions.processedQuantityByTask(sourceTaskIds);
        List<ApsValidationIssue> quantityIssues = sourceTasks.stream().filter(task ->
                processedByTask.getOrDefault(task.id(), BigDecimal.ZERO).compareTo(task.taskQty()) > 0)
                .map(task -> new ApsValidationIssue("QUANTITY_NOT_RELEASED", "TASK", task.id(), "taskQty",
                        "有效累计加工量超过任务总量，不能生成重排输入"))
                .toList();
        if (!quantityIssues.isEmpty())
            throw new ApsValidationException(ApsErrorCode.INVALID_REQUEST, "执行数量与任务数量不守恒", quantityIssues);
        List<SolverInput.Task> solverTasks = sourceTasks.stream().map(task -> {
            SolverInput.Task compiled = compileTask(task, lines.get(lineByTaskId.get(task.id())),
                    resourceCompilation, request);
            BigDecimal outstanding = task.taskQty().subtract(
                    processedByTask.getOrDefault(task.id(), BigDecimal.ZERO));
            return outstanding.signum() <= 0 ? null : withQuantity(compiled, outstanding);
        }).filter(Objects::nonNull).toList();
        Set<String> solverTaskIds = solverTasks.stream().map(SolverInput.Task::taskId)
                .collect(java.util.stream.Collectors.toSet());
        List<SolverInput.Dependency> dependencies = selected.stream().flatMap(order -> orders.listDependencies(order.id()).stream())
                .filter(value -> solverTaskIds.contains(value.predecessorTaskId())
                        && solverTaskIds.contains(value.successorTaskId()))
                .sorted(Comparator.comparing(TaskDependency::id)).map(this::dependency).toList();
        List<SolverInput.MaterialDemand> demands = selected.stream().flatMap(order -> orders.listMaterialDemands(order.id()).stream())
                .filter(value -> solverTaskIds.contains(value.targetTaskId()))
                .sorted(Comparator.comparing(com.ruoyi.aps.application.order.OrderCatalog.MaterialDemand::id))
                .map(value -> new SolverInput.MaterialDemand(value.id(), value.targetTaskId(), value.itemId(), value.sourceTaskId(),
                        SolverInput.DemandType.valueOf(value.demandType().name()), decimal(value.requiredQty()), value.uomCode(),
                        nullableDecimal(value.transferBatchQty()), SolverInput.MaterialStatus.valueOf(value.materialStatus()), value.readyAt()))
                .toList();
        List<SolverInput.MaterialSupply> plannedSupplies = demands.stream().filter(value -> value.sourceTaskId() != null)
                .collect(java.util.stream.Collectors.toMap(SolverInput.MaterialDemand::sourceTaskId, value ->
                        new SolverInput.MaterialSupply(stableId("supply", value.sourceTaskId(), value.itemId()), value.itemId(),
                                "TASK_OUTPUT", value.sourceTaskId(), null,
                                solverTasks.stream().filter(task -> task.taskId().equals(value.sourceTaskId())).findFirst()
                                        .map(SolverInput.Task::quantity).orElse(value.requiredQuantity()),
                                value.uomCode(), "PLANNED_QUALIFIED"), (left, right) -> left))
                .values().stream().sorted(Comparator.comparing(SolverInput.MaterialSupply::supplyId)).toList();
        List<SolverInput.MaterialSupply> supplies = new ArrayList<>(plannedSupplies);
        if (quantities != null)
            supplies.addAll(quantities.listReleasedSuppliesForTasks(sourceTasks.stream().map(Task::id).toList())
                    .stream().map(value -> new SolverInput.MaterialSupply(value.outputLotId(), value.itemId(),
                            "EXECUTION_RELEASED", value.sourceTaskId(),
                            value.availableAt() == null ? request.capturedAt() : value.availableAt(),
                            decimal(value.quantity()), value.uomCode(), "AVAILABLE")).toList());
        supplies.sort(Comparator.comparing(SolverInput.MaterialSupply::supplyId));
        List<SolverInput.SharedBatchCandidate> sharedBatches = compileSharedBatches(request, sourceTasks,
                solverTasks);
        BaselineCompilation baseline = compileBaseVersion(request, solverTasks, sharedBatches);
        long executionRevision = executions == null ? request.executionRevision()
                : executions.currentExecutionRevision();
        List<SolverInput.ActualOccupancy> actualOccupancies = executions == null ? List.of()
                : executions.listPlanningOccupancies(sourceTaskIds,
                        request.capturedAt(), request.horizonEndAt());

        SolverInput source = new SolverInput("1.0", "SOLVER_INPUT", request.requestId(), request.planVersionId(),
                request.capturedAt(), request.definitionRevision(), executionRevision, "0".repeat(64),
                "SHA-256", "JCS-RFC8785", request.modelVersion(),
                new SolverInput.Scope(request.siteCode(), request.workshopIds().stream().sorted().toList()),
                new SolverInput.Horizon(request.horizonStartAt(), request.detailEndAt(), request.horizonEndAt(),
                        request.planningAnchorAt(), request.timeUnitSeconds(), request.displayTimeZone()),
                baseline.baseVersion(), new SolverInput.Parameters("FORWARD", request.maxSolveSeconds(), request.randomSeed(),
                        request.solverSearchThreads(), request.absoluteGapLimit(), request.relativeGapLimit()),
                resourceCompilation.resources(), resourceCompilation.windows(), solverTasks, dependencies, supplies,
                demands, sharedBatches, baseline.locks(), actualOccupancies);
        SolverInputCodec.EncodedInput encoded = codec.encodeWithHash(source);
        ValidationResult readiness = validator.validateInput(encoded.value(), encoded.bytes(), request.capturedAt());
        return new Compilation(encoded.value(), encoded.bytes(), readiness);
    }

    private BaselineCompilation compileBaseVersion(CompileRequest request, List<SolverInput.Task> tasks,
            List<SolverInput.SharedBatchCandidate> sharedBatches)
    {
        BaseVersionRequest requested = request.baseVersion();
        if (requested == null) return new BaselineCompilation(null, List.of());
        if (plans == null) throw invalidBase(requested.planVersionId(), "当前运行边界没有基线版本仓储");
        PlanRepository.BaselineSnapshot snapshot = plans.findCurrentPublishedBaseline(requested.planVersionId())
                .orElseThrow(() -> invalidBase(requested.planVersionId(), "基线必须是当前正式版本"));
        if (!snapshot.plan().inputHash().equals(requested.inputHash()))
            throw invalidBase(requested.planVersionId(), "基线 inputHash 已变化，请刷新后重试");
        Set<String> taskIds = tasks.stream().map(SolverInput.Task::taskId).collect(java.util.stream.Collectors.toSet());
        Set<String> excludedTaskIds = Set.copyOf(requested.excludedTaskIds());
        Set<String> sharedKeys = sharedBatches.stream().map(value -> value.members().stream()
                .map(SolverInput.SharedBatchMember::taskId).sorted().collect(java.util.stream.Collectors.joining("\u0000")))
                .collect(java.util.stream.Collectors.toSet());
        List<SolverInput.BaselineJob> jobs = new ArrayList<>();
        Map<String, CurrentJob> currentJobs = new HashMap<>();
        for (PlanRepository.BaselineJob job : snapshot.jobs())
        {
            long excludedMembers = job.memberTaskIds().stream().filter(excludedTaskIds::contains).count();
            if (excludedMembers > 0)
            {
                if (excludedMembers != job.memberTaskIds().size())
                    throw invalidBase(job.jobId(), "结构调整不能只排除共享基线作业的部分成员");
                continue;
            }
            long selectedMembers = job.memberTaskIds().stream().filter(taskIds::contains).count();
            if (selectedMembers == 0) continue;
            if (selectedMembers != job.memberTaskIds().size())
                throw invalidBase(job.jobId(), "共享基线作业不能只选择部分成员进入重排范围");
            String members = job.memberTaskIds().stream().sorted()
                    .collect(java.util.stream.Collectors.joining("\u0000"));
            if (job.memberTaskIds().size() > 1 && !sharedKeys.contains(members))
                throw invalidBase(job.jobId(), "多成员基线作业必须在本次请求中保持同一固定共享批");
            SolverInput.Task representative = tasks.stream()
                    .filter(value -> job.memberTaskIds().contains(value.taskId())).findFirst().orElseThrow();
            String sourceId = representative.taskId();
            if (job.memberTaskIds().size() > 1)
                sourceId = sharedBatches.stream().filter(value -> value.members().stream()
                        .map(SolverInput.SharedBatchMember::taskId).collect(java.util.stream.Collectors.toSet())
                        .equals(Set.copyOf(job.memberTaskIds()))).map(SolverInput.SharedBatchCandidate::candidateId)
                        .findFirst().orElseThrow();
            currentJobs.put(job.jobId(), new CurrentJob(stableId("job", request.planVersionId(), sourceId),
                    representative));
            jobs.add(new SolverInput.BaselineJob(job.jobId(), job.memberTaskIds(), job.startAt(), job.endAt(),
                    job.resourceIds()));
        }
        if (jobs.isEmpty())
        {
            if (!excludedTaskIds.isEmpty()) return new BaselineCompilation(null, List.of());
            throw invalidBase(requested.planVersionId(), "基线与当前订单范围没有可映射作业");
        }
        List<SolverInput.PlanLock> locks = snapshot.locks().stream()
                .filter(lock -> currentJobs.containsKey(lock.jobId()))
                .map(lock -> compileBaselineLock(lock, currentJobs))
                .sorted(Comparator.comparing(SolverInput.PlanLock::lockId)).toList();
        return new BaselineCompilation(new SolverInput.BaseVersion(snapshot.plan().id(), snapshot.plan().inputHash(),
                requested.freezeEndAt(), jobs.stream().sorted(Comparator.comparing(
                        SolverInput.BaselineJob::baselineJobId)).toList()), locks);
    }

    private SolverInput.PlanLock compileBaselineLock(PlanRepository.BaselineLock lock,
            Map<String, CurrentJob> currentJobs)
    {
        CurrentJob current = currentJobs.get(lock.jobId());
        if (current == null) throw invalidBase(lock.lockId(), "基线锁指向本次范围之外的作业");
        String targetId = current.jobId();
        if (!"JOB".equals(lock.targetType()))
        {
            if (lock.phaseId() == null || lock.segmentNo() == null)
                throw invalidBase(lock.lockId(), "分段或分配锁缺少确定的阶段和分段序号");
            List<SolverInput.Phase> phases = current.task().phases().stream()
                    .sorted(Comparator.comparingInt(SolverInput.Phase::sequenceNo)).toList();
            int phaseNo = -1;
            for (int index = 0; index < phases.size(); index++)
                if (phases.get(index).phaseId().equals(lock.phaseId())) { phaseNo = index + 1; break; }
            if (phaseNo < 0) throw invalidBase(lock.lockId(), "基线锁阶段已不在当前冻结工艺中");
            targetId = stableId("segment", current.jobId(), lock.phaseId(), Integer.toString(phaseNo),
                    Integer.toString(lock.segmentNo()));
            if ("ALLOCATION".equals(lock.targetType()))
            {
                if (lock.requirementId() == null || lock.seatNo() == null || lock.allocationResourceId() == null)
                    throw invalidBase(lock.lockId(), "分配锁缺少需求、席位或原资源身份");
                targetId = stableId("allocation", targetId, lock.requirementId(),
                        Integer.toString(lock.seatNo()), lock.allocationResourceId());
            }
        }
        return new SolverInput.PlanLock(lock.lockId(), lock.targetType(), targetId, lock.lockType(),
                lock.lockedStartAt(), lock.lockedEndAt(),
                lock.lockedResourceId() == null ? List.of() : List.of(lock.lockedResourceId()), lock.reason());
    }

    private ApsValidationException invalidBase(String objectId, String detail)
    {
        return new ApsValidationException(ApsErrorCode.INVALID_REQUEST, "重排基线无效",
                List.of(new ApsValidationIssue("STALE_INPUT", "PLAN_VERSION", objectId,
                        "baseVersion", detail)));
    }

    private List<SolverInput.SharedBatchCandidate> compileSharedBatches(CompileRequest request, List<Task> tasks,
            List<SolverInput.Task> solverTasks)
    {
        Map<String, Task> taskById = tasks.stream().collect(java.util.stream.Collectors.toMap(Task::id, value -> value));
        Map<String, SolverInput.Task> solverTaskById = solverTasks.stream().collect(
                java.util.stream.Collectors.toMap(SolverInput.Task::taskId, value -> value));
        Set<String> assigned = new HashSet<>();
        List<SolverInput.SharedBatchCandidate> result = new ArrayList<>();
        for (SharedBatchRequest batch : request.sharedBatches().stream()
                .sorted(Comparator.comparing(value -> String.join("\u0000", value.members().stream()
                        .map(SharedBatchMemberRequest::taskId).sorted().toList()))).toList())
        {
            if (batch.compatibilityKey() == null || batch.compatibilityKey().isBlank() || batch.members().size() < 2)
                throw invalidBatch("共享批必须提供兼容键和至少两个成员");
            if (batch.members().stream().map(SharedBatchMemberRequest::taskId).distinct().count() != batch.members().size())
                throw invalidBatch("共享批成员不能重复");
            List<Task> members = batch.members().stream().map(member -> taskById.get(member.taskId())).toList();
            if (members.stream().anyMatch(Objects::isNull)) throw invalidBatch("共享批成员必须属于当前计划订单范围");
            Task first = members.get(0);
            OperationSpec operation = routings.findOperation(first.operationSpecId()).orElseThrow(() ->
                    invalidBatch("共享批工序不存在"));
            boolean identityMismatch = members.stream().anyMatch(task -> !task.operationSpecId().equals(first.operationSpecId())
                    || !Objects.equals(task.workCenterId(), first.workCenterId()) || !task.uomCode().equals(first.uomCode()));
            if (identityMismatch || operation.mode() != OperationMode.BATCH
                    || operation.batchCapacity() == null || !first.uomCode().equals(operation.batchUomCode()))
                throw invalidBatch("共享批成员必须属于同一批处理工序、工作中心和容量单位");
            Map<String, SharedBatchMemberRequest> requestedMembers = batch.members().stream().collect(
                    java.util.stream.Collectors.toMap(SharedBatchMemberRequest::taskId, value -> value));
            if (members.stream().anyMatch(task -> !solverTaskById.containsKey(task.id())
                    || requestedMembers.get(task.id()).quantity() == null
                    || requestedMembers.get(task.id()).quantity().compareTo(
                            new BigDecimal(solverTaskById.get(task.id()).quantity())) != 0))
                throw invalidBatch("P0 共享批成员数量必须完整等于扣除有效加工量后的任务待排量，部分数量须先受控拆批");
            if (members.stream().anyMatch(task -> !assigned.add(task.id())))
                throw invalidBatch("同一任务不能重复归属多个固定共享批");
            BigDecimal total = batch.members().stream().map(SharedBatchMemberRequest::quantity)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(operation.batchCapacity()) > 0) throw invalidBatch("共享批成员数量合计超过工序批容量");
            List<OperationPhase> runPhases = operation.phases().stream()
                    .filter(phase -> "RUN".equals(phase.phaseType().name())).toList();
            if (runPhases.size() != 1 || runPhases.get(0).secondsPerUnit().signum() != 0)
                throw invalidBatch("P0 共享批必须且只能有一个固定周期 RUN 阶段，不能按成员时长相加");
            int cycle = runPhases.get(0).fixedSeconds();
            if (cycle <= 0) throw invalidBatch("共享批必须配置正数固定 RUN 周期");
            List<SolverInput.SharedBatchMember> compiledMembers = batch.members().stream()
                    .sorted(Comparator.comparing(SharedBatchMemberRequest::taskId))
                    .map(member -> new SolverInput.SharedBatchMember(member.taskId(), decimal(member.quantity()), first.uomCode()))
                    .toList();
            String candidateId = stableId("manual-batch", request.planVersionId(), batch.compatibilityKey(),
                    String.join("\u0000", compiledMembers.stream().map(SolverInput.SharedBatchMember::taskId).toList()));
            result.add(new SolverInput.SharedBatchCandidate(candidateId, "MANUAL_FIXED", first.operationSpecId(),
                    first.workCenterId(), batch.compatibilityKey().trim(), decimal(operation.batchCapacity()),
                    operation.batchUomCode(), cycle, compiledMembers));
        }
        return List.copyOf(result);
    }

    private ApsValidationException invalidBatch(String detail)
    {
        return new ApsValidationException(ApsErrorCode.INVALID_REQUEST, "共享批配置无效",
                List.of(new ApsValidationIssue("BATCH_INCOMPATIBLE", "PLAN_VERSION",
                        "00000000-0000-4000-8000-000000000000",
                        "sharedBatches", detail)));
    }

    private ResourceCompilation compileResources(ResourceAccessScope access, CompileRequest request)
    {
        Set<String> requested = Set.copyOf(request.workshopIds());
        List<Workshop> workshops = resources.listWorkshops(access).stream().filter(value -> requested.contains(value.id()))
                .sorted(Comparator.comparing(Workshop::id)).toList();
        if (workshops.size() != requested.size()) throw new ApsValidationException(ApsErrorCode.UNAUTHORIZED,
                "计划范围包含无权访问或不存在的车间", List.of());
        List<SolverInput.Resource> compiled = new ArrayList<>();
        List<SolverInput.AvailabilityWindow> windows = new ArrayList<>();
        for (Workshop workshop : workshops)
        {
            for (Resource resource : resources.listResources(access, workshop.id(), null).stream()
                    .filter(value -> "ACTIVE".equals(value.status())).sorted(Comparator.comparing(Resource::id)).toList())
            {
                List<Skill> skills = resources.listSkills(access, resource.id());
                compiled.add(new SolverInput.Resource(resource.id(), SolverInput.ResourceType.valueOf(resource.type().name()),
                        resource.workCenterId(), resource.capacityValue().compareTo(BigDecimal.ONE) <= 0,
                        decimal(resource.capacityValue()), resource.capacityUomCode(), skills.stream()
                                .sorted(Comparator.comparing(Skill::id)).map(skill -> new SolverInput.ResourceSkill(skill.id(),
                                        skill.code(), skill.level(), skill.validFrom(), skill.validTo(), skill.status())).toList()));
                for (NetWindow window : resources.netAvailability(access, resource.id(), request.horizonStartAt(), request.horizonEndAt()))
                    windows.add(new SolverInput.AvailabilityWindow(stableId("net-window", resource.id(), window.start().toString(), window.end().toString()),
                            resource.id(), window.start(), window.end(), decimal(resource.capacityValue().multiply(window.capacityRatio()))));
            }
        }
        return new ResourceCompilation(compiled.stream().sorted(Comparator.comparing(SolverInput.Resource::resourceId)).toList(),
                windows.stream().sorted(Comparator.comparing(SolverInput.AvailabilityWindow::resourceId)
                        .thenComparing(SolverInput.AvailabilityWindow::startAt)).toList());
    }

    private SolverInput.Task compileTask(Task task, OrderLine line, ResourceCompilation resources, CompileRequest request)
    {
        if (line == null) throw new IllegalStateException("任务缺少订单行: " + task.id());
        OperationSpec operation = routings.findOperation(task.operationSpecId()).orElseThrow(() ->
                new ApsValidationException(ApsErrorCode.INVALID_REQUEST, "任务工序快照缺失", List.of(
                        new ApsValidationIssue("MISSING_DURATION", "TASK", task.id(), "operationSpecId", "工序不存在"))));
        Instant earliest = line.earliestStartAt() == null ? request.planningAnchorAt() : max(line.earliestStartAt(), request.horizonStartAt());
        List<SolverInput.Phase> phases = operation.phases().stream().sorted(Comparator.comparingInt(OperationPhase::phaseNo))
                .map(phase -> new SolverInput.Phase(phase.id(), SolverInput.PhaseType.valueOf(phase.phaseType().name()),
                        phase.phaseNo(), phase.fixedSeconds(), decimal(phase.secondsPerUnit()),
                        task.interruptible() && phase.phaseType() == com.ruoyi.aps.application.routing.RoutingCatalog.PhaseType.RUN,
                        phase.maxSegments(), phase.minSegmentSeconds(), phase.resumeSetupSeconds(),
                        SolverInput.SegmentResourcePolicy.valueOf(phase.segmentResourcePolicy().name()),
                        phase.requirements().stream().filter(requirement -> !requirement.optional())
                                .sorted(Comparator.comparingInt(ResourceRequirement::requirementNo))
                                .map(requirement -> requirement(requirement, task, earliest, resources)).toList())).toList();
        return new SolverInput.Task(task.id(), line.id(), task.operationSpecId(), task.workCenterId(),
                line.earliestStartAt() != null && !line.earliestStartAt().isBefore(request.detailEndAt())
                        ? SolverInput.PlanningClass.FUTURE_CARRY_FORWARD : SolverInput.PlanningClass.MANDATORY_DETAIL,
                decimal(task.taskQty()), task.uomCode(), earliest, line.promisedAt(), phases);
    }

    private SolverInput.Task withQuantity(SolverInput.Task task, BigDecimal quantity)
    {
        return new SolverInput.Task(task.taskId(), task.orderLineId(), task.operationSpecId(), task.workCenterId(),
                task.planningClass(), decimal(quantity), task.uomCode(), task.earliestStartAt(), task.promisedAt(),
                task.phases());
    }

    private SolverInput.ResourceRequirement requirement(ResourceRequirement requirement, Task task, Instant at,
            ResourceCompilation resources)
    {
        List<String> candidates = resources.resources().stream()
                .filter(resource -> resource.resourceType().name().equals(requirement.resourceType().name()))
                .filter(resource -> requirement.fixedResourceId() == null || requirement.fixedResourceId().equals(resource.resourceId()))
                .filter(resource -> requirement.workCenterId() == null || requirement.workCenterId().equals(resource.workCenterId()))
                .filter(resource -> requirement.requiredSkillCode() == null || resource.skills().stream().anyMatch(skill ->
                        "ACTIVE".equals(skill.status()) && requirement.requiredSkillCode().equals(skill.skillCode())
                                && skill.skillLevel() >= requirement.minimumSkillLevel()
                                && (skill.validFrom() == null || !at.isBefore(skill.validFrom()))
                                && (skill.validTo() == null || at.isBefore(skill.validTo()))))
                .filter(resource -> resources.windows().stream().anyMatch(window -> window.resourceId().equals(resource.resourceId())
                        && window.endAt().isAfter(at))).map(SolverInput.Resource::resourceId).sorted().toList();
        return new SolverInput.ResourceRequirement(requirement.id(), SolverInput.ResourceType.valueOf(requirement.resourceType().name()),
                requirement.seatCount(), "1", requirement.requiredSkillCode(), requirement.minimumSkillLevel(),
                requirement.holdOnPause(), candidates);
    }

    private SolverInput.Dependency dependency(TaskDependency value)
    {
        SolverInput.RelationType type = switch (value.dependencyType()) {
            case FINISH -> SolverInput.RelationType.FINISH_TO_START;
            case QUANTITY -> SolverInput.RelationType.QUANTITY;
            case SAME_START -> SolverInput.RelationType.SAME_START;
        };
        return new SolverInput.Dependency(value.id(), value.predecessorTaskId(), value.successorTaskId(), type,
                value.lagSeconds(), SolverInput.LagBasis.ELAPSED, nullableDecimal(value.thresholdQty()),
                nullableDecimal(value.thresholdRatio()), nullableDecimal(value.transferBatchQty()), value.uomCode(), value.consumesOutput());
    }

    private void validateRequest(CompileRequest request)
    {
        if(configuredSiteCode!=null && (configuredSiteCode.isBlank() || request==null
                || !configuredSiteCode.equals(request.siteCode()) || !Objects.equals(configuredZoneId,request.displayTimeZone())))
            throw new ApsValidationException(ApsErrorCode.INVALID_REQUEST,"排程站点或显示时区与服务端登记不一致",List.of(
                    new ApsValidationIssue("SITE_MISMATCH","INPUT","","siteCode","请恢复站点配置并重新选择范围")));
        if (request == null || request.orderIds() == null || request.orderIds().isEmpty()
                || request.workshopIds() == null || request.workshopIds().isEmpty()
                || request.requestId() == null || !uuid(request.requestId())
                || request.planVersionId() == null || !uuid(request.planVersionId())
                || request.siteCode() == null || request.siteCode().isBlank() || request.siteCode().length() > 64
                || request.capturedAt() == null || request.planningAnchorAt() == null
                || request.definitionRevision() < 0 || request.executionRevision() < 0
                || request.horizonStartAt() == null || request.detailEndAt() == null || request.horizonEndAt() == null
                || request.horizonStartAt().isAfter(request.detailEndAt()) || request.detailEndAt().isAfter(request.horizonEndAt())
                || !request.horizonEndAt().isAfter(request.horizonStartAt())
                || !Set.of(1, 60).contains(request.timeUnitSeconds())
                || request.modelVersion() == null || !request.modelVersion().equals("aps-cpsat-v1")
                || request.maxSolveSeconds() < 1 || request.maxSolveSeconds() > 3600
                || request.solverSearchThreads() < 1 || request.solverSearchThreads() > 32
                || request.absoluteGapLimit() < 0 || request.relativeGapLimit() < 0 || request.relativeGapLimit() > 1
                || request.baseVersion() != null && (request.baseVersion().planVersionId() == null
                        || !uuid(request.baseVersion().planVersionId()) || request.baseVersion().inputHash() == null
                        || !request.baseVersion().inputHash().matches("^[0-9a-f]{64}$")
                        || request.baseVersion().freezeEndAt() == null
                        || request.baseVersion().freezeEndAt().isBefore(request.planningAnchorAt())
                        || request.baseVersion().freezeEndAt().isAfter(request.horizonEndAt()))
                || new HashSet<>(request.orderIds()).size() != request.orderIds().size()
                || new HashSet<>(request.workshopIds()).size() != request.workshopIds().size()
                || request.orderIds().stream().anyMatch(id -> !uuid(id))
                || request.workshopIds().stream().anyMatch(id -> !uuid(id)))
            throw new IllegalArgumentException("计划范围、订单和 horizon 必须完整且有效");
        try { ZoneId.of(request.displayTimeZone()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("displayTimeZone 必须是有效 IANA 时区", exception); }
    }

    private boolean uuid(String value) { try { UUID.fromString(value); return true; } catch (RuntimeException ignored) { return false; } }

    private String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private String nullableDecimal(BigDecimal value) { return value == null ? null : decimal(value); }
    private String stableId(String... parts) { return UUID.nameUUIDFromBytes(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)).toString(); }
    private Instant max(Instant left, Instant right) { return left.isAfter(right) ? left : right; }

    private record ResourceCompilation(List<SolverInput.Resource> resources, List<SolverInput.AvailabilityWindow> windows) { }
    private record CurrentJob(String jobId, SolverInput.Task task) { }
    private record BaselineCompilation(SolverInput.BaseVersion baseVersion, List<SolverInput.PlanLock> locks) { }
    public record Compilation(SolverInput input, byte[] json, ValidationResult readiness)
    {
        public Compilation { json = json.clone(); }
        @Override public byte[] json() { return json.clone(); }
    }
    public record CompileRequest(String requestId, String planVersionId, String siteCode, List<String> workshopIds,
            List<String> orderIds, Instant capturedAt, long definitionRevision, long executionRevision,
            Instant horizonStartAt, Instant detailEndAt, Instant horizonEndAt, Instant planningAnchorAt,
            int timeUnitSeconds, String displayTimeZone, String modelVersion, int maxSolveSeconds,
            int randomSeed, int solverSearchThreads, double absoluteGapLimit, double relativeGapLimit,
            List<SharedBatchRequest> sharedBatches, BaseVersionRequest baseVersion)
    {
        public CompileRequest
        {
            workshopIds = workshopIds == null ? List.of() : List.copyOf(workshopIds);
            orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
            sharedBatches = sharedBatches == null ? List.of() : List.copyOf(sharedBatches);
        }

        public CompileRequest(String requestId, String planVersionId, String siteCode, List<String> workshopIds,
                List<String> orderIds, Instant capturedAt, long definitionRevision, long executionRevision,
                Instant horizonStartAt, Instant detailEndAt, Instant horizonEndAt, Instant planningAnchorAt,
                int timeUnitSeconds, String displayTimeZone, String modelVersion, int maxSolveSeconds,
                int randomSeed, int solverSearchThreads, double absoluteGapLimit, double relativeGapLimit)
        {
            this(requestId, planVersionId, siteCode, workshopIds, orderIds, capturedAt, definitionRevision,
                    executionRevision, horizonStartAt, detailEndAt, horizonEndAt, planningAnchorAt, timeUnitSeconds,
                    displayTimeZone, modelVersion, maxSolveSeconds, randomSeed, solverSearchThreads,
                    absoluteGapLimit, relativeGapLimit, List.of(), null);
        }

        public CompileRequest(String requestId, String planVersionId, String siteCode, List<String> workshopIds,
                List<String> orderIds, Instant capturedAt, long definitionRevision, long executionRevision,
                Instant horizonStartAt, Instant detailEndAt, Instant horizonEndAt, Instant planningAnchorAt,
                int timeUnitSeconds, String displayTimeZone, String modelVersion, int maxSolveSeconds,
                int randomSeed, int solverSearchThreads, double absoluteGapLimit, double relativeGapLimit,
                List<SharedBatchRequest> sharedBatches)
        {
            this(requestId, planVersionId, siteCode, workshopIds, orderIds, capturedAt, definitionRevision,
                    executionRevision, horizonStartAt, detailEndAt, horizonEndAt, planningAnchorAt, timeUnitSeconds,
                    displayTimeZone, modelVersion, maxSolveSeconds, randomSeed, solverSearchThreads,
                    absoluteGapLimit, relativeGapLimit, sharedBatches, null);
        }
    }

    public record SharedBatchRequest(String compatibilityKey, List<SharedBatchMemberRequest> members)
    {
        public SharedBatchRequest { members = members == null ? List.of() : List.copyOf(members); }
    }
    public record SharedBatchMemberRequest(String taskId, BigDecimal quantity) { }
    public record BaseVersionRequest(String planVersionId, String inputHash, Instant freezeEndAt,
            List<String> excludedTaskIds)
    {
        public BaseVersionRequest
        {
            excludedTaskIds = excludedTaskIds == null ? List.of() : List.copyOf(excludedTaskIds);
        }

        public BaseVersionRequest(String planVersionId, String inputHash, Instant freezeEndAt)
        {
            this(planVersionId, inputHash, freezeEndAt, List.of());
        }
    }
}
