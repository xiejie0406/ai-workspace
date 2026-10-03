package com.ruoyi.aps.application.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ActualOccupancy;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun;
import com.ruoyi.aps.application.execution.ExecutionCatalog.PublishedJob;
import com.ruoyi.aps.application.execution.ExecutionCatalog.PlannedResource;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.domain.execution.ExecutionRunStatus;
import com.ruoyi.aps.solver.contract.SolverInput;

/** M25/M26 生命周期与真实占用的事务端口。 */
public interface ExecutionRepository
{
    default List<ExecutionCatalog.RunOption> listRuns(ResourceAccessScope scope, String keyword, String status, String resourceId) { return List.of(); }
    default List<ExecutionCatalog.ReportMember> listReportMembers(String jobId) { return List.of(); }
    Optional<PublishedJob> findPublishedJobForDispatch(ResourceAccessScope scope, String planVersionId,
            String planJobId);
    Optional<ExecutionRun> findRun(String executionRunId);
    Optional<ExecutionRun> findRunForUpdate(String executionRunId);
    boolean isRunInScope(ResourceAccessScope scope, String executionRunId);
    default boolean isRunForTask(String executionRunId, String taskId) { return false; }
    Optional<ExecutionRun> findRunByRequestId(String requestId);
    Optional<ExecutionRun> findNonTerminalRunByJob(String planJobId);
    java.math.BigDecimal remainingAssignableQuantity(String planJobId);
    boolean hasUnsupportedSameStart(String planJobId);
    List<PlannedResource> listPlannedResources(String planJobId);
    default List<String> listJobTaskIds(String planJobId) { return List.of(); }
    List<PlannedResource> listResumeResources(String executionRunId);
    List<PlannedResource> listNextPhaseResources(String executionRunId);
    boolean hasLaterPlannedSegment(String executionRunId);
    boolean isResourceReady(String resourceId, Instant at);
    boolean hasResourceConflict(String resourceId, Instant at, String excludedExecutionRunId);
    boolean isReplacementCompatible(String executionRunId, ActualOccupancy replaced, String replacementResourceId,
            Instant at);
    Optional<ActualOccupancy> findActiveOccupancy(String executionRunId, String resourceId);
    Optional<ActualOccupancy> findOccupancy(String occupancyId);
    boolean hasCompletedOccupancyEndingAt(String executionRunId, String resourceId, Instant endAt);
    boolean hasProducedQuantity(String executionRunId);
    boolean hasUnresolvedOutput(String executionRunId);
    int nextRunNo(String planJobId);
    void insertRun(ExecutionRun run, String actor);
    int transitionRun(String executionRunId, long expectedRowVersion, ExecutionRunStatus expectedStatus,
            ExecutionRunStatus targetStatus, Instant actualStartAt, Instant actualEndAt, String reason, String actor);
    int incrementRunRevision(String executionRunId, long expectedRowVersion, String actor);
    List<ActualOccupancy> listOccupancies(String executionRunId);
    List<ActualOccupancy> listActiveOccupanciesForUpdate(String executionRunId);
    void insertOccupancy(ActualOccupancy occupancy, String actor);
    int closeOccupancy(String occupancyId, long expectedRowVersion, Instant endAt,
            ExecutionCatalog.OccupancyStatus status, String actor);
    long currentExecutionRevision();
    /** 返回每个任务全部 run 的有效加工量；已被后续更正替代的 M27 不参与累计。 */
    default Map<String, java.math.BigDecimal> processedQuantityByTask(List<String> taskIds) { return Map.of(); }
    default List<SolverInput.ActualOccupancy> listPlanningOccupancies(List<String> taskIds,
            Instant capturedAt, Instant horizonEndAt) { return List.of(); }
}
