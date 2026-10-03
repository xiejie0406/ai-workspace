package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.ModelOperationRecord;
import com.ruoyi.paiflow.domain.ModelFrameRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ModelOperationMapper {
    ModelOperationRecord find(@Param("id") String id);
    ModelOperationRecord lock(@Param("id") String id);
    ModelOperationRecord byRequest(@Param("runId") String runId,@Param("attempt") int attempt,@Param("requestKeyHash") String requestKeyHash);
    int insert(ModelOperationRecord row);
    int update(ModelOperationRecord row);
    ModelFrameRecord frame(@Param("operationId") String id,@Param("seq") long seq);
    List<ModelFrameRecord> frames(@Param("operationId") String id,@Param("after") long after,@Param("limit") int limit);
    int insertFrame(@Param("operationId") String id,@Param("seq") long seq,@Param("frameJson") String json,@Param("frameHash") String hash,@Param("frameBytes") int bytes);
}
