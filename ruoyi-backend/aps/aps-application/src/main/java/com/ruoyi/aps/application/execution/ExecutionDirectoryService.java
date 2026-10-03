package com.ruoyi.aps.application.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.ruoyi.aps.application.execution.ExecutionCatalog.*;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceManagementService;

/** 执行用途的最小目录；候选查询复用正式写入的资格和范围规则。 */
public final class ExecutionDirectoryService {
    private final ExecutionLifecycleService lifecycle;
    private final ExecutionRepository executions;
    private final QuantityRepository quantities;
    private final ResourceManagementService resources;
    private final OrderRepository orders;
    private final com.ruoyi.aps.application.planning.PlanWorkbenchService plans;
    public ExecutionDirectoryService(ExecutionLifecycleService lifecycle,ExecutionRepository executions,
            QuantityRepository quantities,ResourceManagementService resources,OrderRepository orders,
            com.ruoyi.aps.application.planning.PlanWorkbenchService plans) {
        this.lifecycle=lifecycle;this.executions=executions;this.quantities=quantities;this.resources=resources;this.orders=orders;
        this.plans=plans;
    }
    public List<DispatchPlanOption> dispatchPlans(ResourceAccessScope scope) {
        return plans.list(scope).stream().filter(p->"PUBLISHED".equals(p.status()))
                .map(p->new DispatchPlanOption(p.id(),p.versionNo(),p.versionName())).toList();
    }
    public DispatchPlan dispatchPlan(ResourceAccessScope scope,String id) {
        var detail=plans.detail(scope,id);
        if(!"PUBLISHED".equals(detail.plan().status()))throw new ApsBusinessException(ApsErrorCode.CONFLICT,"该计划已不是当前正式计划，请刷新");
        return new DispatchPlan(detail.plan().id(),detail.jobs().stream().map(j->new DispatchJob(j.id(),j.jobCode(),j.plannedQty(),
                executions.remainingAssignableQuantity(j.id()),j.uomCode())).toList());
    }
    public List<RunOption> runs(ResourceAccessScope scope,String keyword,String status,String resourceId) {
        if(keyword.length()>128 || (!status.isBlank() && !java.util.Arrays.stream(com.ruoyi.aps.domain.execution.ExecutionRunStatus.values()).anyMatch(s->s.name().equals(status))))
            throw new ApsBusinessException(ApsErrorCode.INVALID_REQUEST,"执行查询关键字或状态无效");
        return executions.listRuns(scope,keyword.trim(),status,resourceId);
    }
    public List<ResourceOption> replacements(ResourceAccessScope scope,String runId,String oldId,Instant at) {
        var detail=lifecycle.detail(scope,runId);
        if(detail.run().status()!=com.ruoyi.aps.domain.execution.ExecutionRunStatus.RUNNING) return List.of();
        var occupancy=detail.occupancies().stream().filter(o->o.resourceId().equals(oldId)&&o.status()==OccupancyStatus.ACTIVE).findFirst()
                .orElseThrow(()->new ApsBusinessException(ApsErrorCode.NOT_FOUND,"所选实际占用已结束，请刷新"));
        if(occupancy.sourcePlanAllocationId()==null || occupancy.activityType()==OccupancyActivityType.PAUSE_HOLD)return List.of();
        var active=detail.occupancies().stream().filter(o->o.status()==OccupancyStatus.ACTIVE).map(ActualOccupancy::resourceId).toList();
        return resources.listResources(scope,null,null).stream().filter(r->!active.contains(r.id()))
                .filter(r->executions.isReplacementCompatible(runId,occupancy,r.id(),at) && !executions.hasResourceConflict(r.id(),at,runId))
                .map(r->new ResourceOption(r.id(),r.code(),r.name())).toList();
    }
    public List<ReportMemberBalance> members(ResourceAccessScope scope,String runId) {
        var run=lifecycle.detail(scope,runId).run();
        return executions.listReportMembers(run.planJobId()).stream().map(m->new ReportMemberBalance(m.id(),m.memberNo(),m.taskId(),m.taskName(),m.plannedQty(),m.uomCode(),
                m.plannedQty().subtract(quantities.effectiveProcessedForMember(runId,m.id()))
                        .min(run.assignedQty().subtract(quantities.effectiveProcessedForRun(runId))).max(BigDecimal.ZERO))).toList();
    }
    public List<MovementTarget> targets(ResourceAccessScope scope,String runId,String lotId) {
        lifecycle.detail(scope,runId);
        OutputLot lot=quantities.listOutputLotsByRun(runId).stream().filter(l->l.id().equals(lotId)).findFirst()
                .orElseThrow(()->new ApsBusinessException(ApsErrorCode.NOT_FOUND,"产出批不属于该执行单"));
        List<RunOption> runs=executions.listRuns(scope,"","","");
        return orders.listOrders().stream().filter(o->orders.isOrderInScope(scope,o.id())).flatMap(o-> {
            var tasks=orders.listTasks(o.id());
            return orders.listMaterialDemands(o.id()).stream().filter(d->Objects.equals(d.itemId(),lot.itemId())
                    && Objects.equals(d.uomCode(),lot.uomCode()) && (d.sourceTaskId()==null || d.sourceTaskId().equals(lot.sourceTaskId())))
                    .map(d->new MovementTarget(d.id(),d.targetTaskId(),o.orderNo(),tasks.stream().filter(t->t.id().equals(d.targetTaskId())).map(t->t.taskName()).findFirst().orElse("任务"),
                            d.requiredQty().subtract(quantities.allocatedForDemand(d.id())).max(BigDecimal.ZERO),
                            quantities.reservedForDemandAndLot(d.id(),lot.id()),d.uomCode(),
                            runs.stream().filter(r->!List.of("CANCELLED","COMPLETED").contains(r.run().status().name()) && executions.isRunForTask(r.run().id(),d.targetTaskId())).toList()));
        }).sorted(java.util.Comparator.comparing(MovementTarget::orderNo).thenComparing(MovementTarget::materialDemandId)).limit(200).toList();
    }
    public List<QuantityEvent> events(ResourceAccessScope scope,String runId,String lotId) {
        lifecycle.detail(scope,runId);
        if(quantities.listOutputLotsByRun(runId).stream().noneMatch(l->l.id().equals(lotId)))throw new ApsBusinessException(ApsErrorCode.NOT_FOUND,"产出批不属于该执行单");
        return quantities.listEvents(lotId);
    }
    public record ResourceOption(String resourceId,String code,String name) { }
    public record DispatchPlanOption(String planVersionId,long versionNo,String versionName) { }
    public record DispatchPlan(String planVersionId,List<DispatchJob> jobs) { }
    public record DispatchJob(String jobId,String jobCode,BigDecimal plannedQty,BigDecimal remainingQty,String uomCode) { }
    public record ReportMemberBalance(String id,int memberNo,String taskId,String taskName,BigDecimal plannedQty,String uomCode,BigDecimal remainingQty) { }
    public record MovementTarget(String materialDemandId,String targetTaskId,String orderNo,String taskName,
            BigDecimal remainingDemandQty,BigDecimal reservedQty,String uomCode,List<RunOption> executionRuns) { }
}
