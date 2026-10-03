package com.ruoyi.fashion.controller.rest.product;

import java.util.Map;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.fashion.application.stock.FashionWarehouseService;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @FashionModuleEnabled @RequestMapping("/fashion/warehouses")
public class FashionWarehouseController extends BaseController {
    private final FashionWarehouseService service;
    public FashionWarehouseController(FashionWarehouseService service){this.service=service;}
    @GetMapping @PreAuthorize("@ss.hasPermi('fashion:stock:list')")
    public AjaxResult list(){return success(Map.of("items",service.list()));}
    @PostMapping @PreAuthorize("@ss.hasPermi('fashion:stock:import')") @Log(title="新增仓库",businessType=BusinessType.INSERT)
    public AjaxResult create(@RequestBody FashionWarehouseService.Write request){service.create(request,Long.toString(getUserId()));return success();}
    @PutMapping("/{code}") @PreAuthorize("@ss.hasPermi('fashion:stock:import')") @Log(title="编辑仓库",businessType=BusinessType.UPDATE)
    public AjaxResult update(@PathVariable String code,@RequestBody FashionWarehouseService.Write request){service.update(code,request,Long.toString(getUserId()));return success();}
    public record Status(String status){}
    @PutMapping("/{code}/status") @PreAuthorize("@ss.hasPermi('fashion:stock:import')") @Log(title="仓库状态",businessType=BusinessType.UPDATE)
    public AjaxResult status(@PathVariable String code,@RequestBody Status request){service.status(code,request.status(),Long.toString(getUserId()));return success();}
    @PutMapping("/{code}/default") @PreAuthorize("@ss.hasPermi('fashion:stock:import')") @Log(title="默认仓库",businessType=BusinessType.UPDATE)
    public AjaxResult makeDefault(@PathVariable String code){service.makeDefault(code,Long.toString(getUserId()));return success();}
}
