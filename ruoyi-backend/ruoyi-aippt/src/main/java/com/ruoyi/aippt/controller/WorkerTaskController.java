package com.ruoyi.aippt.controller;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.application.*;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.common.core.domain.AjaxResult;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController @RequestMapping("/internal/aippt/tasks") @AipptEnabled @PreAuthorize("hasAuthority('AIPPT_WORKER')") public class WorkerTaskController  {
    private final TaskLeaseService tasks;
    private final AssetService assets;
    private AipptTextCallService textCalls;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTextCalls(AipptTextCallService value) { textCalls=value; }
    public WorkerTaskController(TaskLeaseService t,AssetService a)  {
        tasks=t;
        assets=a;
    }
    private JSONObject json(Map<String,Object> body)  {
        return JSON.parseObject(JSON.toJSONString(body,JSONWriter.Feature.WriteMapNullValue));
    }
    @PostMapping("/claim") public AjaxResult claim(@RequestBody Map<String,Object> input)  {
        return AjaxResult.success(tasks.claim(json(input)));
    }
    @PostMapping("/{id}/heartbeat") public AjaxResult heartbeat(@PathVariable String id,@RequestBody Map<String,Object> input)  {
        return AjaxResult.success(tasks.heartbeat(id,json(input)));
    }
    @PostMapping("/{id}/progress") public AjaxResult progress(@PathVariable String id,@RequestBody Map<String,Object> input)  {
        return AjaxResult.success(tasks.progress(id,json(input)));
    }
    @PostMapping("/{id}/complete") public AjaxResult complete(@PathVariable String id,@RequestBody Map<String,Object> input)  {
        return AjaxResult.success(tasks.complete(id,json(input)));
    }
    @PostMapping("/{id}/fail") public AjaxResult fail(@PathVariable String id,@RequestBody Map<String,Object> input)  {
        return AjaxResult.success(tasks.fail(id,json(input)));
    }
    @PostMapping("/{id}/text") public AjaxResult text(@PathVariable String id,@RequestBody Map<String,Object> input) {
        if(textCalls==null)throw AipptTextRouteService.unavailable();
        return AjaxResult.success(textCalls.text(id,json(input)));
    }
    @PostMapping(value="/{id}/assets",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public AjaxResult upload(@PathVariable String id,@RequestParam MultipartFile file,@RequestParam String role,@RequestParam String leaseToken,@RequestParam long fencingToken,@RequestParam int attempt)  {
        return AjaxResult.success(assets.workerUpload(id,JSONObject.of("leaseToken",leaseToken,"fencingToken",fencingToken,"attempt",attempt),file,role));
    }
    @GetMapping("/{id}/assets/{assetId}") public ResponseEntity<byte[]> read(@PathVariable String id,@PathVariable String assetId,@RequestParam String leaseToken,@RequestParam long fencingToken,@RequestParam int attempt)  {
        byte[] bytes=assets.workerRead(id,assetId,JSONObject.of("leaseToken",leaseToken,"fencingToken",fencingToken,"attempt",attempt));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bytes);
    }
}
