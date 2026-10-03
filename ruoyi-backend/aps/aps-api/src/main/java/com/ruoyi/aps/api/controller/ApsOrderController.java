package com.ruoyi.aps.api.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.order.OrderCatalog.ComponentSpec;
import com.ruoyi.aps.application.order.OrderCatalog.DemandType;
import com.ruoyi.aps.application.order.OrderCatalog.ExpansionResult;
import com.ruoyi.aps.application.order.OrderCatalog.LineDependencySpec;
import com.ruoyi.aps.application.order.OrderCatalog.LotType;
import com.ruoyi.aps.application.order.OrderCatalog.MigrationDiff;
import com.ruoyi.aps.application.order.OrderCatalog.OrderLine;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionLot;
import com.ruoyi.aps.application.order.OrderCatalog.ProductionOrder;
import com.ruoyi.aps.application.order.OrderManagementService;
import com.ruoyi.aps.domain.routing.DependencyType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** IMP-04 的多产品订单、批次和任务展开 HTTP 纵切片。 */
@RestController
@RequestMapping("/api/aps/v1/orders")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
public class ApsOrderController
{
    private final OrderManagementService service;
    private final ApsAuditActorProvider actors;

    public ApsOrderController(OrderManagementService service, ApsAuditActorProvider actors)
    {
        this.service = service;
        this.actors = actors;
    }

    @PreAuthorize("@ss.hasPermi('aps:order:list')")
    @GetMapping
    public List<ProductionOrder> orders() { return service.listOrders(scope()); }

    @PreAuthorize("@ss.hasPermi('aps:order:list')")
    @GetMapping("/{id}")
    public ProductionOrder order(@PathVariable String id) { service.requireOrderScope(scope(), id); return service.getOrder(id); }

