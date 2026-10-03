package com.ruoyi.fashion.application.proposal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 对客提案只传递白名单字段，不包含成本、客户内部备注及正式报价数量。 */
public final class ProposalModels {
    private ProposalModels() {}
    public record ExportRequest(long rowVersion, List<String> combinationIds, String priceMode,
            String imageMode, Map<String,String> adoptedTaskIds) {}
    public record Image(String objectKey, String sha256) {}
    public record Item(String name, String mainSku, String itemNo, String articleNo, String color,
            BigDecimal price, Image image,String productId) {
        public Item(String name,String mainSku,String itemNo,String articleNo,String color,BigDecimal price,Image image){this(name,mainSku,itemNo,articleNo,color,price,image,null);}
    }
    public record Set(String id, String name, List<Item> items, Image modelImage, String currency, String taxMode) {}
    public record Snapshot(String poolId, long version, String title, String capturedAt, String priceMode,
            List<Set> sets) {}
    public record Preflight(boolean ready, List<String> issues, int combinationCount, int maxPerFile) {}
    public record TryOnRequest(long rowVersion, String combinationId, Image model, String requestKey,
            Map<String,String> garmentPositions,BigDecimal maxCost,Boolean costConfirmed) {
        public TryOnRequest(long rowVersion,String combinationId,Image model,String requestKey){this(rowVersion,combinationId,model,requestKey,Map.of(),null,false);}
    }
    public record ReviewRequest(long rowVersion, int resultNo, boolean adopt, Map<String,Boolean> checks, String reason) {}
}
