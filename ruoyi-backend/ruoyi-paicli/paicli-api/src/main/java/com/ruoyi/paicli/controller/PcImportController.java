package com.ruoyi.paicli.controller;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/v1/imports")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcImportController extends PcControllerSupport {
    private final PcImportService service;
    public PcImportController(PcStore s,PcImportService i){super(s);service=i;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paicli:import:read')") public AjaxResult list(@RequestParam long workspaceId){return AjaxResult.success(service.list(actor(),workspaceId));}
    @PostMapping("/preflight") @PreAuthorize("@ss.hasPermi('paicli:import:manage')") @Log(title="PaiCLI迁移预检",businessType=BusinessType.OTHER,isSaveRequestData=false) public AjaxResult preflight(@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.preflight(actor(),key,input));}
    @PostMapping("/{id}") @PreAuthorize("@ss.hasPermi('paicli:import:manage')") @Log(title="PaiCLI迁移批次",businessType=BusinessType.UPDATE,isSaveRequestData=false) public AjaxResult action(@PathVariable long id,@RequestHeader("If-Match") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(service.action(actor(),id,version(v),key,PcJson.text(input,"action")));}
}
