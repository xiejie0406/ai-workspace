package com.ruoyi.fashion.application.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductImage;

public record ProductView(
        String id,
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
        String brand,
        String material,
        String season,
        List<String> tags,
        String mainImageKey,
        List<FashionProductImage> images,
        long visualVersion,
        boolean attributesConfirmed,
        String jdItemId,
        String jdUrl,
        String status,
        List<String> incompleteReasons,
        long rowVersion,
        Instant updateTime,
        java.util.Map<String, String> attributes) {

    public static ProductView from(FashionProduct product) {
        return new ProductView(
                Long.toString(product.id()), product.sourceCode(), product.skuCode(), product.styleCode(),
                product.name(), product.categoryCode(), product.colorCode(), product.colorName(),
                product.sizeCode(), product.sizeSystem(), product.unit(), product.salePrice(),
                product.currency(), product.taxMode(), product.priceAsOf(), product.brand(),
                product.material(), product.season(), product.tags(), product.mainImageKey(),
                product.images(), product.visualVersion(), product.attributesConfirmed(), product.jdItemId(),
                product.jdUrl(), product.status().code(), product.incompleteReasons(), product.rowVersion(),
                product.updateTime(), product.attributes());
    }
}
