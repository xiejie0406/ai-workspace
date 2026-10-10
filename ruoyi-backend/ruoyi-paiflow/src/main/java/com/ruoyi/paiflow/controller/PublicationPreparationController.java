package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.adapter.legacy.LegacyWorkflowController;
import com.ruoyi.paiflow.service.PublicationPreparationService;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/publications/preparations")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class PublicationPreparationController {
    private final PublicationPreparationService preparations;
    public PublicationPreparationController(PublicationPreparationService preparations){this.preparations=preparations;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paiflow:publication:query')")
    public TableDataInfo page(@RequestParam long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="15") int pageSize){
        var page=preparations.page(spaceId,pageNum,pageSize);var result=new TableDataInfo((java.util.List<?>)page.get("records"),page.getLongValue("total"));result.setCode(200);result.setMsg("查询成功");return result;
    }
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:publication:query')")
    public AjaxResult detail(@PathVariable String id){return AjaxResult.success(preparations.detail(id));}
    @PostMapping @PreAuthorize("@ss.hasPermi('paiflow:publication:create')")
    @Log(title="PaiFlow发布准备",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult prepare(@RequestHeader(value="If-Match",required=false) String match,@RequestHeader("Idempotency-Key") String key,@RequestBody JSONObject body){
        if(body==null || !body.keySet().equals(Set.of("applicationId","channel","debugRunId")))throw new ServiceException("发布准备字段无效",400);
        for(String field:body.keySet())if(!(body.get(field) instanceof String))throw new ServiceException("发布准备字段必须为字符串",400);
        return AjaxResult.success(preparations.prepare(body.getString("applicationId"),LegacyWorkflowController.revision(null,match),body.getString("channel"),body.getString("debugRunId"),key));
    }
    @PostMapping("/{id}/withdraw") @PreAuthorize("@ss.hasPermi('paiflow:publication:edit')")
    @Log(title="PaiFlow撤回发布准备",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult withdraw(@PathVariable String id,@RequestHeader(value="If-Match",required=false) String match){
        return AjaxResult.success(preparations.withdraw(id,LegacyWorkflowController.revision(null,match)));
    }
}
