package com.ruoyi.paiflow.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.paiflow.service.SpaceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/paiflow/spaces")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class SpaceController {
    private final SpaceService spaces;
    public SpaceController(SpaceService spaces) { this.spaces=spaces; }
    public record Create(String name,String description,String avatarUrl) {}
    public record Update(String name,String description,String avatarUrl,Long revision) {}
    @GetMapping
    @PreAuthorize("@ss.hasPermi('paiflow:space:query')")
    public AjaxResult list(@RequestParam(required=false) String name) { return AjaxResult.success(spaces.listPersonal(name,false)); }
    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:space:query')")
    public AjaxResult detail(@PathVariable long id) { return AjaxResult.success(spaces.detail(id)); }
    @PostMapping
    @PreAuthorize("@ss.hasPermi('paiflow:space:create')")
    @Log(title="PaiFlow空间",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody Create body) { return AjaxResult.success(spaces.createPersonal(body.name(),body.description(),body.avatarUrl())); }
    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermi('paiflow:space:edit')")
    @Log(title="PaiFlow空间",businessType=BusinessType.UPDATE)
    public AjaxResult update(@PathVariable long id,@RequestBody Update body) {
        spaces.updatePersonal(id,body.name(),body.description(),body.avatarUrl(),body.revision());return AjaxResult.success();
    }
}
