package com.ruoyi.fashion.controller.rest.product;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ruoyi.fashion.application.product.ProductCreate;

public class ProductCreateRequest {
    public String sourceCode;
    public String skuCode;
    public String styleCode;
    public String name;
    public String categoryCode;
    public String colorCode;
    public String colorName;
    public String sizeCode;
    public String sizeSystem;
    public String unit;
    public String brand;
    public String material;
    public String season;
    public String jdItemId;
    public String jdUrl;
    public java.util.Map<String, String> attributes;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("商品请求包含未知字段：" + field);
    }

    ProductCreate toCommand() {
        return new ProductCreate(sourceCode, skuCode, styleCode, name, categoryCode, colorCode, colorName,
                sizeCode, sizeSystem, unit, brand, material, season, jdItemId, jdUrl, attributes);
    }
}
