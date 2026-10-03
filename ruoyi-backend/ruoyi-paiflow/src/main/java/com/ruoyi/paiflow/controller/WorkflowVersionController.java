package com.ruoyi.paiflow.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.WorkflowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/paiflow/workflow-versions")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowVersionController {
    private final WorkflowService workflows;
    public WorkflowVersionController(WorkflowService workflows) { this.workflows=workflows; }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult detail(@PathVariable long id) { return AjaxResult.success(workflows.versionDetail(id)); }
}
