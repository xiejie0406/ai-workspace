package com.ruoyi.paiflow.adapter.legacy;

import com.ruoyi.paiflow.service.ConfigInfoService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 原React config-info路径、参数、数组/对象及九字段原形，只有公开只读配置。 */
@RestController
@RequestMapping("/paiflow/legacy/config-info")
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
@PreAuthorize("isAuthenticated()")
public class LegacyConfigInfoController {
    private final ConfigInfoService configs;
    public LegacyConfigInfoController(ConfigInfoService configs){this.configs=configs;}
    @GetMapping("/get-list-by-category") public LegacyResult<List<ConfigInfoService.View>> list(@RequestParam String category){return LegacyResult.success(configs.list(category));}
    @GetMapping("/get-by-category-and-code") public LegacyResult<ConfigInfoService.View> first(@RequestParam String category,@RequestParam String code){return LegacyResult.success(configs.first(category,code));}
    @GetMapping("/list-by-category-and-code") public LegacyResult<List<ConfigInfoService.View>> list(@RequestParam String category,@RequestParam String code){return LegacyResult.success(configs.list(category,code));}
    @GetMapping("/tags") public LegacyResult<List<ConfigInfoService.View>> tags(@RequestParam String flag){return LegacyResult.success(configs.tags(flag));}
    @GetMapping("/workflow/categories") public LegacyResult<List<String>> categories(){return LegacyResult.success(configs.workflowCategories());}
}
