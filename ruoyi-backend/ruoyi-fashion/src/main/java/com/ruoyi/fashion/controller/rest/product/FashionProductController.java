package com.ruoyi.fashion.controller.rest.product;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.util.List;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.fashion.application.product.FashionProductService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/products")
public class FashionProductController extends BaseController {
    private final FashionProductService service;

    public FashionProductController(FashionProductService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:list')")
    @GetMapping
    public AjaxResult list(
            @RequestParam(required = false) String sourceCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required=false) String attributeKey, @RequestParam(required=false) String attributeValue) {
        return success(service.search(sourceCode, categoryCode, status, keyword, page, pageSize, attributeKey, attributeValue));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:query')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable String id) {
        return success(service.get(id));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:edit')")
    @Log(title = "智能选品商品", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@RequestBody ProductCreateRequest request) {
        return success(service.create(request.toCommand(), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:edit')")
    @Log(title = "智能选品商品批量修改", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/batch")
    public AjaxResult update(@RequestBody ProductBulkUpdateRequest request) {
        List<com.ruoyi.fashion.application.product.ProductPatch> patches = request.products == null
                ? List.of() : request.products.stream().map(ProductPatchRequest::toCommand).toList();
        return success(service.update(patches, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:edit')")
    @Log(title = "智能选品商品上下架", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public AjaxResult status(@PathVariable String id, @RequestBody ProductStatusRequest request) {
        return success(service.changeStatus(id, request.status, request.rowVersion, getUserId()));
    }
}
