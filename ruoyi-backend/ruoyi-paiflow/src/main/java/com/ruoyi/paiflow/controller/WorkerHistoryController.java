package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.*;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@Anonymous
@RequestMapping("/internal/paiflow/v1/runs/{runId}/history")
@ConditionalOnProperty(prefix="paiflow",name={"enabled","runtime-state.enabled"},havingValue="true")
public class WorkerHistoryController {
    private final WorkerIdentityService identities;private final RunHistoryService histories;
    public WorkerHistoryController(WorkerIdentityService identities,RunHistoryService histories){this.identities=identities;this.histories=histories;}
    private static JSONObject json(Map<String,Object> body){RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);return JSON.parseObject(JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue));}
    @PostMapping
    public AjaxResult append(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){
        var worker=identities.authenticate(token);return AjaxResult.success(histories.append(runId,worker,json(body)));
    }
    @PostMapping("/read")
    public AjaxResult read(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){
        var worker=identities.authenticate(token);return AjaxResult.success(histories.read(runId,worker,json(body)));
    }
    @PostMapping("/java/open")
    public AjaxResult openJava(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){
        var worker=identities.authenticate(token);return AjaxResult.success(histories.openJava(runId,worker,json(body)));
    }
    @PostMapping("/python/read")
    public AjaxResult readPython(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){
        var worker=identities.authenticate(token);return AjaxResult.success(histories.readPython(runId,worker,json(body)));
    }
}
