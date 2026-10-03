package com.ruoyi.aps.application.planning;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import com.ruoyi.aps.solver.contract.SolverInput;
import com.ruoyi.aps.solver.contract.SolverResult;
import com.ruoyi.aps.solver.contract.Problem;

/** M19～M24 计划请求、单 Worker 领取、候选保存、工作台读取和锁端口。 */
public interface PlanRepository
{
    /** 先按完整车间范围过滤，再取最近版本；不返回冻结输入或求解大对象。 */
    default List<PlanRecord> listVisiblePlans(List<String> workshopIds, boolean allWorkshops) { return List.of(); }
    Optional<PlanRecord> findByRequestId(String requestId);
    default Optional<String> findRequestFingerprintByRequestId(String requestId) { return Optional.empty(); }
    Optional<PlanRequestSnapshot> findRequestSnapshot(String requestId);
    Optional<BaselineSnapshot> findCurrentPublishedBaseline(String planVersionId);
    default Optional<PlanDetail> findDetail(String planVersionId) { return Optional.empty(); }
    default int advanceCandidateRevision(String planVersionId, long expectedRowVersion, String actor)
    {
        throw new UnsupportedOperationException("计划候选修订尚未由持久化适配器实现");
    }
    default void insertLock(PlanLock lock, String planVersionId, String actor)
    {
        throw new UnsupportedOperationException("计划锁写入尚未由持久化适配器实现");
    }
    default int deleteLock(String planVersionId, String lockId, long expectedRowVersion)
    {
        throw new UnsupportedOperationException("计划锁删除尚未由持久化适配器实现");
    }
    default Optional<PlanRecord> findPlanForUpdate(String planVersionId) { return Optional.empty(); }
    default Optional<PlanRecord> findCurrentPublishedForUpdate() { return Optional.empty(); }
    default Optional<Instant> latestPlanningFactUpdatedAt() { return Optional.empty(); }
    default List<String> findStartedTaskIds(List<String> taskIds) { return List.of(); }
    default int supersedeCurrent(String planVersionId, long expectedRowVersion, Instant publishedAt, String actor)
    {
        throw new UnsupportedOperationException("正式计划替换尚未由持久化适配器实现");
    }
    default int publishCandidate(String planVersionId, long expectedRowVersion, Instant publishedAt,
            String reason, String actor)
    {
        throw new UnsupportedOperationException("计划发布尚未由持久化适配器实现");
    }
    /** 首个成功发布的版本占用业务日冻结基线；已有基线时保持不变。 */
    default int assignDailyBaselineIfAbsent(String planVersionId, LocalDate businessDate,
            Instant assignedAt, String actor) { return 0; }
    default int discardCandidate(String planVersionId, long expectedRowVersion, Instant discardedAt,
            String reason, String actor)
    {
        throw new UnsupportedOperationException("候选废弃尚未由持久化适配器实现");
    }
    long nextVersionNo();
    void insertDraft(PlanRecord plan, byte[] inputJson, String actor);
    default void insertDraft(PlanRecord plan, byte[] inputJson, String requestFingerprint,
            String changeNote, String actor)
    {
        insertDraft(plan, inputJson, actor);
    }
    Optional<ClaimedPlan> claimNext(String actor);
    /**
     * 单 Worker 重启时回收超过安全窗口的遗留 SOLVING。
     *
     * <p>P0 禁止第二 Worker，因此恢复动作只把遗留候选标为 FAILED，不重新使用旧求解过程。</p>
     */
    default int recoverStaleSolving(Instant staleBefore, String actor) { return 0; }
    int requestCancellation(String requestId, long rowVersion, String actor);
    boolean isCancellationRequested(String planVersionId);
    int storeResult(ClaimedPlan claim, SolverResult result, String actor);

    record PlanRecord(String id, String baseVersionId, long versionNo, String versionName, String requestId,
            long definitionRevision, long executionRevision, String inputHash, String status,
            Instant createdAt, Instant updatedAt, long rowVersion) { }

    record ClaimedPlan(PlanRecord plan, SolverInput input) { }

    record BaselineJob(String jobId, List<String> memberTaskIds, Instant startAt, Instant endAt,
            List<String> resourceIds)
    {
        public BaselineJob
        {
            memberTaskIds = memberTaskIds == null ? List.of() : List.copyOf(memberTaskIds);
            resourceIds = resourceIds == null ? List.of() : List.copyOf(resourceIds);
        }
    }

    record BaselineLock(String lockId, String targetType, String jobId, String phaseId, Integer segmentNo,
            String requirementId, Integer seatNo, String allocationResourceId, String lockType,
            Instant lockedStartAt, Instant lockedEndAt, String lockedResourceId, String reason) { }

    record BaselineSnapshot(PlanRecord plan, List<BaselineJob> jobs, List<BaselineLock> locks)
    {
        public BaselineSnapshot
        {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            locks = locks == null ? List.of() : List.copyOf(locks);
        }
    }

    record PlanRequestSnapshot(PlanRecord plan, SolverInput input, SolverResult.SolverStatus solverStatus,
            SolverResult.ResultKind resultKind, List<String> reasonCodes)
    {
        public PlanRequestSnapshot { reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes); }
    }

    record PlanMember(String id, String taskId, int memberNo, BigDecimal plannedQty, String uomCode) { }

    record PlanJob(String id, String operationSpecId, String workCenterId, String jobCode, String jobType,
            String batchCode, BigDecimal plannedQty, String uomCode, BigDecimal capacityValue,
            String capacityUomCode, String compatibilityKey, String carryRunId, Instant startAt, Instant endAt,
            List<PlanMember> members)
    {
        public PlanJob { members = members == null ? List.of() : List.copyOf(members); }

        public PlanJob(String id, String operationSpecId, String workCenterId, String jobCode, String jobType,
                String batchCode, BigDecimal plannedQty, String uomCode, BigDecimal capacityValue,
                String capacityUomCode, String compatibilityKey, Instant startAt, Instant endAt,
                List<PlanMember> members)
        {
            this(id, operationSpecId, workCenterId, jobCode, jobType, batchCode, plannedQty, uomCode,
                    capacityValue, capacityUomCode, compatibilityKey, null, startAt, endAt, members);
        }
    }

    record PlanSegment(String id, String jobId, String phaseId, int segmentNo, String phaseType,
            Instant startAt, Instant endAt, BigDecimal plannedQty, Instant releaseAt, BigDecimal releaseQty,
            String uomCode) { }

    record PlanAllocation(String id, String segmentId, String phaseId, String requirementId, String resourceId,
            String allocationRole, int seatNo, BigDecimal capacityUsed) { }

    record PlanLock(String id, String targetType, String jobId, String segmentId, String allocationId,
            String lockType, Instant lockedStartAt, Instant lockedEndAt, String lockedResourceId,
            String reason, long rowVersion) { }

    record PlanDetail(PlanRecord plan, SolverInput input, String candidateHash, List<PlanJob> jobs,
            List<PlanSegment> segments, List<PlanAllocation> allocations, List<PlanLock> locks,
            List<Problem> problems, SolverResult.SolverStatus solverStatus, SolverResult.ResultKind resultKind)
    {
        public PlanDetail
        {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            segments = segments == null ? List.of() : List.copyOf(segments);
            allocations = allocations == null ? List.of() : List.copyOf(allocations);
            locks = locks == null ? List.of() : List.copyOf(locks);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }
}
