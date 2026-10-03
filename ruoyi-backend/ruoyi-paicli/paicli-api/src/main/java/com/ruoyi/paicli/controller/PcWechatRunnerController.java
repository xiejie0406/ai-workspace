package com.ruoyi.paicli.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.domain.PcRunnerPrincipal;
import com.ruoyi.paicli.service.PcWechatService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/runner/v1/wechat")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcWechatRunnerController {
    private final PcWechatService service;
    public PcWechatRunnerController(PcWechatService s){service=s;}
    @PostMapping("/context") public ResponseEntity<AjaxResult> context(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(service.context(p,key,input)));}
    @PostMapping("/account") public AjaxResult account(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.account(p,key,input));}
    @PostMapping("/turn") public AjaxResult turn(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.turn(p,key,input));}
    @PostMapping("/events") public AjaxResult events(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return AjaxResult.success(service.events(p,input));}
    @PostMapping("/commands") public AjaxResult command(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.commands(p,key,input));}
    @PostMapping("/cursor") public AjaxResult cursor(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.cursor(p,key,input));}
}
