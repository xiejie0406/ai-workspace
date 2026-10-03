package com.ruoyi.paicli.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/v1/prompt-templates")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcPromptController extends PcControllerSupport {
    private final PcPromptService prompts;
    public PcPromptController(PcStore store,PcPromptService service){super(store);prompts=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paicli:connection:read')") public AjaxResult list(@RequestParam(required=false) Long workspaceId){return AjaxResult.success(prompts.list(actor(),workspaceId));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paicli:context:raw')") public AjaxResult raw(@PathVariable long id){return AjaxResult.success(prompts.get(actor(),id,true));}
    @PostMapping @PreAuthorize("@ss.hasPermi('paicli:connection:manage')") @Log(title="PaiCLI提示词覆盖",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult save(@RequestHeader(value="If-Match",defaultValue="0") String v,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(prompts.save(actor(),key,version(v),input));}
}
