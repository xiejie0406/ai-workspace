package com.ruoyi.fashion.controller.rest.product;

import java.util.LinkedHashMap;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.configuration.persistence.FashionSchemaReadiness;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@FashionModuleEnabled
public class FashionReadinessController {
    private final FashionSchemaReadiness readiness;
    public FashionReadinessController(FashionSchemaReadiness readiness) { this.readiness = readiness; }
    @GetMapping("/fashion/readiness")
    @PreAuthorize("@ss.hasPermi('fashion:product:list')")
    public AjaxResult readiness() {
        var result = new LinkedHashMap<String, Object>();
        result.put("ready", readiness.isReady()); result.put("expectedVersion", "4");
        result.put("reason", readiness.reason());
        return AjaxResult.success(result);
    }
}
