package com.ruoyi.aps.api.controller;

import java.time.Instant;
import java.util.List;
import com.ruoyi.aps.api.dto.ApsUtcInput;
import com.ruoyi.aps.application.execution.ExecutionDirectoryService;
import com.ruoyi.aps.application.execution.ExecutionCatalog;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.common.utils.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/aps/v1/execution-runs")
@ConditionalOnProperty(prefix="aps",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.api",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.persistence",name="enabled",havingValue="true")
public class ApsExecutionDirectoryController {
    private final ExecutionDirectoryService service; private final ApsAuditActorProvider actors;
    public ApsExecutionDirectoryController(ExecutionDirectoryService service,ApsAuditActorProvider actors) {this.service=service;this.actors=actors;}
    @GetMapping("/dispatch-plans") @PreAuthorize("@ss.hasPermi('aps:execution:start')")
    public List<ExecutionDirectoryService.DispatchPlanOption> plans(){return service.dispatchPlans(scope());}
    @GetMapping("/dispatch-plans/{id}") @PreAuthorize("@ss.hasPermi('aps:execution:start')")
    public ExecutionDirectoryService.DispatchPlan plan(@PathVariable String id){return service.dispatchPlan(scope(),id);}
    @GetMapping @PreAuthorize("@ss.hasPermi('aps:execution:view')")
    public List<ExecutionCatalog.RunOption> runs(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String resourceId) {
        return service.runs(scope(),keyword,status,resourceId);
    }
    @GetMapping("/{id}/resource-candidates") @PreAuthorize("@ss.hasPermi('aps:execution:start')")
    public List<ExecutionDirectoryService.ResourceOption> replacements(@PathVariable String id,@RequestParam String replacedResourceId,
            @RequestParam(required=false) String at) {return service.replacements(scope(),id,replacedResourceId,at==null?Instant.now():ApsUtcInput.parse(at));}
    @GetMapping("/{id}/report-members") @PreAuthorize("@ss.hasPermi('aps:execution:report')")
    public List<ExecutionDirectoryService.ReportMemberBalance> members(@PathVariable String id) {return service.members(scope(),id);}
    @GetMapping("/{id}/output-lots/{lotId}/movement-targets") @PreAuthorize("@ss.hasPermi('aps:execution:report')")
    public List<ExecutionDirectoryService.MovementTarget> targets(@PathVariable String id,@PathVariable String lotId) {return service.targets(scope(),id,lotId);}
    @GetMapping("/{id}/output-lots/{lotId}/quantity-events") @PreAuthorize("@ss.hasPermi('aps:execution:view')")
    public List<ExecutionCatalog.QuantityEvent> events(@PathVariable String id,@PathVariable String lotId) {return service.events(scope(),id,lotId);}
    private ResourceAccessScope scope(){return new ResourceAccessScope(actors.currentActor().userId(),SecurityUtils.isAdmin()||SecurityUtils.hasPermi("aps:scope:manage"));}
}
