package com.ruoyi.paiflow.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 历史作用域由中心Run关联生成，不接受Worker声明UID、空间或会话。 */
public interface RunHistoryMapper {
    Map<String,Object> pythonSnapshot(@Param("run") String run,@Param("attempt") int attempt);
    int freezePython(@Param("ref") String ref,@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,
             @Param("snapshot") String snapshot,@Param("hash") String hash);
    Map<String,Object> pythonEvidence(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key);
    Map<String,Object> pythonEvidenceByOperation(@Param("operation") String operation);
    int recordPythonEvidence(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key,
             @Param("operation") String operation,@Param("evidence") String evidence,@Param("hash") String hash);
    List<Map<String,Object>> pythonRecent(@Param("space") long space,@Param("workflow") long workflow,
             @Param("owner") long owner,@Param("app") String app,@Param("chat") String chat,@Param("nodes") List<String> nodes);
    Map<String,Object> pythonItem(@Param("id") long id);
    Map<String,Object> pythonRunCost(@Param("run") String run);
    Map<String,Object> pythonOperationCost(@Param("operation") String operation);
    List<String> pythonOperations(@Param("run") String run);
    Map<String,Object> pythonToolCost(@Param("run") String run);
    Map<String,Object> pythonDataCost(@Param("run") String run);
    Map<String,Object> pythonVariableCost(@Param("run") String run);
    Map<String,Object> context(@Param("run") String run);
    int bind(@Param("run") String run,@Param("space") long space,@Param("workflow") long workflow,
             @Param("owner") long owner,@Param("flow") String flow,@Param("app") String app,@Param("chat") String chat);
    Map<String,Object> byRequest(@Param("run") String run,@Param("attempt") int attempt,@Param("key") String key);
    int append(@Param("run") String run,@Param("attempt") int attempt,@Param("node") String node,
               @Param("key") String key,@Param("hash") String hash,@Param("question") String question,@Param("answer") String answer);
    List<Map<String,Object>> recent(@Param("space") long space,@Param("workflow") long workflow,
              @Param("owner") long owner,@Param("app") String app,@Param("chat") String chat,@Param("nodes") List<String> nodes);
    Map<String,Object> javaRound(@Param("run") String run,@Param("attempt") int attempt,@Param("node") String node);
    List<Map<String,Object>> javaRecent(@Param("space") long space,@Param("workflow") long workflow,
              @Param("owner") long owner,@Param("app") String app,@Param("chat") String chat,@Param("node") String node);
    int openJava(@Param("run") String run,@Param("lease") String lease,@Param("attempt") int attempt,
              @Param("node") String node,@Param("version") long version,@Param("dslHash") String dslHash,
              @Param("inputHash") String inputHash,@Param("snapshot") String snapshot,@Param("snapshotHash") String snapshotHash,
              @Param("item") String item,@Param("itemHash") String itemHash);
    int completeJava(@Param("run") String run,@Param("attempt") int attempt,@Param("node") String node,
              @Param("revision") long revision,@Param("operation") String operation,@Param("item") String item,@Param("itemHash") String itemHash);
}
