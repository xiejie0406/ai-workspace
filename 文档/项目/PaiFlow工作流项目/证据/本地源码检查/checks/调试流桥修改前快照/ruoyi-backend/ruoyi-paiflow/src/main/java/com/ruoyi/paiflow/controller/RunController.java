package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.RunService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/runs")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class RunController {
    public record Create(long workflowVersionId,String engineProfile,Map<String,Object> input,String idempotencyKey,Integer timeoutSeconds) {}
    private final RunService runs;
    public RunController(RunService runs) { this.runs=runs; }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paiflow:run:create')")
    @Log(title="PaiFlow运行",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody Create body) {
        JSONObject input=body.input()==null?null:JSON.parseObject(JSON.toJSONString(body.input()));
        return AjaxResult.success(runs.create(body.workflowVersionId(),body.engineProfile(),input,body.idempotencyKey(),body.timeoutSeconds()==null?900:body.timeoutSeconds()));
    }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public AjaxResult detail(@PathVariable String id) { return AjaxResult.success(runs.detail(id)); }
    @GetMapping("/{id}/events")
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public AjaxResult events(@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) { return AjaxResult.success(runs.events(id,after,limit)); }
    @PostMapping("/{id}/cancel")
    @PreAuthorize("@ss.hasPermi('paiflow:run:cancel')")
    @Log(title="PaiFlow停止运行",businessType=BusinessType.UPDATE)
    public AjaxResult cancel(@PathVariable String id) { return AjaxResult.success(runs.cancel(id)); }
}
