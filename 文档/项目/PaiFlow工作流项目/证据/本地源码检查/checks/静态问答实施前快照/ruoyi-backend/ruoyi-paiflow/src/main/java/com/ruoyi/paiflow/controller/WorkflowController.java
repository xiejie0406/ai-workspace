package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowController;
import com.ruoyi.paiflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/workflows")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowController {
    private final WorkflowService workflows;
    public WorkflowController(WorkflowService workflows) { this.workflows=workflows; }
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult list(@RequestParam long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String search) {
        var page=workflows.list(spaceId,pageNum,pageSize,search,null,2,null);var result=AjaxResult.success();result.put("rows",page.pageData());result.put("total",page.totalCount());return result;
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult detail(@PathVariable long id,HttpServletResponse response) { return result(workflows.detail(id,null),response); }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:create')")
    @Log(title="PaiFlow工作流",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody JSONObject body,HttpServletResponse response) { return result(workflows.create(LegacyWorkflowController.scope(null,body.getLong("spaceId")),body),response); }
    @PutMapping("/{id}/draft")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow工作流草稿",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult save(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) { return result(workflows.save(id,body.getLong("spaceId"),LegacyWorkflowController.revision(body.getLong("revision"),ifMatch),body),response); }
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:remove')")
    @Log(title="PaiFlow工作流",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable long id,@RequestHeader("If-Match") String ifMatch) { workflows.delete(id,null,LegacyWorkflowController.revision(null,ifMatch));return AjaxResult.success(); }
    @PostMapping("/{id}/copy")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:create')")
    @Log(title="PaiFlow工作流复制",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult copy(@PathVariable long id,@RequestBody JSONObject body,HttpServletResponse response) { return result(workflows.copy(id,LegacyWorkflowController.scope(null,body.getLong("spaceId"))),response); }
    @PostMapping("/{id}/versions")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow工作流版本",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult version(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) { return result(workflows.createVersion(id,null,LegacyWorkflowController.revision(body.getLong("revision"),ifMatch),body),response); }
    @GetMapping("/{id}/versions")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult versions(@PathVariable long id,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) { var page=workflows.versions(id,null,pageNum,pageSize);var result=AjaxResult.success();result.put("rows",page.records());result.put("total",page.total());return result; }
    @PostMapping("/{id}/versions/{versionId}/restore")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow工作流恢复",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult restore(@PathVariable long id,@PathVariable long versionId,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) { return result(workflows.restore(id,versionId,null,LegacyWorkflowController.revision(body.getLong("revision"),ifMatch)),response); }
    private static AjaxResult result(JSONObject body,HttpServletResponse response) { response.setHeader("ETag","\""+body.getLong("revision")+"\"");return AjaxResult.success(body); }
}
