package com.ruoyi.aps.api.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.planning.PlanRepository;
import com.ruoyi.aps.application.planning.PlanLockService;
import com.ruoyi.aps.application.planning.PlanAdjustmentService;
import com.ruoyi.aps.application.planning.PlanCandidateLifecycleService;
import com.ruoyi.aps.application.planning.PlanPublishService;
import com.ruoyi.aps.application.planning.PlanStructuralAdjustmentService;
import com.ruoyi.aps.application.planning.PlanWorkbenchService;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.solver.contract.Problem;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IMP-08 生产工作台：候选审查、人工计划、锁、废弃和系统内发布。 */
@RestController
@RequestMapping("/api/aps/v1/plan-versions")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
public class ApsPlanWorkbenchController
{
    private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";
    private final PlanWorkbenchService service;
    private final PlanLockService locks;
    private final PlanAdjustmentService adjustments;
    private final PlanStructuralAdjustmentService structuralAdjustments;
    private final PlanPublishService publications;
    private final PlanCandidateLifecycleService candidateLifecycle;
    private final ApsAuditActorProvider actors;

    public ApsPlanWorkbenchController(PlanWorkbenchService service, PlanLockService locks,
            PlanAdjustmentService adjustments, PlanStructuralAdjustmentService structuralAdjustments,
            PlanPublishService publications,
            PlanCandidateLifecycleService candidateLifecycle, ApsAuditActorProvider actors)
    {
        this.service = service;
        this.locks = locks;
        this.adjustments = adjustments;
        this.structuralAdjustments = structuralAdjustments;
        this.publications = publications;
        this.candidateLifecycle = candidateLifecycle;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("@ss.hasPermi('aps:planning:view')")
    public List<PlanListItem> list()
    {
        return service.list(scope(actors.currentActor())).stream().map(plan -> new PlanListItem(
                plan.id(), plan.versionNo(), plan.versionName(), plan.status(), plan.requestId(),
                plan.baseVersionId(), plan.rowVersion(), plan.updatedAt())).toList();
    }

    public record PlanListItem(String planVersionId, long versionNo, String versionName, String status,
            String requestId, String baseVersionId, long rowVersion, Instant updatedAt) { }

    @GetMapping("/{planVersionId}")
    @PreAuthorize("@ss.hasPermi('aps:planning:view')")
    public PlanDetailResponse detail(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId)
    {
        return response(service.detail(scope(actors.currentActor()), planVersionId));
    }

    @GetMapping("/{planVersionId}/comparison")
    @PreAuthorize("@ss.hasPermi('aps:planning:view')")
    public PlanComparisonResponse comparison(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @RequestParam(required = false) @Pattern(regexp = UUID_PATTERN) String baseVersionId)
    {
        PlanWorkbenchService.PlanComparison value = service.compare(scope(actors.currentActor()), planVersionId,
                baseVersionId);
        return new PlanComparisonResponse("1.0", "PLAN_VERSION_COMPARISON", value.baseVersionId(),
                value.targetVersionId(), value.summary(), value.jobs());
    }

    @PostMapping("/{planVersionId}/locks")
    @PreAuthorize("@ss.hasPermi('aps:planning:lock')")
    public PlanDetailResponse createLock(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @Valid @RequestBody CreateLockRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        return response(locks.create(scope(actor), new PlanLockService.CreateLock(planVersionId,
                request.expectedPlanRowVersion(), request.targetType(), request.targetId(), request.lockType(),
                request.requestedResourceId(), request.reason()), actor.username()));
    }

    @DeleteMapping("/{planVersionId}/locks/{lockId}")
    @PreAuthorize("@ss.hasPermi('aps:planning:lock')")
    public PlanDetailResponse deleteLock(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @PathVariable @Pattern(regexp = UUID_PATTERN) String lockId,
            @RequestParam @Min(0) long expectedPlanRowVersion,
            @RequestParam @Min(0) long expectedLockRowVersion)
    {
        ApsAuditActor actor = actors.currentActor();
        return response(locks.delete(scope(actor), planVersionId, lockId, expectedPlanRowVersion,
                expectedLockRowVersion, actor.username()));
    }

    @PostMapping("/{planVersionId}/adjustments")
    @PreAuthorize("@ss.hasPermi('aps:planning:adjust')")
    public ResponseEntity<AdjustmentAcceptedResponse> createAdjustment(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_PATTERN) String requestId,
            @Valid @RequestBody CreateAdjustmentRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        PlanAdjustmentService.CreatedAdjustment created = adjustments.create(scope(actor),
                new PlanAdjustmentService.Adjustment(requestId, planVersionId, request.expectedBaseRowVersion(),
                        ApsUtcInput.parse(request.capturedAt()), request.targetType(), request.targetId(),
                        ApsUtcInput.parse(request.requestedStartAt()), ApsUtcInput.parse(request.requestedEndAt()),
                        request.requestedResourceId(), request.reason()), actor.username());
        var impact = created.impact();
        AdjustmentAcceptedResponse response = new AdjustmentAcceptedResponse("1.0", "PLAN_ADJUSTMENT_ACCEPTED",
                created.plan().requestId(), created.plan().id(), created.plan().baseVersionId(),
                created.plan().status(), created.reused(), created.baseCandidateHash(),
                new ImpactResponse(impact.affectedTaskIds(), impact.affectedResourceIds(), impact.reasons(),
                        impact.globalRevalidationRequired()));
        return ResponseEntity.status(created.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{planVersionId}/structural-adjustments")
    @PreAuthorize("@ss.hasPermi('aps:planning:adjust')")
    public ResponseEntity<StructuralAdjustmentAcceptedResponse> createStructuralAdjustment(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID_PATTERN) String requestId,
            @Valid @RequestBody CreateStructuralAdjustmentRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        PlanStructuralAdjustmentService.CreatedStructuralAdjustment created = structuralAdjustments.create(
                scope(actor), new PlanStructuralAdjustmentService.StructuralAdjustment(requestId, planVersionId,
                        request.expectedBaseRowVersion(), ApsUtcInput.parse(request.capturedAt()), request.action(),
                        request.orderId(), request.parentLotId(), request.splitQuantity(), request.compatibilityKey(),
                        request.members() == null ? List.of() : request.members().stream().map(member ->
                                new PlanStructuralAdjustmentService.BatchMember(member.taskId(), member.quantity()))
                                .toList(), request.reason()), actor.username());
        var impact = created.impact();
        StructuralAdjustmentAcceptedResponse response = new StructuralAdjustmentAcceptedResponse("1.0",
                "PLAN_STRUCTURAL_ADJUSTMENT_ACCEPTED", created.plan().requestId(), created.plan().id(),
                created.plan().baseVersionId(), created.plan().status(), created.reused(),
                request.action().name(), created.derivedLotId(), created.baseCandidateHash(),
                new ImpactResponse(impact.affectedTaskIds(), impact.affectedResourceIds(), impact.reasons(),
                        impact.globalRevalidationRequired()));
        return ResponseEntity.status(created.reused() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{planVersionId}/publish")
    @PreAuthorize("@ss.hasPermi('aps:planning:publish')")
    public PublishResponse publish(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @Valid @RequestBody PublishRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        PlanPublishService.PublishedPlan result = publications.publish(scope(actor), planVersionId,
                request.expectedPlanRowVersion(), request.reason(), actor.username());
        return new PublishResponse("1.0", "PLAN_PUBLISHED", response(result.detail()), result.reused(),
                result.supersededVersionId(), result.reason(), result.outboundStatus());
    }

    @PostMapping("/{planVersionId}/discard")
    @PreAuthorize("@ss.hasPermi('aps:planning:cancel')")
    public DiscardCandidateResponse discard(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String planVersionId,
            @Valid @RequestBody DiscardCandidateRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        PlanCandidateLifecycleService.DiscardedCandidate result = candidateLifecycle.discard(scope(actor),
                planVersionId, request.expectedPlanRowVersion(), request.reason(), actor.username());
        return new DiscardCandidateResponse("1.0", "PLAN_CANDIDATE_DISCARDED", response(result.detail()),
                result.reason(), result.discardedAt());
    }

    private PlanDetailResponse response(PlanRepository.PlanDetail detail)
    {
        PlanRepository.PlanRecord plan = detail.plan();
        java.util.Set<String> plannedTaskIds = detail.jobs().stream().flatMap(job -> job.members().stream())
                .map(PlanRepository.PlanMember::taskId).collect(java.util.stream.Collectors.toSet());
        List<String> unplannedTaskIds = detail.input() == null ? List.of() : detail.input().tasks().stream()
                .map(com.ruoyi.aps.solver.contract.SolverInput.Task::taskId)
                .filter(taskId -> !plannedTaskIds.contains(taskId)).sorted().toList();
        PlanWorkbenchService.PlanFreshness freshness = service.freshness(detail);
        PlanVersionSummary version = new PlanVersionSummary(plan.id(), plan.baseVersionId(), plan.versionNo(),
                plan.versionName(), plan.status(), plan.definitionRevision(), plan.executionRevision(),
                plan.inputHash(), plan.rowVersion(), plan.updatedAt());
        return new PlanDetailResponse("1.0", "PLAN_VERSION_DETAIL", version, detail.candidateHash(),
                detail.solverStatus() == null ? null : detail.solverStatus().name(),
                detail.resultKind() == null ? null : detail.resultKind().name(), unplannedTaskIds,
                freshness.inputCapturedAt(), freshness.latestFactUpdatedAt(), freshness.stale(),
                detail.jobs().stream().map(job -> new PlanJobResponse(job.id(), job.operationSpecId(),
                        job.workCenterId(), job.jobCode(), job.jobType(), job.batchCode(), decimal(job.plannedQty()),
                        job.uomCode(), decimal(job.capacityValue()), job.capacityUomCode(), job.compatibilityKey(),
                        job.carryRunId(), job.startAt(), job.endAt(), job.members().stream().map(member -> new PlanMemberResponse(
                                member.id(), member.taskId(), member.memberNo(), decimal(member.plannedQty()),
                                member.uomCode())).toList())).toList(),
                detail.segments().stream().map(segment -> new PlanSegmentResponse(segment.id(), segment.jobId(),
                        segment.phaseId(), segment.segmentNo(), segment.phaseType(), segment.startAt(), segment.endAt(),
                        decimal(segment.plannedQty()), segment.releaseAt(), decimal(segment.releaseQty()),
                        segment.uomCode())).toList(),
                detail.allocations().stream().map(allocation -> new PlanAllocationResponse(allocation.id(),
                        allocation.segmentId(), allocation.phaseId(), allocation.requirementId(),
                        allocation.resourceId(), allocation.allocationRole(), allocation.seatNo(),
                        decimal(allocation.capacityUsed()))).toList(), detail.locks(), detail.problems());
    }

    private ResourceAccessScope scope(ApsAuditActor actor)
    {
        return new ResourceAccessScope(actor.userId(),
                SecurityUtils.isAdmin() || SecurityUtils.hasPermi("aps:scope:manage"));
    }

    private String decimal(java.math.BigDecimal value)
    {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    public record PlanDetailResponse(String schemaVersion, String contractType, PlanVersionSummary version,
            String candidateHash, String solverStatus, String resultKind, List<String> unplannedTaskIds,
            Instant inputCapturedAt, Instant latestFactUpdatedAt, boolean stale,
            List<PlanJobResponse> jobs, List<PlanSegmentResponse> segments,
            List<PlanAllocationResponse> allocations, List<PlanRepository.PlanLock> locks,
            List<Problem> problems) { }

    public record PlanVersionSummary(String planVersionId, String baseVersionId, long versionNo,
            String versionName, String status, long definitionRevision, long executionRevision,
            String inputHash, long rowVersion, Instant updatedAt) { }

    public record PlanJobResponse(String jobId, String operationSpecId, String workCenterId, String jobCode,
            String jobType, String batchCode, String plannedQty, String uomCode, String capacityValue,
            String capacityUomCode, String compatibilityKey, String carryRunId, Instant startAt, Instant endAt,
            List<PlanMemberResponse> members) { }

    public record PlanMemberResponse(String id, String taskId, int memberNo, String plannedQty,
            String uomCode) { }

    public record PlanSegmentResponse(String id, String jobId, String phaseId, int segmentNo, String phaseType,
            Instant startAt, Instant endAt, String plannedQty, Instant releaseAt, String releaseQty,
            String uomCode) { }

    public record PlanAllocationResponse(String id, String segmentId, String phaseId, String requirementId,
            String resourceId, String allocationRole, int seatNo, String capacityUsed) { }

    public record PlanComparisonResponse(String schemaVersion, String contractType, String baseVersionId,
            String targetVersionId, PlanWorkbenchService.ChangeSummary summary,
            List<PlanWorkbenchService.JobChange> jobs) { }

    public record CreateLockRequest(@Min(0) long expectedPlanRowVersion,
            @NotBlank @Pattern(regexp = "JOB|SEGMENT|ALLOCATION") String targetType,
            @NotBlank @Pattern(regexp = UUID_PATTERN) String targetId,
            @NotBlank @Pattern(regexp = "TIME|RESOURCE|FULL") String lockType,
            @Pattern(regexp = UUID_PATTERN) String requestedResourceId,
            @NotBlank @Size(max = 500) String reason) { }

    public record CreateAdjustmentRequest(@Min(0) long expectedBaseRowVersion,
            @NotBlank String capturedAt,
            @NotBlank @Pattern(regexp = "JOB|SEGMENT|ALLOCATION") String targetType,
            @NotBlank @Pattern(regexp = UUID_PATTERN) String targetId,
            @NotBlank String requestedStartAt,
            @NotBlank String requestedEndAt,
            @Pattern(regexp = UUID_PATTERN) String requestedResourceId,
            @NotBlank @Size(max = 500) String reason) { }

    public record CreateStructuralAdjustmentRequest(@Min(0) long expectedBaseRowVersion,
            @NotBlank String capturedAt,
            @NotNull PlanStructuralAdjustmentService.Action action,
            @Pattern(regexp = UUID_PATTERN) String orderId,
            @Pattern(regexp = UUID_PATTERN) String parentLotId,
            @DecimalMin(value = "0", inclusive = false) BigDecimal splitQuantity,
            @Size(max = 128) String compatibilityKey,
            @Size(max = 100) List<@Valid StructuralBatchMemberRequest> members,
            @NotBlank @Size(max = 500) String reason) { }

    public record StructuralBatchMemberRequest(@NotBlank @Pattern(regexp = UUID_PATTERN) String taskId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) { }

    public record ImpactResponse(List<String> affectedTaskIds, List<String> affectedResourceIds,
            Map<String, List<String>> reasons, boolean globalRevalidationRequired) { }

    public record PublishRequest(@Min(0) long expectedPlanRowVersion,
            @NotBlank @Size(max = 500) String reason) { }

    public record PublishResponse(String schemaVersion, String contractType, PlanDetailResponse plan,
            boolean reused, String supersededVersionId, String reason, String outboundStatus) { }

    public record DiscardCandidateRequest(@Min(0) long expectedPlanRowVersion,
            @NotBlank @Size(max = 500) String reason) { }

    public record DiscardCandidateResponse(String schemaVersion, String contractType, PlanDetailResponse plan,
            String reason, Instant discardedAt) { }

    public record AdjustmentAcceptedResponse(String schemaVersion, String contractType, String requestId,
            String planVersionId, String basePlanVersionId, String planStatus, boolean reused,
            String baseCandidateHash, ImpactResponse impact) { }

    public record StructuralAdjustmentAcceptedResponse(String schemaVersion, String contractType,
            String requestId, String planVersionId, String basePlanVersionId, String planStatus,
            boolean reused, String action, String derivedLotId, String baseCandidateHash,
            ImpactResponse impact) { }
}
