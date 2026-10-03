package com.ruoyi.aps.api.controller;

import java.util.UUID;
import com.ruoyi.aps.application.foundation.ApsAuditActorProvider;
import com.ruoyi.aps.application.planning.PlanReadinessService;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/aps/v1/readiness")
@ConditionalOnProperty(prefix="aps",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.api",name="enabled",havingValue="true")
@ConditionalOnProperty(prefix="aps.persistence",name="enabled",havingValue="true")
public class ApsReadinessController {
    private final PlanReadinessService service;
    private final ApsAuditActorProvider actors;
    public ApsReadinessController(PlanReadinessService service,ApsAuditActorProvider actors){this.service=service;this.actors=actors;}
    @PostMapping
    @PreAuthorize("@ss.hasPermi('aps:readiness:view') and @ss.hasPermi('aps:order:list')")
    public PlanReadinessService.Readiness check(@Valid @RequestBody ApsPlanRequestController.PlanRequestInput input){
        var actor=actors.currentActor();
        var scope=new ResourceAccessScope(actor.userId(),SecurityUtils.isAdmin()||SecurityUtils.hasPermi("aps:scope:manage"));
        return service.check(scope,input.toCompileRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString()));
    }
}
