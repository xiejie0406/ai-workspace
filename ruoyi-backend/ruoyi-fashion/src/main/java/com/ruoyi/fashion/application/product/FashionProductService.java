package com.ruoyi.fashion.application.product;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.application.product.port.FashionProductRepository;
import com.ruoyi.fashion.application.product.port.ProductSearchCriteria;
import com.ruoyi.fashion.application.security.FashionDictionaryGuard;
import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductStatus;
import com.ruoyi.fashion.domain.shared.FashionFieldWhitelistGuard;
import com.ruoyi.fashion.domain.shared.FashionId;
import com.ruoyi.fashion.domain.shared.FashionIdGenerator;
import com.ruoyi.fashion.domain.shared.FashionTimeSource;
import com.ruoyi.fashion.infrastructure.persistence.FashionCatalogWriteLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@FashionModuleEnabled
@Service
public class FashionProductService {
    public static final Set<String> EDITABLE_FIELDS = Set.of(
            "name", "categoryCode", "colorCode", "colorName", "sizeCode", "sizeSystem", "unit",
            "brand", "material", "season", "tags", "jdItemId", "jdUrl", "attributesConfirmed", "attributes");
    private static final Set<String> SIZE_SYSTEMS = Set.of("CN", "EU", "US", "LETTER", "FREE");

    private final FashionProductRepository repository;
    private final FashionDictionaryGuard dictionaryGuard;
    private final FashionIdGenerator idGenerator;
    private final FashionTimeSource timeSource;
    private final FashionCatalogWriteLock catalogWriteLock;
    private final TransactionTemplate transaction;

    public FashionProductService(
            FashionProductRepository repository,
            FashionDictionaryGuard dictionaryGuard,
            FashionIdGenerator idGenerator,
            FashionTimeSource timeSource,
            FashionCatalogWriteLock catalogWriteLock,
            @Qualifier("fashionTransactionTemplate") TransactionTemplate transaction) {
        this.repository = repository;
        this.dictionaryGuard = dictionaryGuard;
        this.idGenerator = idGenerator;
        this.timeSource = timeSource;
        this.catalogWriteLock = catalogWriteLock;
        this.transaction = transaction;
    }

    public ProductPage search(
            String sourceCode, String categoryCode, String status, String keyword, int page, int pageSize) {
        return search(sourceCode, categoryCode, status, keyword, page, pageSize, null, null);
    }

