package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.ModelService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 模型原生草稿与不可变版本；验证入口复用相同受控候选/回执事务。 */
@RestController
@RequestMapping("/paiflow/models")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelController {
    private final ModelService models;
    private static final Pattern TAG=Pattern.compile("\"paiflow-model-([1-9][0-9]{0,18})-([0-9]{1,19})\"");
    public ModelController(ModelService models) {this.models=models;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult list(@RequestParam long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) String search,@RequestParam(required=false) Integer type) {return page(models.list(spaceId,pageNum,pageSize,search,type));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult detail(@PathVariable long id,@RequestParam(required=false) Long spaceId,HttpServletResponse response) {return result(models.detail(id,spaceId),response);}
    @PostMapping @PreAuthorize("@ss.hasPermi('paiflow:model:create')")
    @Log(title="PaiFlow模型草稿",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody JSONObject body,HttpServletResponse response) {return result(models.createDraft(space(body),body),response);}
    @PutMapping("/{id}/draft") @PreAuthorize("@ss.hasPermi('paiflow:model:edit')")
    @Log(title="PaiFlow模型草稿",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult update(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response) {return result(models.saveDraft(id,optionalSpace(body),condition(id,body.get("revision"),match),body),response);}
    @PostMapping("/{id}/validate") @PreAuthorize("@ss.hasPermi('paiflow:model:edit')")
    @Log(title="PaiFlow模型校验",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult validate(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response) {
        if(body.get("id")!=null && ModelService.positive(body.get("id"),"id")!=id)throw new ServiceException("模型ID不一致",400);
        long type=ModelService.positive(body.get("type"),"type");if(type!=1 && type!=2)throw new ServiceException("模型类型只能为1或2",400);
        var request=new JSONObject(body);request.put("id",id);return result(models.validateLegacy(space(body),condition(id,body.get("revision"),match),request,type==2),response);
    }
    @PutMapping("/{id}/enabled") @PreAuthorize("@ss.hasPermi('paiflow:model:edit')")
    @Log(title="PaiFlow模型启停",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult enabled(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response) {
        if(!(body.get("enabled") instanceof Boolean value))throw new ServiceException("enabled必须为布尔值",400);
        if(body.get("id")!=null && ModelService.positive(body.get("id"),"id")!=id)throw new ServiceException("模型ID不一致",400);
        return result(models.switchEnabled(id,optionalSpace(body),condition(id,body.get("revision"),match),value),response);
    }
    @DeleteMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:model:remove')")
    @Log(title="PaiFlow模型删除",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable long id,@RequestParam(required=false) Long spaceId,@RequestParam(required=false) String revision,@RequestHeader(value="If-Match",required=false) String match) {models.delete(id,spaceId,condition(id,revision,match));return AjaxResult.success();}
    @GetMapping("/{id}/versions") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult versions(@PathVariable long id,@RequestParam(required=false) Long spaceId,@RequestParam(defaultValue="1") int pageNum,@RequestParam(defaultValue="10") int pageSize) {return page(models.versions(id,spaceId,pageNum,pageSize));}
    @GetMapping("/versions/{versionId}") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult version(@PathVariable long versionId,@RequestParam(required=false) Long spaceId) {return AjaxResult.success(models.versionDetail(versionId,spaceId));}
    @GetMapping("/category-tree") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult category(@RequestParam long spaceId) {return AjaxResult.success(models.categoryTree(spaceId));}
    @GetMapping("/local-files") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult localFiles(@RequestParam long spaceId){return AjaxResult.success(models.localModelList(spaceId));}
    @PostMapping("/{id}/deployment-status") @PreAuthorize("@ss.hasPermi('paiflow:model:edit')")
    public AjaxResult deploymentStatus(@PathVariable long id,@RequestBody JSONObject body,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response){return result(models.refreshDeployment(id,optionalSpace(body),condition(id,body.get("revision"),match)),response);}
    @GetMapping("/category-tree/diagnostics") @PreAuthorize("@ss.hasPermi('paiflow:model:query')")
    public AjaxResult diagnostics(@RequestParam long spaceId) {return AjaxResult.success(models.categoryDiagnostics(spaceId));}
    private static AjaxResult page(ModelService.Page page) {return AjaxResult.success().put("rows",page.records()).put("total",page.total()).put("pageNum",page.current()).put("pageSize",page.size());}
    private static AjaxResult result(JSONObject value,HttpServletResponse response) {headers(value,response);return AjaxResult.success(value);}
    public static void headers(JSONObject value,HttpServletResponse response) {response.setHeader("ETag","\"paiflow-model-"+value.getLongValue("id")+"-"+value.getLongValue("revision")+"\"");}
    /** 条件绑定明确模型ID；禁止弱tag、通配符、别的卡片和body/header冲突。 */
    public static Long condition(long id,Object supplied,String header) {
        Long value=ModelService.optionalRevision(supplied);if(header!=null) {var parsed=TAG.matcher(header);if(!parsed.matches())throw new ServiceException("If-Match须为先前读取的模型ETag",400);
            long tagId=ModelService.positive(parsed.group(1),"ETag模型ID");Long tagRevision=ModelService.optionalRevision(parsed.group(2));if(tagId!=id)throw new ServiceException("If-Match不属于本模型",409);if(value!=null && !Objects.equals(value,tagRevision))throw new ServiceException("模型版本条件不一致",400);value=tagRevision;}
        if(value==null)throw new ServiceException("缺少先前读取的模型版本条件",409);return value;
    }
    private static long space(JSONObject body) {return ModelService.positive(body.get("spaceId"),"spaceId");}
    private static Long optionalSpace(JSONObject body) {return body.get("spaceId")==null?null:space(body);}
}
