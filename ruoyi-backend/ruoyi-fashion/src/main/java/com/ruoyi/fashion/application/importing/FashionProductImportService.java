package com.ruoyi.fashion.application.importing;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import com.ruoyi.fashion.domain.product.ProductAttributeFields;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.importing.port.FashionImportRepository;
import com.ruoyi.fashion.application.material.port.FashionObjectStoragePort;
import com.ruoyi.fashion.application.product.FashionProductService;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.security.FashionDictionaryGuard;
import com.ruoyi.fashion.domain.importing.FashionImportBatch;
import com.ruoyi.fashion.domain.importing.FashionImportDetail;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductStatus;
import com.ruoyi.fashion.domain.shared.FashionId;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import com.ruoyi.fashion.infrastructure.storage.FashionHashing;
import com.ruoyi.fashion.infrastructure.storage.FashionUploadPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@FashionModuleEnabled
@Service
public class FashionProductImportService {
    private static final Set<String> OPTIONAL_CLEARABLE = java.util.stream.Stream.concat(
            Set.of("brand", "material", "jdItemId", "jdUrl").stream(), ProductAttributeFields.FIELDS.keySet().stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final FashionProductImportParser parser;
    private final FashionImportRepository imports;
    private final FashionProductRepository products;
    private final FashionDictionaryGuard dictionaryGuard;
    private final FashionObjectStoragePort storage;
    private final FashionIdGenerator idGenerator;
    private final FashionTimeSource timeSource;
    private final FashionCatalogWriteLock catalogWriteLock;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public FashionProductImportService(
            FashionProductImportParser parser,
            FashionImportRepository imports,
            FashionProductRepository products,
            FashionDictionaryGuard dictionaryGuard,
            FashionObjectStoragePort storage,
            FashionIdGenerator idGenerator,
            FashionTimeSource timeSource,
            FashionCatalogWriteLock catalogWriteLock,
            @Qualifier("fashionTransactionTemplate") TransactionTemplate transaction,
            ObjectMapper objectMapper) {
        this.parser = parser;
        this.imports = imports;
        this.products = products;
        this.dictionaryGuard = dictionaryGuard;
        this.storage = storage;
        this.idGenerator = idGenerator;
        this.timeSource = timeSource;
        this.catalogWriteLock = catalogWriteLock;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    public ProductImportView preview(
            String filename,
            byte[] content,
            Map<String, String> requestedMapping,
            Set<String> clearFields,
            String sourceCode,
            Instant asOf,
            long operatorId) {
        dictionaryGuard.requireActiveValue("fashion_product_source", sourceCode);
        Instant now = timeSource.now();
        if (asOf == null || asOf.isAfter(now.plus(5, ChronoUnit.MINUTES))) {
            throw new ServiceException("导入业务时间不能为空或晚于服务器时间 5 分钟以上");
        }
        Set<String> clears = clearFields == null ? Set.of() : Set.copyOf(clearFields);
        if (!OPTIONAL_CLEARABLE.containsAll(clears)) {
            throw new ServiceException("显式清空仅允许可选商品属性");
        }
        String safeFilename = FashionUploadPolicy.safeFilename(filename);
        String fileHash = FashionHashing.sha256(content);
        Map<String, String> mapping = parser.resolvedMapping(requestedMapping);
        String requestKey = stableHash(Map.of(
                "type", "product", "fileHash", fileHash, "mapping", new TreeMap<>(mapping),
                "clearFields", clears.stream().sorted().toList(), "sourceCode", sourceCode, "asOf", asOf.toString()));
        java.util.Optional<FashionImportBatch> existing = imports.findBatchByRequestKey(requestKey);
        if (existing.isPresent()) {
            return view(existing.get());
        }

        List<ParsedProductRow> parsed = parser.parse(safeFilename, content, mapping);
        String extension = FashionUploadPolicy.extension(safeFilename);
        String fileKey = "imports/product/" + fileHash + "/source." + extension;
        storage.putIfAbsent(fileKey, content,
                "csv".equals(extension) ? "text/csv" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

        List<String> keys = parsed.stream().map(row -> businessKey(row.values())).toList();
        Map<String, Long> frequencies = new HashMap<>();
        keys.forEach(key -> frequencies.merge(key, 1L, Long::sum));
        Map<String, FashionProduct> current = products.findByBusinessKeys(new HashSet<>(keys));
        long batchId = idGenerator.nextId();
        List<FashionImportDetail> details = new ArrayList<>();
        int errors = 0;
        for (int index = 0; index < parsed.size(); index++) {
            ParsedProductRow row = parsed.get(index);
            List<ImportRowError> rowErrors = new ArrayList<>(row.errors());
            if (!sourceCode.equals(row.values().get("sourceCode"))) {
                rowErrors.add(error("sourceCode", "scope_mismatch", row.values().get("sourceCode"),
                        "文件来源编码必须与本批范围一致"));
            }
            String key = keys.get(index);
            if (frequencies.getOrDefault(key, 0L) > 1L) {
                rowErrors.add(error("skuCode", "duplicate_business_key", row.values().get("skuCode"),
                        "同一批次来源＋SKU 只能出现一次"));
            }
            validateRow(row.values(), rowErrors);
            FashionProduct before = current.get(key);
            Map<String, Object> normalized = normalize(row.values(), clears, before);
            String changeType = before == null ? "added" : (sameProductFields(before, normalized) ? "unchanged" : "changed");
            if (!rowErrors.isEmpty()) {
                errors++;
            }
            Instant created = now.plusNanos(index);
            details.add(new FashionImportDetail(
                    idGenerator.nextId(), batchId, index + 1, row.sourceRowNo(), before == null ? null : before.id(),
                    key, new LinkedHashMap<>(row.values()), normalized, beforeSnapshot(before), null,
                    rowErrors.isEmpty() ? "valid" : "invalid", List.copyOf(rowErrors), changeType,
                    operatorId, created, operatorId, created, 1L, "input"));
        }
        String scopeHash = stableHash(keys.stream().sorted().toList());
        String baseDataHash = baseDataHash(details, current);
        FashionImportBatch batch = new FashionImportBatch(
                batchId, "PRODUCT-" + batchId, FashionProductImportParser.TEMPLATE_CODE,
                FashionProductImportParser.TEMPLATE_VERSION, "product", "import", sourceCode,
                safeFilename, fileKey, fileHash, mapping,
                Map.of("sourceCode", sourceCode, "clearFields", clears.stream().sorted().toList()),
                scopeHash, baseDataHash, asOf, requestKey, 0, parsed.size(), errors,
                errors == 0 ? "validated" : "invalid", now, null,
                errors == 0 ? null : "存在 " + errors + " 行阻断错误", operatorId, now, operatorId, now, 1L,
                null, null, null);
        try {
            transaction.executeWithoutResult(status -> {
                imports.insertBatch(batch);
                imports.insertDetails(details);
            });
        } catch (DuplicateKeyException exception) {
            return imports.findBatchByRequestKey(requestKey).map(this::view)
                    .orElseThrow(() -> new ServiceException("重复请求竞争后无法读取原批次"));
        }
        return ProductImportView.from(batch, details.stream().map(ImportDetailView::from).toList());
    }

    public ProductImportView get(String batchId) {
        FashionImportBatch batch = requireBatch(FashionId.parse(batchId).value());
        if (!"product".equals(batch.importType())) {
            throw new ServiceException("批次不是商品资料导入");
        }
        return view(batch);
    }

    public ProductImportView publish(String batchId, long rowVersion, long operatorId) {
        long id = FashionId.parse(batchId).value();
        FashionImportBatch before = requireBatch(id);
        if ("success".equals(before.status())) {
            return view(before);
        }
        if (!"validated".equals(before.status()) || before.errorCount() != 0) {
            throw new ServiceException("只有无错误的 validated 商品批次可以发布");
        }
        Boolean published = transaction.execute(status -> catalogWriteLock.executeLocked(10, () -> {
            FashionImportBatch batch = requireBatch(id);
            List<FashionImportDetail> details = imports.findDetails(id);
            Map<String, FashionProduct> current = products.findByBusinessKeys(
                    details.stream().map(FashionImportDetail::businessKey).collect(java.util.stream.Collectors.toSet()));
            String currentHash = baseDataHash(details, current);
            if (!Objects.equals(batch.baseDataHash(), currentHash)) {
                imports.markBatchConflict(id, operatorId, timeSource.now(), "商品范围或 row_version 已变化，请重新预览");
                return false;
            }
            Instant now = timeSource.now();
            if (!imports.markPublishing(id, rowVersion, operatorId, now)) {
                throw new ServiceException("导入批次已被其他操作修改，请刷新后重试");
            }
            for (FashionImportDetail detail : details) {
                if (!"valid".equals(detail.status())) {
                    throw new ServiceException("批次包含未通过的明细");
                }
                FashionProduct old = current.get(detail.businessKey());
                FashionProduct target = importedProduct(detail.normalizedData(), old, id, operatorId, now);
                if (old == null) {
                    products.insert(target);
                } else if (!products.updateImported(target, old.rowVersion())) {
                    throw new ServiceException("商品发布发生并发冲突，请重新预览");
                }
                FashionProduct saved = products.findById(target.id())
                        .orElseThrow(() -> new IllegalStateException("发布后的商品不存在"));
                imports.markDetailApplied(detail.id(), saved.id(), afterSnapshot(saved), operatorId, now);
            }
            imports.markBatchSuccess(id, operatorId, now);
            return true;
        }));
        if (!Boolean.TRUE.equals(published)) {
            throw new ServiceException("商品范围或 row_version 已变化，请重新预览");
        }
        return view(requireBatch(id));
    }

    private ProductImportView view(FashionImportBatch batch) {
        return ProductImportView.from(batch,
                imports.findDetails(batch.id()).stream().map(ImportDetailView::from).toList());
    }

    private void validateRow(Map<String, String> values, List<ImportRowError> errors) {
        tryValidation("skuCode", values.get("skuCode"), errors,
                () -> FashionProductService.requireCode("skuCode", values.get("skuCode"), 64));
        tryValidation("styleCode", values.get("styleCode"), errors,
                () -> FashionProductService.requireCode("styleCode", values.get("styleCode"), 64));
        tryValidation("name", values.get("name"), errors,
                () -> FashionProductService.requireText("name", values.get("name"), 120));
        tryValidation("colorName", values.get("colorName"), errors,
                () -> FashionProductService.requireText("colorName", values.get("colorName"), 32));
        tryValidation("sizeCode", values.get("sizeCode"), errors,
                () -> FashionProductService.requireText("sizeCode", values.get("sizeCode"), 32));
        validateDictionary("fashion_product_source", "sourceCode", values, errors);
        validateDictionary("fashion_product_category", "categoryCode", values, errors);
        validateDictionary("fashion_product_color", "colorCode", values, errors);
        validateDictionary("fashion_product_unit", "unit", values, errors);
        validateDictionary("fashion_product_season", "season", values, errors);
        if (!Set.of("CN", "EU", "US", "LETTER", "FREE").contains(values.get("sizeSystem"))) {
            errors.add(error("sizeSystem", "invalid_enum", values.get("sizeSystem"), "尺码制式无效"));
        }
        ProductAttributeFields.FIELDS.keySet().forEach(field -> {
            String value = values.get(field);
            if (value != null) tryValidation(field, value, errors, () -> ProductAttributeFields.normalize(Map.of(field, value)));
        });
        String jdUrl = values.get("jdUrl");
        if (jdUrl != null && (!jdUrl.startsWith("https://") || jdUrl.length() > 2048)) {
            errors.add(error("jdUrl", "invalid_https_url", jdUrl, "京东链接必须是 HTTPS URL"));
        }
    }

    private void validateDictionary(
            String dictionary, String field, Map<String, String> values, List<ImportRowError> errors) {
        tryValidation(field, values.get(field), errors,
                () -> dictionaryGuard.requireActiveValue(dictionary, values.get(field)));
    }

    private static void tryValidation(String field, String value, List<ImportRowError> errors, Runnable validation) {
        try {
            validation.run();
        } catch (RuntimeException exception) {
            errors.add(error(field, "invalid_value", value, exception.getMessage()));
        }
    }

    private static ImportRowError error(String field, String code, String value, String message) {
        String summary = value == null ? "[empty]" : (value.length() > 32 ? value.substring(0, 32) + "…" : value);
        return new ImportRowError(field, code, summary, message);
    }

    private static Map<String, Object> normalize(
            Map<String, String> values, Set<String> clears, FashionProduct before) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (value != null || clears.contains(key) || !OPTIONAL_CLEARABLE.contains(key)) {
                result.put(key, value);
            } else if (before != null) {
                result.put(key, existingValue(before, key));
            } else {
                result.put(key, null);
            }
        });
        return result;
    }

