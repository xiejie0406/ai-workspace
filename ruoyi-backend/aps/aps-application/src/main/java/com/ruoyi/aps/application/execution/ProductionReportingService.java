package com.ruoyi.aps.application.execution;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ActualOccupancy;
import com.ruoyi.aps.application.execution.ExecutionCatalog.DispositionType;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ExecutionRun;
import com.ruoyi.aps.application.execution.ExecutionCatalog.OccupancyStatus;
import com.ruoyi.aps.application.execution.ExecutionCatalog.OutputLot;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ProductionReport;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QualityDecision;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QualityStatus;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QuantityEvent;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ReportTarget;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ReportType;
import com.ruoyi.aps.application.execution.ExecutionCatalog.MaterialTarget;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QuantityOperation;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ReservedAllocation;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.foundation.ApsTransactionOperations;
import com.ruoyi.aps.application.order.OrderRepository;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.domain.execution.ExecutionAction;
import com.ruoyi.aps.domain.execution.ExecutionRunStateMachine;
import com.ruoyi.aps.domain.execution.ExecutionRunStatus;
import com.ruoyi.aps.domain.execution.ExecutionTransitionContext;
import com.ruoyi.aps.domain.quantity.ProductionReportQuantities;
import com.ruoyi.aps.domain.quantity.QuantityLedger;
import com.ruoyi.aps.domain.quantity.QuantityLedger.Bucket;
import com.ruoyi.aps.domain.quantity.QuantityLedger.EventType;
import com.ruoyi.aps.domain.quantity.QuantityLedger.Movement;

/** M27～M29 报工、质量处置、数量投影和冲正事务。 */
public final class ProductionReportingService
{
    private final ExecutionRepository executions;
    private final QuantityRepository quantities;
    private final OrderRepository orders;
    private final RecoveryOrderService recoveries;
    private final ApsTransactionOperations transactions;
    private final Runnable afterReportInserted;

    public ProductionReportingService(ExecutionRepository executions, QuantityRepository quantities,
            ApsTransactionOperations transactions)
    {
        this(executions, quantities, null, transactions, () -> { });
    }

    /** 仅供事务故障注入测试使用；生产装配使用三参数构造器。 */
    public ProductionReportingService(ExecutionRepository executions, QuantityRepository quantities,
            ApsTransactionOperations transactions, Runnable afterReportInserted)
    {
        this(executions, quantities, null, transactions, afterReportInserted);
    }

    /** 生产装配使用该构造器，使质量处置与派生批/层级状态处于同一事务。 */
    public ProductionReportingService(ExecutionRepository executions, QuantityRepository quantities,
            OrderRepository orders, ApsTransactionOperations transactions)
    {
        this(executions, quantities, orders, transactions, () -> { });
    }

    private ProductionReportingService(ExecutionRepository executions, QuantityRepository quantities,
            OrderRepository orders, ApsTransactionOperations transactions, Runnable afterReportInserted)
    {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.quantities = Objects.requireNonNull(quantities, "quantities");
        this.orders = orders;
        this.recoveries = orders == null ? null : new RecoveryOrderService(orders);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.afterReportInserted = Objects.requireNonNull(afterReportInserted, "afterReportInserted");
    }

