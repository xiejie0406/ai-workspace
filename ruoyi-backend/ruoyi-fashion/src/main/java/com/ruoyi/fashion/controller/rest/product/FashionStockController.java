package com.ruoyi.fashion.controller.rest.product;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import com.ruoyi.fashion.infrastructure.persistence.stock.FashionStockQuery;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/stocks")
public class FashionStockController extends BaseController {
    private final FashionStockQuery query;
    public FashionStockController(FashionStockQuery query){this.query=query;}
    @GetMapping
    @PreAuthorize("@ss.hasPermi('fashion:stock:list')")
    public AjaxResult list(@RequestParam String warehouseCode,@RequestParam(required=false) String sourceCode,@RequestParam(required=false) String keyword,
        @RequestParam(defaultValue="all") String mode,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) {
        return success(query.list(warehouseCode,sourceCode,keyword,mode,page,pageSize));
    }
}
