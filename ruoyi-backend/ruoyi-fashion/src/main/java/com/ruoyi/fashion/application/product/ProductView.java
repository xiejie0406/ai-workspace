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
        java.util.Map<String, String> attributes,
        BigDecimal groupPrice,
        BigDecimal vipPrice,
        ImageSummary imageSummary) {

    public record ImageSummary(int totalCount, int activeCount, int proposalAllowedCount, int aiAllowedCount,
            String reviewStatus, String proposalPermission, String mainImageStatus) {}

    public static ProductView from(FashionProduct product) {
        return from(product, null);
    }

    public static ProductView from(FashionProduct product,
            com.ruoyi.fashion.application.product.port.FashionProductRepository.CommercialPrices prices) {
        return new ProductView(
                Long.toString(product.id()), product.sourceCode(), product.skuCode(), product.styleCode(),
                product.name(), product.categoryCode(), product.colorCode(), product.colorName(),
                product.sizeCode(), product.sizeSystem(), product.unit(), product.salePrice(),
                product.currency(), product.taxMode(), product.priceAsOf(), product.brand(),
                product.material(), product.season(), product.tags(), product.mainImageKey(),
                product.images(), product.visualVersion(), product.attributesConfirmed(), product.jdItemId(),
                product.jdUrl(), product.status().code(), product.incompleteReasons(), product.rowVersion(),
                product.updateTime(), product.attributes(), prices == null ? null : prices.groupPrice(),
                prices == null ? null : prices.vipPrice(), imageSummary(product));
    }

    private static ImageSummary imageSummary(FashionProduct product) {
        List<FashionProductImage> images = product.images() == null ? List.of() : product.images();
        var active = images.stream().filter(i -> "active".equals(i.status())).toList();
        var confirmed = active.stream().filter(i -> i.confirmedBy() > 0 && i.confirmedAt() != null).toList();
        int proposal = (int) confirmed.stream().filter(FashionProductImage::allowProposal).count();
        int ai = (int) confirmed.stream().filter(FashionProductImage::allowAi).count();
        String mainStatus = images.stream().filter(i -> java.util.Objects.equals(product.mainImageKey(), i.objectKey()))
                .map(FashionProductImage::status).findFirst().orElse(null);
        return new ImageSummary(images.size(), active.size(), proposal, ai,
                images.isEmpty() ? "none" : (!active.isEmpty() && active.size() == confirmed.size() ? "confirmed" : "unconfirmed"),
                images.isEmpty() ? "none" : (proposal > 0 ? "allowed" : "denied"), mainStatus);
    }
}