    private static Object existingValue(FashionProduct p, String key) {
        return switch (key) {
            case "brand" -> p.brand();
            case "material" -> p.material();
            case "jdItemId" -> p.jdItemId();
            case "jdUrl" -> p.jdUrl();
            default -> p.attributes().get(key);
        };
    }

    private static boolean sameProductFields(FashionProduct p, Map<String, Object> n) {
        return Objects.equals(p.styleCode(), n.get("styleCode"))
                && Objects.equals(p.name(), n.get("name"))
                && Objects.equals(p.categoryCode(), n.get("categoryCode"))
                && Objects.equals(p.colorCode(), n.get("colorCode"))
                && Objects.equals(p.colorName(), n.get("colorName"))
                && Objects.equals(p.sizeCode(), n.get("sizeCode"))
                && Objects.equals(p.sizeSystem(), n.get("sizeSystem"))
                && Objects.equals(p.unit(), n.get("unit"))
                && Objects.equals(p.brand(), n.get("brand"))
                && Objects.equals(p.material(), n.get("material"))
                && Objects.equals(p.season(), n.get("season"))
                && Objects.equals(p.jdItemId(), n.get("jdItemId"))
                && Objects.equals(p.jdUrl(), n.get("jdUrl"))
                && Objects.equals(p.attributes(), attributes(n));
    }

