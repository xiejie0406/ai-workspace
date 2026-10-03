package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.AgentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/my-bot")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyMyBotController {
    private final AgentService agents;
    public LegacyMyBotController(AgentService agents) { this.agents=agents; }
    @PostMapping("/list")
    public LegacyResult<AgentService.Page> list(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space) { return LegacyResult.success(agents.legacyList(space,body)); }
    @PostMapping("/bot-detail")
    public LegacyResult<JSONObject> detail(@RequestParam long botId,@RequestHeader(value="space-id",required=false) String space,HttpServletResponse response) { return LegacyBotController.result(agents.legacyDetail(botId,space),response); }
    @PostMapping("/delete")
    @Log(title="PaiFlow智能体",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Boolean> delete(@RequestParam long botId,@RequestParam(required=false) Long revision,@RequestParam(required=false) Long workflowRevision,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String match,@RequestHeader(value="X-Workflow-Revision",required=false) String workflowMatch) { agents.legacyDelete(botId,space,LegacyWorkflowController.revision(AgentService.optionalRevision(revision,"revision"),match),LegacyWorkflowController.revision(AgentService.optionalRevision(workflowRevision,"workflowRevision"),workflowMatch));return LegacyResult.success(true); }
}
