package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowController;
import com.ruoyi.paiflow.service.AgentService;
import com.ruoyi.paiflow.service.ApplicationService;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/applications")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ApplicationController {
    private final ApplicationService applications;
    public ApplicationController(ApplicationService applications){this.applications=applications;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paiflow:application:query')")
    public TableDataInfo page(@RequestParam long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="15") int pageSize){var page=applications.page(spaceId,pageNum,pageSize);var result=new TableDataInfo((java.util.List<?>)page.get("records"),page.getLongValue("total"));result.setCode(200);result.setMsg("查询成功");return result;}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:application:query')")
    public AjaxResult detail(@PathVariable String id){return AjaxResult.success(applications.detail(id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('paiflow:application:create')")
    @Log(title="PaiFlow应用登记",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestParam long spaceId,@RequestBody JSONObject body){return AjaxResult.success(applications.create(spaceId,body));}
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:application:edit')")
    @Log(title="PaiFlow应用登记",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult update(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match,@RequestBody JSONObject body){return AjaxResult.success(applications.update(id,revision(match),body));}
    @PostMapping("/{id}/binding") @PreAuthorize("@ss.hasPermi('paiflow:application:edit')")
    @Log(title="PaiFlow应用固定版本",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult bind(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match,@RequestBody JSONObject body){
        exact(body,Set.of("agentId","workflowVersionId"));
        return AjaxResult.success(applications.bind(id,revision(match),AgentService.number(body.get("agentId"),"agentId",true),AgentService.number(body.get("workflowVersionId"),"workflowVersionId",true)));
    }
    @PostMapping("/{id}/disable") @PreAuthorize("@ss.hasPermi('paiflow:application:edit')")
    @Log(title="PaiFlow停用应用",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult disable(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match){return AjaxResult.success(applications.disable(id,revision(match)));}
    @DeleteMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:application:remove')")
    @Log(title="PaiFlow删除应用",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match){applications.delete(id,revision(match));return AjaxResult.success();}
    @PostMapping("/{id}/runs") @PreAuthorize("@ss.hasPermi('paiflow:application:run')")
    @Log(title="PaiFlow应用运行",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult run(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match,
            @RequestHeader("Idempotency-Key") String key,@RequestBody JSONObject body){
        exact(body,Set.of("input","timeoutSeconds"));Object raw=body.get("input");
        if(!(raw instanceof JSONObject input))throw new ServiceException("应用输入必须为对象",400);
        long seconds=body.containsKey("timeoutSeconds")?AgentService.number(body.get("timeoutSeconds"),"timeoutSeconds",true):600;
        if(seconds>3600)throw new ServiceException("运行截止时间须在1至3600秒内",400);
        return AjaxResult.success(applications.invoke(id,revision(match),input,key,(int)seconds));
    }
    private static Long revision(String match){return LegacyWorkflowController.revision(null,match);}
    private static void exact(JSONObject body,Set<String> keys){if(body==null || !keys.containsAll(body.keySet()))throw new ServiceException("应用请求字段无效",400);}
}
