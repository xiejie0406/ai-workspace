package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
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
    private com.ruoyi.paiflow.service.RunQueryService queries;
    @org.springframework.beans.factory.annotation.Autowired
    public void useQueries(com.ruoyi.paiflow.service.RunQueryService queries){this.queries=queries;}
    public RunController(RunService runs) { this.runs=runs; }
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public AjaxResult page(@RequestParam long spaceId,@RequestParam(required=false) Long workflowId,@RequestParam(required=false) String state,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return AjaxResult.success(queries.page(spaceId,workflowId,state,page,size));}
    @GetMapping("/statistics")
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public AjaxResult statistics(@RequestParam long spaceId,@RequestParam(required=false) Long workflowId){return AjaxResult.success(queries.statistics(spaceId,workflowId));}
    @GetMapping(value="/{id}/traces/export",produces="application/x-ndjson")
    @PreAuthorize("@ss.hasPermi('paiflow:run:query')")
    public org.springframework.http.ResponseEntity<byte[]> export(@PathVariable String id){return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=paiflow-trace.ndjson").header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff").body(queries.export(id));}
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paiflow:run:create')")
    @Log(title="PaiFlow运行",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody Create body) {
        JSONObject input=body.input()==null?null:JSON.parseObject(JSON.toJSONString(body.input(),JSONWriter.Feature.WriteMapNullValue));
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
