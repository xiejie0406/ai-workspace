package com.ruoyi.aps.infrastructure.mysql.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.ruoyi.aps.application.execution.ExecutionCatalog;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ActualOccupancy;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun;
import com.ruoyi.aps.application.execution.ExecutionCatalog.PlannedResource;
import com.ruoyi.aps.application.execution.ExecutionCatalog.PublishedJob;
import com.ruoyi.aps.application.execution.ExecutionRepository;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.domain.execution.ExecutionRunStatus;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsExecutionMapper;
import com.ruoyi.aps.infrastructure.mysql.support.ApsRowMapperSupport;
import com.ruoyi.aps.solver.contract.SolverInput;

public final class MybatisExecutionRepository extends ApsRowMapperSupport implements ExecutionRepository
{
    private final ApsExecutionMapper mapper;

    public MybatisExecutionRepository(ApsExecutionMapper mapper) { this.mapper = mapper; }
    @Override public List<ExecutionCatalog.ReportMember> listReportMembers(String id) {
        return mapper.listReportMembers(id).stream().map(r->new ExecutionCatalog.ReportMember(text(r,"id"),number(r,"member_no").intValue(),text(r,"task_id"),text(r,"task_name"),decimal(r,"planned_qty"),text(r,"uom_code"))).toList();
    }
    @Override public List<ExecutionCatalog.RunOption> listRuns(ResourceAccessScope scope,String keyword,String status,String resourceId) {
        return mapper.listRuns(scope.actorUserId(),scope.allWorkshops(),keyword,status,resourceId).stream()
                .map(row -> new ExecutionCatalog.RunOption(run(row),text(row,"job_code"),text(row,"order_numbers"),text(row,"task_names"))).toList();
    }

    @Override public Optional<PublishedJob> findPublishedJobForDispatch(ResourceAccessScope scope,
            String planVersionId, String planJobId)
    {
        Map<String, Object> row = mapper.findPublishedJobForDispatch(scope.actorUserId(), scope.allWorkshops(),
                planVersionId, planJobId);
        return Optional.ofNullable(row).map(value -> new PublishedJob(text(value, "plan_version_id"),
                text(value, "plan_job_id"), decimal(value, "planned_qty"), text(value, "uom_code"),
                bool(value, "current_published"), bool(value, "quality_gate_required")));
    }

