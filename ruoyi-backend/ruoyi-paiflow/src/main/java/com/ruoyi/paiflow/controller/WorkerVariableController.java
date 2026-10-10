package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@Anonymous
@RequestMapping("/internal/paiflow/v1/runs/{runId}/variables")
@ConditionalOnProperty(prefix="paiflow",name={"enabled","runtime-state.enabled"},havingValue="true")
public class WorkerVariableController {
    private final WorkerIdentityService identities;private final RunVariableService variables;
    public WorkerVariableController(WorkerIdentityService identities,RunVariableService variables){this.identities=identities;this.variables=variables;}
    @PostMapping
    public AjaxResult operate(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);
        return AjaxResult.success(variables.operate(runId,worker,JSON.parseObject(JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue))));
    }
}
