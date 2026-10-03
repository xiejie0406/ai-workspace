package com.ruoyi.aps.api.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import com.ruoyi.aps.application.foundation.ApsAuditActor;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.foundation.ApsBusinessException;
import com.ruoyi.aps.application.foundation.ApsErrorCode;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.application.resource.ResourceCatalog.Availability;
import com.ruoyi.aps.application.resource.ResourceCatalog.Candidate;
import com.ruoyi.aps.application.resource.ResourceCatalog.ReadinessIssue;
import com.ruoyi.aps.application.resource.ResourceCatalog.Resource;
import com.ruoyi.aps.application.resource.ResourceCatalog.Skill;
import com.ruoyi.aps.application.resource.ResourceCatalog.WorkCenter;
import com.ruoyi.aps.application.resource.ResourceCatalog.Workshop;
import com.ruoyi.aps.application.resource.ResourceManagementService;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.domain.resource.AvailabilityType;
import com.ruoyi.aps.domain.resource.ResourceCalendarService.NetWindow;
import com.ruoyi.aps.domain.resource.ResourceType;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/** IMP-03 的 M01～M05 HTTP 纵切片。 */
@RestController
@RequestMapping("/api/aps/v1/resources")
@ConditionalOnProperty(prefix = "aps", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.api", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "aps.persistence", name = "enabled", havingValue = "true")
public class ApsResourceController
{
    private final ResourceManagementService service;
    private final ApsAuditActorProvider actors;

    public ApsResourceController(ResourceManagementService service, ApsAuditActorProvider actors)
    {
        this.service = service;
        this.actors = actors;
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/workshops")
    public List<Workshop> workshops()
    {
        return service.listWorkshops(scope());
    }

    /** 业务选择目录仍使用车间范围，不要求取得资源维护权限。 */
    @PreAuthorize("@ss.hasPermi('aps:resource:list') or @ss.hasPermi('aps:routing:list') or @ss.hasPermi('aps:order:list') or @ss.hasPermi('aps:planning:view') or @ss.hasPermi('aps:execution:view') or @ss.hasPermi('aps:report:view') or @ss.hasPermi('aps:report:export')")
    @GetMapping("/directory")
    public Directory directory()
    {
        ResourceAccessScope access = scope();
        List<Workshop> workshops = service.listWorkshops(access);
        List<WorkCenter> centers = workshops.stream().flatMap(workshop ->
                service.listWorkCenters(access, workshop.id()).stream()).toList();
        List<ResourceOption> resources = service.listResources(access, null, null).stream()
                .map(value -> new ResourceOption(value.id(), value.workshopId(), value.workCenterId(),
                        value.code(), value.name(), value.type(), value.status())).toList();
        return new Directory(workshops, centers, resources);
    }

    public record ResourceOption(String id, String workshopId, String workCenterId, String code,
            String name, ResourceType type, String status) { }
    public record Directory(List<Workshop> workshops, List<WorkCenter> centers, List<ResourceOption> resources) { }

    @PreAuthorize("@ss.hasPermi('aps:resource:add')")
    @PostMapping("/workshops")
    public Workshop createWorkshop(@Valid @RequestBody WorkshopInput input)
    {
        return service.saveWorkshop(scope(), actor(), input.toModel(null));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:edit')")
    @PutMapping("/workshops/{id}")
    public Workshop updateWorkshop(@PathVariable String id, @Valid @RequestBody WorkshopInput input)
    {
        return service.saveWorkshop(scope(), actor(), input.toModel(id));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/workshops/{workshopId}/centers")
    public List<WorkCenter> centers(@PathVariable String workshopId)
    {
        return service.listWorkCenters(scope(), workshopId);
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:add')")
    @PostMapping("/centers")
    public WorkCenter createCenter(@Valid @RequestBody WorkCenterInput input)
    {
        return service.saveWorkCenter(scope(), actor(), input.toModel(null));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:edit')")
    @PutMapping("/centers/{id}")
    public WorkCenter updateCenter(@PathVariable String id, @Valid @RequestBody WorkCenterInput input)
    {
        return service.saveWorkCenter(scope(), actor(), input.toModel(id));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping
    public List<Resource> resources(@RequestParam(required = false) String workshopId,
            @RequestParam(required = false) String workCenterId)
    {
        return service.listResources(scope(), workshopId, workCenterId);
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:add')")
    @PostMapping
    public Resource createResource(@Valid @RequestBody ResourceInput input)
    {
        return service.saveResource(scope(), actor(), input.toModel(null));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:edit')")
    @PutMapping("/{id}")
    public Resource updateResource(@PathVariable String id, @Valid @RequestBody ResourceInput input)
    {
        return service.saveResource(scope(), actor(), input.toModel(id));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/{resourceId}/skills")
    public List<Skill> skills(@PathVariable String resourceId)
    {
        return service.listSkills(scope(), resourceId);
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:edit')")
    @PostMapping("/{resourceId}/skills")
    public Skill saveSkill(@PathVariable String resourceId, @Valid @RequestBody SkillInput input)
    {
        return service.saveSkill(scope(), actor(), input.toModel(resourceId,
                optionalUtc(input.validFrom()), optionalUtc(input.validTo())));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/{resourceId}/availability")
    public List<Availability> availability(@PathVariable String resourceId)
    {
        return service.listAvailability(scope(), resourceId);
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:edit')")
    @PostMapping("/{resourceId}/availability")
    public Availability saveAvailability(@PathVariable String resourceId, @Valid @RequestBody AvailabilityInput input)
    {
        return service.saveAvailability(scope(), actor(), input.toModel(resourceId,
                utc(input.startAt()), utc(input.endAt())));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:import')")
    @PostMapping("/{resourceId}/availability-import")
    public List<Availability> importAvailability(@PathVariable String resourceId,
            @RequestBody List<@Valid AvailabilityInput> inputs)
    {
        if (inputs == null || inputs.isEmpty() || inputs.size() > 10_000)
        {
            throw new ApsBusinessException(ApsErrorCode.INVALID_REQUEST,
                    "单次时间窗导入数量必须在 1 到 10000 之间");
        }
        List<Availability> values = inputs.stream().map(input -> input.toModel(resourceId,
                utc(input.startAt()), utc(input.endAt()))).toList();
        return service.importAvailability(scope(), actor(), values);
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/{resourceId}/net-availability")
    public List<NetWindow> netAvailability(@PathVariable String resourceId, @RequestParam String from,
            @RequestParam String to)
    {
        return service.netAvailability(scope(), resourceId, utc(from), utc(to));
    }

    @PreAuthorize("@ss.hasPermi('aps:readiness:view')")
    @GetMapping("/workshops/{workshopId}/readiness")
    public List<ReadinessIssue> readiness(@PathVariable String workshopId, @RequestParam String at)
    {
        return service.readiness(scope(), workshopId, utc(at));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list')")
    @GetMapping("/workshops/{workshopId}/candidates")
    public List<Candidate> candidates(@PathVariable String workshopId, @RequestParam String skillCode,
            @RequestParam int minimumLevel, @RequestParam String start, @RequestParam String end)
    {
        return service.candidates(scope(), workshopId, skillCode, minimumLevel, utc(start), utc(end));
    }

    @PreAuthorize("@ss.hasPermi('aps:resource:list') and @ss.hasPermi('aps:scope:manage')")
    @GetMapping(value = "/personnel-export", produces = "text/csv;charset=UTF-8")
    public String exportPersonnel(@RequestParam(required = false) String workshopId)
    {
        StringBuilder csv = new StringBuilder("resourceId,resourceCode,resourceName,ruoyiUserId,teamName\r\n");
        service.listResources(scope(), workshopId, null).stream().filter(value -> value.type() == ResourceType.PERSON)
                .forEach(value -> csv.append(csv(value.id())).append(',').append(csv(value.code())).append(',')
                        .append(csv(value.name())).append(',').append(csv(value.ruoyiUserId())).append(',')
                        .append(csv(value.teamName())).append("\r\n"));
        return csv.toString();
    }

    private ResourceAccessScope scope()
    {
        ApsAuditActor actor = actor();
        return new ResourceAccessScope(actor.userId(), SecurityUtils.isAdmin() || SecurityUtils.hasPermi("aps:scope:manage"));
    }

    private ApsAuditActor actor()
    {
        return actors.currentActor();
    }

    private String csv(String value)
    {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private Instant optionalUtc(String value)
    {
        return value == null || value.isBlank() ? null : utc(value);
    }

    private Instant utc(String value)
    {
        return ApsUtcInput.parse(value);
    }

    public record WorkshopInput(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String managerUserId, String status, @Size(max = 500) String remark,
            @PositiveOrZero long rowVersion)
    {
        Workshop toModel(String id) { return new Workshop(id, code, name, managerUserId, status, remark, rowVersion); }
    }

    public record WorkCenterInput(@NotBlank String workshopId, @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name, String centerType,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal concurrentCapacity,
            @Size(max = 16) String capacityUomCode, String status, @Size(max = 500) String remark,
            @PositiveOrZero long rowVersion)
    {
        WorkCenter toModel(String id) { return new WorkCenter(id, workshopId, code, name, centerType,
                concurrentCapacity, capacityUomCode, status, remark, rowVersion); }
    }

    public record ResourceInput(@NotBlank String workshopId, String workCenterId,
            @NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name,
            @NotNull ResourceType type, @Size(max = 64) String ruoyiUserId, @Size(max = 128) String teamName,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal capacityValue,
            @Size(max = 16) String capacityUomCode, String status, @Size(max = 500) String remark,
            @PositiveOrZero long rowVersion)
    {
        Resource toModel(String id) { return new Resource(id, workshopId, workCenterId, code, name, type, ruoyiUserId,
                teamName, capacityValue, capacityUomCode, status, remark, rowVersion); }
    }

    public record SkillInput(String id, @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name, @Min(1) @Max(10) int level, String validFrom, String validTo,
            @Size(max = 128) String certificateRef, String status, @PositiveOrZero long rowVersion)
    {
        Skill toModel(String resourceId, Instant validFromValue, Instant validToValue) { return new Skill(id, resourceId, code, name, level, validFromValue, validToValue,
                certificateRef, status, rowVersion); }
    }

    public record AvailabilityInput(String id, @NotNull AvailabilityType type, @NotBlank String startAt,
            @NotBlank String endAt, @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") BigDecimal capacityRatio,
            @Size(max = 24) String sourceType, @Size(max = 128) String sourceRef, @Size(max = 500) String reason,
            @PositiveOrZero long rowVersion)
    {
        Availability toModel(String resourceId, Instant startAtValue, Instant endAtValue) { return new Availability(id, resourceId, type, startAtValue, endAtValue,
                capacityRatio, sourceType, sourceRef, reason, rowVersion); }
    }
}
