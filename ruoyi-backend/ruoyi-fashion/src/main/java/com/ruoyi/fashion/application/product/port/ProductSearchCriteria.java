package com.ruoyi.fashion.application.product.port;

public record ProductSearchCriteria(
        String sourceCode,
        String categoryCode,
        String status,
        String keyword,
        int offset,
        int limit, String attributeKey, String attributeValue) {

    public ProductSearchCriteria(String sourceCode,String categoryCode,String status,String keyword,int offset,int limit) {
        this(sourceCode,categoryCode,status,keyword,offset,limit,null,null);
    }

    public ProductSearchCriteria {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new IllegalArgumentException("分页参数无效");
        }
    }
}
