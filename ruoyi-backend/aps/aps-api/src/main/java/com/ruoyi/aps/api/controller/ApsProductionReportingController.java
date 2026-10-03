package com.ruoyi.aps.api.controller;

import java.math.BigDecimal;
import java.util.List;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.application.execution.ExecutionCatalog.OutputLot;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ProductionReport;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QualityDecision;
import com.ruoyi.aps.application.execution.ExecutionCatalog.ReportType;
import com.ruoyi.aps.application.execution.ExecutionCatalog.QuantityOperation;
import com.ruoyi.aps.application.execution.ExecutionLifecycleService;
import com.ruoyi.aps.application.execution.ProductionReportingService;
import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.domain.quantity.ProductionReportQuantities;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** IMP09-C 报工、质量与冲正 HTTP 适配层。 */
@RestController
@RequestMapping("/api/aps/v1")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
public class ApsProductionReportingController
{
    private static final String UUID = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$";
    private final ProductionReportingService reporting;
    private final ExecutionLifecycleService lifecycle;
    private final ApsAuditActorProvider actors;

    public ApsProductionReportingController(ProductionReportingService reporting,
            ExecutionLifecycleService lifecycle, ApsAuditActorProvider actors)
    {
        this.reporting = reporting;
        this.lifecycle = lifecycle;
        this.actors = actors;
    }

