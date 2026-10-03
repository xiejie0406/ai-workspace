package com.ruoyi.aps.application.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ActualOccupancy;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun;
import com.ruoyi.aps.application.execution.ExecutionCatalog.OccupancyActivityType;
import com.ruoyi.aps.application.execution.ExecutionCatalog.OccupancyStatus;
import com.ruoyi.aps.application.execution.ExecutionCatalog.PlannedResource;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.domain.execution.ExecutionAction;
import com.ruoyi.aps.domain.execution.ExecutionRunStateMachine;
import com.ruoyi.aps.domain.execution.ExecutionRunStatus;
import com.ruoyi.aps.domain.execution.ExecutionTransitionContext;

/** M25/M26 创建、生命周期与资源切换用例。 */
public final class ExecutionLifecycleService
{
    private final ExecutionRepository executions;
    private final OrderRepository orders;
    private final ApsTransactionOperations transactions;

    public ExecutionLifecycleService(ExecutionRepository executions, ApsTransactionOperations transactions)
    {
        this(executions, null, transactions);
    }

    public ExecutionLifecycleService(ExecutionRepository executions, OrderRepository orders,
            ApsTransactionOperations transactions)
    {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.orders = orders;
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public CreatedRun create(ResourceAccessScope scope, CreateRun command, String actor)
    {
        requireCreate(command);
        return transactions.serializable(() -> {
            var existing = executions.findRunByRequestId(command.requestId());
            if (existing.isPresent())
            {
                requireScope(scope, existing.get().id());
                requireSameCreate(existing.get(), command);
                return new CreatedRun(existing.get(), true);
            }
            var job = executions.findPublishedJobForDispatch(scope, command.planVersionId(), command.planJobId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "当前正式计划作业不存在或不在数据范围内"));
            if (!job.currentPublished())
            {
                throw error(ApsErrorCode.EXECUTION_INPUT_STALE, "只能从当时唯一当前正式计划创建执行 run");
            }
            if (!job.uomCode().equals(command.uomCode()))
            {
                throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION, "执行 run 单位必须与计划作业一致");
            }
            if (executions.findNonTerminalRunByJob(command.planJobId()).isPresent())
            {
                throw error(ApsErrorCode.EXECUTION_CONFLICT, "同一计划作业已存在非终态 run");
            }
            if (command.assignedQty().compareTo(executions.remainingAssignableQuantity(command.planJobId())) > 0)
            {
                throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, "派工数量超过计划作业剩余可分配量");
            }
            ExecutionRun run = new ExecutionRun(stable("execution-run", command.requestId()),
                    command.planVersionId(), command.planJobId(), executions.nextRunNo(command.planJobId()),
                    ExecutionRunStatus.READY, command.assignedQty().stripTrailingZeros(), command.uomCode(),
                    null, null, null, command.requestId(), 0);
            executions.insertRun(run, actor);
            recompute(run.planJobId(), actor);
            return new CreatedRun(run, false);
        });
    }

    public ExecutionRun transition(ResourceAccessScope scope, Transition command, String actor)
    {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.action(), "action");
        Objects.requireNonNull(command.occurredAt(), "occurredAt");
        return transactions.serializable(() -> {
            ExecutionRun run = executions.findRunForUpdate(command.executionRunId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "执行 run 不存在"));
            requireScope(scope, run.id());
            ExecutionRunStatus target = targetFor(run.status(), command.action());
            if (run.status() == target && run.rowVersion() == command.expectedRowVersion() + 1)
            {
                return run;
            }
            if (run.rowVersion() != command.expectedRowVersion())
            {
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 行版本已变化");
            }
            ExecutionTransitionContext context = new ExecutionTransitionContext(false,
                    executions.hasProducedQuantity(run.id()), !executions.hasUnresolvedOutput(run.id()), true);
            try
            {
                target = ExecutionRunStateMachine.transition(run.status(), command.action(), context);
            }
            catch (IllegalStateException exception)
            {
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, exception.getMessage());
            }
            applyOccupancyTransition(run, command, target, actor);
            Instant startAt = command.action() == ExecutionAction.START ? command.occurredAt() : run.actualStartAt();
            Instant endAt = target == ExecutionRunStatus.CANCELLED && run.actualStartAt() != null
                    ? command.occurredAt() : run.actualEndAt();
            int updated = executions.transitionRun(run.id(), run.rowVersion(), run.status(), target, startAt, endAt,
                    command.reason(), actor);
            if (updated != 1)
            {
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 状态已被并发修改");
            }
            recompute(run.planJobId(), actor);
            return executions.findRun(run.id()).orElseThrow();
        });
    }

    public ResourceChanged changeResource(ResourceAccessScope scope, ResourceChange command, String actor)
    {
        Objects.requireNonNull(command, "command");
        return transactions.serializable(() -> {
            String occupancyId = stable("resource-change", command.requestId());
            var replay = executions.findOccupancy(occupancyId);
            if (replay.isPresent())
            {
                ActualOccupancy occupancy = replay.get();
                requireScope(scope, occupancy.executionRunId());
                if (!occupancy.executionRunId().equals(command.executionRunId())
                        || !occupancy.resourceId().equals(command.resourceId())
                        || occupancy.activityType() != command.activityType()
                        || !occupancy.startAt().equals(command.occurredAt())
                        || (command.planSegmentId() != null
                                && !Objects.equals(occupancy.planSegmentId(), command.planSegmentId()))
                        || !Objects.equals(occupancy.reason(), command.reason())
                        || !executions.hasCompletedOccupancyEndingAt(command.executionRunId(),
                                command.replacedResourceId(), command.occurredAt()))
                {
                    throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他资源切换意图");
                }
                return new ResourceChanged(executions.findRun(command.executionRunId()).orElseThrow(), true);
            }
            ExecutionRun run = executions.findRunForUpdate(command.executionRunId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "执行 run 不存在"));
            requireScope(scope, run.id());
            if (run.status() != ExecutionRunStatus.RUNNING)
            {
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, "仅 RUNNING run 可切换人员或设备");
            }
            if (run.rowVersion() != command.expectedRowVersion())
            {
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 行版本已变化");
            }
            ActualOccupancy replaced = executions.findActiveOccupancy(run.id(), command.replacedResourceId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "待替换资源没有活动实际占用"));
            if (executions.findActiveOccupancy(run.id(), command.resourceId()).isPresent())
                throw error(ApsErrorCode.EXECUTION_CONFLICT, "所选资源已占用本执行单的其他席位");
            if (replaced.activityType() == OccupancyActivityType.PAUSE_HOLD
                    || replaced.sourcePlanAllocationId() == null
                    || (command.planSegmentId() != null
                            && !Objects.equals(command.planSegmentId(), replaced.planSegmentId()))
                    || command.activityType() != replaced.activityType())
            {
                throw error(ApsErrorCode.INVALID_REQUEST, "资源切换必须保持当前计划段、活动类型和来源席位");
            }
            if (!executions.isReplacementCompatible(run.id(), replaced, command.resourceId(), command.occurredAt())
                    || executions.hasResourceConflict(command.resourceId(), command.occurredAt(), run.id()))
            {
                throw error(ApsErrorCode.EXECUTION_CONFLICT, "替换资源不满足类型、技能、日历或占用约束");
            }
            if (executions.closeOccupancy(replaced.id(), replaced.rowVersion(), command.occurredAt(),
                    OccupancyStatus.COMPLETED, actor) != 1)
            {
                throw error(ApsErrorCode.STALE_VERSION, "实际占用已被并发修改");
            }
            executions.insertOccupancy(new ActualOccupancy(occupancyId, run.planJobId(), run.id(),
                    command.planSegmentId() == null ? replaced.planSegmentId() : command.planSegmentId(),
                    replaced.sourcePlanAllocationId(), command.resourceId(), command.activityType(),
                    command.occurredAt(), null,
                    OccupancyStatus.ACTIVE, null, command.reason(), 0), actor);
            if (executions.incrementRunRevision(run.id(), run.rowVersion(), actor) != 1)
            {
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 已被并发修改");
            }
            return new ResourceChanged(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    /** 关闭当前计划段的真实占用，并在同一事务打开下一个计划段。run 状态保持 RUNNING。 */
    public PhaseAdvanced advancePhase(ResourceAccessScope scope, PhaseAdvance command, String actor)
    {
        Objects.requireNonNull(command, "command");
        if (blank(command.requestId()) || blank(command.executionRunId()) || command.occurredAt() == null)
            throw error(ApsErrorCode.INVALID_REQUEST, "阶段推进参数不完整");
        return transactions.serializable(() -> {
            ExecutionRun run = executions.findRunForUpdate(command.executionRunId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "执行 run 不存在"));
            requireScope(scope, run.id());
            if (run.status() != ExecutionRunStatus.RUNNING)
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, "仅 RUNNING run 可推进计划阶段");
            List<ActualOccupancy> active = currentPhaseOccupancies(run.id());
            if (run.rowVersion() == command.expectedRowVersion() + 1 && isPhaseAdvanceRequest(run, active, command))
            {
                if (!isSamePhaseAdvanceReplay(active, command))
                    throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他阶段推进意图");
                return new PhaseAdvanced(run, true);
            }
            if (run.rowVersion() != command.expectedRowVersion())
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 行版本已变化");
            requireSingleCurrentSegment(active);
            List<PlannedResource> next = executions.listNextPhaseResources(run.id());
            if (next.isEmpty())
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, "当前已是最后计划阶段，不能继续推进");
            close(active, command.occurredAt(), actor);
            open(run, next, command.occurredAt(), actor, "phase-advance:" + command.requestId(),
                    command.reason());
            if (executions.incrementRunRevision(run.id(), run.rowVersion(), actor) != 1)
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 已被并发修改");
            return new PhaseAdvanced(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    public ExecutionDetail detail(ResourceAccessScope scope, String executionRunId)
    {
        ExecutionRun run = executions.findRun(executionRunId)
                .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "执行 run 不存在"));
        requireScope(scope, run.id());
        return new ExecutionDetail(run, executions.listOccupancies(run.id()), executions.currentExecutionRevision());
    }

    private void applyOccupancyTransition(ExecutionRun run, Transition command, ExecutionRunStatus target,
            String actor)
    {
        switch (command.action())
        {
            case START -> {
                if (executions.hasUnsupportedSameStart(run.planJobId()))
                {
                    throw error(ApsErrorCode.UNSUPPORTED_SYNC_RULE, "SAME_START 仍属于失败关闭约束");
                }
                List<PlannedResource> all = executions.listPlannedResources(run.planJobId());
                int first = all.stream().mapToInt(PlannedResource::segmentNo).min().orElse(Integer.MAX_VALUE);
                open(run, all.stream().filter(value -> value.segmentNo() == first).toList(),
                        command.occurredAt(), actor, "start", null);
            }
            case PAUSE -> {
                List<ActualOccupancy> active = executions.listActiveOccupanciesForUpdate(run.id());
                close(active, command.occurredAt(), actor);
                java.util.Map<String, PlannedResource> plannedByAllocation = executions
                        .listPlannedResources(run.planJobId()).stream().collect(java.util.stream.Collectors.toMap(
                                PlannedResource::sourcePlanAllocationId, value -> value, (left, right) -> left));
                List<String> holdResources = active.stream().filter(value -> {
                    PlannedResource planned = plannedByAllocation.get(value.sourcePlanAllocationId());
                    return planned != null && planned.holdOnPause();
                }).map(ActualOccupancy::resourceId).distinct().toList();
                for (String resourceId : holdResources)
                {
                    executions.insertOccupancy(new ActualOccupancy(stable("pause-hold", run.id(), resourceId,
                            Long.toString(run.rowVersion())), run.planJobId(), run.id(), null, null, resourceId,
                            OccupancyActivityType.PAUSE_HOLD, command.occurredAt(), null, OccupancyStatus.ACTIVE,
                            null, command.reason(), 0), actor);
                }
            }
            case RESUME -> {
                close(executions.listActiveOccupanciesForUpdate(run.id()), command.occurredAt(), actor);
                open(run, executions.listResumeResources(run.id()), command.occurredAt(), actor, "resume", null);
            }
            case CANCEL -> close(executions.listActiveOccupanciesForUpdate(run.id()), command.occurredAt(), actor);
            default -> throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION,
                    "该生命周期入口不处理完工或质量完结");
        }
        if (target == ExecutionRunStatus.RUNNING && executions.listActiveOccupanciesForUpdate(run.id()).isEmpty())
        {
            throw error(ApsErrorCode.EXECUTION_CONFLICT, "没有可建立实际占用的有效计划资源");
        }
    }

    private void open(ExecutionRun run, List<PlannedResource> resources, Instant at, String actor,
            String idempotencyNamespace, String occupancyReason)
    {
        java.util.LinkedHashMap<String, PlannedResource> distinct = new java.util.LinkedHashMap<>();
        resources.forEach(resource -> distinct.putIfAbsent(resource.resourceId(), resource));
        for (PlannedResource resource : distinct.values())
        {
            if (!executions.isResourceReady(resource.resourceId(), at)
                    || executions.hasResourceConflict(resource.resourceId(), at, run.id()))
            {
                throw error(ApsErrorCode.EXECUTION_CONFLICT, "资源不可用或已存在真实占用：" + resource.resourceId());
            }
            executions.insertOccupancy(new ActualOccupancy(stable("occupancy", idempotencyNamespace, run.id(),
                    resource.planSegmentId(), resource.resourceId(), Long.toString(run.rowVersion())),
                    run.planJobId(), run.id(), resource.planSegmentId(), resource.sourcePlanAllocationId(),
                    resource.resourceId(), resource.activityType(), at, null, OccupancyStatus.ACTIVE, null,
                    occupancyReason, 0), actor);
        }
    }

    private List<ActualOccupancy> currentPhaseOccupancies(String runId)
    {
        List<ActualOccupancy> active = executions.listActiveOccupanciesForUpdate(runId);
        if (active.isEmpty() || active.stream().anyMatch(value ->
                value.activityType() == OccupancyActivityType.PAUSE_HOLD))
            throw error(ApsErrorCode.EXECUTION_CONFLICT, "当前没有唯一可推进的活动计划阶段");
        return active;
    }

    private void requireSingleCurrentSegment(List<ActualOccupancy> active)
    {
        if (active.stream().map(ActualOccupancy::planSegmentId).anyMatch(Objects::isNull)
                || active.stream().map(ActualOccupancy::planSegmentId).distinct().count() != 1
                || active.stream().map(ActualOccupancy::sourcePlanAllocationId).anyMatch(Objects::isNull)
                || active.stream().map(ActualOccupancy::sourcePlanAllocationId).distinct().count() != active.size())
            throw error(ApsErrorCode.EXECUTION_CONFLICT, "活动占用缺少唯一计划段或来源席位，不能推进阶段");
    }

    private boolean isPhaseAdvanceRequest(ExecutionRun run, List<ActualOccupancy> active, PhaseAdvance command)
    {
        return !active.isEmpty() && active.stream().allMatch(value -> value.sourcePlanAllocationId() != null
                && value.id().equals(stable("occupancy", "phase-advance:" + command.requestId(), run.id(),
                        value.planSegmentId(), value.resourceId(), Long.toString(command.expectedRowVersion()))));
    }

    private boolean isSamePhaseAdvanceReplay(List<ActualOccupancy> active, PhaseAdvance command)
    {
        return active.stream().allMatch(value -> value.startAt().equals(command.occurredAt())
                && Objects.equals(value.reason(), command.reason()));
    }

    private void close(List<ActualOccupancy> occupancies, Instant at, String actor)
    {
        for (ActualOccupancy occupancy : occupancies)
        {
            if (at.isBefore(occupancy.startAt()) || executions.closeOccupancy(occupancy.id(), occupancy.rowVersion(),
                    at, OccupancyStatus.COMPLETED, actor) != 1)
            {
                throw error(ApsErrorCode.STALE_VERSION, "实际占用结束时间无效或已被并发修改");
            }
        }
    }

    private ExecutionRunStatus targetFor(ExecutionRunStatus current, ExecutionAction action)
    {
        return switch (action)
        {
            case START, RESUME -> ExecutionRunStatus.RUNNING;
            case PAUSE -> ExecutionRunStatus.PAUSED;
            case CANCEL -> ExecutionRunStatus.CANCELLED;
            case FINISH_PROCESSING -> ExecutionRunStatus.WAIT_QUALITY;
            case COMPLETE_QUALITY -> ExecutionRunStatus.COMPLETED;
        };
    }

    private void requireCreate(CreateRun command)
    {
        Objects.requireNonNull(command, "command");
        if (blank(command.requestId()) || blank(command.planVersionId()) || blank(command.planJobId())
                || blank(command.uomCode()) || command.assignedQty() == null || command.assignedQty().signum() <= 0)
        {
            throw error(ApsErrorCode.INVALID_REQUEST, "执行 run 创建参数不完整或数量无效");
        }
    }

    private void requireSameCreate(ExecutionRun run, CreateRun command)
    {
        if (!run.planVersionId().equals(command.planVersionId()) || !run.planJobId().equals(command.planJobId())
                || !run.uomCode().equals(command.uomCode())
                || run.assignedQty().compareTo(command.assignedQty()) != 0)
        {
            throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他执行 run 创建意图");
        }
    }

    private void requireScope(ResourceAccessScope scope, String executionRunId)
    {
        Objects.requireNonNull(scope, "scope");
        if (!executions.isRunInScope(scope, executionRunId))
        {
            throw error(ApsErrorCode.NOT_FOUND, "执行 run 不存在或不在数据范围内");
        }
    }

    private static String stable(String... parts)
    {
        String[] normalized = java.util.Arrays.stream(parts).map(value -> value == null ? "" : value)
                .toArray(String[]::new);
        return UUID.nameUUIDFromBytes(String.join("\u0000", normalized).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private void recompute(String planJobId, String actor)
    {
        if (orders != null)
            executions.listJobTaskIds(planJobId).forEach(taskId -> orders.recomputeExecutionStatuses(taskId, actor));
    }
    private static ApsBusinessException error(ApsErrorCode code, String message)
    {
        return new ApsBusinessException(code, message);
    }

    public record CreateRun(String requestId, String planVersionId, String planJobId, BigDecimal assignedQty,
            String uomCode) { }
    public record CreatedRun(ExecutionRun run, boolean reused) { }
    public record Transition(String executionRunId, ExecutionAction action, long expectedRowVersion,
            Instant occurredAt, String reason) { }
    public record ResourceChange(String requestId, String executionRunId, long expectedRowVersion,
            String replacedResourceId, String resourceId, String planSegmentId,
            OccupancyActivityType activityType, Instant occurredAt, String reason) { }
    public record ResourceChanged(ExecutionRun run, boolean reused) { }
    public record PhaseAdvance(String requestId, String executionRunId, long expectedRowVersion,
            Instant occurredAt, String reason) { }
    public record PhaseAdvanced(ExecutionRun run, boolean reused) { }
    public record ExecutionDetail(ExecutionRun run, List<ActualOccupancy> occupancies,
            long executionRevision) { }
}
