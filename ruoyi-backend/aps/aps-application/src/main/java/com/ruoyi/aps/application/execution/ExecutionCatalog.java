package com.ruoyi.aps.application.execution;

import java.math.BigDecimal;
import java.time.Instant;
import com.ruoyi.aps.domain.execution.ExecutionRunStatus;
import com.ruoyi.aps.domain.quantity.QuantityLedger.Bucket;
import com.ruoyi.aps.domain.quantity.QuantityLedger.EventType;
import com.ruoyi.aps.domain.quantity.ProductionReportQuantities;

/** M25～M29 应用端口使用的持久化中立数据模型。 */
public final class ExecutionCatalog
{
    private ExecutionCatalog()
    {
    }

    public enum ReportType { PROGRESS, COMPLETE, CORRECTION }
    public enum OccupancyActivityType { SETUP, RUN, UNLOAD, WAIT_HOLD, PAUSE_HOLD, TRANSPORT }
    public enum OccupancyStatus { ACTIVE, COMPLETED, CORRECTION, VOID }
    public enum QualityStatus { PENDING, RELEASED, HOLD, REJECTED, CLOSED }
    public enum DispositionType { NONE, REWORK, SCRAP, USE_AS_IS }
    public enum QualityDecision { HOLD, RELEASE, REJECT, REWORK, SCRAP, USE_AS_IS }
    public enum QuantityOperation { RESERVE, UNRESERVE, CONSUME, TRANSFER }

    public record PublishedJob(String planVersionId, String planJobId, BigDecimal plannedQty, String uomCode,
            boolean currentPublished, boolean qualityGateRequired) { }

    public record PlannedResource(String planSegmentId, int segmentNo, String sourcePlanAllocationId,
            String resourceId, OccupancyActivityType activityType, boolean holdOnPause) { }

    public record ExecutionRun(String id, String planVersionId, String planJobId, int runNo,
            ExecutionRunStatus status, BigDecimal assignedQty, String uomCode, Instant actualStartAt,
            Instant actualEndAt, String pauseReason, String requestId, long rowVersion) { }
    public record RunOption(ExecutionRun run, String jobCode, String orderNumbers, String taskNames) { }
    public record ReportMember(String id,int memberNo,String taskId,String taskName,BigDecimal plannedQty,String uomCode) { }

    public record ActualOccupancy(String id, String planJobId, String executionRunId, String planSegmentId,
            String sourcePlanAllocationId, String resourceId, OccupancyActivityType activityType,
            Instant startAt, Instant endAt,
            OccupancyStatus status, String correctionOfId, String reason, long rowVersion) { }

    public record ProductionReport(String id, String planJobId, String executionRunId, String planJobMemberId,
            String taskId, ReportType reportType, Instant reportedAt, ProductionReportQuantities quantities,
            String uomCode, String defectReason, String correctionOfId, String requestId,
            String operatorUserId, long rowVersion) { }

    public record ReportTarget(String planJobId, String executionRunId, String planJobMemberId, String taskId,
            String productionLotId, String itemId, BigDecimal memberQty, BigDecimal taskQty, String uomCode,
            boolean qualityGateRequired) { }

    public record OutputLot(String id, String productionLotId, String sourceTaskId, String sourceReportId,
            String itemId, String sourceOutputLotId, String outputLotNo, QualityStatus qualityStatus,
            BigDecimal totalQty, BigDecimal availableQty, BigDecimal reservedQty, BigDecimal consumedQty,
            BigDecimal scrappedQty, String uomCode, DispositionType dispositionType, String dispositionReason,
            String approvedBy, Instant approvedAt, long rowVersion) { }

    public record QuantityEvent(String id, String outputLotId, String itemId, String materialDemandId,
            String executionRunId, String productionReportId, String targetTaskId, String reversalOfId,
            EventType eventType, Bucket fromBucket, Bucket toBucket, BigDecimal quantity, String uomCode,
            String requestId, Instant occurredAt, String actorUserId, String reason) { }

    public record ReleasedSupply(String outputLotId, String itemId, String sourceTaskId,
            Instant availableAt, BigDecimal quantity, String uomCode) { }

    public record MaterialTarget(String id, String targetTaskId, String itemId, String sourceTaskId,
            BigDecimal requiredQty, String uomCode, String status, long rowVersion) { }

    /** 某产出批当前仍占用的下游预留；用于报废 RESERVED 数量时同步释放 M18 分配。 */
    public record ReservedAllocation(String materialDemandId, String targetTaskId, BigDecimal quantity) { }
}