    @PostMapping("/execution-runs/{executionRunId}/reports")
    @PreAuthorize("@ss.hasPermi('aps:execution:report')")
    public ResponseEntity<ApsExecutionController.ExecutionRunDetailResponse> report(
            @PathVariable @Pattern(regexp = UUID) String executionRunId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID) String requestId,
            @Valid @RequestBody CreateProductionReportRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        ProductionReportQuantities quantity = quantities(request.quantities());
        var changed = reporting.createReport(scope(actor), new ProductionReportingService.CreateReport(requestId,
                executionRunId, request.expectedRowVersion(), request.planJobMemberId(), request.taskId(),
                ReportType.valueOf(request.reportType()), ApsUtcInput.parse(request.reportedAt()), quantity,
                request.uomCode(), request.defectReason(), actor.userId()), actor.username());
        return ResponseEntity.status(changed.reused() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response(scope(actor), changed.run().id()));
    }

    @PostMapping("/production-reports/{reportId}/corrections")
    @PreAuthorize("@ss.hasPermi('aps:execution:report')")
    public ResponseEntity<ApsExecutionController.ExecutionRunDetailResponse> correct(
            @PathVariable @Pattern(regexp = UUID) String reportId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID) String requestId,
            @Valid @RequestBody CorrectProductionReportRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        var changed = reporting.correctReport(scope(actor), reportId,
                new ProductionReportingService.CorrectReport(requestId, request.expectedRunRowVersion(),
                        request.expectedReportRowVersion(), ApsUtcInput.parse(request.reportedAt()),
                        quantities(request.quantities()), request.reason()), actor.username());
        return ResponseEntity.status(changed.reused() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response(scope(actor), changed.run().id()));
    }

    @PostMapping("/output-lots/{outputLotId}/quality-decisions")
    @PreAuthorize("@ss.hasPermi('aps:execution:quality')")
    public ApsExecutionController.ExecutionRunDetailResponse decide(
            @PathVariable @Pattern(regexp = UUID) String outputLotId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID) String requestId,
            @Valid @RequestBody QualityDecisionRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        var changed = reporting.decideQuality(scope(actor), outputLotId,
                new ProductionReportingService.DecideQuality(requestId, request.expectedRunRowVersion(),
                        request.expectedOutputLotRowVersion(), QualityDecision.valueOf(request.decision()),
                        request.quantity(), ApsUtcInput.parse(request.occurredAt()), request.reason(),
                        Boolean.TRUE.equals(request.createReplenishment())), actor.username());
        return response(scope(actor), changed.run().id());
    }

    @PostMapping("/output-lots/{outputLotId}/quantity-movements")
    @PreAuthorize("@ss.hasPermi('aps:execution:report')")
    public ApsExecutionController.ExecutionRunDetailResponse moveQuantity(
            @PathVariable @Pattern(regexp = UUID) String outputLotId,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = UUID) String requestId,
            @Valid @RequestBody QuantityMovementRequest request)
    {
        ApsAuditActor actor = actors.currentActor();
        var changed = reporting.moveQuantity(scope(actor), outputLotId,
                new ProductionReportingService.MoveQuantity(requestId, request.expectedRunRowVersion(),
                        request.expectedOutputLotRowVersion(), request.materialDemandId(), request.targetTaskId(),
                        request.targetExecutionRunId(), QuantityOperation.valueOf(request.operation()),
                        request.quantity(), ApsUtcInput.parse(request.occurredAt()), request.reason()),
                actor.username());
        return response(scope(actor), changed.run().id());
    }

    private ApsExecutionController.ExecutionRunDetailResponse response(ResourceAccessScope scope, String runId)
    {
        var detail = lifecycle.detail(scope, runId);
        List<ProductionReport> reports = reporting.listReports(runId);
        List<OutputLot> lots = reporting.listOutputLots(runId);
        return new ApsExecutionController.ExecutionRunDetailResponse("1.0", "EXECUTION_RUN_DETAIL", detail.run(),
                detail.occupancies(), reports, lots, detail.executionRevision());
    }

    private ProductionReportQuantities quantities(ProductionReportQuantitiesRequest value)
    {
        return new ProductionReportQuantities(value.processedQty(), value.goodQty(), value.pendingQty(),
                value.rejectedQty(), value.scrapQty(), value.transferredQty());
    }

    private ResourceAccessScope scope(ApsAuditActor actor)
    {
        return new ResourceAccessScope(actor.userId(),
                SecurityUtils.isAdmin() || SecurityUtils.hasPermi("aps:scope:manage"));
    }

    public record ProductionReportQuantitiesRequest(
            @NotNull @DecimalMin("0") BigDecimal processedQty,
            @NotNull @DecimalMin("0") BigDecimal goodQty,
            @NotNull @DecimalMin("0") BigDecimal pendingQty,
            @NotNull @DecimalMin("0") BigDecimal rejectedQty,
            @NotNull @DecimalMin("0") BigDecimal scrapQty,
            @NotNull @DecimalMin("0") BigDecimal transferredQty) { }

    public record CreateProductionReportRequest(@Min(0) long expectedRowVersion,
            @NotBlank @Pattern(regexp = UUID) String planJobMemberId,
            @NotBlank @Pattern(regexp = UUID) String taskId,
            @NotBlank @Pattern(regexp = "PROGRESS|COMPLETE") String reportType,
            @NotBlank String reportedAt, @NotNull @Valid ProductionReportQuantitiesRequest quantities,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,15}$") String uomCode,
            @Size(max = 500) String defectReason, @Size(max = 64) String operatorUserId) { }

    public record CorrectProductionReportRequest(@Min(0) long expectedRunRowVersion,
            @Min(0) long expectedReportRowVersion, @NotBlank String reportedAt,
            @NotNull @Valid ProductionReportQuantitiesRequest quantities,
            @NotBlank @Size(max = 500) String reason) { }

    public record QualityDecisionRequest(@Min(0) long expectedRunRowVersion,
            @Min(0) long expectedOutputLotRowVersion,
            @NotBlank @Pattern(regexp = "HOLD|RELEASE|REJECT|REWORK|SCRAP|USE_AS_IS") String decision,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotBlank String occurredAt, @Size(max = 500) String reason,
            Boolean createReplenishment) { }

    public record QuantityMovementRequest(@Min(0) long expectedRunRowVersion,
            @Min(0) long expectedOutputLotRowVersion,
            @NotBlank @Pattern(regexp = UUID) String materialDemandId,
            @NotBlank @Pattern(regexp = UUID) String targetTaskId,
            @Pattern(regexp = UUID) String targetExecutionRunId,
            @NotBlank @Pattern(regexp = "RESERVE|UNRESERVE|CONSUME|TRANSFER") String operation,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotBlank String occurredAt, @Size(max = 500) String reason) { }
}
