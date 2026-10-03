package com.ruoyi.paismart.api;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paismart.application.billing.RateLimitService;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/paismart/rate-limits")
@PreAuthorize("@ss.hasPermi('paismart:usage:adjust')")
@PaismartEnabled
public class RateLimitController {
    private final RateLimitService service;
    public RateLimitController(RateLimitService service){this.service=service;}
    @GetMapping public AjaxResult get(){return AjaxResult.success(service.configuration());}
    @PutMapping @Log(title="知识库限流策略",businessType=BusinessType.UPDATE)
    public AjaxResult put(@RequestBody Map<String,Object>b,@RequestHeader("If-Match")long version){return AjaxResult.success(service.save(b,version));}
}
