package com.ruoyi.paicli.mapper;

import com.ruoyi.paicli.domain.*;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/** 所有 SQL 路由限制为 PcTable；列表必须提供 workspace 或 owner 的归属过滤。 */
public interface PcLedgerMapper {
    List<PcRecord> publishedReleasesForUpdate();
    int ensureDefaultSelection(@Param("kind") String kind,@Param("now") Instant now);
    PcRecord find(@Param("table") PcTable table, @Param("id") long id, @Param("lock") boolean lock);
    PcRecord findKey(@Param("table") PcTable table, @Param("key") String key, @Param("lock") boolean lock);
    List<PcRecord> list(@Param("table") PcTable table, @Param("directoryContextId") Long directoryContextId,
                        @Param("ownerId") Long ownerId, @Param("parentId") Long parentId,
                        @Param("state") String state);
    int insert(@Param("table") PcTable table, @Param("row") PcRecord row);
    int update(@Param("table") PcTable table, @Param("row") PcRecord row, @Param("expected") long expected);
    List<PcRecord> events(@Param("directoryContextId") Long directoryContextId, @Param("sessionId") long sessionId,
                         @Param("cursor") long cursor, @Param("limit") int limit, @Param("now") Instant now);
    long minCursor(@Param("directoryContextId") Long directoryContextId, @Param("sessionId") long sessionId,
                   @Param("now") Instant now);
    List<PcRecord> expiredLeases(@Param("now") Instant now, @Param("limit") int limit);
    List<PcRecord> queued(@Param("directoryContextId") Long directoryContextId, @Param("runnerId") long runnerId);
    int activeTasks(@Param("directoryContextId") Long directoryContextId, @Param("sessionId") Long sessionId,
                    @Param("ownerId") Long ownerId, @Param("runnerId") Long runnerId);
    List<PcRecord> visibleWorkspaces(@Param("userId") long userId);
    List<PcRecord> searchTasks(@Param("directoryContextId") Long directoryContextId,@Param("userId") long userId,@Param("state") String state,@Param("search") String search,@Param("requester") String requester);
    List<PcRecord> searchMemories(@Param("directoryContextId") Long directoryContextId,@Param("userId") long userId,@Param("personal") boolean personal,@Param("search") String search);
    List<PcRecord> audits(@Param("directoryContextId") Long directoryContextId,@Param("userId") long userId,@Param("toolFilter") String toolFilter,@Param("decisionFilter") String decisionFilter,@Param("taskId") Long taskId);
    List<PcRecord> messages(@Param("directoryContextId") Long directoryContextId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    List<PcRecord> recentMessages(@Param("directoryContextId") Long directoryContextId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId,@Param("end") long end,@Param("limit") int limit);
    long messageCount(@Param("directoryContextId") Long directoryContextId,@Param("ownerId") long ownerId,@Param("sessionId") long sessionId);
    List<PcRecord> taskEvents(@Param("directoryContextId") Long directoryContextId,@Param("sessionId") long sessionId,@Param("taskId") long taskId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    long sessionRecordEnd(@Param("table") PcTable table,@Param("directoryContextId") Long directoryContextId,@Param("sessionId") long sessionId);
    List<PcRecord> sessionRecords(@Param("table") PcTable table,@Param("directoryContextId") Long directoryContextId,@Param("sessionId") long sessionId,@Param("after") long after,@Param("end") long end,@Param("limit") int limit);
    List<PcRecord> recentSessionRecords(@Param("table") PcTable table,@Param("directoryContextId") Long directoryContextId,@Param("sessionId") long sessionId,@Param("limit") int limit);
    List<PcRecord> visible(@Param("table") PcTable table,@Param("userId") long userId,@Param("directoryContextId") Long directoryContextId,@Param("ownerOnly") boolean ownerOnly);
}
