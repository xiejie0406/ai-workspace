package com.ruoyi.fashion.controller.rest.importing;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.io.IOException;
import java.time.Instant;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.importing.FashionCatalogValueImportService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/imports")
public class FashionCatalogValueImportController extends BaseController {
    private final FashionCatalogValueImportService service;

    public FashionCatalogValueImportController(FashionCatalogValueImportService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('fashion:price:import')")
    @GetMapping("/prices/template")
    public ResponseEntity<byte[]> priceTemplate(@RequestParam(defaultValue = "xlsx") String format) throws IOException {
        if ("xlsx".equals(format)) return FashionTemplateWorkbook.response("fashion-price-v1.xlsx", com.ruoyi.fashion.application.importing.CatalogImportType.PRICE.headers());
        if (!"csv".equals(format)) throw new IllegalArgumentException("模板格式必须是 xlsx 或 csv");
        return template("price-v1.csv", "fashion-price-v1.csv");
    }

    @PreAuthorize("@ss.hasPermi('fashion:stock:import')")
    @GetMapping("/stocks/template")
    public ResponseEntity<byte[]> stockTemplate(@RequestParam(defaultValue = "xlsx") String format) throws IOException {
        if ("xlsx".equals(format)) return FashionTemplateWorkbook.response("fashion-stock-v1.xlsx", com.ruoyi.fashion.application.importing.CatalogImportType.STOCK.headers());
        if (!"csv".equals(format)) throw new IllegalArgumentException("模板格式必须是 xlsx 或 csv");
        return template("stock-v1.csv", "fashion-stock-v1.csv");
    }

    @PreAuthorize("@ss.hasPermi('fashion:price:import')")
    @Log(title = "智能选品价格全量预览", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    @PostMapping(value = "/prices/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult previewPrice(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sourceCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam String asOf) throws IOException {
        return success(service.preview("price", file.getOriginalFilename(), file.getBytes(), sourceCode,
                categoryCode, null, parseInstant(asOf), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:stock:import')")
    @Log(title = "智能选品库存全量预览", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    @PostMapping(value = "/stocks/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult previewStock(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sourceCode,
            @RequestParam(required = false) String categoryCode,
            @RequestParam String warehouseCode,
            @RequestParam String asOf) throws IOException {
        return success(service.preview("stock", file.getOriginalFilename(), file.getBytes(), sourceCode,
                categoryCode, warehouseCode, parseInstant(asOf), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:price:import')")
    @GetMapping("/prices/{batchId}")
    public AjaxResult priceDetail(@PathVariable String batchId) {
        return success(service.get("price", batchId));
    }

    @PreAuthorize("@ss.hasPermi('fashion:stock:import')")
    @GetMapping("/stocks/{batchId}")
    public AjaxResult stockDetail(@PathVariable String batchId) {
        return success(service.get("stock", batchId));
    }

    @PreAuthorize("@ss.hasPermi('fashion:price:import')")
    @Log(title = "智能选品价格全量发布", businessType = BusinessType.IMPORT)
    @PostMapping("/prices/{batchId}/publish")
    public AjaxResult publishPrice(@PathVariable String batchId, @RequestBody ProductImportPublishRequest request) {
        return success(service.publish("price", batchId, request.rowVersion, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:stock:import')")
    @Log(title = "智能选品库存全量发布", businessType = BusinessType.IMPORT)
    @PostMapping("/stocks/{batchId}/publish")
    public AjaxResult publishStock(@PathVariable String batchId, @RequestBody ProductImportPublishRequest request) {
        return success(service.publish("stock", batchId, request.rowVersion, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:price:import') and @ss.hasPermi('fashion:import:restore')")
    @Log(title = "智能选品价格历史恢复预览", businessType = BusinessType.UPDATE)
    @PostMapping("/prices/{batchId}/restore")
    public AjaxResult restorePrice(@PathVariable String batchId, @RequestBody CatalogRestoreRequest request) {
        return success(service.restore("price", batchId, request.requestKey, request.operatorNote, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:stock:import') and @ss.hasPermi('fashion:import:restore')")
    @Log(title = "智能选品库存历史恢复预览", businessType = BusinessType.UPDATE)
    @PostMapping("/stocks/{batchId}/restore")
    public AjaxResult restoreStock(@PathVariable String batchId, @RequestBody CatalogRestoreRequest request) {
        return success(service.restore("stock", batchId, request.requestKey, request.operatorNote, getUserId()));
    }

    private static ResponseEntity<byte[]> template(String resourceName, String downloadName) throws IOException {
        byte[] content;
        try (java.io.InputStream input = new ClassPathResource("fashion/import-templates/" + resourceName)
                .getInputStream()) {
            content = input.readAllBytes();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + downloadName)
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(content);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("asOf 必须是 ISO-8601 UTC 时间");
        }
    }
}
