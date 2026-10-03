package com.ruoyi.fashion.application.proposal;
import java.util.*;
import java.time.Instant;
import tools.jackson.databind.JsonNode;
public interface ProposalDeliveryRepository {
    record Entry(String id,long owner,String poolId,long draftVersion,String requestKey,String title,String status,
            JsonNode snapshot,String fileKey,String fileHash,Long fileSize,int pageCount,long downloadCount,Instant createdAt,String errorMessage){}
    Optional<Entry> find(String id,long owner);
    Optional<Entry> request(String key,long owner);
    List<Entry> list(long owner,int page,int pageSize);
    long count(long owner);
    default Map<String, Map<String, Object>> listContext(Set<String> ids,long owner) { return Map.of(); }
    void insert(Entry entry);
    boolean finish(Entry entry);
    boolean incrementDownload(String id,long owner);
}
