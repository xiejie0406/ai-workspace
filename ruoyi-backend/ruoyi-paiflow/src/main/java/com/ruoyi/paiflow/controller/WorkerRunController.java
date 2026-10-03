package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.RunProtocolLimits;
import com.ruoyi.paiflow.service.WorkerIdentityService;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/** 使用共享Anonymous入口能力承载受限服务身份；每条路由先验证Worker，用户token不能代替。 */
@RestController
@Anonymous
@RequestMapping("/internal/paiflow/v1/runs")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerRunController {
    private final WorkerIdentityService identities;
    private final RunLeaseService runs;
    public WorkerRunController(WorkerIdentityService identities,RunLeaseService runs) { this.identities=identities;this.runs=runs; }
    private static JSONObject json(Map<String,Object> body) {
        RunJsonBoundary.check(body,RunProtocolLimits.EVENT_BYTES);
        // Map边界保留实际原帧的显式空值；任意节点输出不能在后续投影中猜补。
        return JSON.parseObject(JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue));
    }
    private static JSONObject lease(Map<String,Object> body) {
        var value=json(body);RunJsonBoundary.fields(value,Set.of("leaseId","attempt"),Set.of());return value;
    }
    @PostMapping("/claim")
    public AjaxResult claim(@RequestHeader(value="Authorization",required=false) String token) {
        var worker=identities.authenticate(token);var value=runs.claim(worker);
        return AjaxResult.success().put("data",value);
    }
    @PostMapping("/{runId}/lease/verify")
    public AjaxResult verify(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);var value=lease(body);
        return AjaxResult.success(runs.verify(runId,worker,value.getString("leaseId"),RunLeaseService.attempt(value)));
    }
    @PostMapping("/{runId}/heartbeat")
    public AjaxResult heartbeat(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);var value=lease(body);
        return AjaxResult.success(runs.heartbeat(runId,worker,value.getString("leaseId"),RunLeaseService.attempt(value)));
    }
    @PostMapping("/{runId}/execution/begin")
    public AjaxResult beginExecution(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);var value=lease(body);
        return AjaxResult.success(runs.beginExecution(runId,worker,value.getString("leaseId"),RunLeaseService.attempt(value)));
    }
    @PostMapping("/{runId}/events")
    public AjaxResult event(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);return AjaxResult.success(runs.append(runId,worker,json(body)));
    }
    @PostMapping("/{runId}/result")
    public AjaxResult result(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String runId,@RequestBody Map<String,Object> body) {
        var worker=identities.authenticate(token);return AjaxResult.success(runs.result(runId,worker,json(body)));
    }
}
