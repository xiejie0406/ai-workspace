package com.ruoyi.aps.api.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.routing.RoutingCatalog.DurationModel;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.ItemType;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationMode;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationPhase;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.PhaseType;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceHoldPolicy;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.SegmentResourcePolicy;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdgeDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraph;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraphDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNodeDraft;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteValidation;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;
import com.ruoyi.aps.application.routing.RoutingManagementService;
import com.ruoyi.aps.domain.resource.ResourceType;
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

/** IMP-04 的产品、工序与路线版本 HTTP 纵切片。 */
@RestController
@RequestMapping("/api/aps/v1/routings")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
public class ApsRoutingController
{
    private final RoutingManagementService service;
    private final ApsAuditActorProvider actors;

    public ApsRoutingController(RoutingManagementService service, ApsAuditActorProvider actors)
    {
        this.service = service;
        this.actors = actors;
    }

    @PreAuthorize("@ss.hasPermi('aps:routing:list')")
    @GetMapping("/items")
    public List<Item> items() { return service.listItems(); }

    @PreAuthorize("@ss.hasPermi('aps:routing:add')")
    @PostMapping("/items")
    public Item createItem(@Valid @RequestBody ItemInput input) { return service.saveItem(input.model(null), actor()); }

    @PreAuthorize("@ss.hasPermi('aps:routing:edit')")
    @PutMapping("/items/{id}")
    public Item updateItem(@PathVariable String id, @Valid @RequestBody ItemInput input) { return service.saveItem(input.model(id), actor()); }

    @PreAuthorize("@ss.hasPermi('aps:routing:list')")
    @GetMapping("/operations")
    public List<OperationSpec> operations() { return service.listOperations(); }

    @PreAuthorize("@ss.hasPermi('aps:routing:add')")
    @PostMapping("/operations")
    public OperationSpec createOperation(@Valid @RequestBody OperationInput input) { return service.saveOperation(input.model(null), actor()); }

    @PreAuthorize("@ss.hasPermi('aps:routing:edit')")
    @PutMapping("/operations/{id}")
    public OperationSpec updateOperation(@PathVariable String id, @Valid @RequestBody OperationInput input) { return service.saveOperation(input.model(id), actor()); }

