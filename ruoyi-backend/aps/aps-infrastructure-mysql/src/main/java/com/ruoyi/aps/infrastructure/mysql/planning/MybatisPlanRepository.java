package com.ruoyi.aps.infrastructure.mysql.planning;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ruoyi.aps.application.planning.PlanRepository;
import com.ruoyi.aps.application.planning.PlanRepository.BaselineLock;
import com.ruoyi.aps.application.planning.PlanRepository.PlanAllocation;
import com.ruoyi.aps.application.planning.PlanRepository.PlanDetail;
import com.ruoyi.aps.application.planning.PlanRepository.PlanJob;
import com.ruoyi.aps.application.planning.PlanRepository.PlanLock;
import com.ruoyi.aps.application.planning.PlanRepository.PlanMember;
import com.ruoyi.aps.application.planning.PlanRepository.PlanSegment;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper.AllocationRow;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper.JobRow;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper.MemberRow;
import com.ruoyi.aps.infrastructure.mysql.mapper.ApsPlanMapper.SegmentRow;
import com.ruoyi.aps.infrastructure.mysql.support.ApsRowMapperSupport;
import com.ruoyi.aps.solver.contract.PlanCandidate;
import com.ruoyi.aps.solver.contract.Problem;
import com.ruoyi.aps.solver.contract.SolverInput;
import com.ruoyi.aps.solver.contract.SolverInputCodec;
import com.ruoyi.aps.solver.contract.SolverResult;

public final class MybatisPlanRepository extends ApsRowMapperSupport implements PlanRepository
{
    private final ApsPlanMapper mapper;
    private final SolverInputCodec codec = new SolverInputCodec();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public MybatisPlanRepository(ApsPlanMapper mapper) { this.mapper = mapper; }

    @Override public List<PlanRecord> listVisiblePlans(List<String> workshopIds, boolean allWorkshops)
    {
        if (!allWorkshops && workshopIds.isEmpty()) return List.of();
        return mapper.listVisiblePlans(workshopIds, allWorkshops).stream().map(this::record).toList();
    }

    @Override public Optional<PlanRecord> findByRequestId(String requestId)
    {
        return Optional.ofNullable(mapper.findByRequestId(requestId)).map(this::record);
    }

    @Override public Optional<String> findRequestFingerprintByRequestId(String requestId)
    {
        return Optional.ofNullable(mapper.findRequestFingerprintByRequestId(requestId));
    }

    @Override public Optional<PlanRequestSnapshot> findRequestSnapshot(String requestId)
    {
        Map<String, Object> row = mapper.findRequestSnapshot(requestId);
        if (row == null) return Optional.empty();
        SolverResult.SolverStatus solverStatus = null;
        SolverResult.ResultKind resultKind = null;
        List<String> reasonCodes = List.of();
        Map<String, Object> solver = readMap(row.get("solver_summary_json"));
        if (solver != null)
        {
            Object status = solver.get("solverStatus");
            Object kind = solver.get("resultKind");
            if (status != null) solverStatus = SolverResult.SolverStatus.valueOf(status.toString());
            if (kind != null) resultKind = SolverResult.ResultKind.valueOf(kind.toString());
        }
        Map<String, Object> validation = readMap(row.get("validation_summary_json"));
        if (validation != null && validation.get("problems") instanceof List<?> problems)
        {
            LinkedHashSet<String> codes = new LinkedHashSet<>();
            for (Object problem : problems)
                if (problem instanceof Map<?, ?> value && value.get("reasonCode") != null)
                    codes.add(value.get("reasonCode").toString());
            reasonCodes = List.copyOf(codes);
        }
        SolverInput input = codec.decode(text(row, "input_snapshot_json").getBytes(StandardCharsets.UTF_8));
        return Optional.of(new PlanRequestSnapshot(record(row), input, solverStatus, resultKind, reasonCodes));
    }

