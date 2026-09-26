package com.ruoyi.fashion.application.proposal;
import tools.jackson.databind.JsonNode;
public record ProposalImageTask(String id,String poolId,long ownerId,long draftVersion,String combinationId,
        String inputHash,JsonNode inputs,String requestKey,String status,String providerTaskId,
        JsonNode results,String errorMessage,JsonNode review,long rowVersion) {}
