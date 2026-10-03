package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.WorkflowRecord;
import com.ruoyi.paiflow.domain.WorkflowVersionRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 所有正式写入经中心事务；SQL使用绑定参数和资源空间条件。 */
public interface WorkflowMapper {
    WorkflowRecord find(@Param("id") long id);
    WorkflowRecord lock(@Param("id") long id);
    WorkflowRecord findByFlowId(@Param("flowId") String flowId);
    List<WorkflowRecord> list(@Param("spaceId") long spaceId,@Param("search") String search,@Param("flowId") String flowId,@Param("order") int order,@Param("offset") long offset,@Param("limit") int limit);
    long count(@Param("spaceId") long spaceId,@Param("search") String search,@Param("flowId") String flowId);
    long countName(@Param("spaceId") long spaceId,@Param("name") String name,@Param("excludeId") Long excludeId);
    int insert(WorkflowRecord workflow);
    int update(WorkflowRecord workflow);
    int delete(@Param("id") long id,@Param("spaceId") long spaceId,@Param("revision") long revision,@Param("actor") String actor);
    int insertVersion(WorkflowVersionRecord version);
    WorkflowVersionRecord version(@Param("id") long id);
    WorkflowVersionRecord debugVersion(@Param("owner") long owner,@Param("key") String key);
    List<WorkflowVersionRecord> versions(@Param("workflowId") long workflowId,@Param("offset") long offset,@Param("limit") int limit);
    long countVersions(@Param("workflowId") long workflowId);
    WorkflowVersionRecord latestVersion(@Param("workflowId") long workflowId);
    WorkflowVersionRecord versionByName(@Param("workflowId") long workflowId,@Param("name") String name);
    int selectVersion(@Param("workflowId") long workflowId,@Param("spaceId") long spaceId,@Param("versionId") long versionId,@Param("revision") long revision,@Param("actor") String actor);
}
