package com.ruoyi.aps.application.planning;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceManagementService;

/** IMP-08 工作台只读详情与版本差异；不在读取动作中修改候选。 */
public final class PlanWorkbenchService
{
    private final PlanRepository plans;
    private final ResourceManagementService resources;

    public PlanWorkbenchService(PlanRepository plans, ResourceManagementService resources)
    {
        this.plans = Objects.requireNonNull(plans);
        this.resources = Objects.requireNonNull(resources);
    }

    public PlanRepository.PlanDetail detail(ResourceAccessScope access, String planVersionId)
    {
        PlanRepository.PlanDetail detail = plans.findDetail(planVersionId).orElseThrow(() ->
                new ApsBusinessException(ApsErrorCode.NOT_FOUND, "计划版本不存在"));
        assertVisible(access, detail);
        return detail;
    }

    public List<PlanRepository.PlanRecord> list(ResourceAccessScope access)
    {
        List<String> workshopIds = access.allWorkshops() ? List.of()
                : resources.listWorkshops(access).stream().map(value -> value.id()).toList();
        if (!access.allWorkshops() && workshopIds.isEmpty()) return List.of();
        return plans.listVisiblePlans(workshopIds, access.allWorkshops());
    }

    /** 与发布前事实水位使用同一口径，页面只提示，不在读取动作中改变候选状态。 */
    public PlanFreshness freshness(PlanRepository.PlanDetail detail)
    {
        Instant capturedAt = detail.input() == null ? null : detail.input().capturedAt();
        Instant latestFactUpdatedAt = plans.latestPlanningFactUpdatedAt().orElse(null);
        return new PlanFreshness(capturedAt, latestFactUpdatedAt,
                capturedAt != null && latestFactUpdatedAt != null && latestFactUpdatedAt.isAfter(capturedAt));
    }

    public PlanComparison compare(ResourceAccessScope access, String targetVersionId, String requestedBaseVersionId)
    {
        PlanRepository.PlanDetail target = detail(access, targetVersionId);
        String baseVersionId = requestedBaseVersionId == null || requestedBaseVersionId.isBlank()
                ? target.plan().baseVersionId() : requestedBaseVersionId;
        if (baseVersionId == null)
            throw new ApsBusinessException(ApsErrorCode.INVALID_REQUEST, "当前计划没有基线，请明确指定 baseVersionId");
        PlanRepository.PlanDetail base = detail(access, baseVersionId);

        Map<String, JobProjection> before = project(base);
        Map<String, JobProjection> after = project(target);
        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<JobChange> changes = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int changed = 0;
        int unchanged = 0;
        for (String key : keys)
        {
            JobProjection left = before.get(key);
            JobProjection right = after.get(key);
            ChangeType type;
            List<String> dimensions = new ArrayList<>();
            if (left == null)
            {
                type = ChangeType.ADDED;
                added++;
            }
            else if (right == null)
            {
                type = ChangeType.REMOVED;
                removed++;
            }
            else
            {
                if (!left.startAt().equals(right.startAt()) || !left.endAt().equals(right.endAt()))
                    dimensions.add("TIME");
                if (!left.resourceIds().equals(right.resourceIds())) dimensions.add("RESOURCE");
                if (!left.jobType().equals(right.jobType())) dimensions.add("JOB_TYPE");
                if (left.plannedQty().compareTo(right.plannedQty()) != 0
                        || !left.uomCode().equals(right.uomCode())) dimensions.add("QUANTITY");
                if (!Objects.equals(left.workCenterId(), right.workCenterId())) dimensions.add("WORK_CENTER");
                type = dimensions.isEmpty() ? ChangeType.UNCHANGED : ChangeType.CHANGED;
                if (type == ChangeType.CHANGED) changed++; else unchanged++;
            }
            JobProjection identity = right == null ? left : right;
            changes.add(new JobChange(type.name(), identity.memberTaskIds(), dimensions,
                    left == null ? null : left.jobId(), right == null ? null : right.jobId(),
                    left == null ? null : left.startAt(), left == null ? null : left.endAt(),
                    right == null ? null : right.startAt(), right == null ? null : right.endAt(),
                    left == null ? List.of() : left.resourceIds(),
                    right == null ? List.of() : right.resourceIds()));
        }
        return new PlanComparison(baseVersionId, targetVersionId,
                new ChangeSummary(added, removed, changed, unchanged), changes);
    }

    private Map<String, JobProjection> project(PlanRepository.PlanDetail detail)
    {
        Map<String, String> segmentJobs = new LinkedHashMap<>();
        for (PlanRepository.PlanSegment segment : detail.segments()) segmentJobs.put(segment.id(), segment.jobId());
        Map<String, Set<String>> jobResources = new LinkedHashMap<>();
        for (PlanRepository.PlanAllocation allocation : detail.allocations())
        {
            String jobId = segmentJobs.get(allocation.segmentId());
            if (jobId != null) jobResources.computeIfAbsent(jobId, ignored -> new LinkedHashSet<>())
                    .add(allocation.resourceId());
        }
        Map<String, JobProjection> result = new LinkedHashMap<>();
        for (PlanRepository.PlanJob job : detail.jobs())
        {
            List<String> members = job.members().stream().map(PlanRepository.PlanMember::taskId)
                    .sorted().toList();
            String key = String.join("\u0000", members);
            if (members.isEmpty() || result.containsKey(key))
                throw new ApsBusinessException(ApsErrorCode.CONFLICT, "计划作业成员不能稳定匹配版本差异");
            List<String> resourceIds = jobResources.getOrDefault(job.id(), Set.of()).stream().sorted().toList();
            result.put(key, new JobProjection(job.id(), members, job.jobType(), job.workCenterId(), job.plannedQty(),
                    job.uomCode(), job.startAt(), job.endAt(), resourceIds));
        }
        return result;
    }

    private void assertVisible(ResourceAccessScope access, PlanRepository.PlanDetail detail)
    {
        if (access.allWorkshops()) return;
        Set<String> visible = resources.listWorkshops(access).stream().map(value -> value.id())
                .collect(java.util.stream.Collectors.toSet());
        if (!visible.containsAll(detail.input().scope().workshopIds()))
            throw new ApsBusinessException(ApsErrorCode.NOT_FOUND, "计划版本不存在");
    }

    private enum ChangeType { ADDED, REMOVED, CHANGED, UNCHANGED }

    private record JobProjection(String jobId, List<String> memberTaskIds, String jobType, String workCenterId,
            java.math.BigDecimal plannedQty, String uomCode, Instant startAt, Instant endAt,
            List<String> resourceIds) { }

    public record ChangeSummary(int added, int removed, int changed, int unchanged) { }

    public record JobChange(String changeType, List<String> memberTaskIds, List<String> dimensions,
            String baseJobId, String targetJobId, Instant baseStartAt, Instant baseEndAt,
            Instant targetStartAt, Instant targetEndAt, List<String> baseResourceIds,
            List<String> targetResourceIds)
    {
        public JobChange
        {
            memberTaskIds = List.copyOf(memberTaskIds);
            dimensions = List.copyOf(dimensions);
            baseResourceIds = List.copyOf(baseResourceIds);
            targetResourceIds = List.copyOf(targetResourceIds);
        }
    }

    public record PlanComparison(String baseVersionId, String targetVersionId, ChangeSummary summary,
            List<JobChange> jobs)
    {
        public PlanComparison { jobs = List.copyOf(jobs); }
    }

    public record PlanFreshness(Instant inputCapturedAt, Instant latestFactUpdatedAt, boolean stale) { }
}
