package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.TextConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/text-configs")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class TextConfigController {
    private final TextConfigService configs;
    public TextConfigController(TextConfigService configs) { this.configs=configs; }
    @GetMapping @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult list() { var rows=configs.list();return AjaxResult.success().put("rows",rows).put("total",rows.size()); }
    @PostMapping @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult create(@RequestBody JSONObject body) { return AjaxResult.success(configs.create(body)); }
    @PutMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult update(@PathVariable long id,@RequestBody JSONObject body) { return AjaxResult.success(configs.update(id,body)); }
    @DeleteMapping("/{id}") @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    @Log(title="PaiFlow文本分隔符",businessType=BusinessType.DELETE,isSaveRequestData=false,isSaveResponseData=false)
    public AjaxResult delete(@PathVariable long id,@RequestParam(required=false) Long revision) { configs.delete(id,revision);return AjaxResult.success(); }
}
