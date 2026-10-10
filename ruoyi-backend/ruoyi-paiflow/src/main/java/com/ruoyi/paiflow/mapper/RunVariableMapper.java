package com.ruoyi.paiflow.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RunVariableMapper {
    Map<String,Object> find(@Param("scope") String scope);
    Map<String,Object> lock(@Param("scope") String scope);
    int initialize(@Param("scope") String scope);
    int update(@Param("scope") String scope,@Param("revision") long revision,@Param("json") String json);
    Map<String,Object> request(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key);
    int record(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key,
               @Param("hash") String hash,@Param("scope") String scope,@Param("revision") long revision);
    Map<String,Object> snapshot(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,@Param("node") String node,@Param("key") String key);
    java.util.List<Map<String,Object>> snapshots(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,@Param("node") String node);
    int recordSnapshot(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,@Param("node") String node,@Param("key") String key,@Param("operation") String operation,@Param("scope") String scope,@Param("nodeHash") String nodeHash,@Param("requestJson") String requestJson,@Param("responseJson") String responseJson,@Param("responseHash") String responseHash);
}
