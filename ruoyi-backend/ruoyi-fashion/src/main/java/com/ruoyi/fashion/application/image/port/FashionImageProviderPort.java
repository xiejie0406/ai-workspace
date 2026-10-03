package com.ruoyi.fashion.application.image.port;

import java.math.BigDecimal;
import java.util.List;

import tools.jackson.databind.JsonNode;

/** Provider Adapter 不接收客户、价格、库存或内部备注。 */
public interface FashionImageProviderPort {
    boolean available();

    /** 只描述适配器支持范围；可用性仍需凭据、私有素材访问与预算配置就绪。 */
    default Capabilities capabilities() {
        return new Capabilities("disabled", "", List.of(), false, false, null, "CNY");
    }

    record Capabilities(String providerCode, String model, List<String> supportedPositions,
            boolean requiresPublicUrls, boolean supportsCancellation, BigDecimal estimatedUnitCost, String currency) {}

    ProviderResult submit(String requestKey, String imageType, JsonNode inputs, JsonNode parameters, int count);

    ProviderResult query(String providerTaskId);

    default void cancel(String providerTaskId) {
    }

    record ProviderResult(
            String status,
            String providerCode,
            String providerTaskId,
            JsonNode results,
            BigDecimal actualCost,
            String currency,
            String errorCode,
            String errorMessage,
            boolean retryable) {
    }
}
