package com.ruoyi.fashion.controller.rest.agent;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.fashion.application.proposal.FashionTaskQuery;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @FashionModuleEnabled @RequestMapping("/fashion/tasks")
public class FashionTaskController extends BaseController {
    private final FashionTaskQuery service;
    public FashionTaskController(FashionTaskQuery service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('fashion:ai:run:list') or @ss.hasPermi('fashion:quote:query')")
    public AjaxResult list(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return success(service.list(getUserId(),SecurityUtils.hasPermi("fashion:ai:run:list"),SecurityUtils.hasPermi("fashion:quote:query"),page,pageSize));}
}
