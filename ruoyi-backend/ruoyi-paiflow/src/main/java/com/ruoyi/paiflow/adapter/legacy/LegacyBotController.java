package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.AgentService;
import com.ruoyi.paiflow.service.AgentTemplateCatalog;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原BotCreateForm适配；其不使用的返回管理字段不能成为正式所有权/发布事实。 */
@RestController
@RequestMapping("/paiflow/legacy/bot")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyBotController {
    private final AgentService agents;private final AgentTemplateCatalog catalog;
    public LegacyBotController(AgentService agents,AgentTemplateCatalog catalog) { this.agents=agents;this.catalog=catalog; }
    @PostMapping("/create")
    @Log(title="PaiFlow智能体",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> create(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,HttpServletResponse response) { return result(agents.legacyCreate(space,1,form(body)),response); }
    @PostMapping("/update")
    @Log(title="PaiFlow智能体",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> update(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="X-Workflow-Revision",required=false) String workflowMatch,HttpServletResponse response) { return result(agents.legacyUpdate(id(body),space,revision(body,match),workflowRevision(body,workflowMatch),form(body)),response); }
    @PostMapping("/type-list")
    public LegacyResult<List<JSONObject>> types(@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="Accept-Language",defaultValue="zh") String language) { agents.legacyCatalogAccess(space);return LegacyResult.success(catalog.types(language)); }
    @GetMapping("/template")
    public LegacyResult<List<JSONObject>> templates(@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="Accept-Language",defaultValue="zh") String language,@RequestParam(required=false) Integer botId) { agents.legacyCatalogAccess(space);return LegacyResult.success(catalog.templates(language,botId)); }
    @RequestMapping(value={"/ai-avatar-gen","/ai-sentence-gen","/generate-input-example","/ai-prologue-gen"},method=RequestMethod.POST)
    public LegacyResult<Void> generate(@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailable(space,"paiflow:agent:create","模型/图像生成资源端口");return LegacyResult.success(null); }
    @GetMapping("/bot-model")
    public LegacyResult<Void> models(@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailable(space,"paiflow:agent:query","智能体模型资源目录");return LegacyResult.success(null); }
    public static long id(JSONObject body) { return AgentService.number(body.get("botId"),"botId",true); }
    public static Long revision(JSONObject body,String header) { return LegacyWorkflowController.revision(AgentService.optionalRevision(body.get("revision"),"revision"),header); }
    public static Long workflowRevision(JSONObject body,String header) { return LegacyWorkflowController.revision(AgentService.optionalRevision(body.get("workflowRevision"),"workflowRevision"),header); }
    public static JSONObject form(JSONObject body) {
        var result=JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        for(String key:List.of("id","uid","ownerUserId","botName","publicationStatus","publishStatus","publishResult","publishChannels","releaseType","botStatus","botwebStatus","isPublic","canPublish","deleted","createBy","updateBy","createTime","updateTime","editable","inputExampleList","supportUploadList","hotNum","marketBotId","isFavorite","af","resourceBindingStatus","applicationBindingStatus"))result.remove(key);
        return result;
    }
    public static LegacyResult<JSONObject> result(JSONObject value,HttpServletResponse response) { headers(value,response);return LegacyResult.success(value); }
    public static void headers(JSONObject value,HttpServletResponse response) { response.setHeader("ETag","\""+value.getLongValue("revision")+"\"");if(value.get("workflowRevision")!=null)response.setHeader("X-Workflow-Revision","\""+value.getLongValue("workflowRevision")+"\""); }
}
