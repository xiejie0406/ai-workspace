package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.CheckpointService;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import com.ruoyi.paiflow.service.RunProtocolLimits;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@Anonymous
@RequestMapping("/internal/paiflow/v1/runs/{runId}/checkpoints")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerCheckpointController {
    private final WorkerIdentityService identities;private final CheckpointService checkpoints;
    public WorkerCheckpointController(WorkerIdentityService identities,CheckpointService checkpoints){this.identities=identities;this.checkpoints=checkpoints;}
    private static JSONObject json(Map<String,Object> body){RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);return JSON.parseObject(JSON.toJSONString(body));}
    @PostMapping
    public AjaxResult create(@RequestHeader(value="Authorization",required=false)String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);return AjaxResult.success(checkpoints.create(runId,worker,json(body)));
    }
    @PostMapping("/{checkpointId}/resume/poll")
    public AjaxResult poll(@RequestHeader(value="Authorization",required=false)String token,@PathVariable String runId,@PathVariable String checkpointId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);return AjaxResult.success(checkpoints.poll(runId,checkpointId,worker,json(body)));
    }
    @PostMapping("/{checkpointId}/consume")
    public AjaxResult consume(@RequestHeader(value="Authorization",required=false)String token,@PathVariable String runId,@PathVariable String checkpointId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);return AjaxResult.success(checkpoints.consume(runId,checkpointId,worker,json(body)));
    }
}
