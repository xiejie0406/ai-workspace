package com.ruoyi.paicli.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.domain.PcRunnerPrincipal;
import com.ruoyi.paicli.service.PcRunnerOutputService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/runner/v1/tool-outputs")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcRunnerOutputController {
    private final PcRunnerOutputService service;
    public PcRunnerOutputController(PcRunnerOutputService s){service=s;}
    @PostMapping public AjaxResult upload(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.upload(p,key,input));}
    @PostMapping("/images") public AjaxResult image(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.image(p,key,input));}
    @PostMapping("/chunks/begin") public AjaxResult begin(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.begin(p,key,input));}
    @PostMapping("/{id}/chunks") public AjaxResult chunk(@AuthenticationPrincipal PcRunnerPrincipal p,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.chunk(p,key,id,input));}
    @PostMapping("/{id}/complete") public AjaxResult complete(@AuthenticationPrincipal PcRunnerPrincipal p,@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.complete(p,key,id,input));}
    @PostMapping("/{id}/read") public ResponseEntity<AjaxResult> read(@AuthenticationPrincipal PcRunnerPrincipal p,@PathVariable long id,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(service.read(p,id,input)));}
}