    @Override public Optional<BaselineSnapshot> findCurrentPublishedBaseline(String planVersionId)
    {
        Map<String, Object> plan = mapper.findCurrentPublishedBaseline(planVersionId);
        if (plan == null) return Optional.empty();
        Map<String, BaselineAccumulator> jobs = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : mapper.findBaselineJobMembers(planVersionId))
        {
            String jobId = text(row, "job_id");
            BaselineAccumulator value = jobs.computeIfAbsent(jobId, ignored -> new BaselineAccumulator(jobId,
                    instant(row.get("planned_start_at")), instant(row.get("planned_end_at"))));
            value.members.add(text(row, "task_id"));
        }
        for (Map<String, Object> row : mapper.findBaselineJobResources(planVersionId))
        {
            BaselineAccumulator value = jobs.get(text(row, "job_id"));
            if (value != null) value.resources.add(text(row, "resource_id"));
        }
        List<BaselineLock> locks = mapper.findBaselineLocks(planVersionId).stream().map(row ->
                new BaselineLock(text(row, "lock_id"), text(row, "target_type"), text(row, "job_id"),
                        nullable(row, "phase_id"), nullableInteger(row, "segment_no"),
                        nullable(row, "requirement_id"), nullableInteger(row, "seat_no"),
                        nullable(row, "allocation_resource_id"), text(row, "lock_type"),
                        instant(row.get("locked_start_at")), instant(row.get("locked_end_at")),
                        nullable(row, "locked_resource_id"), text(row, "lock_reason"))).toList();
        return Optional.of(new BaselineSnapshot(record(plan), jobs.values().stream().map(value ->
                new BaselineJob(value.jobId, value.members, value.startAt, value.endAt,
                        value.resources.stream().sorted().toList())).toList(), locks));
    }

    @Override public Optional<PlanDetail> findDetail(String planVersionId)
    {
        Map<String, Object> plan = mapper.findPlanVersionById(planVersionId);
        if (plan == null) return Optional.empty();
        Map<String, DetailJobAccumulator> jobs = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : mapper.findPlanJobMembers(planVersionId))
        {
            String jobId = text(row, "id");
            DetailJobAccumulator value = jobs.computeIfAbsent(jobId, ignored -> new DetailJobAccumulator(row));
            if (row.get("member_id") != null)
                value.members.add(new PlanMember(text(row, "member_id"), text(row, "task_id"),
                        number(row, "member_no").intValue(), decimal(row, "member_planned_qty"),
                        text(row, "member_uom_code")));
        }
        List<PlanSegment> segments = mapper.findPlanSegments(planVersionId).stream().map(row ->
                new PlanSegment(text(row, "id"), text(row, "plan_job_id"), text(row, "operation_phase_id"),
                        number(row, "segment_no").intValue(), text(row, "phase_type"),
                        instant(row.get("start_at")), instant(row.get("end_at")),
                        nullableDecimal(row, "planned_qty"), instant(row.get("release_at")),
                        nullableDecimal(row, "release_qty"), text(row, "uom_code"))).toList();
        List<PlanAllocation> allocations = mapper.findPlanAllocations(planVersionId).stream().map(row ->
                new PlanAllocation(text(row, "id"), text(row, "plan_segment_id"),
                        text(row, "operation_phase_id"), text(row, "resource_requirement_id"),
                        text(row, "resource_id"), text(row, "allocation_role"),
                        number(row, "seat_no").intValue(), decimal(row, "capacity_used"))).toList();
        List<PlanLock> locks = mapper.findBaselineLocks(planVersionId).stream().map(row ->
                new PlanLock(text(row, "lock_id"), text(row, "target_type"), text(row, "job_id"),
                        nullable(row, "plan_segment_id"), nullable(row, "plan_allocation_id"),
                        text(row, "lock_type"), instant(row.get("locked_start_at")),
                        instant(row.get("locked_end_at")), nullable(row, "locked_resource_id"),
                        text(row, "lock_reason"), number(row, "row_version").longValue())).toList();
        Map<String, Object> validation = readMap(plan.get("validation_summary_json"));
        String candidateHash = validation == null || validation.get("candidateHash") == null
                || validation.get("candidateHash").toString().isBlank()
                        ? null : validation.get("candidateHash").toString();
        List<Problem> problems = validation == null || validation.get("problems") == null
                ? List.of() : json.convertValue(validation.get("problems"), new TypeReference<List<Problem>>() { });
        Map<String, Object> solver = readMap(plan.get("solver_summary_json"));
        SolverResult.SolverStatus solverStatus = solver == null || solver.get("solverStatus") == null
                ? null : SolverResult.SolverStatus.valueOf(solver.get("solverStatus").toString());
        SolverResult.ResultKind resultKind = solver == null || solver.get("resultKind") == null
                ? null : SolverResult.ResultKind.valueOf(solver.get("resultKind").toString());
        SolverInput input = codec.decode(text(plan, "input_snapshot_json").getBytes(StandardCharsets.UTF_8));
        return Optional.of(new PlanDetail(record(plan), input, candidateHash,
                jobs.values().stream().map(DetailJobAccumulator::toJob).toList(),
                segments, allocations, locks, problems, solverStatus, resultKind));
    }

    @Override public int advanceCandidateRevision(String planVersionId, long expectedRowVersion, String actor)
    {
        return mapper.advanceCandidateRevision(planVersionId, expectedRowVersion, actor);
    }

    @Override public void insertLock(PlanLock lock, String planVersionId, String actor)
    {
        mapper.insertLock(lock, planVersionId, actor);
    }

    @Override public int deleteLock(String planVersionId, String lockId, long expectedRowVersion)
    {
        return mapper.deleteLock(planVersionId, lockId, expectedRowVersion);
    }

    @Override public Optional<PlanRecord> findPlanForUpdate(String planVersionId)
    {
        return Optional.ofNullable(mapper.findPlanVersionByIdForUpdate(planVersionId)).map(this::record);
    }

    @Override public Optional<PlanRecord> findCurrentPublishedForUpdate()
    {
        return Optional.ofNullable(mapper.findCurrentPublishedForUpdate()).map(this::record);
    }

    @Override public Optional<java.time.Instant> latestPlanningFactUpdatedAt()
    {
        return Optional.ofNullable(mapper.latestPlanningFactUpdatedAt());
    }

    @Override public List<String> findStartedTaskIds(List<String> taskIds)
    {
        return taskIds == null || taskIds.isEmpty() ? List.of() : mapper.findStartedTaskIds(taskIds);
    }

    @Override public int supersedeCurrent(String planVersionId, long expectedRowVersion,
            java.time.Instant publishedAt, String actor)
    {
        return mapper.supersedeCurrent(planVersionId, expectedRowVersion, publishedAt, actor);
    }

    @Override public int publishCandidate(String planVersionId, long expectedRowVersion,
            java.time.Instant publishedAt, String reason, String actor)
    {
        return mapper.publishCandidate(planVersionId, expectedRowVersion, publishedAt, reason, actor);
    }

    @Override public int assignDailyBaselineIfAbsent(String planVersionId, java.time.LocalDate businessDate,
            java.time.Instant assignedAt, String actor)
    {
        return mapper.assignDailyBaselineIfAbsent(planVersionId, businessDate, assignedAt, actor);
    }

    @Override public int discardCandidate(String planVersionId, long expectedRowVersion,
            java.time.Instant discardedAt, String reason, String actor)
    {
        return mapper.discardCandidate(planVersionId, expectedRowVersion, discardedAt, reason, actor);
    }

    @Override public long nextVersionNo() { return mapper.nextVersionNo(); }

    @Override public void insertDraft(PlanRecord plan, byte[] inputJson, String actor)
    {
        mapper.insertDraft(plan, new String(inputJson, StandardCharsets.UTF_8), null, null, actor);
    }

    @Override public void insertDraft(PlanRecord plan, byte[] inputJson, String requestFingerprint,
            String changeNote, String actor)
    {
        mapper.insertDraft(plan, new String(inputJson, StandardCharsets.UTF_8), requestFingerprint, changeNote, actor);
    }

    @Override public Optional<ClaimedPlan> claimNext(String actor)
    {
        Map<String, Object> row = mapper.findNextDraft();
        if (row == null) return Optional.empty();
        PlanRecord plan = record(row);
        if (mapper.claim(plan.id(), plan.rowVersion(), actor) != 1) return Optional.empty();
        PlanRecord claimed = new PlanRecord(plan.id(), plan.baseVersionId(), plan.versionNo(), plan.versionName(),
                plan.requestId(), plan.definitionRevision(), plan.executionRevision(), plan.inputHash(), "SOLVING",
                plan.createdAt(), plan.updatedAt(), plan.rowVersion() + 1);
        return Optional.of(new ClaimedPlan(claimed,
                codec.decode(text(row, "input_snapshot_json").getBytes(StandardCharsets.UTF_8))));
    }

    @Override public int recoverStaleSolving(Instant staleBefore, String actor)
    {
        return mapper.recoverStaleSolving(staleBefore, actor);
    }

    @Override public int requestCancellation(String requestId, long rowVersion, String actor)
    {
        return mapper.cancel(requestId, rowVersion, actor);
    }

    @Override public boolean isCancellationRequested(String planVersionId)
    {
        return mapper.isCancelled(planVersionId) == 1;
    }

    @Override public int storeResult(ClaimedPlan claim, SolverResult result, String actor)
    {
        if (result.candidate() != null) insertCandidate(claim.plan().id(), claim.input(), result.candidate(), actor);
        String validation = write(Map.of("problems", result.problems(), "candidateHash",
                result.candidateHash() == null ? "" : result.candidateHash()));
        if (result.planStatus() == SolverResult.PlanStatus.CANCELLED && isCancellationRequested(claim.plan().id()))
            return 1;
        Map<String, Object> solverEnvelope = new java.util.LinkedHashMap<>();
        solverEnvelope.put("solverStatus", result.solverStatus() == null ? null : result.solverStatus().name());
        solverEnvelope.put("resultKind", result.resultKind() == null ? null : result.resultKind().name());
        solverEnvelope.put("summary", result.solverSummary());
        return mapper.complete(claim.plan().id(), claim.plan().rowVersion(), result.planStatus().name(),
                write(solverEnvelope), validation, actor);
    }

    private void insertCandidate(String planVersionId, SolverInput input, PlanCandidate candidate, String actor)
    {
        Map<String, PlanCandidate.Segment> segments = candidate.segments().stream()
                .collect(java.util.stream.Collectors.toMap(PlanCandidate.Segment::segmentId, value -> value));
        Map<String, PlanCandidate.Job> jobs = candidate.jobs().stream()
                .collect(java.util.stream.Collectors.toMap(PlanCandidate.Job::jobId, value -> value));
        Map<String, PlanCandidate.Allocation> allocations = candidate.allocations().stream()
                .collect(java.util.stream.Collectors.toMap(PlanCandidate.Allocation::allocationId, value -> value));
        int jobNo = 0;
        for (PlanCandidate.Job job : candidate.jobs().stream().sorted(Comparator.comparing(PlanCandidate.Job::jobId)).toList())
        {
            SolverInput.SharedBatchCandidate shared = "SHARED_BATCH".equals(job.jobType())
                    ? sharedSource(input, job) : null;
            int currentJobNo = ++jobNo;
            mapper.insertJob(new JobRow(job.jobId(), planVersionId, job.operationSpecId(), job.workCenterId(),
                    "JOB-" + currentJobNo, job.jobType(), shared == null ? null : "BATCH-" + currentJobNo,
                    decimal(job.plannedQuantity()), job.uomCode(), shared == null ? null : decimal(shared.capacity()),
                    shared == null ? null : shared.capacityUomCode(), shared == null ? null : shared.compatibilityKey(),
                    job.carryRunId(), job.startAt(), job.endAt()), actor);
            Map<String, String> memberQuantities = memberQuantities(input, job, shared);
            int memberNo = 0;
            for (String taskId : job.memberTaskIds())
                mapper.insertMember(new MemberRow(stable("member", job.jobId(), taskId), job.jobId(), taskId,
                        job.operationSpecId(), ++memberNo, decimal(memberQuantities.get(taskId)), job.uomCode(), null), actor);
        }
        for (PlanCandidate.Segment segment : candidate.segments())
        {
            PlanCandidate.Job job = jobs.get(segment.jobId());
            mapper.insertSegment(new SegmentRow(segment.segmentId(), segment.jobId(), job.operationSpecId(),
                    segment.phaseId(), segment.segmentNo(), segment.phaseType().name(), segment.startAt(), segment.endAt(),
                    decimal(segment.plannedQuantity()), segment.releaseAt(), nullableDecimal(segment.releaseQuantity()),
                    job.uomCode()), actor);
        }
        for (PlanCandidate.Allocation allocation : candidate.allocations())
        {
            PlanCandidate.Segment segment = segments.get(allocation.segmentId());
            mapper.insertAllocation(new AllocationRow(allocation.allocationId(), allocation.segmentId(), segment.phaseId(),
                    allocation.requirementId(), allocation.resourceId(), allocation.resourceType().name(),
                    allocation.seatNo(), decimal(allocation.capacityUsed())), actor);
        }
        for (SolverInput.PlanLock lock : input.locks())
        {
            if (lock.lockedResourceIds().size() > 1)
                throw new IllegalStateException("P0 M24 单行锁不能持久化多个资源: " + lock.lockId());
            String jobId;
            String segmentId = null;
            String allocationId = null;
            switch (lock.targetType())
            {
                case "JOB" -> jobId = lock.targetId();
                case "SEGMENT" -> {
                    PlanCandidate.Segment segment = segments.get(lock.targetId());
                    if (segment == null) throw new IllegalStateException("计划锁目标分段不存在: " + lock.lockId());
                    jobId = segment.jobId();
                    segmentId = segment.segmentId();
                }
                case "ALLOCATION" -> {
                    PlanCandidate.Allocation allocation = allocations.get(lock.targetId());
                    if (allocation == null) throw new IllegalStateException("计划锁目标分配不存在: " + lock.lockId());
                    PlanCandidate.Segment segment = segments.get(allocation.segmentId());
                    if (segment == null) throw new IllegalStateException("计划锁目标分段不存在: " + lock.lockId());
                    jobId = segment.jobId();
                    segmentId = segment.segmentId();
                    allocationId = allocation.allocationId();
                }
                default -> throw new IllegalStateException("未知计划锁目标类型: " + lock.targetType());
            }
            if (!jobs.containsKey(jobId)) throw new IllegalStateException("计划锁目标作业不存在: " + lock.lockId());
            // 求解输入中的 lockId 标识约束来源；M24 主键属于具体候选版本，不能跨版本复用。
            String candidateLockId = stable("candidate-lock", planVersionId, lock.lockId());
            mapper.insertLock(new PlanLock(candidateLockId, lock.targetType(), jobId, segmentId, allocationId,
                    lock.lockType(), lock.lockedStartAt(), lock.lockedEndAt(),
                    lock.lockedResourceIds().isEmpty() ? null : lock.lockedResourceIds().get(0),
                    lock.reason(), 0), planVersionId, actor);
        }
    }

    /**
     * 普通任务的作业数量就是成员数量；共享批次必须回到已冻结的求解输入读取每个成员的独立数量，
     * 不能把整批数量重复写给每个成员，否则 A60+B40 会被错误持久化为 100+100。
     */
    private Map<String, String> memberQuantities(SolverInput input, PlanCandidate.Job job,
            SolverInput.SharedBatchCandidate source)
    {
        if ("CARRY".equals(job.jobType()))
        {
            if (job.carryRunId() == null) throw new IllegalStateException("carry 作业缺少来源 run: " + job.jobId());
            List<SolverInput.ActualOccupancyMember> members = input.actualOccupancies().stream()
                    .filter(value -> job.carryRunId().equals(value.executionRunId()))
                    .filter(value -> value.releaseConfidence() == SolverInput.ReleaseConfidence.TRUSTED
                            && !value.members().isEmpty())
                    .findFirst().map(SolverInput.ActualOccupancy::members).orElseThrow(() ->
                            new IllegalStateException("carry 作业没有匹配的可信活动占用: " + job.jobId()));
            Map<String, String> quantities = members.stream().collect(java.util.stream.Collectors.toMap(
                    SolverInput.ActualOccupancyMember::taskId,
                    SolverInput.ActualOccupancyMember::remainingQuantity));
            if (!quantities.keySet().equals(Set.copyOf(job.memberTaskIds())))
                throw new IllegalStateException("carry 作业成员与活动 run 不一致: " + job.jobId());
            return quantities;
        }
        if (!"SHARED_BATCH".equals(job.jobType()))
        {
            if (job.memberTaskIds().size() != 1)
                throw new IllegalStateException("非共享作业必须且只能有一个任务成员: " + job.jobId());
            return Map.of(job.memberTaskIds().get(0), job.plannedQuantity());
        }
        if (source == null) throw new IllegalStateException("共享批次作业没有匹配的输入候选: " + job.jobId());
        Map<String, String> quantities = source.members().stream().collect(java.util.stream.Collectors.toMap(
                SolverInput.SharedBatchMember::taskId, SolverInput.SharedBatchMember::quantity));
        BigDecimal sum = quantities.values().stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.compareTo(decimal(job.plannedQuantity())) != 0)
            throw new IllegalStateException("共享批次成员数量之和与作业数量不一致: " + job.jobId());
        return quantities;
    }

    private SolverInput.SharedBatchCandidate sharedSource(SolverInput input, PlanCandidate.Job job)
    {
        Set<String> jobMembers = Set.copyOf(job.memberTaskIds());
        return input.sharedBatchCandidates().stream()
                .filter(value -> value.operationSpecId().equals(job.operationSpecId()))
                .filter(value -> value.workCenterId().equals(job.workCenterId()))
                .filter(value -> value.members().stream().map(SolverInput.SharedBatchMember::taskId)
                        .collect(java.util.stream.Collectors.toSet()).equals(jobMembers))
                .findFirst().orElseThrow(() -> new IllegalStateException("共享批次作业没有匹配的输入候选: " + job.jobId()));
    }

    private PlanRecord record(Map<String, Object> row)
    {
        return new PlanRecord(text(row, "id"), nullable(row, "base_version_id"), number(row, "version_no").longValue(),
                text(row, "version_name"), text(row, "request_id"), number(row, "definition_revision").longValue(),
                number(row, "execution_revision").longValue(), text(row, "input_hash"), text(row, "status"),
                instant(row.get("created_at")), instant(row.get("updated_at")), number(row, "row_version").longValue());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object value)
    {
        if (value == null) return null;
        try { return json.readValue(value.toString(), Map.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("计划摘要 JSON 解析失败", exception); }
    }

    private String write(Object value)
    {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("计划结果 JSON 序列化失败", exception); }
    }
    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private BigDecimal nullableDecimal(String value) { return value == null ? null : decimal(value); }
    private Integer nullableInteger(Map<String, Object> row, String key)
    {
        return row.get(key) == null ? null : number(row, key).intValue();
    }
    private String stable(String... values) { return java.util.UUID.nameUUIDFromBytes(String.join("\u0000", values)
            .getBytes(StandardCharsets.UTF_8)).toString(); }

    private static final class BaselineAccumulator
    {
        private final String jobId;
        private final java.time.Instant startAt;
        private final java.time.Instant endAt;
        private final List<String> members = new ArrayList<>();
        private final Set<String> resources = new LinkedHashSet<>();

        private BaselineAccumulator(String jobId, java.time.Instant startAt, java.time.Instant endAt)
        {
            this.jobId = jobId;
            this.startAt = startAt;
            this.endAt = endAt;
        }
    }

    private final class DetailJobAccumulator
    {
        private final Map<String, Object> row;
        private final List<PlanMember> members = new ArrayList<>();

        private DetailJobAccumulator(Map<String, Object> row) { this.row = row; }

        private PlanJob toJob()
        {
            return new PlanJob(text(row, "id"), text(row, "operation_spec_id"),
                    nullable(row, "work_center_id"), text(row, "job_code"), text(row, "job_type"),
                    nullable(row, "batch_code"), decimal(row, "planned_qty"), text(row, "uom_code"),
                    nullableDecimal(row, "capacity_value"), nullable(row, "capacity_uom_code"),
                    nullable(row, "compatibility_key"), nullable(row, "carry_run_id"),
                    instant(row.get("planned_start_at")),
                    instant(row.get("planned_end_at")), members);
        }
    }
}