    private FashionProduct importedProduct(
            Map<String, Object> n, FashionProduct old, long batchId, long operatorId, Instant now) {
        long id = old == null ? idGenerator.nextId() : old.id();
        return new FashionProduct(
                id, text(n, "sourceCode"), text(n, "skuCode"), text(n, "styleCode"), text(n, "name"),
                text(n, "categoryCode"), text(n, "colorCode"), text(n, "colorName"), text(n, "sizeCode"),
                text(n, "sizeSystem"), text(n, "unit"), old == null ? null : old.salePrice(),
                old == null ? "CNY" : old.currency(), old == null ? "included" : old.taxMode(),
                old == null ? null : old.priceAsOf(), old == null ? null : old.lastPriceImportBatchId(),
                text(n, "brand"), text(n, "material"), text(n, "season"), old == null ? List.of() : old.tags(),
                old == null ? null : old.mainImageKey(), old == null ? List.of() : old.images(),
                old == null ? 1L : old.visualVersion(), old == null ? null : old.lastAiRunId(),
                old != null && old.attributesConfirmed(), old == null ? null : old.attributesConfirmedBy(),
                old == null ? null : old.attributesConfirmedAt(), text(n, "jdItemId"), text(n, "jdUrl"), batchId,
                old == null ? FashionProductStatus.DRAFT : old.status(),
                old == null ? operatorId : old.createBy(), old == null ? now : old.createTime(),
                operatorId, now, old == null ? 1L : old.rowVersion() + 1, n.keySet().stream().anyMatch(ProductAttributeFields.FIELDS::containsKey)
                        ? attributes(n) : (old == null ? Map.of() : old.attributes()));
    }

