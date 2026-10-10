package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.service.ApplicationService;
import com.ruoyi.paiflow.service.AgentService;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原App表单使用同一中心登记，不请求旧Tenant或将登记伪装成渠道发布。 */
@RestController
@RequestMapping("/paiflow/legacy/publish-api")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyPublishApiController {
    private final ApplicationService applications;
    private final AgentService agents;
    public LegacyPublishApiController(ApplicationService applications,AgentService agents){this.applications=applications;this.agents=agents;}
    @PostMapping("/create-user-app")
    @Log(title="PaiFlow原应用登记",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> create(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){
        applications.legacyCreate(space,body);return LegacyResult.success(true);
    }
    @GetMapping("/app-list")
    public LegacyResult<List<JSONObject>> list(@RequestHeader(value="space-id",required=false) String space){return LegacyResult.success(applications.legacyList(space));}
    @GetMapping("/get-bot-api-info")
    public LegacyResult<JSONObject> apiInfo(@RequestHeader(value="space-id",required=false) String space,@RequestParam long botId){
        agents.legacyDetail(botId,space);throw new ServiceException("应用API发布与调用凭据尚未接通，不能返回发布成功",503);
    }
    @PostMapping("/create-bot-api")
    public LegacyResult<JSONObject> publish(@RequestHeader(value="space-id",required=false) String space,@RequestBody JSONObject body){
        if(body==null || !Set.of("botId","appId").containsAll(body.keySet()) || !(body.get("appId") instanceof String id))
            throw new ServiceException("原应用API请求字段无效",400);
        var application=applications.detail(id);long botId=AgentService.number(body.get("botId"),"botId",true);
        agents.legacyDetail(botId,space);
        var agent=agents.detail(botId,application.getLong("spaceId"));
        if(!java.util.Objects.equals(application.getLong("spaceId"),agent.getLong("spaceId")))
            throw new ServiceException("应用与智能体不在同一资源域",409);
        throw new ServiceException("应用API发布与调用凭据尚未接通，不能登记为已发布",503);
    }
}
