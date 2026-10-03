package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.ModelOperationJson;
import com.ruoyi.paiflow.service.ModelOperationService;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 原Python节点通过服务身份访问公开绑定/操作事实；私有SDK许可不在HTTP路由中暴露。 */
@RestController @Anonymous @RequestMapping("/internal/paiflow/v1/runs")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerModelController {
    private final WorkerIdentityService identities;private final ModelOperationService models;
    public WorkerModelController(WorkerIdentityService identities,ModelOperationService models){this.identities=identities;this.models=models;}
    private static JSONObject json(Map<String,Object> value){return JSON.parseObject(ModelOperationJson.canonical(value,ModelOperationJson.REQUEST_BYTES));}
    @PostMapping("/{runId}/resources/{resourceId}")
    public AjaxResult resource(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String resourceId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(models.resource(runId,worker,resourceId,json(body)));}
    @PostMapping("/{runId}/operations")
    public AjaxResult start(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(models.start(runId,worker,json(body)));}
    @PostMapping("/{runId}/operations/{operationId}/frames")
    public AjaxResult frames(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String operationId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(models.frames(runId,worker,operationId,json(body)));}
    @PostMapping("/{runId}/operations/{operationId}/cancel")
    public AjaxResult cancel(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@PathVariable String operationId,@RequestBody Map<String,Object> body){var worker=identities.authenticate(token);return AjaxResult.success(models.cancel(runId,worker,operationId,json(body)));}
}
