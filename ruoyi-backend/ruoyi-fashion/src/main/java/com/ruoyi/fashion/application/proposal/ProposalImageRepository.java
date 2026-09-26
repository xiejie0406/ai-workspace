package com.ruoyi.fashion.application.proposal;
import java.util.*;
public interface ProposalImageRepository {
    Optional<ProposalImageTask> find(String id,long owner);
    Optional<ProposalImageTask> request(String key,long owner);
    List<ProposalImageTask> list(String poolId,long owner);
    void insert(ProposalImageTask task);
    boolean update(ProposalImageTask task,long expectedVersion);
}
