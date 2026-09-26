package com.ruoyi.fashion.domain.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** SKU 粒度商品；来源、款号、颜色仅用于页面分组，不拆额外 SPU/颜色款表。 */
public record FashionProduct(
        long id,
        String sourceCode,
        String skuCode,
        String styleCode,
        String name,
        String categoryCode,
        String colorCode,
        String colorName,
        String sizeCode,
        String sizeSystem,
        String unit,
        BigDecimal salePrice,
        String currency,
        String taxMode,
        Instant priceAsOf,
        Long lastPriceImportBatchId,
        String brand,
        String material,
        String season,
        List<String> tags,
        String mainImageKey,
        List<FashionProductImage> images,
        long visualVersion,
        Long lastAiRunId,
        boolean attributesConfirmed,
        Long attributesConfirmedBy,
        Instant attributesConfirmedAt,
        String jdItemId,
        String jdUrl,
        Long lastProductImportBatchId,
        FashionProductStatus status,
        long createBy,
        Instant createTime,
        long updateBy,
        Instant updateTime,
        long rowVersion,
        java.util.Map<String, String> attributes) {

    public FashionProduct(long id,
        String sourceCode,
        String skuCode,
        String styleCode,
        String name,
        String categoryCode,
        String colorCode,
        String colorName,
        String sizeCode,
        String sizeSystem,
        String unit,
        BigDecimal salePrice,
        String currency,
        String taxMode,
        Instant priceAsOf,
        Long lastPriceImportBatchId,
        String brand,
        String material,
        String season,
        List<String> tags,
        String mainImageKey,
        List<FashionProductImage> images,
        long visualVersion,
        Long lastAiRunId,
        boolean attributesConfirmed,
        Long attributesConfirmedBy,
        Instant attributesConfirmedAt,
        String jdItemId,
        String jdUrl,
        Long lastProductImportBatchId,
        FashionProductStatus status,
        long createBy,
        Instant createTime,
        long updateBy,
        Instant updateTime,
        long rowVersion) {
        this(id, sourceCode, skuCode, styleCode, name, categoryCode, colorCode, colorName, sizeCode, sizeSystem, unit, salePrice, currency, taxMode, priceAsOf, lastPriceImportBatchId, brand, material, season, tags, mainImageKey, images, visualVersion, lastAiRunId, attributesConfirmed, attributesConfirmedBy, attributesConfirmedAt, jdItemId, jdUrl, lastProductImportBatchId, status, createBy, createTime, updateBy, updateTime, rowVersion, java.util.Map.of());
    }

    public FashionProduct {
        attributes = attributes == null ? java.util.Map.of() : java.util.Map.copyOf(attributes);
    }

    public String businessKey() {
        return sourceCode + ":" + skuCode;
    }

    public List<String> incompleteReasons() {
        java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
        if (salePrice == null || salePrice.signum() <= 0) {
            reasons.add("missing_price");
        }
        if (mainImageKey == null || mainImageKey.isBlank()) {
            reasons.add("missing_image");
        }
        if (!attributesConfirmed) {
            reasons.add("attributes_unconfirmed");
        }
        if (status != FashionProductStatus.ACTIVE) {
            reasons.add("not_active");
        }
        return List.copyOf(reasons);
    }
}