    public ProductPage search(String sourceCode,String categoryCode,String status,String keyword,int page,int pageSize,String attributeKey,String attributeValue) {
        if (attributeKey != null && !attributeKey.isBlank() && !com.ruoyi.fashion.domain.product.ProductAttributeFields.FIELDS.containsKey(attributeKey)) throw new ServiceException("属性筛选字段无效");
        if (attributeValue != null && attributeValue.length()>255) throw new ServiceException("属性筛选值过长");
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(pageSize, 1), 200);
        if (status != null && !status.isBlank()) {
            FashionProductStatus.fromCode(status);
        }
        ProductSearchCriteria criteria = new ProductSearchCriteria(
                trimToNull(sourceCode), trimToNull(categoryCode), trimToNull(status), trimToNull(keyword),
                (normalizedPage - 1) * normalizedSize, normalizedSize, trimToNull(attributeKey), trimToNull(attributeValue));
        List<FashionProduct> products = repository.search(criteria);
        var prices = repository.commercialPrices(products.stream().map(FashionProduct::id).collect(java.util.stream.Collectors.toSet()));
        return new ProductPage(
                products.stream().map(product -> ProductView.from(product, prices.get(product.id()))).toList(),
                repository.count(criteria), normalizedPage, normalizedSize);
    }

    public ProductView get(String id) {
        var product = requireProduct(FashionId.parse(id).value());
        return ProductView.from(product, repository.commercialPrices(Set.of(product.id())).get(product.id()));
    }

    public ProductView create(ProductCreate command, long operatorId) {
        validateCreate(command);
        return transaction.execute(status -> catalogWriteLock.executeLocked(10, () -> {
            String businessKey = businessKey(command.sourceCode(), command.skuCode());
            if (!repository.findByBusinessKeys(Set.of(businessKey)).isEmpty()) {
                throw new ServiceException("来源内 SKU 已存在：" + command.skuCode());
            }
            Instant now = timeSource.now();
            FashionProduct product = new FashionProduct(
                    idGenerator.nextId(), command.sourceCode().trim(), command.skuCode().trim(),
                    command.styleCode().trim(), command.name().trim(), command.categoryCode().trim(),
                    command.colorCode().trim(), command.colorName().trim(), command.sizeCode().trim(),
                    command.sizeSystem().trim(), command.unit().trim(), null, "CNY", "included", null, null,
                    trimToNull(command.brand()), trimToNull(command.material()), command.season().trim(), List.of(),
                    null, List.of(), 1L, null, false, null, null, trimToNull(command.jdItemId()),
                    trimToNull(command.jdUrl()), null, FashionProductStatus.DRAFT,
                    operatorId, now, operatorId, now, 1L, com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(command.attributes()));
            repository.insert(product);
            return ProductView.from(product);
        }));
    }

    public List<ProductView> update(List<ProductPatch> patches, long operatorId) {
        if (patches == null || patches.isEmpty() || patches.size() > 200) {
            throw new ServiceException("一次必须修改 1～200 个商品");
        }
        return transaction.execute(status -> catalogWriteLock.executeLocked(10, () -> {
            var products = patches.stream().map(patch -> updateOne(patch, operatorId)).toList();
            var prices = repository.commercialPrices(products.stream().map(FashionProduct::id).collect(java.util.stream.Collectors.toSet()));
            return products.stream().map(product -> ProductView.from(product,prices.get(product.id()))).toList();
        }));
    }

    public ProductView changeStatus(String id, String statusCode, long rowVersion, long operatorId) {
        long productId = FashionId.parse(id).value();
        FashionProductStatus newStatus = FashionProductStatus.fromCode(statusCode);
        return transaction.execute(status -> catalogWriteLock.executeLocked(10, () -> {
            requireProduct(productId);
            if (!repository.updateStatus(productId, newStatus, rowVersion, operatorId)) {
                throw conflict();
            }
            return view(requireProduct(productId));
        }));
    }

    /** AI 输出只有在操作人主动采用后，才与人工确认标记一并写入商品事实。 */
    public ProductView applyAttributeSuggestion(
            String id, Map<String, Object> suggestions, long expectedRowVersion, long runId, long operatorId) {
        long productId = FashionId.parse(id).value();
        if (suggestions == null || suggestions.isEmpty()) {
            throw new ServiceException("商品属性建议没有可采用字段");
        }
        return transaction.execute(status -> catalogWriteLock.executeLocked(10, () -> {
            FashionProduct current = requireProduct(productId);
            Map<String, Object> safe = new LinkedHashMap<>();
            suggestions.forEach((field, value) -> {
                if (!Set.of("categoryCode", "colorCode", "colorName", "season", "tags")
                        .contains(field)) {
                    throw new ServiceException("AI 商品属性包含不可采用字段：" + field);
                }
                safe.putAll(normalizeChanges(Map.of(field, value), current));
            });
            safe.put("lastAiRunId", runId);
            safe.put("attributesConfirmed", true);
            if (!repository.updateFields(productId, safe, expectedRowVersion, operatorId)) throw conflict();
            return view(requireProduct(productId));
        }));
    }

    private FashionProduct updateOne(ProductPatch patch, long operatorId) {
        long id = FashionId.parse(patch.id()).value();
        if (patch.changes() == null || patch.changes().isEmpty()) {
            throw new ServiceException("商品修改字段不能为空");
        }
        try {
            FashionFieldWhitelistGuard.requireOnlyAllowed(patch.changes(), EDITABLE_FIELDS);
        } catch (IllegalArgumentException exception) {
            throw new ServiceException(exception.getMessage());
        }
        FashionProduct current = requireProduct(id);
        Map<String, Object> normalized = normalizeChanges(patch.changes(), current);
        if (!repository.updateFields(id, normalized, patch.rowVersion(), operatorId)) {
            throw conflict();
        }
        return requireProduct(id);
    }

    private Map<String, Object> normalizeChanges(Map<String, Object> changes, FashionProduct current) {
        Map<String, Object> result = new LinkedHashMap<>();
        changes.forEach((field, raw) -> {
            if ("attributes".equals(field)) {
                if (!(raw instanceof Map<?, ?> values)) throw new ServiceException("attributes 必须是字段对象");
                result.put(field, com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(values));
                return;
            }
            if ("tags".equals(field)) {
                if (!(raw instanceof List<?> values) || values.size() > 50) {
                    throw new ServiceException("tags 必须是不超过 50 项的数组");
                }
                List<String> tags = values.stream().map(value -> value == null ? "" : value.toString().trim())
                        .filter(value -> !value.isBlank()).distinct().toList();
                if (tags.size() != values.size() || tags.stream().anyMatch(value -> value.length() > 80)) {
                    throw new ServiceException("tags 含空值、重复值或超长值");
                }
                result.put(field, tags);
                return;
            }
            if ("attributesConfirmed".equals(field)) {
                if (!(raw instanceof Boolean)) {
                    throw new ServiceException("attributesConfirmed 必须是布尔值");
                }
                result.put(field, raw);
                return;
            }
            String value = raw == null ? null : raw.toString().trim();
            boolean optional = Set.of("brand", "material", "jdItemId", "jdUrl").contains(field);
            if (!optional && (value == null || value.isBlank())) {
                throw new ServiceException(field + " 不能为空");
            }
            if ("categoryCode".equals(field)) {
                dictionaryGuard.requireActiveValue("fashion_product_category", value);
            } else if ("colorCode".equals(field)) {
                dictionaryGuard.requireActiveValue("fashion_product_color", value);
            } else if ("unit".equals(field)) {
                dictionaryGuard.requireActiveValue("fashion_product_unit", value);
            } else if ("season".equals(field)) {
                dictionaryGuard.requireActiveValue("fashion_product_season", value);
            } else if ("sizeSystem".equals(field) && !SIZE_SYSTEMS.contains(value)) {
                throw new ServiceException("sizeSystem 必须是 CN、EU、US、LETTER 或 FREE");
            } else if ("jdUrl".equals(field)) {
                validateJdUrl(value);
            }
            requireLength(field, value, maxLength(field));
            result.put(field, trimToNull(value));
        });
        return result;
    }

    private void validateCreate(ProductCreate command) {
        if (command == null) {
            throw new ServiceException("商品数据不能为空");
        }
        requireCode("skuCode", command.skuCode(), 64);
        requireCode("styleCode", command.styleCode(), 64);
        requireCode("colorCode", command.colorCode(), 32);
        requireText("name", command.name(), 120);
        requireText("colorName", command.colorName(), 32);
        requireText("sizeCode", command.sizeCode(), 32);
        if (!SIZE_SYSTEMS.contains(command.sizeSystem())) {
            throw new ServiceException("sizeSystem 必须是 CN、EU、US、LETTER 或 FREE");
        }
        dictionaryGuard.requireActiveValue("fashion_product_source", command.sourceCode());
        dictionaryGuard.requireActiveValue("fashion_product_category", command.categoryCode());
        dictionaryGuard.requireActiveValue("fashion_product_color", command.colorCode());
        dictionaryGuard.requireActiveValue("fashion_product_unit", command.unit());
        dictionaryGuard.requireActiveValue("fashion_product_season", command.season());
        requireLength("brand", trimToNull(command.brand()), 80);
        requireLength("material", trimToNull(command.material()), 255);
        requireLength("jdItemId", trimToNull(command.jdItemId()), 64);
        validateJdUrl(trimToNull(command.jdUrl()));
    }

    private FashionProduct requireProduct(long id) {
        return repository.findById(id).orElseThrow(() -> new ServiceException("商品不存在或已不可见"));
    }

    private ProductView view(FashionProduct product) {
        return ProductView.from(product,repository.commercialPrices(Set.of(product.id())).get(product.id()));
    }

    public static String businessKey(String sourceCode, String skuCode) {
        return sourceCode.trim() + ":" + skuCode.trim();
    }

    public static void requireCode(String field, String value, int maximum) {
        requireText(field, value, maximum);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new ServiceException(field + " 只能包含字母、数字、点、下划线和连字符");
        }
    }

    public static void requireText(String field, String value, int maximum) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(field + " 不能为空");
        }
        requireLength(field, value.trim(), maximum);
    }

    private static void requireLength(String field, String value, int maximum) {
        if (value != null && value.length() > maximum) {
            throw new ServiceException(field + " 长度不能超过 " + maximum);
        }
    }

    private static int maxLength(String field) {
        return switch (field) {
            case "name" -> 120;
            case "brand" -> 80;
            case "material" -> 255;
            case "jdUrl" -> 2048;
            case "jdItemId" -> 64;
            default -> 32;
        };
    }

    private static void validateJdUrl(String value) {
        if (value != null && (!value.startsWith("https://") || value.length() > 2048)) {
            throw new ServiceException("京东来源链接必须是长度不超过 2048 的 HTTPS URL");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ServiceException conflict() {
        return new ServiceException("商品已被其他操作修改，请刷新后重试");
    }
}
