package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.PromptService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原提示库URL/表单和JSON消费者共用中心提示业务。官方目录写入另需平台catalog权限。 */
@RestController
@RequestMapping("/paiflow/legacy/prompt/manage")
@ConditionalOnProperty(prefix="paiflow",name={"enabled","prompt-library.enabled"},havingValue="true")
public class LegacyPromptController {
    private final PromptService prompts;public LegacyPromptController(PromptService prompts){this.prompts=prompts;}
    @PostMapping("/list") public LegacyResult<PromptService.Page> list(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){return LegacyResult.success(prompts.list(space,body));}
    @GetMapping("/official-prompt") public LegacyResult<List<JSONObject>> official(){return LegacyResult.success(prompts.official());}
    @PostMapping(value="/create",consumes="application/json") public LegacyResult<JSONObject> createJson(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){return LegacyResult.success(prompts.create(space,body,false,false));}
    @PostMapping(value="/create",consumes={"multipart/form-data","application/x-www-form-urlencoded"}) public LegacyResult<JSONObject> createForm(@RequestHeader(value="space-id",required=false) String space,HttpServletRequest request){return LegacyResult.success(prompts.create(space,form(request),false,false));}
    @PostMapping("/create-group") public LegacyResult<JSONObject> group(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){return LegacyResult.success(prompts.create(space,body,true,false));}
    @PostMapping("/official-prompt") public LegacyResult<JSONObject> catalog(@RequestBody JSONObject body){return LegacyResult.success(prompts.create(null,body,false,true));}
    @PostMapping(value="/detail",consumes={"multipart/form-data","application/x-www-form-urlencoded"}) public LegacyResult<JSONObject> detail(@RequestHeader(value="space-id",required=false) String space,HttpServletRequest request){return LegacyResult.success(prompts.detail(space,id(form(request))));}
    @PostMapping({"/save","/rename"}) public LegacyResult<JSONObject> save(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){return LegacyResult.success(prompts.save(space,id(body),body));}
    @PostMapping("/delete") public LegacyResult<Void> delete(@RequestHeader(value="space-id",required=false) String space,HttpServletRequest request){var body=form(request);prompts.delete(space,id(body),body.getLong("revision"));return LegacyResult.success(null);}
    @PostMapping("/commit") public LegacyResult<JSONObject> commit(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){return LegacyResult.success(prompts.commit(space,id(body),body));}
    @PostMapping("/listVersion") public LegacyResult<List<Map<String,Object>>> versions(@RequestHeader(value="space-id",required=false) String space,HttpServletRequest request){return LegacyResult.success(prompts.versions(space,id(form(request))));}
    @PostMapping("/revert") public LegacyResult<JSONObject> revert(@RequestHeader(value="space-id",required=false) String space,HttpServletRequest request){var body=form(request);Long version=body.getLong("version");if(version==null||version<1)throw new ServiceException("提示版本无效",400);return LegacyResult.success(prompts.revert(space,id(body),version,body.getLong("revision")));}
    private static long id(JSONObject body){Long id=body.getLong(body.containsKey("id")?"id":"promptId");if(id==null||id<1)throw new ServiceException("提示词ID无效",400);return id;}
    private static JSONObject form(HttpServletRequest request){var body=new JSONObject();request.getParameterMap().forEach((key,values)->{if(values.length!=1)throw new ServiceException("提示词参数不能重复",400);body.put(key,values[0]);});return body;}
}
