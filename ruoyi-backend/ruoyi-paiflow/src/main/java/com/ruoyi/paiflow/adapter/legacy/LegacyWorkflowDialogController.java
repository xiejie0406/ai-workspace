package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.WorkflowDialogService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 保留原调试历史三请求；清除计数只指本人显示记录。 */
@RestController
@RequestMapping("/paiflow/legacy/workflow")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyWorkflowDialogController {
    private final WorkflowDialogService dialogs;
    public LegacyWorkflowDialogController(WorkflowDialogService dialogs){this.dialogs=dialogs;}
    @PostMapping("/dialog")
    @Log(title="PaiFlow调试显示记录",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<String> save(@RequestBody JSONObject body,@RequestHeader(value="space-id",required=false) String header) {
        return LegacyResult.success(dialogs.save(body,header));
    }
    @GetMapping("/dialog/list")
    public LegacyResult<List<WorkflowDialogService.View>> list(@RequestParam long workflowId,@RequestParam(required=false) Integer type,
            @RequestHeader(value="space-id",required=false) String header) {
        return LegacyResult.success(dialogs.list(workflowId,type,header));
    }
    @GetMapping("/dialog/clear")
    @Log(title="PaiFlow调试显示记录",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public LegacyResult<Integer> clear(@RequestParam long workflowId,@RequestParam(required=false) Integer type,
            @RequestHeader(value="space-id",required=false) String header) {
        return LegacyResult.success(dialogs.clear(workflowId,type,header));
    }
}
