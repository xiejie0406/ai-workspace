package com.ruoyi.paicli.controller;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/v1/personal")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcPersonalController extends PcControllerSupport {
    private final PcPersonalService personal;
    private final PcSessionService sessions;private final PcConnectionService connections;
    public PcPersonalController(PcStore store,PcPersonalService personal,PcSessionService sessions,PcConnectionService connections){super(store);this.personal=personal;this.sessions=sessions;this.connections=connections;}
    @GetMapping("/context") @PreAuthorize("@ss.hasPermi('paicli:session:read')")
    public AjaxResult context(){return AjaxResult.success(personal.context(actor()));}
    @PostMapping("/bootstrap") @PreAuthorize("@ss.hasPermi('paicli:session:write')")
    public AjaxResult bootstrap(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(personal.bootstrap(actor(),key,input));}
    @PutMapping("/settings") @PreAuthorize("@ss.hasPermi('paicli:connection:manage')")
    public AjaxResult settings(@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String version,@RequestBody Map<String,Object> input){return AjaxResult.success(personal.saveSettings(actor(),key,version(version),input,connections));}
    @GetMapping("/embedding/releases") @PreAuthorize("@ss.hasPermi('paicli:session:read')")
    public AjaxResult embeddingReleases(){return AjaxResult.success(personal.embeddingReleases(actor()));}
    @PutMapping("/embedding/selection") @PreAuthorize("@ss.hasPermi('paicli:session:write')")
    public AjaxResult embeddingSelection(@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String version,@RequestBody Map<String,Object> input){return AjaxResult.success(personal.selectEmbedding(actor(),key,version(version),input));}
    @PostMapping("/embedding/verify") @PreAuthorize("@ss.hasPermi('paicli:connection:manage')")
    public AjaxResult embeddingVerify(@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String version,@RequestBody Map<String,Object> input){return AjaxResult.success(connections.verifyEmbedding(actor(),key,version(version),input));}
    @GetMapping("/directories") @PreAuthorize("@ss.hasPermi('paicli:session:read')")
    public AjaxResult directories(){return AjaxResult.success(personal.directories(actor()));}
    @PostMapping("/directories") @PreAuthorize("@ss.hasPermi('paicli:session:write')")
    public AjaxResult requestDirectory(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(personal.requestDirectory(actor(),key,input,sessions));}
}
