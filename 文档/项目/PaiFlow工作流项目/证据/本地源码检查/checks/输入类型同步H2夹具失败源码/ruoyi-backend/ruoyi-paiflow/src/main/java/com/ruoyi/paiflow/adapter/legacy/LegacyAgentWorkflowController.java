package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.AgentService;
import com.ruoyi.paiflow.adapter.legacy.dto.LegacyWorkflowInputTypeDto;
import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 画布/Agent共用一次事务；模板和发布缺真实依赖时明确失败。 */
@RestController
@RequestMapping("/paiflow/legacy")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyAgentWorkflowController {
    private final AgentService agents;
    public LegacyAgentWorkflowController(AgentService agents) { this.agents=agents; }
    @PostMapping("/workflow/base-save")
    @Log(title="PaiFlow工作流智能体",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> save(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="X-Workflow-Revision",required=false) String workflowMatch,HttpServletResponse response) { var request=LegacyBotController.form(body);return LegacyBotController.result(body.get("botId")==null?agents.legacyCreate(space,3,request):agents.legacyUpdate(LegacyBotController.id(body),space,LegacyBotController.revision(body,match),LegacyBotController.workflowRevision(body,workflowMatch),request),response); }
    @PostMapping("/workflow/copy-bot")
    @Log(title="PaiFlow工作流智能体复制",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Void> copy(@RequestParam long botId,@RequestHeader(value="space-id",required=false) String space) { agents.legacyCopy(botId,space);return LegacyResult.success(null); }
    @GetMapping("/workflow/bot/templateGroup")
    public LegacyResult<Void> templateGroup(@RequestHeader(value="space-id",required=false) String space) { return missingTemplate(space); }
    @PostMapping("/workflow/bot/templateList")
    public LegacyResult<Void> template(@RequestHeader(value="space-id",required=false) String space) { return missingTemplate(space); }
    @PostMapping("/workflow/bot/createFromTemplate")
    public LegacyResult<Void> createTemplate(@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailable(space,"paiflow:agent:create","原工作流模板实体/分组/绑定资料");return LegacyResult.success(null); }
    private LegacyResult<Void> missingTemplate(String space) { agents.legacyUnavailable(space,"paiflow:agent:query","原工作流模板实体/分组/绑定资料");return LegacyResult.success(null); }
    @PostMapping("/workflow/bot/get-inputs-type")
    @Log(title="PaiFlow智能体输入类型同步",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<List<LegacyWorkflowInputTypeDto>> inputTypes(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,HttpServletResponse response) {
        var result=agents.legacyInputTypes(LegacyBotController.id(body),space);
        response.setHeader("ETag","\""+result.agentRevision()+"\"");response.setHeader("X-PaiFlow-Agent-Id",Long.toString(result.agentId()));
        return LegacyResult.success(result.fields().toJavaList(LegacyWorkflowInputTypeDto.class));
    }
    @PostMapping("/publish/bots/{botId}")
    @Log(title="PaiFlow智能体发布",businessType=BusinessType.OTHER,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Void> publish(@PathVariable long botId,@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailableForAgent(botId,space,"真实MARKET/API/MCP/微信/飞书发布与回执");return LegacyResult.success(null); }
    @PostMapping("/workflow/publish")
    public LegacyResult<Void> publishWorkflow(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailableForAgent(LegacyBotController.id(body),space,"Tenant App/API发布资源端口");return LegacyResult.success(null); }
    @PostMapping("/workflow/updateSynchronize")
    public LegacyResult<Void> synchronize(@RequestHeader(value="space-id",required=false) String space) { agents.legacyUnavailable(space,"paiflow:agent:edit","原签名远端同步协议；本地绑定已走同一Service");return LegacyResult.success(null); }
}
