package com.ruoyi.paiflow.controller;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.paiflow.domain.NodeCatalog;
import com.ruoyi.paiflow.service.DslValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 无数据库副作用的中心预检入口；功能默认关闭，不能据此认定Worker已就绪。 */
@RestController
@RequestMapping("/paiflow")
@ConditionalOnProperty(prefix = "paiflow", name = "enabled", havingValue = "true")
public class WorkflowContractController {
    @GetMapping("/nodes/catalog")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:query')")
    public AjaxResult catalog() { return AjaxResult.success(NodeCatalog.entries()); }

    @PostMapping("/workflows/validate")
    @PreAuthorize("@ss.hasPermi('paiflow:workflow:edit')")
    public AjaxResult validate(@RequestBody JSONObject workflow, @RequestParam(defaultValue = "python") String engineProfile) {
        return AjaxResult.success(new DslValidationService().validate(workflow, engineProfile));
    }
}
