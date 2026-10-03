package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.controller.ModelController;
import com.ruoyi.paiflow.service.ModelService;
import com.ruoyi.paiflow.service.DefaultPersonalSpaceService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原/api/model的页面协议；平台身份/空间由中心确认，旧uid不参与授权。 */
@RestController
@RequestMapping("/paiflow/legacy/api/model")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyModelController {
    private final ModelService models;
    public LegacyModelController(ModelService models) {this.models=models;}
    @PostMapping
    @Log(title="PaiFlow模型校验",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> validate(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response) {
        Long selected=scope(space,body.get("spaceId"));var value=selected==null?models.validateLegacyDefault(expected(body,match),form(body),false):models.validateLegacy(selected,expected(body,match),form(body),false);ModelController.headers(value,response);return LegacyResult.success("Model validation passed");
    }
    @PostMapping("/local-model")
    @Log(title="PaiFlow本地模型部署",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> local(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,HttpServletResponse response) {
        Long selected=scope(space,body.get("spaceId"));var value=selected==null?models.validateLegacyDefault(expected(body,match),form(body),true):models.validateLegacy(selected,expected(body,match),form(body),true);ModelController.headers(value,response);return LegacyResult.success(true);
    }
    @PostMapping("/list")
    public LegacyResult<ModelService.Page> list(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space) {Long selected=scope(space,body.get("spaceId"));return LegacyResult.success(selected==null?models.legacyDefaultList(body):models.legacyList(selected,body));}
    @GetMapping("/detail")
    public LegacyResult<JSONObject> detail(@RequestParam int llmSource,@RequestParam long modelId,@RequestParam(required=false) Long spaceId,@RequestHeader(value="space-id",required=false) String space,HttpServletResponse response) {
        Long selected=scope(space,spaceId);var value=selected==null?models.legacyDefaultDetail(modelId,llmSource):models.legacyDetail(modelId,selected,llmSource);if(llmSource==0)ModelController.headers(value,response);return LegacyResult.success(value);
    }
    @GetMapping({"/on","/off"})
    @Log(title="PaiFlow模型启停",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> enabled(@RequestParam int llmSource,@RequestParam long modelId,@RequestParam(required=false) Long spaceId,@RequestParam(required=false) String revision,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,jakarta.servlet.http.HttpServletRequest request,HttpServletResponse response) {
        Long expected=ModelController.condition(modelId,revision,match);Long selected=scope(space,spaceId);if(llmSource!=0){if(selected==null)models.legacyDefaultUnavailable("官方/微调模型启停");else models.unavailable(selected,"官方/微调模型启停");}
        boolean enabled=request.getRequestURI().endsWith("/on");var value=selected==null?models.legacyDefaultEnabled(modelId,expected,enabled):models.switchEnabled(modelId,selected,expected,enabled);ModelController.headers(value,response);return LegacyResult.success(true);
    }
    @GetMapping("/delete")
    @Log(title="PaiFlow模型删除",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> delete(@RequestParam long modelId,@RequestParam(required=false) Long spaceId,@RequestParam(required=false) String revision,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match) {Long selected=scope(space,spaceId);Long expected=ModelController.condition(modelId,revision,match);if(selected==null)models.legacyDefaultDelete(modelId,expected);else models.delete(modelId,selected,expected);return LegacyResult.success(true);}
    @GetMapping("/category-tree")
    public LegacyResult<List<JSONObject>> categories(@RequestHeader(value="space-id",required=false) String space) {Long selected=scope(space,null);return LegacyResult.success(selected==null?models.legacyDefaultCategories():models.categoryTree(selected));}
    @GetMapping({"/rsa/public-key","/local-model/list","/off-model","/check-model-base"})
    public LegacyResult<Void> unavailable(@RequestHeader(value="space-id",required=false) String space) {Long selected=scope(space,null);if(selected==null)models.legacyDefaultUnavailable("原RSA/模型部署目录/下架绑定端口");else models.unavailable(selected,"原RSA/模型部署目录/下架绑定端口");return LegacyResult.success(null);}
    private static Long expected(JSONObject body,String header) {
        if(body.get("id")==null){if(header!=null || body.get("revision")!=null)throw new ServiceException("新建模型不能携带已有版本条件",400);return null;}
        return ModelController.condition(ModelService.positive(body.get("id"),"id"),body.get("revision"),header);
    }
    public static JSONObject form(JSONObject body) {var result=JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue));result.remove("uid");return result;}
    public static Long scope(String header,Object body) {Long selected=DefaultPersonalSpaceService.parse(header);Long value=body==null || "".equals(body)?null:ModelService.positive(body,"spaceId");if(value!=null && !java.util.Objects.equals(selected,value))throw new ServiceException("请求空间标识与当前空间不一致",400);return selected;}
}
