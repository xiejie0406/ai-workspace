package com.ruoyi.fashion.controller.rest.importing;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.infrastructure.persistence.importing.FashionImportHistoryQuery;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/imports")
public class FashionImportHistoryController extends BaseController {
    private final FashionImportHistoryQuery query;
    public FashionImportHistoryController(FashionImportHistoryQuery query){this.query=query;}
    @GetMapping("/products/history")
    @PreAuthorize("@ss.hasPermi('fashion:product:import')")
    public AjaxResult products(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return success(query.list("product",page,pageSize));}
    @GetMapping("/prices/history")
    @PreAuthorize("@ss.hasPermi('fashion:price:import')")
    public AjaxResult prices(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return success(query.list("price",page,pageSize));}
    @GetMapping("/stocks/history")
    @PreAuthorize("@ss.hasPermi('fashion:stock:import')")
    public AjaxResult stocks(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return success(query.list("stock",page,pageSize));}
}
