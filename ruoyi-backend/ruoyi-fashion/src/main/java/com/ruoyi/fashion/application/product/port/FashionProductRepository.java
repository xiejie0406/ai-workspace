package com.ruoyi.fashion.application.product.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ruoyi.fashion.domain.product.FashionProduct;
import com.ruoyi.fashion.domain.product.FashionProductImage;
import com.ruoyi.fashion.domain.product.FashionProductStatus;

public interface FashionProductRepository {
    record CommercialPrices(BigDecimal groupPrice, BigDecimal vipPrice) {}

    /** 一页商品一次读取双价，缺值保持null，主数据仍由fq_product持有。 */
    default Map<Long, CommercialPrices> commercialPrices(Set<Long> ids) { return Map.of(); }
    List<FashionProduct> search(ProductSearchCriteria criteria);

    long count(ProductSearchCriteria criteria);

    Optional<FashionProduct> findById(long id);

    Map<String, FashionProduct> findByBusinessKeys(Set<String> businessKeys);

    List<FashionProduct> findByStyleColor(String sourceCode, String styleCode, String colorCode);

    List<FashionProduct> findActiveForScope(String sourceCode, String categoryCode);

    void insert(FashionProduct product);

    boolean updateImported(FashionProduct product, long expectedRowVersion);

    boolean updateFields(long id, Map<String, Object> fields, long expectedRowVersion, long operatorId);

    boolean updateStatus(long id, FashionProductStatus status, long expectedRowVersion, long operatorId);

    boolean updatePrice(
            long id,
            BigDecimal salePrice,
            String currency,
            String taxMode,
            Instant priceAsOf,
            Long batchId,
            long expectedRowVersion,
            long operatorId,
            Instant now);

    boolean updateImages(
            long id,
            List<FashionProductImage> images,
            String mainImageKey,
            long expectedRowVersion,
            long operatorId);
}
