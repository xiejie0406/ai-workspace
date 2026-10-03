package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.adapter.legacy.LegacyBotController;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowController;
import com.ruoyi.paiflow.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/agents")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class AgentController {
    private final AgentService agents;
    public AgentController(AgentService agents) { this.agents=agents; }
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paiflow:agent:query')")
    public AjaxResult list(@RequestParam long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="15") int pageSize,@RequestParam(required=false) String search,@RequestParam(required=false) Integer version) { var page=agents.list(spaceId,JSONObject.of("pageIndex",pageNum,"pageSize",pageSize,"searchValue",search,"version",version));var result=AjaxResult.success();result.put("rows",page.pageData());result.put("total",page.totalCount());return result; }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:agent:query')")
    public AjaxResult detail(@PathVariable long id,HttpServletResponse response) { return result(agents.detail(id,null),response); }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paiflow:agent:create')")
    @Log(title="PaiFlow智能体",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody JSONObject body,HttpServletResponse response) { long value=body.containsKey("version")?AgentService.number(body.get("version"),"version",true):1;if(value!=1 && value!=3)throw new com.ruoyi.common.exception.ServiceException("智能体类型只能为1或3",400);return result(agents.create(LegacyWorkflowController.scope(null,body.getLong("spaceId")),(int)value,body),response); }
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:agent:edit')")
    @Log(title="PaiFlow智能体",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult update(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="X-Workflow-Revision",required=false) String workflowMatch,HttpServletResponse response) { return result(agents.update(id,body.getLong("spaceId"),LegacyBotController.revision(body,match),LegacyBotController.workflowRevision(body,workflowMatch),body),response); }
    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:agent:remove')")
    @Log(title="PaiFlow智能体",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable long id,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="X-Workflow-Revision",required=false) String workflowMatch) { agents.delete(id,null,LegacyWorkflowController.revision(null,match),LegacyWorkflowController.revision(null,workflowMatch));return AjaxResult.success(); }
    @PostMapping("/{id}/copy")
    @PreAuthorize("@ss.hasPermi('paiflow:agent:copy')")
    @Log(title="PaiFlow智能体复制",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult copy(@PathVariable long id,@RequestBody JSONObject body,HttpServletResponse response) { return result(agents.copy(id,LegacyWorkflowController.scope(null,body.getLong("spaceId"))),response); }
    private static AjaxResult result(JSONObject value,HttpServletResponse response) { LegacyBotController.headers(value,response);return AjaxResult.success(value); }
}