    private static Map<String, String> attributes(Map<String, Object> values) {
        Map<String, String> result = new LinkedHashMap<>();
        ProductAttributeFields.FIELDS.keySet().forEach(field -> {
            Object value = values.get(field);
            if (value != null && !value.toString().isBlank()) result.put(field, value.toString());
        });
        return result;
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static String businessKey(Map<String, String> values) {
        String source = values.get("sourceCode");
        String sku = values.get("skuCode");
        return (source == null ? "" : source) + ":" + (sku == null ? "" : sku);
    }

    private static Map<String, Object> beforeSnapshot(FashionProduct product) {
        if (product == null) {
            return Map.of("table", "fq_product", "exists", false);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("table", "fq_product");
        result.put("id", Long.toString(product.id()));
        result.put("exists", true);
        result.put("rowVersion", product.rowVersion());
        result.put("fields", productFields(product));
        return result;
    }

    private static Map<String, Object> afterSnapshot(FashionProduct product) {
        Map<String, Object> result = new LinkedHashMap<>(beforeSnapshot(product));
        result.put("businessTime", product.updateTime().toString());
        return result;
    }

    private static Map<String, Object> productFields(FashionProduct p) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceCode", p.sourceCode());
        result.put("skuCode", p.skuCode());
        result.put("styleCode", p.styleCode());
        result.put("name", p.name());
        result.put("categoryCode", p.categoryCode());
        result.put("colorCode", p.colorCode());
        result.put("colorName", p.colorName());
        result.put("sizeCode", p.sizeCode());
        result.put("sizeSystem", p.sizeSystem());
        result.put("unit", p.unit());
        result.put("brand", p.brand());
        result.put("material", p.material());
        result.put("season", p.season());
        result.put("jdItemId", p.jdItemId());
        result.put("jdUrl", p.jdUrl());
        result.putAll(p.attributes());
        return result;
    }

    private String baseDataHash(List<FashionImportDetail> details, Map<String, FashionProduct> current) {
        List<Map<String, Object>> values = details.stream()
                .sorted(Comparator.comparing(FashionImportDetail::businessKey))
                .map(detail -> {
                    FashionProduct p = current.get(detail.businessKey());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("businessKey", detail.businessKey());
                    item.put("exists", p != null);
                    item.put("id", p == null ? null : Long.toString(p.id()));
                    item.put("rowVersion", p == null ? null : p.rowVersion());
                    return item;
                }).toList();
        return stableHash(values);
    }

    private String stableHash(Object value) {
        try {
            return FashionHashing.sha256(objectMapper.writer()
                    .with(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value));
        } catch (JacksonException exception) {
            throw new ServiceException("无法计算导入摘要");
        }
    }

    private FashionImportBatch requireBatch(long id) {
        return imports.findBatchById(id).orElseThrow(() -> new ServiceException("导入批次不存在"));
    }
}