    public Changed createReport(ResourceAccessScope scope, CreateReport command, String actor)
    {
        requireReportCommand(command);
        return transactions.serializable(() -> {
            var replay = quantities.findReportByRequestId(command.requestId());
            if (replay.isPresent())
            {
                ProductionReport report = replay.get();
                requireScope(scope, report.executionRunId());
                requireSameReport(report, command);
                return new Changed(executions.findRun(report.executionRunId()).orElseThrow(), true);
            }
            ExecutionRun run = lockRun(scope, command.executionRunId(), command.expectedRunRowVersion());
            if (run.status() != ExecutionRunStatus.RUNNING)
            {
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, "仅 RUNNING run 可提交现场报工");
            }
            if (run.actualStartAt() != null && command.reportedAt().isBefore(run.actualStartAt()))
            {
                throw error(ApsErrorCode.INVALID_REQUEST, "报工时间不能早于实际开工时间");
            }
            ReportTarget target = target(command.executionRunId(), command.planJobMemberId(), command.taskId());
            requireQuantityScope(run, target, command.uomCode(), command.quantities(), null);
            if (command.reportType() == ReportType.COMPLETE && executions.hasLaterPlannedSegment(run.id()))
                throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION,
                        "完工报工只允许在最后计划阶段提交，请先推进阶段");
            ProductionReport report = new ProductionReport(stable("production-report", command.requestId()),
                    run.planJobId(), run.id(), target.planJobMemberId(), target.taskId(), command.reportType(),
                    command.reportedAt(), command.quantities(), command.uomCode(), command.defectReason(), null,
                    command.requestId(), command.operatorUserId() == null ? actor : command.operatorUserId(), 0);
            quantities.insertReport(report, actor);
            afterReportInserted.run();
            createOutputLots(report, target, command.reportedAt(), actor, List.of());
            finishOrAdvance(run, report.reportType() == ReportType.COMPLETE, target.qualityGateRequired(),
                    command.reportedAt(), actor);
            recompute(target.taskId(), actor);
            return new Changed(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    public Changed correctReport(ResourceAccessScope scope, String reportId, CorrectReport command, String actor)
    {
        requireCorrection(command);
        return transactions.serializable(() -> {
            var replay = quantities.findReportByRequestId(command.requestId());
            if (replay.isPresent())
            {
                ProductionReport corrected = replay.get();
                requireScope(scope, corrected.executionRunId());
                if (!Objects.equals(corrected.correctionOfId(), reportId)
                        || corrected.quantities().processedQty().compareTo(command.quantities().processedQty()) != 0
                        || !corrected.reportedAt().equals(command.reportedAt())
                        || !Objects.equals(corrected.defectReason(), command.reason()))
                {
                    throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他报工更正意图");
                }
                return new Changed(executions.findRun(corrected.executionRunId()).orElseThrow(), true);
            }
            ProductionReport source = quantities.findReportForUpdate(reportId)
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "待更正报工不存在"));
            requireScope(scope, source.executionRunId());
            if (source.rowVersion() != command.expectedReportRowVersion())
            {
                throw error(ApsErrorCode.STALE_VERSION, "报工行版本已变化");
            }
            if (quantities.isReportCorrected(source.id()))
            {
                throw error(ApsErrorCode.CONFLICT, "该报工已存在替代快照，请更正当前有效记录");
            }
            ExecutionRun run = lockRun(scope, source.executionRunId(), command.expectedRunRowVersion());
            ReportTarget target = target(run.id(), source.planJobMemberId(), source.taskId());
            requireQuantityScope(run, target, source.uomCode(), command.quantities(), source);

            List<OutputLot> oldLots = quantities.listOutputLotsByReport(source.id());
            for (OutputLot oldLot : oldLots)
            {
                reverseLot(oldLot, command.requestId(), command.reportedAt(), command.reason(), actor);
            }
            ProductionReport correction = new ProductionReport(stable("production-report", command.requestId()),
                    source.planJobId(), source.executionRunId(), source.planJobMemberId(), source.taskId(),
                    ReportType.CORRECTION, command.reportedAt(), command.quantities(), source.uomCode(),
                    command.reason(), source.id(), command.requestId(), actor, 0);
            quantities.insertReport(correction, actor);
            afterReportInserted.run();
            createOutputLots(correction, target, command.reportedAt(), actor, oldLots);
            advanceAfterQualityOrCorrection(run, actor);
            recompute(target.taskId(), actor);
            return new Changed(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    public Changed decideQuality(ResourceAccessScope scope, String outputLotId, DecideQuality command, String actor)
    {
        requireDecision(command);
        return transactions.serializable(() -> {
            String markerId = stable("quality-decision", command.requestId(), "0");
            var replay = quantities.findEventForUpdate(markerId);
            if (replay.isPresent())
            {
                QuantityEvent event = replay.get();
                OutputLot replayLot = quantities.findOutputLotForUpdate(event.outputLotId()).orElseThrow();
                ProductionReport report = quantities.findReportForUpdate(replayLot.sourceReportId()).orElseThrow();
                requireScope(scope, report.executionRunId());
                if (!replayedDecisionMatches(replayLot, event, outputLotId, command))
                {
                    throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他质量数量意图");
                }
                return new Changed(executions.findRun(report.executionRunId()).orElseThrow(), true);
            }
            OutputLot lot = quantities.findOutputLotForUpdate(outputLotId)
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "产出批不存在"));
            ProductionReport report = quantities.findReportForUpdate(lot.sourceReportId()).orElseThrow();
            ExecutionRun run = executions.findRunForUpdate(report.executionRunId()).orElseThrow();
            requireScope(scope, run.id());
            if (run.rowVersion() == command.expectedRunRowVersion() + 1 && isNoMovementReplay(lot, command))
                return new Changed(run, true);
            if (run.rowVersion() != command.expectedRunRowVersion())
                throw error(ApsErrorCode.STALE_VERSION, "执行 run 行版本已变化");
            if (lot.rowVersion() != command.expectedOutputLotRowVersion())
            {
                throw error(ApsErrorCode.STALE_VERSION, "产出批行版本已变化");
            }
            if (lot.qualityStatus() == QualityStatus.CLOSED)
            {
                throw error(ApsErrorCode.QUALITY_DISPOSITION_REQUIRED, "已关闭产出批不能再次质量处置");
            }

            QuantityLedger ledger = ledger(lot.id());
            List<DecisionMovement> movements = decisionMovements(lot.id(), ledger, command);
            java.util.Set<String> affectedDemands = new java.util.LinkedHashSet<>();
            for (int index = 0; index < movements.size(); index++)
            {
                DecisionMovement decisionMovement = movements.get(index);
                Movement movement = decisionMovement.movement();
                ledger.apply(movement);
                quantities.insertEvent(new QuantityEvent(markerIdFor(command.requestId(), index), lot.id(),
                        lot.itemId(), decisionMovement.materialDemandId(), null, null,
                        decisionMovement.targetTaskId(), null, movement.eventType(), movement.fromBucket(),
                        movement.toBucket(), movement.quantity(), lot.uomCode(),
                        index == 0 ? command.requestId() : stable("quality-request", command.requestId(),
                                Integer.toString(index)), command.occurredAt(), actor, command.reason()), actor);
                if (decisionMovement.materialDemandId() != null)
                    affectedDemands.add(decisionMovement.materialDemandId());
            }
            OutputLot projected = decidedLot(lot, ledger, command, actor);
            if (quantities.updateOutputLotProjection(projected, lot.rowVersion(), actor) != 1)
            {
                throw error(ApsErrorCode.STALE_VERSION, "产出批已被并发修改");
            }
            affectedDemands.forEach(demandId -> quantities.refreshMaterialDemandStatus(demandId, actor));
            if (recoveries != null && (command.decision() == QualityDecision.REWORK
                    || command.decision() == QualityDecision.SCRAP && command.createReplenishment()))
                recoveries.create(lot, command.decision(), command.quantity(), command.reason(),
                        command.requestId(), actor);
            advanceAfterQualityOrCorrection(run, actor);
            recompute(lot.sourceTaskId(), actor);
            return new Changed(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    public List<ProductionReport> listReports(String executionRunId) { return quantities.listReports(executionRunId); }
    public List<OutputLot> listOutputLots(String executionRunId) { return quantities.listOutputLotsByRun(executionRunId); }

    public Changed moveQuantity(ResourceAccessScope scope, String outputLotId, MoveQuantity command, String actor)
    {
        requireMovement(command);
        return transactions.serializable(() -> {
            var replay = quantities.findEventByRequestId(command.requestId());
            if (replay.isPresent())
            {
                QuantityEvent event = replay.get();
                OutputLot lot = quantities.findOutputLotForUpdate(event.outputLotId()).orElseThrow();
                ProductionReport report = quantities.findReportForUpdate(lot.sourceReportId()).orElseThrow();
                requireScope(scope, report.executionRunId());
                if (!event.outputLotId().equals(outputLotId)
                        || !Objects.equals(event.materialDemandId(), command.materialDemandId())
                        || !Objects.equals(event.targetTaskId(), command.targetTaskId())
                        || !Objects.equals(event.executionRunId(), command.operation() == QuantityOperation.CONSUME
                                ? command.targetExecutionRunId() : null)
                        || event.eventType() != EventType.valueOf(command.operation().name())
                        || event.quantity().compareTo(command.quantity()) != 0
                        || !event.occurredAt().equals(command.occurredAt())
                        || !Objects.equals(event.reason(), command.reason()))
                    throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他数量流转意图");
                return new Changed(executions.findRun(report.executionRunId()).orElseThrow(), true);
            }
            OutputLot lot = quantities.findOutputLotForUpdate(outputLotId)
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "产出批不存在"));
            ProductionReport report = quantities.findReportForUpdate(lot.sourceReportId()).orElseThrow();
            ExecutionRun run = lockRun(scope, report.executionRunId(), command.expectedRunRowVersion());
            if (lot.rowVersion() != command.expectedOutputLotRowVersion())
                throw error(ApsErrorCode.STALE_VERSION, "产出批行版本已变化");
            MaterialTarget demand = quantities.findMaterialTargetForUpdate(command.materialDemandId())
                    .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "物料需求不存在"));
            if (!scope.allWorkshops() && (orders == null || !orders.isTaskInScope(scope, demand.targetTaskId())))
                throw error(ApsErrorCode.FORBIDDEN, "目标需求不在完整车间权限范围内");
            if (!demand.targetTaskId().equals(command.targetTaskId()) || !demand.itemId().equals(lot.itemId())
                    || !demand.uomCode().equals(lot.uomCode())
                    || demand.sourceTaskId() != null && !demand.sourceTaskId().equals(lot.sourceTaskId()))
                throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION, "产出批与目标物料需求的来源、物料或单位不匹配");
            if (command.operation() == QuantityOperation.CONSUME
                    && (!executions.isRunInScope(scope, command.targetExecutionRunId())
                            || !executions.isRunForTask(command.targetExecutionRunId(), demand.targetTaskId())))
                throw error(ApsErrorCode.NOT_FOUND, "投入目标 run 不存在、不在数据范围内或不执行目标任务");
            if ((command.operation() == QuantityOperation.RESERVE || command.operation() == QuantityOperation.TRANSFER)
                    && quantities.allocatedForDemand(demand.id()).add(command.quantity())
                            .compareTo(demand.requiredQty()) > 0)
                throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, "数量分配超过目标需求");
            if ((command.operation() == QuantityOperation.UNRESERVE || command.operation() == QuantityOperation.CONSUME)
                    && quantities.reservedForDemandAndLot(demand.id(), lot.id())
                            .compareTo(command.quantity()) < 0)
                throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, "该需求在产出批上的预留余额不足");

            Movement movement = switch (command.operation())
            {
                case RESERVE -> new Movement(EventType.RESERVE, Bucket.AVAILABLE, Bucket.RESERVED, command.quantity());
                case UNRESERVE -> new Movement(EventType.UNRESERVE, Bucket.RESERVED, Bucket.AVAILABLE, command.quantity());
                case CONSUME -> new Movement(EventType.CONSUME, Bucket.RESERVED, Bucket.CONSUMED, command.quantity());
                case TRANSFER -> new Movement(EventType.TRANSFER, Bucket.AVAILABLE, Bucket.TRANSFERRED, command.quantity());
            };
            QuantityLedger ledger = ledger(lot.id());
            try { ledger.apply(movement); }
            catch (IllegalStateException exception)
            {
                throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, exception.getMessage());
            }
            quantities.insertEvent(new QuantityEvent(stable("quantity-movement", command.requestId()), lot.id(),
                    lot.itemId(), demand.id(), command.operation() == QuantityOperation.CONSUME
                            ? command.targetExecutionRunId() : null, null, demand.targetTaskId(), null,
                    movement.eventType(), movement.fromBucket(), movement.toBucket(), movement.quantity(),
                    lot.uomCode(), command.requestId(), command.occurredAt(), actor, command.reason()), actor);
            OutputLot projected = projected(lot, ledger, lot.qualityStatus(), lot.dispositionType(),
                    lot.dispositionReason(), lot.approvedBy(), lot.approvedAt());
            if (quantities.updateOutputLotProjection(projected, lot.rowVersion(), actor) != 1)
                throw error(ApsErrorCode.STALE_VERSION, "产出批已被并发修改");
            quantities.refreshMaterialDemandStatus(demand.id(), actor);
            incrementRun(run, actor);
            recompute(lot.sourceTaskId(), actor);
            recompute(demand.targetTaskId(), actor);
            return new Changed(executions.findRun(run.id()).orElseThrow(), false);
        });
    }

    private void createOutputLots(ProductionReport report, ReportTarget target, Instant at, String actor,
            List<OutputLot> sources)
    {
        createLot(report, target, "GOOD", report.quantities().goodQty(), target.qualityGateRequired(),
                BigDecimal.ZERO, at, actor, source(sources, "GOOD"));
        createLot(report, target, "PENDING", report.quantities().pendingQty(), true,
                BigDecimal.ZERO, at, actor, source(sources, "PENDING"));
        createLot(report, target, "REJECTED", report.quantities().rejectedQty(), true,
                report.quantities().scrapQty(), at, actor, source(sources, "REJECTED"));
    }

    private void createLot(ProductionReport report, ReportTarget target, String kind, BigDecimal total,
            boolean pendingQuality, BigDecimal immediateScrap, Instant at, String actor, String sourceLotId)
    {
        if (total.signum() == 0) return;
        String lotId = stable("output-lot", report.id(), kind);
        BigDecimal scrapped = immediateScrap.min(total);
        BigDecimal available = !pendingQuality ? total : BigDecimal.ZERO;
        BigDecimal pending = total.subtract(available).subtract(scrapped);
        QualityStatus status = switch (kind)
        {
            case "GOOD" -> pending.signum() == 0 ? QualityStatus.RELEASED : QualityStatus.PENDING;
            case "REJECTED" -> pending.signum() == 0 ? QualityStatus.CLOSED : QualityStatus.REJECTED;
            default -> QualityStatus.PENDING;
        };
        DispositionType disposition = scrapped.signum() > 0 ? DispositionType.SCRAP : DispositionType.NONE;
        String dispositionReason = scrapped.signum() > 0
                ? (report.defectReason() == null ? "报工废品" : report.defectReason()) : null;
        OutputLot lot = new OutputLot(lotId, target.productionLotId(), target.taskId(), report.id(),
                target.itemId(), sourceLotId, "OL-" + report.id() + "-" + kind.charAt(0), status,
                total, available, BigDecimal.ZERO, BigDecimal.ZERO, scrapped, target.uomCode(), disposition,
                dispositionReason, scrapped.signum() > 0 ? actor : null,
                scrapped.signum() > 0 ? at : null, 0);
        quantities.insertOutputLot(lot, actor);
        Movement produced = Movement.produce(total);
        quantities.insertEvent(event(stable("quantity-event", report.id(), kind, "produce"), lot, produced,
                report.id(), stable("quantity-request", report.requestId(), kind, "produce"), at, actor, null), actor);
        if (available.signum() > 0)
        {
            Movement released = new Movement(EventType.QUALITY_RELEASE, Bucket.PENDING_QUALITY,
                    Bucket.AVAILABLE, available);
            quantities.insertEvent(event(stable("quantity-event", report.id(), kind, "release"), lot, released,
                    null, stable("quantity-request", report.requestId(), kind, "release"), at, actor, null), actor);
        }
        if (scrapped.signum() > 0)
        {
            Movement scrap = new Movement(EventType.SCRAP, Bucket.PENDING_QUALITY, Bucket.SCRAPPED, scrapped);
            quantities.insertEvent(event(stable("quantity-event", report.id(), kind, "scrap"), lot, scrap,
                    null, stable("quantity-request", report.requestId(), kind, "scrap"), at, actor,
                    report.defectReason() == null ? "报工废品" : report.defectReason()), actor);
        }
    }

    private void reverseLot(OutputLot lot, String requestId, Instant at, String reason, String actor)
    {
        List<QuantityEvent> events = new ArrayList<>(quantities.listEvents(lot.id()));
        java.util.Set<String> affectedDemands = events.stream().map(QuantityEvent::materialDemandId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        java.util.Set<String> affectedTasks = events.stream().map(QuantityEvent::targetTaskId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        if (events.stream().anyMatch(event -> event.eventType() == EventType.REVERSE))
        {
            throw error(ApsErrorCode.CONFLICT, "产出批已包含冲正事件，不能重复冲正");
        }
        QuantityLedger ledger = ledger(events);
        Collections.reverse(events);
        int index = 0;
        for (QuantityEvent original : events)
        {
            Movement movement;
            try { movement = Movement.reverse(movement(original)); }
            catch (IllegalArgumentException exception) { throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION, exception.getMessage()); }
            ledger.apply(movement);
            quantities.insertEvent(event(stable("quantity-reverse", requestId, original.id()), lot, movement,
                    null, stable("quantity-reverse-request", requestId, Integer.toString(index++)), at, actor,
                    reason == null ? "报工更正冲正" : reason, original.id()), actor);
        }
        OutputLot closed = projected(lot, ledger, QualityStatus.CLOSED, lot.dispositionType(),
                reason, actor, at);
        if (quantities.updateOutputLotProjection(closed, lot.rowVersion(), actor) != 1)
        {
            throw error(ApsErrorCode.STALE_VERSION, "冲正时产出批已被并发修改");
        }
        affectedDemands.forEach(demandId -> quantities.refreshMaterialDemandStatus(demandId, actor));
        affectedTasks.forEach(taskId -> recompute(taskId, actor));
    }

    private List<DecisionMovement> decisionMovements(String outputLotId, QuantityLedger ledger,
            DecideQuality command)
    {
        BigDecimal quantity = command.quantity();
        try
        {
            List<Movement> movements = switch (command.decision())
            {
                case HOLD, REJECT -> {
                    requirePendingExactly(ledger, quantity, "隔离或拒收必须覆盖当前全部待处置量");
                    yield List.of();
                }
                case RELEASE, USE_AS_IS -> {
                    if (quantity.compareTo(ledger.balance(Bucket.PENDING_QUALITY)) > 0)
                        throw new IllegalStateException("放行量超过待检量");
                    yield List.of(new Movement(EventType.QUALITY_RELEASE, Bucket.PENDING_QUALITY,
                            Bucket.AVAILABLE, quantity));
                }
                case REWORK -> {
                    requireReason(command.reason());
                    if (quantity.compareTo(ledger.balance(Bucket.PENDING_QUALITY)) > 0)
                        throw new IllegalStateException("返工量超过待处置量");
                    yield List.of(new Movement(EventType.ADJUST, Bucket.PENDING_QUALITY,
                            Bucket.ADJUSTMENT, quantity));
                }
                case SCRAP -> scrapMovements(ledger, quantity);
            };
            return enrichReservedScrap(outputLotId, movements);
        }
        catch (IllegalArgumentException | IllegalStateException exception)
        {
            throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, exception.getMessage());
        }
    }

    private List<DecisionMovement> enrichReservedScrap(String outputLotId, List<Movement> movements)
    {
        List<DecisionMovement> result = new ArrayList<>();
        List<ReservedAllocation> allocations = null;
        int allocationIndex = 0;
        for (Movement movement : movements)
        {
            if (movement.eventType() != EventType.SCRAP || movement.fromBucket() != Bucket.RESERVED)
            {
                result.add(new DecisionMovement(movement, null, null));
                continue;
            }
            if (allocations == null) allocations = quantities.listReservedAllocations(outputLotId);
            BigDecimal remaining = movement.quantity();
            while (remaining.signum() > 0 && allocationIndex < allocations.size())
            {
                ReservedAllocation allocation = allocations.get(allocationIndex++);
                BigDecimal taken = allocation.quantity().min(remaining);
                result.add(new DecisionMovement(new Movement(EventType.UNRESERVE, Bucket.RESERVED,
                        Bucket.AVAILABLE, taken), allocation.materialDemandId(), allocation.targetTaskId()));
                remaining = remaining.subtract(taken);
            }
            if (remaining.signum() > 0)
                throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION,
                        "预留桶与下游需求分配不一致，不能报废未归属的预留数量");
            result.add(new DecisionMovement(new Movement(EventType.SCRAP, Bucket.AVAILABLE,
                    Bucket.SCRAPPED, movement.quantity()), null, null));
        }
        return result;
    }

    private List<Movement> scrapMovements(QuantityLedger ledger, BigDecimal requested)
    {
        List<Movement> result = new ArrayList<>();
        BigDecimal remaining = requested;
        for (Bucket source : List.of(Bucket.PENDING_QUALITY, Bucket.AVAILABLE, Bucket.RESERVED))
        {
            BigDecimal take = ledger.balance(source).min(remaining);
            if (take.signum() > 0)
            {
                result.add(new Movement(EventType.SCRAP, source, Bucket.SCRAPPED, take));
                remaining = remaining.subtract(take);
            }
        }
        if (remaining.signum() > 0) throw new IllegalStateException("报废量超过待检、可用和预留余额");
        return result;
    }

    private OutputLot decidedLot(OutputLot lot, QuantityLedger ledger, DecideQuality command, String actor)
    {
        BigDecimal pending = ledger.balance(Bucket.PENDING_QUALITY);
        QualityStatus status = switch (command.decision())
        {
            case HOLD -> QualityStatus.HOLD;
            case REJECT -> QualityStatus.REJECTED;
            case RELEASE, USE_AS_IS -> pending.signum() == 0 ? QualityStatus.RELEASED : QualityStatus.PENDING;
            case REWORK, SCRAP -> pending.signum() > 0 ? QualityStatus.REJECTED
                    : ledger.balance(Bucket.AVAILABLE).signum() > 0
                            || ledger.balance(Bucket.RESERVED).signum() > 0
                                    ? QualityStatus.RELEASED : QualityStatus.CLOSED;
        };
        DispositionType disposition = switch (command.decision())
        {
            case REWORK -> DispositionType.REWORK;
            case SCRAP -> DispositionType.SCRAP;
            case USE_AS_IS -> DispositionType.USE_AS_IS;
            default -> DispositionType.NONE;
        };
        return projected(lot, ledger, status, disposition, command.reason(), actor, command.occurredAt());
    }

    private OutputLot projected(OutputLot lot, QuantityLedger ledger, QualityStatus status,
            DispositionType disposition, String reason, String actor, Instant at)
    {
        return new OutputLot(lot.id(), lot.productionLotId(), lot.sourceTaskId(), lot.sourceReportId(),
                lot.itemId(), lot.sourceOutputLotId(), lot.outputLotNo(), status, lot.totalQty(),
                ledger.balance(Bucket.AVAILABLE), ledger.balance(Bucket.RESERVED),
                ledger.balance(Bucket.CONSUMED), ledger.balance(Bucket.SCRAPPED), lot.uomCode(), disposition,
                reason, actor, at, lot.rowVersion());
    }

    private void finishOrAdvance(ExecutionRun run, boolean completeReport, boolean qualityGate,
            Instant at, String actor)
    {
        if (!completeReport)
        {
            incrementRun(run, actor);
            return;
        }
        if (quantities.effectiveProcessedForRun(run.id()).compareTo(run.assignedQty()) != 0)
        {
            throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION,
                    "完工报工后的有效累计加工量必须等于 run 派工量");
        }
        closeOccupancies(run.id(), at, actor);
        boolean resolved = !executions.hasUnresolvedOutput(run.id());
        ExecutionRunStatus target;
        try
        {
            target = ExecutionRunStateMachine.transition(run.status(), ExecutionAction.FINISH_PROCESSING,
                    new ExecutionTransitionContext(qualityGate, true, true, resolved));
        }
        catch (IllegalStateException exception)
        {
            throw error(ApsErrorCode.INVALID_EXECUTION_TRANSITION, exception.getMessage());
        }
        transitionRun(run, target, run.actualStartAt(), at, null, actor);
    }

    private void advanceAfterQualityOrCorrection(ExecutionRun run, String actor)
    {
        if (run.status() == ExecutionRunStatus.WAIT_QUALITY && !executions.hasUnresolvedOutput(run.id()))
        {
            transitionRun(run, ExecutionRunStatus.COMPLETED, run.actualStartAt(), run.actualEndAt(), null, actor);
        }
        else incrementRun(run, actor);
    }

    private void transitionRun(ExecutionRun run, ExecutionRunStatus target, Instant start, Instant end,
            String reason, String actor)
    {
        if (executions.transitionRun(run.id(), run.rowVersion(), run.status(), target, start, end, reason, actor) != 1)
            throw error(ApsErrorCode.STALE_VERSION, "执行 run 已被并发修改");
    }

    private void incrementRun(ExecutionRun run, String actor)
    {
        if (executions.incrementRunRevision(run.id(), run.rowVersion(), actor) != 1)
            throw error(ApsErrorCode.STALE_VERSION, "执行 run 已被并发修改");
    }

    private void closeOccupancies(String runId, Instant at, String actor)
    {
        for (ActualOccupancy occupancy : executions.listActiveOccupanciesForUpdate(runId))
        {
            if (at.isBefore(occupancy.startAt()) || executions.closeOccupancy(occupancy.id(), occupancy.rowVersion(),
                    at, OccupancyStatus.COMPLETED, actor) != 1)
                throw error(ApsErrorCode.STALE_VERSION, "完工时间无效或实际占用已被并发修改");
        }
    }

    private ExecutionRun lockRun(ResourceAccessScope scope, String id, long expectedVersion)
    {
        ExecutionRun run = executions.findRunForUpdate(id)
                .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "执行 run 不存在"));
        requireScope(scope, run.id());
        if (run.rowVersion() != expectedVersion) throw error(ApsErrorCode.STALE_VERSION, "执行 run 行版本已变化");
        return run;
    }

    private ReportTarget target(String runId, String memberId, String taskId)
    {
        return quantities.findReportTargetForUpdate(runId, memberId, taskId)
                .orElseThrow(() -> error(ApsErrorCode.NOT_FOUND, "报工成员或任务不属于该执行 run"));
    }

    private void requireQuantityScope(ExecutionRun run, ReportTarget target, String uom,
            ProductionReportQuantities value, ProductionReport replaced)
    {
        if (!run.uomCode().equals(uom) || !target.uomCode().equals(uom))
            throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION, "报工单位必须与 run、成员和任务一致");
        BigDecimal old = replaced == null ? BigDecimal.ZERO : replaced.quantities().processedQty();
        BigDecimal runProjected = quantities.effectiveProcessedForRun(run.id()).subtract(old)
                .add(value.processedQty());
        BigDecimal memberProjected = quantities.effectiveProcessedForMember(run.id(), target.planJobMemberId())
                .subtract(old).add(value.processedQty());
        if (runProjected.compareTo(run.assignedQty()) > 0 || memberProjected.compareTo(target.memberQty()) > 0
                || memberProjected.compareTo(target.taskQty()) > 0)
            throw error(ApsErrorCode.INSUFFICIENT_QUANTITY, "有效累计报工超过 run、计划成员或任务数量");
    }

    private QuantityLedger ledger(String lotId) { return ledger(quantities.listEvents(lotId)); }
    private QuantityLedger ledger(List<QuantityEvent> events)
    {
        QuantityLedger ledger = new QuantityLedger();
        try { events.forEach(event -> ledger.apply(movement(event))); }
        catch (IllegalStateException exception) { throw error(ApsErrorCode.QUANTITY_BALANCE_VIOLATION, exception.getMessage()); }
        return ledger;
    }
    private Movement movement(QuantityEvent event)
    {
        return new Movement(event.eventType(), event.fromBucket(), event.toBucket(), event.quantity());
    }

    private QuantityEvent event(String id, OutputLot lot, Movement movement, String reportId, String requestId,
            Instant at, String actor, String reason)
    {
        return event(id, lot, movement, reportId, requestId, at, actor, reason, null);
    }
    private QuantityEvent event(String id, OutputLot lot, Movement movement, String reportId, String requestId,
            Instant at, String actor, String reason, String reversalOf)
    {
        return new QuantityEvent(id, lot.id(), lot.itemId(), null,
                movement.eventType() == EventType.PRODUCE ? findRunId(reportId) : null,
                movement.eventType() == EventType.PRODUCE ? reportId : null, null, reversalOf,
                movement.eventType(), movement.fromBucket(), movement.toBucket(), movement.quantity(),
                lot.uomCode(), requestId, at, actor, reason);
    }
    private String findRunId(String reportId)
    {
        return quantities.findReportForUpdate(reportId).orElseThrow().executionRunId();
    }

    private boolean isNoMovementReplay(OutputLot lot, DecideQuality command)
    {
        if (lot.rowVersion() != command.expectedOutputLotRowVersion() + 1) return false;
        boolean sameState = (command.decision() == QualityDecision.HOLD && lot.qualityStatus() == QualityStatus.HOLD)
                || (command.decision() == QualityDecision.REJECT && lot.qualityStatus() == QualityStatus.REJECTED);
        return sameState && Objects.equals(lot.dispositionReason(), command.reason())
                && Objects.equals(lot.approvedAt(), command.occurredAt())
                && ledger(lot.id()).balance(Bucket.PENDING_QUALITY).compareTo(command.quantity()) == 0;
    }
    private boolean replayedDecisionMatches(OutputLot lot, QuantityEvent first, String outputLotId,
            DecideQuality command)
    {
        if (!first.outputLotId().equals(outputLotId) || !first.occurredAt().equals(command.occurredAt())
                || !Objects.equals(first.reason(), command.reason())) return false;
        EventType expectedType = switch (command.decision())
        {
            case RELEASE, USE_AS_IS -> EventType.QUALITY_RELEASE;
            case REWORK -> EventType.ADJUST;
            case SCRAP -> EventType.SCRAP;
            default -> null;
        };
        if (first.eventType() != expectedType
                && !(command.decision() == QualityDecision.SCRAP && first.eventType() == EventType.UNRESERVE))
            return false;
        if (command.decision() == QualityDecision.USE_AS_IS && lot.dispositionType() != DispositionType.USE_AS_IS)
            return false;
        if (command.decision() == QualityDecision.RELEASE && lot.dispositionType() == DispositionType.USE_AS_IS)
            return false;
        BigDecimal total = BigDecimal.ZERO;
        for (int index = 0; ; index++)
        {
            var event = quantities.findEventForUpdate(markerIdFor(command.requestId(), index));
            if (event.isEmpty()) break;
            if (!event.get().outputLotId().equals(outputLotId)
                    || !event.get().occurredAt().equals(command.occurredAt())
                    || command.decision() != QualityDecision.SCRAP && event.get().eventType() != expectedType
                    || command.decision() == QualityDecision.SCRAP
                            && event.get().eventType() != EventType.SCRAP
                            && event.get().eventType() != EventType.UNRESERVE
                    || !Objects.equals(event.get().reason(), command.reason())) return false;
            if (event.get().eventType() == expectedType) total = total.add(event.get().quantity());
        }
        return total.compareTo(command.quantity()) == 0;
    }
    private void requirePendingExactly(QuantityLedger ledger, BigDecimal quantity, String message)
    {
        if (quantity.compareTo(ledger.balance(Bucket.PENDING_QUALITY)) != 0)
            throw new IllegalStateException(message);
    }
    private void requireReason(String reason)
    {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("该质量处置必须填写原因");
    }
    private String source(List<OutputLot> sources, String kind)
    {
        String suffix = "-" + kind.charAt(0);
        return sources.stream().filter(lot -> lot.outputLotNo().endsWith(suffix)).map(OutputLot::id)
                .findFirst().orElse(null);
    }
    private String markerIdFor(String requestId, int index) { return stable("quality-decision", requestId, Integer.toString(index)); }

    private void requireReportCommand(CreateReport command)
    {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.reportType(), "reportType");
        Objects.requireNonNull(command.reportedAt(), "reportedAt");
        Objects.requireNonNull(command.quantities(), "quantities");
        command.quantities().requirePositiveUnlessCorrection(false);
        if (blank(command.requestId()) || blank(command.executionRunId()) || blank(command.planJobMemberId())
                || blank(command.taskId()) || blank(command.uomCode()))
            throw error(ApsErrorCode.INVALID_REQUEST, "报工参数不完整");
    }
    private void requireCorrection(CorrectReport command)
    {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.reportedAt(), "reportedAt");
        Objects.requireNonNull(command.quantities(), "quantities");
        if (blank(command.requestId()) || blank(command.reason()))
            throw error(ApsErrorCode.INVALID_REQUEST, "报工更正必须包含幂等键和原因");
    }
    private void requireDecision(DecideQuality command)
    {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.decision(), "decision");
        Objects.requireNonNull(command.occurredAt(), "occurredAt");
        if (blank(command.requestId()) || command.quantity() == null || command.quantity().signum() <= 0)
            throw error(ApsErrorCode.INVALID_REQUEST, "质量处置参数不完整或数量无效");
        if (command.createReplenishment() && command.decision() != QualityDecision.SCRAP)
            throw error(ApsErrorCode.INVALID_REQUEST, "只有报废处置允许同时创建补产批");
        if (List.of(QualityDecision.REWORK, QualityDecision.SCRAP, QualityDecision.USE_AS_IS)
                .contains(command.decision())) requireReason(command.reason());
    }
    private void requireMovement(MoveQuantity command)
    {
        if (command == null || blank(command.requestId()) || blank(command.materialDemandId())
                || blank(command.targetTaskId()) || command.operation() == null || command.quantity() == null
                || command.quantity().signum() <= 0 || command.occurredAt() == null)
            throw error(ApsErrorCode.INVALID_REQUEST, "数量流转参数不完整或数量无效");
        if (command.operation() == QuantityOperation.CONSUME && blank(command.targetExecutionRunId()))
            throw error(ApsErrorCode.INVALID_REQUEST, "投入事件必须指定目标执行 run");
        if (command.operation() != QuantityOperation.CONSUME && !blank(command.targetExecutionRunId()))
            throw error(ApsErrorCode.INVALID_REQUEST, "只有投入事件允许指定目标执行 run");
    }
    private void requireSameReport(ProductionReport report, CreateReport command)
    {
        if (!report.executionRunId().equals(command.executionRunId())
                || !report.planJobMemberId().equals(command.planJobMemberId())
                || !report.taskId().equals(command.taskId()) || report.reportType() != command.reportType()
                || !report.reportedAt().equals(command.reportedAt()) || !report.uomCode().equals(command.uomCode())
                || !report.quantities().equals(command.quantities())
                || !Objects.equals(report.defectReason(), command.defectReason())
                || (command.operatorUserId() != null
                        && !Objects.equals(report.operatorUserId(), command.operatorUserId())))
            throw error(ApsErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键已绑定其他报工意图");
    }
    private void requireScope(ResourceAccessScope scope, String runId)
    {
        Objects.requireNonNull(scope, "scope");
        if (!executions.isRunInScope(scope, runId))
            throw error(ApsErrorCode.NOT_FOUND, "执行 run 不存在或不在数据范围内");
    }
    private void recompute(String taskId, String actor)
    {
        if (orders != null) orders.recomputeExecutionStatuses(taskId, actor);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static ApsBusinessException error(ApsErrorCode code, String message) { return new ApsBusinessException(code, message); }
    private static String stable(String... parts)
    {
        return UUID.nameUUIDFromBytes(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public record CreateReport(String requestId, String executionRunId, long expectedRunRowVersion,
            String planJobMemberId, String taskId, ReportType reportType, Instant reportedAt,
            ProductionReportQuantities quantities, String uomCode, String defectReason, String operatorUserId) { }
    public record CorrectReport(String requestId, long expectedRunRowVersion, long expectedReportRowVersion,
            Instant reportedAt, ProductionReportQuantities quantities, String reason) { }
    public record DecideQuality(String requestId, long expectedRunRowVersion, long expectedOutputLotRowVersion,
            QualityDecision decision, BigDecimal quantity, Instant occurredAt, String reason,
            boolean createReplenishment) { }
    public record MoveQuantity(String requestId, long expectedRunRowVersion, long expectedOutputLotRowVersion,
            String materialDemandId, String targetTaskId, String targetExecutionRunId,
            QuantityOperation operation, BigDecimal quantity, Instant occurredAt, String reason) { }
    public record Changed(ExecutionRun run, boolean reused) { }
    private record DecisionMovement(Movement movement, String materialDemandId, String targetTaskId) { }
}
