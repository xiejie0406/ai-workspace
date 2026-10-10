package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

/** Runner身份由专属SecurityFilterChain提供，每次业务写入再次核对租约与撤销状态。 */
@RestController @RequestMapping("/paicli/runner/v1")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcRunnerController {
    private final PcDeviceService devices;private final PcRunnerService runner;private final PcToolService tools;private final PcRunnerContextService context;private final PcPersonalService personal;
    public PcRunnerController(PcDeviceService d,PcRunnerService r,PcToolService t,PcRunnerContextService c,PcPersonalService personal){devices=d;runner=r;tools=t;context=c;this.personal=personal;}
    @PostMapping("/sessions") public ResponseEntity<AjaxResult> exchange(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(devices.exchange(p,key,input)));}
    @PostMapping("/personal-directory-proof") public AjaxResult proof(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(personal.prove(p,key,input));}
    @PostMapping("/claims") public AjaxResult claim(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(runner.claim(p,key,input));}
    @PostMapping("/heartbeats") public AjaxResult heartbeat(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(runner.heartbeat(p,key,input));}
    @PostMapping("/proposals") public AjaxResult proposal(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(tools.propose(p,key,input));}
    @PostMapping("/permits") public AjaxResult permit(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(tools.permit(p,key,input,false));}
    @PostMapping("/permits/consume") public AjaxResult consume(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(tools.permit(p,key,input,true));}
    @PostMapping("/receipts") public AjaxResult receipt(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(tools.receipt(p,key,input));}
    @PostMapping("/events") public AjaxResult event(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(runner.event(p,key,input));}
    @PostMapping("/history/read") public ResponseEntity<AjaxResult> history(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.read(p,input,false)));}
    @PostMapping("/ledger/read") public ResponseEntity<AjaxResult> ledger(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.read(p,input,true)));}
    @PostMapping("/provider-context") public ResponseEntity<AjaxResult> providerContext(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.providerContext(p,input)));}
    @PostMapping("/mcp-context") public ResponseEntity<AjaxResult> mcpContext(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.mcpContext(p,input)));}
    @PostMapping("/revert-context") public ResponseEntity<AjaxResult> revertContext(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(tools.revertContext(p,input)));}
    @PostMapping("/context") public ResponseEntity<AjaxResult> context(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.context(p,input)));}
    @GetMapping("/context") public ResponseEntity<AjaxResult> contextGet(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestParam Map<String,String> input){return context(p,new LinkedHashMap<>(input));}
    @PostMapping("/artifacts/{id}/image") public ResponseEntity<AjaxResult> image(@AuthenticationPrincipal PcRunnerPrincipal p,@PathVariable long id,@RequestBody Map<String,Object> input){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AjaxResult.success(context.image(p,id,input)));}
    @PostMapping("/memory") public AjaxResult memory(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(context.memory(p,key,input));}
    @PostMapping("/plans/review") public AjaxResult review(@AuthenticationPrincipal PcRunnerPrincipal p,@RequestBody Map<String,Object> input){return AjaxResult.success(context.review(p,input));}
}