    @Override public Optional<ExecutionRun> findRun(String id) { return Optional.ofNullable(mapper.findRun(id)).map(this::run); }
    @Override public Optional<ExecutionRun> findRunForUpdate(String id) { return Optional.ofNullable(mapper.findRunForUpdate(id)).map(this::run); }
    @Override public boolean isRunInScope(ResourceAccessScope scope, String id)
    {
        return mapper.isRunInScope(scope.actorUserId(), scope.allWorkshops(), id) > 0;
    }
    @Override public boolean isRunForTask(String runId, String taskId) { return mapper.isRunForTask(runId, taskId) > 0; }
    @Override public Optional<ExecutionRun> findRunByRequestId(String id) { return Optional.ofNullable(mapper.findRunByRequestId(id)).map(this::run); }
    @Override public Optional<ExecutionRun> findNonTerminalRunByJob(String id) { return Optional.ofNullable(mapper.findNonTerminalRunByJob(id)).map(this::run); }
    @Override public BigDecimal remainingAssignableQuantity(String id) { return mapper.remainingAssignableQuantity(id); }
    @Override public boolean hasUnsupportedSameStart(String id) { return mapper.hasUnsupportedSameStart(id) > 0; }
    @Override public int nextRunNo(String id) { return mapper.nextRunNo(id); }
    @Override public List<PlannedResource> listPlannedResources(String id) { return planned(mapper.listPlannedResources(id)); }
    @Override public List<String> listJobTaskIds(String id) { return mapper.listJobTaskIds(id); }
    @Override public List<PlannedResource> listResumeResources(String executionRunId)
    {
        return planned(mapper.listResumeResources(executionRunId));
    }
    @Override public List<PlannedResource> listNextPhaseResources(String executionRunId)
    {
        ExecutionRun run = findRun(executionRunId).orElseThrow();
        List<ActualOccupancy> active = listActiveOccupanciesForUpdate(executionRunId).stream()
                .filter(value -> value.activityType() != ExecutionCatalog.OccupancyActivityType.PAUSE_HOLD)
                .toList();
        List<String> segmentIds = active.stream().map(ActualOccupancy::planSegmentId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (segmentIds.size() != 1) return List.of();
        List<PlannedResource> all = listPlannedResources(run.planJobId());
        int current = all.stream().filter(value -> segmentIds.get(0).equals(value.planSegmentId()))
                .mapToInt(PlannedResource::segmentNo).min().orElse(Integer.MAX_VALUE);
        int next = all.stream().mapToInt(PlannedResource::segmentNo).filter(value -> value > current)
                .min().orElse(Integer.MAX_VALUE);
        return all.stream().filter(value -> value.segmentNo() == next).toList();
    }
    @Override public boolean hasLaterPlannedSegment(String executionRunId)
    {
        ExecutionRun run = findRun(executionRunId).orElseThrow();
        List<String> activeSegments = listActiveOccupanciesForUpdate(executionRunId).stream()
                .filter(value -> value.activityType() != ExecutionCatalog.OccupancyActivityType.PAUSE_HOLD)
                .map(ActualOccupancy::planSegmentId).filter(java.util.Objects::nonNull).distinct().toList();
        if (activeSegments.size() != 1) return true;
        List<PlannedResource> all = listPlannedResources(run.planJobId());
        int current = all.stream().filter(value -> activeSegments.get(0).equals(value.planSegmentId()))
                .mapToInt(PlannedResource::segmentNo).min().orElse(Integer.MIN_VALUE);
        return current == Integer.MIN_VALUE || all.stream().anyMatch(value -> value.segmentNo() > current);
    }
    @Override public boolean isResourceReady(String id, Instant at) { return mapper.isResourceReady(id, at) > 0; }
    @Override public boolean hasResourceConflict(String id, Instant at, String excludedRunId) { return mapper.hasResourceConflict(id, at, excludedRunId) > 0; }
    @Override public boolean isReplacementCompatible(String runId, ActualOccupancy replaced, String replacementId, Instant at)
    {
        return isResourceReady(replacementId, at) && mapper.isReplacementCompatible(runId, replaced.id(),
                replaced.resourceId(), replacementId, at) > 0;
    }
    @Override public Optional<ActualOccupancy> findActiveOccupancy(String runId, String resourceId) { return Optional.ofNullable(mapper.findActiveOccupancy(runId, resourceId)).map(this::occupancy); }
    @Override public Optional<ActualOccupancy> findOccupancy(String id) { return Optional.ofNullable(mapper.findOccupancy(id)).map(this::occupancy); }
    @Override public boolean hasCompletedOccupancyEndingAt(String runId, String resourceId, Instant endAt)
    {
        return mapper.hasCompletedOccupancyEndingAt(runId, resourceId, endAt) > 0;
    }
    @Override public boolean hasProducedQuantity(String id) { return mapper.hasProducedQuantity(id) > 0; }
    @Override public boolean hasUnresolvedOutput(String id) { return mapper.hasUnresolvedOutput(id) > 0; }
    @Override public void insertRun(ExecutionRun run, String actor) { mapper.insertRun(run, run.status().name(), actor); }
    @Override public int transitionRun(String id, long version, ExecutionRunStatus expected, ExecutionRunStatus target,
            Instant start, Instant end, String reason, String actor)
    {
        return mapper.transitionRun(id, version, expected.name(), target.name(), start, end, reason, actor);
    }
    @Override public int incrementRunRevision(String id, long version, String actor) { return mapper.incrementRunRevision(id, version, actor); }
    @Override public List<ActualOccupancy> listOccupancies(String id) { return mapper.listOccupancies(id).stream().map(this::occupancy).toList(); }
    @Override public List<ActualOccupancy> listActiveOccupanciesForUpdate(String id) { return mapper.listActiveOccupanciesForUpdate(id).stream().map(this::occupancy).toList(); }
    @Override public void insertOccupancy(ActualOccupancy value, String actor) { mapper.insertOccupancy(value, value.activityType().name(), value.status().name(), actor); }
    @Override public int closeOccupancy(String id, long version, Instant end, ExecutionCatalog.OccupancyStatus status, String actor) { return mapper.closeOccupancy(id, version, end, status.name(), actor); }
    @Override public long currentExecutionRevision() { return mapper.currentExecutionRevision(); }
    @Override public Map<String, BigDecimal> processedQuantityByTask(List<String> taskIds)
    {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        return mapper.processedQuantityByTask(taskIds).stream().collect(java.util.stream.Collectors.toMap(
                row -> text(row, "task_id"), row -> decimal(row, "processed_qty"), BigDecimal::add,
                LinkedHashMap::new));
    }
    @Override public List<SolverInput.ActualOccupancy> listPlanningOccupancies(List<String> taskIds,
            Instant capturedAt, Instant horizonEndAt)
    {
        if (taskIds == null || taskIds.isEmpty()) return List.of();
        Map<String, List<Map<String, Object>>> physical = mapper.listPlanningOccupancies(taskIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(row -> text(row, "id"), LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        return physical.values().stream().map(rows -> {
            Map<String, Object> row = rows.get(0);
            int memberCount = number(row, "member_count").intValue();
            BigDecimal assigned = decimal(row, "assigned_qty");
            BigDecimal jobPlanned = decimal(row, "job_planned_qty");
            boolean allocationKnown = memberCount == 1 || assigned.compareTo(jobPlanned) == 0;
            List<SolverInput.ActualOccupancyMember> members = rows.stream().map(member -> {
                BigDecimal allocated = memberCount == 1 ? assigned : decimal(member, "member_planned_qty");
                BigDecimal remaining = allocated.subtract(decimal(member, "processed_qty")).max(BigDecimal.ZERO);
                return new SolverInput.ActualOccupancyMember(text(member, "task_id"),
                        remaining.stripTrailingZeros().toPlainString(), text(member, "uom_code", "PCS"));
            }).filter(member -> new BigDecimal(member.remainingQuantity()).signum() > 0)
                    .sorted(java.util.Comparator.comparing(SolverInput.ActualOccupancyMember::taskId)).toList();
            BigDecimal remaining = members.stream().map(value -> new BigDecimal(value.remainingQuantity()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal runRemaining = assigned.subtract(decimal(row, "run_processed_qty")).max(BigDecimal.ZERO);
            Number segmentSeconds = (Number) row.get("segment_seconds");
            boolean trusted = "RUNNING".equals(text(row, "run_status"))
                    && nullable(row, "plan_segment_id") != null && segmentSeconds != null
                    && nullable(row, "resource_requirement_id") != null && nullable(row, "seat_no") != null
                    && nullable(row, "capacity_used") != null
                    && !"PAUSE_HOLD".equals(text(row, "activity_type")) && allocationKnown
                    && !members.isEmpty() && runRemaining.signum() > 0;
            int duration = trusted ? Math.max(1, BigDecimal.valueOf(segmentSeconds.longValue())
                    .multiply(runRemaining).divide(assigned, 0, java.math.RoundingMode.CEILING).intValueExact()) : 0;
            Instant predictedRelease = trusted ? capturedAt.plusSeconds(duration) : null;
            if (trusted && predictedRelease.isAfter(horizonEndAt)) trusted = false;
            Instant releaseAt = trusted ? predictedRelease : horizonEndAt;
            SolverInput.CurrentPhase phase = nullable(row, "operation_phase_id") == null ? null
                    : new SolverInput.CurrentPhase(text(row, "operation_spec_id"),
                            text(row, "operation_phase_id"),
                            SolverInput.PhaseType.valueOf(text(row, "phase_type")));
            SolverInput.ActualOccupancyMember single = members.size() == 1 ? members.get(0) : null;
            return new SolverInput.ActualOccupancy(text(row, "id"), text(row, "plan_job_id"),
                    text(row, "execution_run_id"), nullable(row, "plan_segment_id"), text(row, "resource_id"),
                    SolverInput.ResourceType.valueOf(text(row, "resource_type")),
                    single == null ? null : single.taskId(),
                    text(row, "activity_type"), instant(row.get("start_at")), null, phase,
                    trusted ? duration : null, trusted ? remaining.stripTrailingZeros().toPlainString() : null,
                    trusted && single != null ? single.uomCode() : null, trusted ? releaseAt : null, trusted
                            ? SolverInput.ReleaseConfidence.TRUSTED : SolverInput.ReleaseConfidence.UNKNOWN,
                    trusted ? members : List.of(), trusted ? nullable(row, "resource_requirement_id") : null,
                    trusted && nullable(row, "seat_no") != null ? number(row, "seat_no").intValue() : null,
                    trusted && nullable(row, "capacity_used") != null
                            ? decimal(row, "capacity_used").stripTrailingZeros().toPlainString() : null);
        }).toList();
    }

    private String text(Map<String, Object> row, String key, String fallback)
    {
        String value = nullable(row, key);
        return value == null ? fallback : value;
    }

    private List<PlannedResource> planned(List<Map<String, Object>> rows)
    {
        return rows.stream().map(row -> new PlannedResource(text(row, "plan_segment_id"),
                number(row, "segment_no").intValue(), text(row, "source_plan_allocation_id"),
                text(row, "resource_id"), ExecutionCatalog.OccupancyActivityType.valueOf(text(row, "activity_type")),
                bool(row, "hold_on_pause"))).toList();
    }

    private ExecutionRun run(Map<String, Object> row)
    {
        return new ExecutionRun(text(row, "id"), text(row, "plan_version_id"), text(row, "plan_job_id"),
                number(row, "run_no").intValue(), ExecutionRunStatus.valueOf(text(row, "status")),
                decimal(row, "assigned_qty"), text(row, "uom_code"), instant(row.get("actual_start_at")),
                instant(row.get("actual_end_at")), nullable(row, "pause_reason"), text(row, "request_id"),
                number(row, "row_version").longValue());
    }

    private ActualOccupancy occupancy(Map<String, Object> row)
    {
        return new ActualOccupancy(text(row, "id"), text(row, "plan_job_id"), text(row, "execution_run_id"),
                nullable(row, "plan_segment_id"), nullable(row, "source_plan_allocation_id"),
                text(row, "resource_id"),
                ExecutionCatalog.OccupancyActivityType.valueOf(text(row, "activity_type")),
                instant(row.get("start_at")), instant(row.get("end_at")),
                ExecutionCatalog.OccupancyStatus.valueOf(text(row, "status")), nullable(row, "correction_of_id"),
                nullable(row, "reason"), number(row, "row_version").longValue());
    }

}
