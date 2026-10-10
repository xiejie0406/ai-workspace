package com.ruoyi.paicli.controller;

import com.ruoyi.paicli.service.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/v1/project-rules")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcProjectRuleController extends PcControllerSupport {
    private final PcProjectRuleService rules;
    public PcProjectRuleController(PcStore store,PcProjectRuleService rules){super(store);this.rules=rules;}
    @GetMapping @PreAuthorize("@ss.hasPermi('paicli:knowledge:read')") public AjaxResult list(@RequestParam(required=false) Long directoryContextId){return AjaxResult.success(rules.list(actor(),directoryContextId));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paicli:context:raw')") public AjaxResult raw(@PathVariable long id){return AjaxResult.success(rules.get(actor(),id,true));}
    @PostMapping @PreAuthorize("@ss.hasPermi('paicli:knowledge:manage')") @Log(title="PaiCLI PAI规则登记",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult save(@RequestHeader("Idempotency-Key") String key,@RequestHeader("If-Match") String version,@RequestBody Map<String,Object> input){return AjaxResult.success(rules.save(actor(),key,version(version),input));}
}
