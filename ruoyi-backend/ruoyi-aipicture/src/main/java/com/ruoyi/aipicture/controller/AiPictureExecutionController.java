package com.ruoyi.aipicture.controller;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.aipicture.config.AiPictureEnabled;
import com.ruoyi.aipicture.domain.Json;
import com.ruoyi.aipicture.service.RunService;

/** 只进入独立服务 HMAC 链；结果登记仍逐任务验证租约与尝试版本。 */
@RestController @RequestMapping("/aipicture/internal/v1/execution") @AiPictureEnabled
public class AiPictureExecutionController {
    private final RunService runs;
    public AiPictureExecutionController(RunService runs) {this.runs=runs;}
    @PostMapping("/claim") public Object claim(@RequestBody Map<String,Object> request) {return runs.claim(Json.string(request.get("worker_id")));}
    @PostMapping("/heartbeat") public Object heartbeat(@RequestBody Map<String,Object> request) {return runs.heartbeat(request);}
    @PostMapping("/receipt") public Object receipt(@RequestBody Map<String,Object> request) {return runs.receipt(request);}
    @PostMapping("/artifacts") public Object artifacts(@RequestBody Map<String,Object> request) throws IOException {return runs.uploadArtifact(request);}
    @GetMapping("/assets/{id}") public ResponseEntity<byte[]> asset(@PathVariable String id,@RequestParam String run_id,@RequestParam int attempt,@RequestParam String lease_token) throws IOException {return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(runs.inputAsset(Json.uuid(id),Json.of("run_id",run_id,"attempt",attempt,"lease_token",lease_token)));}
}
