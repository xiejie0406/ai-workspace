package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paicli.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/paicli/v1/sessions")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcCommandController extends PcControllerSupport {
    private final PcCommandService commands;
    public PcCommandController(PcStore s,PcCommandService c){super(s);commands=c;}
    @PostMapping("/{id}/commands") @PreAuthorize("@ss.hasPermi('paicli:session:write')")
    public AjaxResult command(@PathVariable long id,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> input){return AjaxResult.success(commands.command(actor(),id,key,PcJson.text(input,"name"),PcJson.text(input,"arguments")));}
}
