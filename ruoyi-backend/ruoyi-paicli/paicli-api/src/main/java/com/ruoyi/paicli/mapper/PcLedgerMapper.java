package com.ruoyi.paicli.mapper;

import com.ruoyi.paicli.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/** 所有 SQL 路由限制为 PcTable；列表必须提供 workspace 或 owner 的归属过滤。 */
public interface PcLedgerMapper {
    PcRecord find(@Param("table") PcTable table, @Param("id") long id, @Param("lock") boolean lock);
    PcRecord findKey(@Param("table") PcTable table, @Param("key") String key, @Param("lock") boolean lock);
    List<PcRecord> list(@Param("table") PcTable table, @Param("workspaceId") Long workspaceId,
                        @Param("ownerId") Long ownerId, @Param("parentId") Long parentId,
                        @Param("state") String state);
    int insert(@Param("table") PcTable table, @Param("row") PcRecord row);
    int update(@Param("table") PcTable table, @Param("row") PcRecord row, @Param("expected") long expected);
    List<PcRecord> events(@Param("workspaceId") long workspaceId, @Param("sessionId") long sessionId,
                         @Param("cursor") long cursor, @Param("limit") int limit, @Param("now") Instant now);
    long minCursor(@Param("workspaceId") long workspaceId, @Param("sessionId") long sessionId,
                   @Param("now") Instant now);
    List<PcRecord> expiredLeases(@Param("now") Instant now, @Param("limit") int limit);
    List<PcRecord> queued(@Param("workspaceId") long workspaceId, @Param("runnerId") long runnerId);
    int activeTasks(@Param("workspaceId") Long workspaceId, @Param("sessionId") Long sessionId,
                    @Param("ownerId") Long ownerId, @Param("runnerId") Long runnerId);
    List<PcRecord> visibleWorkspaces(@Param("userId") long userId);
    List<PcRecord> searchTasks(@Param("workspaceId") long workspaceId,@Param("userId") long userId,@Param("state") String state,@Param("search") String search,@Param("requester") String requester);
    List<PcRecord> searchMemories(@Param("workspaceId") Long workspaceId,@Param("userId") long userId,@Param("personal") boolean personal,@Param("search") String search);
    List<PcRecord> audits(@Param("workspaceId") long workspaceId,@Param("userId") long userId,@Param("toolFilter") String toolFilter,@Param("decisionFilter") String decisionFilter,@Param("taskId") Long taskId);
    List<PcRecord> messages(@Param("workspaceId") long workspaceId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    List<PcRecord> recentMessages(@Param("workspaceId") long workspaceId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId,@Param("end") long end,@Param("limit") int limit);
    long messageCount(@Param("workspaceId") long workspaceId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId);
    List<PcRecord> taskEvents(@Param("workspaceId") long workspaceId,@Param("sessionId") long sessionId,@Param("taskId") long taskId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    long sessionRecordEnd(@Param("table") PcTable table,@Param("workspaceId") long workspaceId,@Param("sessionId") long sessionId);
    List<PcRecord> sessionRecords(@Param("table") PcTable table,@Param("workspaceId") long workspaceId,@Param("sessionId") long sessionId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    List<PcRecord> recentSessionRecords(@Param("table") PcTable table,@Param("workspaceId") long workspaceId,@Param("sessionId") long sessionId,@Param("limit") int limit);
    List<PcRecord> visible(@Param("table") PcTable table,@Param("userId") long userId,@Param("workspaceId") Long workspaceId,@Param("ownerOnly") boolean ownerOnly);
}
