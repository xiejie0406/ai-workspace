package com.ruoyi.fashion.application.proposal;
import tools.jackson.databind.JsonNode;
public record ProposalImageTask(String id,String poolId,long ownerId,long draftVersion,String combinationId,
        String inputHash,JsonNode inputs,String requestKey,String status,String providerTaskId,
        JsonNode results,String errorMessage,JsonNode review,long rowVersion,Costs costs) {
    public ProposalImageTask(String id,String poolId,long ownerId,long draftVersion,String combinationId,String inputHash,JsonNode inputs,String requestKey,String status,String providerTaskId,JsonNode results,String errorMessage,JsonNode review,long rowVersion){this(id,poolId,ownerId,draftVersion,combinationId,inputHash,inputs,requestKey,status,providerTaskId,results,errorMessage,review,rowVersion,Costs.empty());}
    public record Costs(String providerCode,String model,java.math.BigDecimal estimatedCost,java.math.BigDecimal actualCost,
            String currency,String billingStatus,java.time.Instant submittedAt,java.time.Instant finishedAt,
            java.time.Instant cancelRequestedAt,java.time.Instant nextQueryAt,int retryCount) {
        public static Costs empty(){return new Costs(null,null,null,null,null,"unknown",null,null,null,null,0);}
    }
}