    @PreAuthorize("@ss.hasPermi('aps:routing:publish')")
    @PostMapping("/operations/{id}/activate")
    public OperationSpec activateOperation(@PathVariable String id, @Valid @RequestBody VersionInput input)
    {
        return service.activateOperation(id, input.rowVersion(), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:routing:list')")
    @GetMapping("/routes")
    public List<RouteVersion> routes(@RequestParam(required = false) String itemId) { return service.listRoutes(itemId); }

    @PreAuthorize("@ss.hasPermi('aps:routing:list')")
    @GetMapping("/routes/{id}")
    public RouteGraph route(@PathVariable String id) { return service.getRouteGraph(id); }

    @PreAuthorize("@ss.hasPermi('aps:routing:add')")
    @PostMapping("/routes")
    public RouteVersion createRoute(@Valid @RequestBody RouteInput input)
    {
        return service.createRoute(new RouteVersion(null, input.itemId(), input.routeCode(), input.versionNo(),
                "DRAFT", optionalUtc(input.effectiveFrom()), optionalUtc(input.effectiveTo()), input.changeNote(),
                null, null, 0), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:routing:edit')")
    @PutMapping("/routes/{id}/graph")
    public RouteGraph saveGraph(@PathVariable String id, @Valid @RequestBody GraphInput input)
    {
        return service.saveGraph(id, input.rowVersion(), input.model(), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:routing:add')")
    @PostMapping("/routes/{id}/copy")
    public RouteGraph copyRoute(@PathVariable String id, @Valid @RequestBody CopyInput input)
    {
        return service.copyRoute(id, input.versionNo(), input.changeNote(), actor());
    }

    @PreAuthorize("@ss.hasPermi('aps:routing:list')")
    @GetMapping("/routes/{id}/validation")
    public RouteValidation validateRoute(@PathVariable String id) { return service.validateRoute(id); }

    @PreAuthorize("@ss.hasPermi('aps:routing:publish')")
    @PostMapping("/routes/{id}/publish")
    public RouteVersion publishRoute(@PathVariable String id, @Valid @RequestBody VersionInput input)
    {
        return service.publishRoute(id, input.rowVersion(), actor());
    }

    private String actor() { return actors.currentActor().username(); }
    private Instant optionalUtc(String value) { return value == null || value.isBlank() ? null : ApsUtcInput.parse(value); }

    public record ItemInput(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name,
            @NotNull ItemType type, @Size(max = 255) String specification, @NotBlank @Size(max = 16) String baseUomCode,
            @NotBlank String status, @Size(max = 500) String remark, @PositiveOrZero long rowVersion)
    {
        Item model(String id) { return new Item(id, code, name, type, specification, baseUomCode, status, remark, rowVersion); }
    }

    public record RequirementInput(String id, @Positive int requirementNo, @NotNull ResourceType resourceType,
            String workCenterId, String fixedResourceId, @Positive int seatCount, @Size(max = 64) String requiredSkillCode,
            @Min(1) @Max(10) Integer minimumSkillLevel, String capabilityRuleJson, boolean optional,
            boolean holdOnPause)
    {
        ResourceRequirement model() { return new ResourceRequirement(id, null, requirementNo, resourceType,
                workCenterId, fixedResourceId, seatCount, requiredSkillCode, minimumSkillLevel,
                capabilityRuleJson, optional, holdOnPause, 0); }
    }

    public record PhaseInput(String id, @Positive int phaseNo, @NotNull PhaseType phaseType,
            @NotBlank @Size(max = 128) String name, @NotNull DurationModel durationModel,
            @PositiveOrZero int fixedSeconds, @NotNull @DecimalMin("0") BigDecimal secondsPerUnit,
            @NotNull ResourceHoldPolicy resourceHoldPolicy, @Positive int maxSegments,
            @PositiveOrZero int minSegmentSeconds, @PositiveOrZero int resumeSetupSeconds,
            @NotNull SegmentResourcePolicy segmentResourcePolicy, @NotNull List<@Valid RequirementInput> requirements)
    {
        OperationPhase model() { return new OperationPhase(id, null, phaseNo, phaseType, name, durationModel,
                fixedSeconds, secondsPerUnit, resourceHoldPolicy, maxSegments, minSegmentSeconds,
                resumeSetupSeconds, segmentResourcePolicy, requirements.stream().map(RequirementInput::model).toList(), 0); }
    }

    public record OperationInput(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name,
            @NotNull OperationMode mode, String outputItemId, boolean interruptible, boolean qualityGateRequired,
            @DecimalMin(value = "0", inclusive = false) BigDecimal batchCapacity, @Size(max = 16) String batchUomCode,
            String compatibilityRuleJson, @Size(max = 500) String remark,
            @NotEmpty @Size(max = 64) List<@Valid PhaseInput> phases, @PositiveOrZero long rowVersion)
    {
        OperationSpec model(String id) { return new OperationSpec(id, code, name, mode, outputItemId, interruptible,
                qualityGateRequired, batchCapacity, batchUomCode, compatibilityRuleJson, "DRAFT", remark,
                phases.stream().map(PhaseInput::model).toList(), rowVersion); }
    }

    public record RouteInput(@NotBlank String itemId, @NotBlank @Size(max = 64) String routeCode,
            @NotBlank @Size(max = 32) String versionNo, String effectiveFrom, String effectiveTo,
            @Size(max = 500) String changeNote) { }

    public record NodeInput(String id, @NotBlank String operationSpecId, @NotBlank @Size(max = 64) String nodeCode,
            @NotBlank @Size(max = 128) String nodeName, int displayOrder,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantityMultiplier, boolean terminal)
    {
        RouteNodeDraft model() { return new RouteNodeDraft(id, operationSpecId, nodeCode, nodeName, displayOrder,
                quantityMultiplier, terminal); }
    }

    public record EdgeInput(String id, @NotBlank String predecessorNodeCode, @NotBlank String successorNodeCode,
            @NotNull DependencyType dependencyType, @DecimalMin(value = "0", inclusive = false) BigDecimal thresholdQty,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal thresholdRatio,
            @DecimalMin(value = "0", inclusive = false) BigDecimal transferBatchQty,
            @PositiveOrZero int lagSeconds, boolean consumesOutput)
    {
        RouteEdgeDraft model() { return new RouteEdgeDraft(id, predecessorNodeCode, successorNodeCode, dependencyType,
                thresholdQty, thresholdRatio, transferBatchQty, lagSeconds, consumesOutput); }
    }

    public record GraphInput(@NotEmpty @Size(max = 500) List<@Valid NodeInput> nodes,
            @NotNull @Size(max = 2000) List<@Valid EdgeInput> edges,@PositiveOrZero Long rowVersion)
    {
        RouteGraphDraft model() { return new RouteGraphDraft(nodes.stream().map(NodeInput::model).toList(),
                edges.stream().map(EdgeInput::model).toList()); }
    }

    public record CopyInput(@NotBlank @Size(max = 32) String versionNo, @Size(max = 500) String changeNote) { }
    public record VersionInput(@PositiveOrZero long rowVersion) { }
}