    @PreAuthorize("@ss.hasPermi('aps:order:add')")
    @PostMapping
    public ProductionOrder create(@Valid @RequestBody OrderInput input)
    {
        ProductionOrder value = model(input); service.requireInputScope(scope(), value);
        return service.createOrImport(value, actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:order:import')")
    @PostMapping("/import")
    public ProductionOrder importOrder(@Valid @RequestBody OrderInput input)
    {
        ProductionOrder value = model(input); service.requireInputScope(scope(), value);
        return service.createOrImport(value, actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:order:edit')")
    @PostMapping("/{id}/expand")
    public ExpansionResult expand(@PathVariable String id) { service.requireOrderScope(scope(), id); return service.expandOrder(id, actor()); }

    @PreAuthorize("@ss.hasPermi('aps:order:list')")
    @GetMapping("/{id}/expansion")
    public ExpansionResult expansion(@PathVariable String id) { service.requireOrderScope(scope(), id); return service.getExpansion(id); }

    @PreAuthorize("@ss.hasPermi('aps:order:release')")
    @PostMapping("/{id}/release")
    public ProductionOrder release(@PathVariable String id, @Valid @RequestBody VersionInput input)
    {
        service.requireOrderScope(scope(), id);
        return service.releaseOrder(id, input.rowVersion(), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:order:edit')")
    @PostMapping("/lots")
    public ProductionLot createDerivedLot(@Valid @RequestBody DerivedLotInput input)
    {
        service.requireLotScope(scope(), input.parentLotId());
        return service.createDerivedLot(input.parentLotId(), input.lotType(), input.plannedQty(),
                input.recoveryReason(), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:order:list')")
    @GetMapping("/lines/{lineId}/route-migration")
    public MigrationDiff previewMigration(@PathVariable String lineId, @RequestParam String targetRouteVersionId)
    {
        service.requireLineScope(scope(), lineId); service.requireRouteScope(scope(), targetRouteVersionId);
        return service.previewRouteMigration(lineId, targetRouteVersionId);
    }

    @PreAuthorize("@ss.hasPermi('aps:order:edit')")
    @PostMapping("/lines/{lineId}/route-migration")
    public OrderLine confirmMigration(@PathVariable String lineId, @Valid @RequestBody MigrationInput input)
    {
        service.requireLineScope(scope(), lineId); service.requireRouteScope(scope(), input.targetRouteVersionId());
        return service.confirmRouteMigration(lineId, input.expectedCurrentRouteVersionId(),
                input.targetRouteVersionId(), input.rowVersion(), input.confirmed(), actor());
    }

    private ProductionOrder model(OrderInput input)
    {
        List<OrderLine> lines = input.lines().stream().map(line -> new OrderLine(null, null, line.lineNo(),
                line.itemId(), line.routeVersionId(), line.demandQty(), line.uomCode(), optionalUtc(line.promisedAt()),
                optionalUtc(line.earliestStartAt()), "DRAFT", line.components().stream().map(ComponentInput::model).toList(),
                line.dependencies().stream().map(DependencyInput::model).toList(), 0)).toList();
        return new ProductionOrder(null, input.orderNo(), input.sourceSystem(), input.externalId(), input.customerCode(),
                input.customerName(), input.priority(), optionalUtc(input.promisedAt()), optionalUtc(input.earliestStartAt()),
                "DRAFT", input.remark(), lines, 0);
    }

    @PreAuthorize("@ss.hasPermi('aps:order:edit')")
    @PutMapping("/{id}")
    public ProductionOrder update(@PathVariable String id, @Valid @RequestBody DraftOrderInput input)
    {
        service.requireOrderScope(scope(), id); service.requireInputScope(scope(), model(input.order()));
        return service.updateDraft(id, input.rowVersion(), model(input.order()), actor());
    }
    public record DraftOrderInput(@PositiveOrZero long rowVersion, @NotNull @Valid OrderInput order) { }

    private String actor() { return actors.currentActor().username(); }
    private com.ruoyi.aps.application.resource.ResourceAccessScope scope() {
        return new com.ruoyi.aps.application.resource.ResourceAccessScope(actors.currentActor().userId(),
                com.ruoyi.common.utils.SecurityUtils.isAdmin() || com.ruoyi.common.utils.SecurityUtils.hasPermi("aps:scope:manage"));
    }
    private Instant optionalUtc(String value) { return value == null || value.isBlank() ? null : ApsUtcInput.parse(value); }

    public record ComponentInput(@Positive int demandNo, @NotBlank String targetNodeCode, @NotBlank String itemId,
            Integer sourceLineNo, String sourceNodeCode, @NotNull DemandType demandType,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal requiredQtyPerUnit,
            @NotBlank @Size(max = 16) String uomCode,
            @DecimalMin(value = "0", inclusive = false) BigDecimal transferBatchQty)
    {
        ComponentSpec model() { return new ComponentSpec(demandNo, targetNodeCode, itemId, sourceLineNo,
                sourceNodeCode, demandType, requiredQtyPerUnit, uomCode, transferBatchQty); }
    }

    public record DependencyInput(@Positive int predecessorLineNo, @NotBlank String predecessorNodeCode,
            @NotBlank String successorNodeCode, @NotNull DependencyType dependencyType,
            @DecimalMin(value = "0", inclusive = false) BigDecimal thresholdQty,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal thresholdRatio,
            @DecimalMin(value = "0", inclusive = false) BigDecimal transferBatchQty,
            @PositiveOrZero int lagSeconds, boolean consumesOutput)
    {
        LineDependencySpec model() { return new LineDependencySpec(predecessorLineNo, predecessorNodeCode,
                successorNodeCode, dependencyType, thresholdQty, thresholdRatio, transferBatchQty,
                lagSeconds, consumesOutput); }
    }

    public record LineInput(@Positive int lineNo, @NotBlank String itemId, @NotBlank String routeVersionId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal demandQty,
            @NotBlank @Size(max = 16) String uomCode, String promisedAt, String earliestStartAt,
            @NotNull @Size(max = 200) List<@Valid ComponentInput> components,
            @NotNull @Size(max = 200) List<@Valid DependencyInput> dependencies) { }

    public record OrderInput(@NotBlank @Size(max = 64) String orderNo, @Size(max = 32) String sourceSystem,
            @Size(max = 128) String externalId, @Size(max = 64) String customerCode,
            @Size(max = 128) String customerName, @Min(1) @Max(100) int priority,
            String promisedAt, String earliestStartAt, @Size(max = 500) String remark,
            @NotEmpty @Size(max = 1000) List<@Valid LineInput> lines) { }

    public record DerivedLotInput(@NotBlank String parentLotId, @NotNull LotType lotType,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal plannedQty,
            @NotBlank @Size(max = 500) String recoveryReason) { }

    public record MigrationInput(@NotBlank String expectedCurrentRouteVersionId,
            @NotBlank String targetRouteVersionId, @PositiveOrZero long rowVersion, boolean confirmed) { }

    public record VersionInput(@PositiveOrZero long rowVersion) { }
}
