package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.WorkflowService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/legacy/workflow/version")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowVersionController {
    private final LegacyWorkflowScopeFacade workflows;
    public LegacyWorkflowVersionController(LegacyWorkflowScopeFacade workflows) { this.workflows=workflows; }
    @GetMapping("/list")
    public LegacyResult<WorkflowService.VersionPage> list(@RequestParam String flowId,@RequestParam(defaultValue="1") int current,@RequestParam(defaultValue="10") int size,@RequestHeader(value="space-id",required=false) String space) {
        return LegacyResult.success(workflows.versions(flowId,space,current,size));
    }
    @PostMapping
    @Log(title="PaiFlow工作流版本",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> create(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) {
        return LegacyWorkflowController.result(workflows.createVersion(body,space,LegacyWorkflowController.revision(body.getLong("revision"),ifMatch)),response);
    }
    @PostMapping("/restore")
    @Log(title="PaiFlow工作流版本恢复",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<JSONObject> restore(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space,@RequestHeader(value="If-Match",required=false) String ifMatch,HttpServletResponse response) {
        return LegacyWorkflowController.result(workflows.restore(body,space,LegacyWorkflowController.revision(body.getLong("revision"),ifMatch)),response);
    }
    @PostMapping("/get-version-name")
    public LegacyResult<JSONObject> suggest(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String space) { return LegacyResult.success(workflows.suggest(body,space)); }
}
