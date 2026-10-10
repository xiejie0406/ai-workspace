package com.ruoyi.paicli.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paicli.service.PcTerminalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/paicli/v1/terminals")
@ConditionalOnProperty(prefix="paicli",name="enabled",havingValue="true")
public class PcTerminalController {
    private final PcTerminalService terminals;
    public PcTerminalController(PcTerminalService t){terminals=t;}
    @PostMapping @PreAuthorize("@ss.hasPermi('paicli:terminal:use')")
    public AjaxResult create(@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.create(SecurityUtils.getLoginUser(),input));}
    @GetMapping("/{id}") @PreAuthorize("@ss.hasPermi('paicli:terminal:read')")
    public AjaxResult detail(@PathVariable long id){return AjaxResult.success(terminals.detail(id,SecurityUtils.getLoginUser()));}
    @PostMapping("/{id}/tickets") @PreAuthorize("@ss.hasPermi('paicli:terminal:read')")
    public AjaxResult ticket(@PathVariable long id,@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.ticket(id,SecurityUtils.getLoginUser(),input));}
    @PostMapping("/{id}/claim") @PreAuthorize("@ss.hasPermi('paicli:terminal:use')")
    public AjaxResult claim(@PathVariable long id,@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.claim(id,SecurityUtils.getLoginUser(),input));}
    @PostMapping("/{id}/stop") @PreAuthorize("@ss.hasPermi('paicli:terminal:use')")
    public AjaxResult stop(@PathVariable long id,@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.stop(id,SecurityUtils.getLoginUser(),input));}
    @PostMapping("/{id}/attachments") @PreAuthorize("@ss.hasPermi('paicli:terminal:use')")
    public AjaxResult attachments(@PathVariable long id,@RequestBody Map<String,Object> input){return AjaxResult.success(terminals.attachments(id,SecurityUtils.getLoginUser(),input));}
}
