package com.ruoyi.fashion.controller.rest.importing;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.importing.FashionProductImportService;
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
@RequestMapping("/fashion/imports/products")
public class FashionProductImportController extends BaseController {
    private final FashionProductImportService service;
    private final ObjectMapper objectMapper;

    public FashionProductImportController(FashionProductImportService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:import')")
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) throws IOException {
        if ("csv".equals(format)) {
            try (java.io.InputStream input = new ClassPathResource("fashion/import-templates/product-v1.csv").getInputStream()) {
                return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=fashion-product-v1.csv")
                        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(input.readAllBytes());
            }
        }
        if (!"xlsx".equals(format)) throw new ServiceException("模板格式必须是 xlsx 或 csv");
        return FashionTemplateWorkbook.response("fashion-product-v2.xlsx",
                new com.ruoyi.fashion.application.importing.FashionProductImportParser().defaultMapping());
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:list') or @ss.hasPermi('fashion:product:import')")
    @GetMapping("/fields")
    public AjaxResult fields() {
        return success(Map.of("templateVersion", "2.0", "mapping",
                new com.ruoyi.fashion.application.importing.FashionProductImportParser().defaultMapping(),
                "attributes", com.ruoyi.fashion.domain.product.ProductAttributeFields.DEFINITIONS));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:import')")
    @Log(title = "智能选品商品导入预览", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult preview(
            @RequestPart("file") MultipartFile file,
            @RequestParam String sourceCode,
            @RequestParam String asOf,
            @RequestParam(required = false) String mapping,
            @RequestParam(required = false) String clearFields) throws IOException {
        return success(service.preview(
                file.getOriginalFilename(), file.getBytes(), mapping(mapping), clearFields(clearFields),
                sourceCode, parseInstant(asOf), getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:import')")
    @GetMapping("/{batchId}")
    public AjaxResult detail(@PathVariable String batchId) {
        return success(service.get(batchId));
    }

    @PreAuthorize("@ss.hasPermi('fashion:product:import')")
    @Log(title = "智能选品商品导入发布", businessType = BusinessType.IMPORT)
    @PostMapping("/{batchId}/publish")
    public AjaxResult publish(
            @PathVariable String batchId,
            @RequestBody ProductImportPublishRequest request) {
        return success(service.publish(batchId, request.rowVersion, getUserId()));
    }

    private Map<String, String> mapping(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, String>>() {});
        } catch (Exception exception) {
            throw new ServiceException("字段映射 JSON 无效");
        }
    }

    private static Set<String> clearFields(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("asOf 必须是 ISO-8601 UTC 时间");
        }
    }
}
