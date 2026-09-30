package com.ruoyi.fashion.application.product;

public record ProductCreate(
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
        String brand,
        String material,
        String season,
        String jdItemId,
        String jdUrl,
        java.util.Map<String, String> attributes) {

    public ProductCreate(String sourceCode,
        String skuCode,
        String styleCode,
        String name,
        String categoryCode,
        String colorCode,
        String colorName,
        String sizeCode,
        String sizeSystem,
        String unit,
        String brand,
        String material,
        String season,
        String jdItemId,
        String jdUrl) {
        this(sourceCode, skuCode, styleCode, name, categoryCode, colorCode, colorName, sizeCode, sizeSystem, unit, brand, material, season, jdItemId, jdUrl, java.util.Map.of());
    }

    public ProductCreate {
        attributes = com.ruoyi.fashion.domain.product.ProductAttributeFields.normalize(attributes);
    }
}
