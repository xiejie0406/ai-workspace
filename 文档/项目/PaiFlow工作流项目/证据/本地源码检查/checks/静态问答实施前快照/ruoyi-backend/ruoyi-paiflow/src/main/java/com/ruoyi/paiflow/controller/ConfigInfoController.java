package com.ruoyi.paiflow.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.service.ConfigInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 公共目录沿用平台认证；当前活动主体仍由Service逐次核对，无写接口。 */
@RestController
@RequestMapping("/paiflow/config-info")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
@PreAuthorize("isAuthenticated()")
public class ConfigInfoController {
    private final ConfigInfoService configs;
    public ConfigInfoController(ConfigInfoService configs){this.configs=configs;}
    @GetMapping("/get-list-by-category") public AjaxResult list(@RequestParam String category){return AjaxResult.success(configs.list(category));}
    @GetMapping("/get-by-category-and-code") public AjaxResult first(@RequestParam String category,@RequestParam String code){return AjaxResult.success(configs.first(category,code));}
    @GetMapping("/list-by-category-and-code") public AjaxResult list(@RequestParam String category,@RequestParam String code){return AjaxResult.success(configs.list(category,code));}
    @GetMapping("/tags") public AjaxResult tags(@RequestParam String flag){return AjaxResult.success(configs.tags(flag));}
    @GetMapping("/workflow/categories") public AjaxResult categories(){return AjaxResult.success(configs.workflowCategories());}
}
