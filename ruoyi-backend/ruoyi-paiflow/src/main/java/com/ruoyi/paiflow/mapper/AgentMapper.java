package com.ruoyi.paiflow.mapper;

import com.ruoyi.paiflow.domain.AgentRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 智能体查询与所有写入使用同域条件、参数绑定及revision比较。 */
public interface AgentMapper {
    AgentRecord find(@Param("id") long id);
    AgentRecord lock(@Param("id") long id);
    AgentRecord byWorkflow(@Param("workflowId") long workflowId);
    long count(@Param("spaceId") long spaceId,@Param("search") String search,@Param("version") Integer version);
    List<AgentRecord> list(@Param("spaceId") long spaceId,@Param("search") String search,@Param("version") Integer version,@Param("sort") String sort,@Param("offset") long offset,@Param("limit") int limit);
    long countName(@Param("spaceId") long spaceId,@Param("name") String name,@Param("excludeId") Long excludeId);
    long countOwner(@Param("spaceId") long spaceId,@Param("ownerUserId") long ownerUserId);
    long countAllOwner(@Param("ownerUserId") long ownerUserId);
    int lockOwnerGuard(@Param("ownerUserId") long ownerUserId);
    int insert(AgentRecord agent);
    int update(AgentRecord agent);
    int delete(@Param("id") long id,@Param("spaceId") long spaceId,@Param("revision") long revision,@Param("actor") String actor);
}
