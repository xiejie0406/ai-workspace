package com.ruoyi.aippt.infrastructure.persistence;
import java.util.*;
import org.apache.ibatis.annotations.*;
/** table 参数仅由 Repository 固定白名单构造，绝不来自 HTTP。 */
public interface AipptMapper  {
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_export WHERE status<>'deleted' AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL #{days} DAY) LIMIT 100") List<Map<String,Object>> agedExports(@Param("days")int days);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_snapshot WHERE status<>'deleted' AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL #{days} DAY) LIMIT 100") List<Map<String,Object>> agedSnapshots(@Param("days")int days);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_outbox WHERE status='pending' AND JSON_UNQUOTE(JSON_EXTRACT(body,'$.type')) IN ('asset_cleanup','asset_orphan','asset_stage_cleanup') ORDER BY created_at LIMIT 100") List<Map<String,Object>> pendingOutbox();
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_outbox WHERE status='pending' AND JSON_UNQUOTE(JSON_EXTRACT(body,'$.type')) IN ('task_created','event_ready') ORDER BY created_at,id LIMIT 20") List<Map<String,Object>> pendingDeliveryOutbox();
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM ${table} WHERE id=#{id}") Map<String,Object> get(@Param("table")String table,@Param("id")String id);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM ${table} WHERE id=#{id} FOR UPDATE") Map<String,Object> lock(@Param("table")String table,@Param("id")String id);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM ${table} WHERE project_id=#{project} ORDER BY created_at,id") List<Map<String,Object>> children(@Param("table")String table,@Param("project")String project);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_project WHERE owner_user_id=#{owner} AND status NOT IN ('deleted','sample') AND (#{status}='' OR status=#{status}) AND (#{q}='' OR JSON_UNQUOTE(JSON_EXTRACT(body,'$.title')) LIKE CONCAT('%',#{q},'%')) ORDER BY updated_at DESC,id LIMIT #{limit} OFFSET #{offset}") List<Map<String,Object>> projects(@Param("owner")long owner,@Param("status")String status,@Param("q")String q,@Param("limit")int limit,@Param("offset")int offset);
    @Select("SELECT count(*) FROM aippt_project WHERE owner_user_id=#{owner} AND status NOT IN ('deleted','sample') AND (#{status}='' OR status=#{status}) AND (#{q}='' OR JSON_UNQUOTE(JSON_EXTRACT(body,'$.title')) LIKE CONCAT('%',#{q},'%'))") long projectCount(@Param("owner")long owner,@Param("status")String status,@Param("q")String q);
    @Insert("INSERT INTO ${table}(id,project_id,owner_user_id,status,revision,body,created_at,updated_at) VALUES(#{id},#{project},#{owner},#{status},#{revision},#{body},UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))") int insert(@Param("table")String table,@Param("id")String id,@Param("project")String project,@Param("owner")long owner,@Param("status")String status,@Param("revision")long revision,@Param("body")String body);
    @Update("UPDATE ${table} SET body=#{body},status=#{status},revision=#{next},updated_at=UTC_TIMESTAMP(3) WHERE id=#{id} AND revision=#{expected}") int update(@Param("table")String table,@Param("id")String id,@Param("status")String status,@Param("body")String body,@Param("expected")long expected,@Param("next")long next);
    @Delete("DELETE FROM ${table} WHERE id=#{id}") int delete(@Param("table")String table,@Param("id")String id);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_task WHERE status='running' ORDER BY created_at") List<Map<String,Object>> running();
    @Select({"<script>",
            "SELECT body,revision,status,owner_user_id,project_id FROM aippt_task WHERE status='queued'",
            "<if test='types != null'>",
            "AND JSON_UNQUOTE(JSON_EXTRACT(body,'$.type')) IN",
            "<foreach collection='types' item='type' open='(' separator=',' close=')'>#{type}</foreach>",
            "</if>",
            "ORDER BY created_at,id LIMIT 100",
            "</script>"})
    List<Map<String,Object>> claimable(@Param("types") Set<String> types);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_task WHERE (status='queued' AND JSON_UNQUOTE(JSON_EXTRACT(body,'$.deadline')) < #{now}) OR (status='running' AND (JSON_UNQUOTE(JSON_EXTRACT(body,'$.lease_until')) < #{now} OR JSON_UNQUOTE(JSON_EXTRACT(body,'$.deadline')) < #{now}))") List<Map<String,Object>> expired(@Param("now")String now);
    @Select("SELECT id FROM aippt_dispatch_gate WHERE id=1 FOR UPDATE") int lockDispatch();
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_project WHERE owner_user_id=#{owner} AND status='sample' AND JSON_UNQUOTE(JSON_EXTRACT(body,'$.theme_id'))=#{theme} LIMIT 1") Map<String,Object> sample(@Param("owner")long owner,@Param("theme")String theme);
    @Select("SELECT event_seq FROM aippt_project WHERE id=#{project}") long lastSequence(@Param("project")String project);
    @Select("SELECT body,revision,status,owner_user_id,project_id FROM aippt_event WHERE project_id=#{project} AND revision>#{seq} ORDER BY revision LIMIT 500") List<Map<String,Object>> events(@Param("project")String project,@Param("seq")long seq);
    @Update("UPDATE aippt_project SET event_seq=event_seq+1 WHERE id=#{project}") int advanceSequence(@Param("project")String project);
    @Select("SELECT COALESCE(MIN(revision),0) FROM aippt_event WHERE project_id=#{project}") long firstSequence(@Param("project")String project);
    @Delete("DELETE FROM aippt_event WHERE created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL #{days} DAY) LIMIT 500") int purgeEvents(@Param("days")int days);
    @Select("SELECT body FROM aippt_idempotency WHERE id=#{id}") String idempotent(@Param("id")String id);
}
