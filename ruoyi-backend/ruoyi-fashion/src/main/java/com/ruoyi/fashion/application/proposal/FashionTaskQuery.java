package com.ruoyi.fashion.application.proposal;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.fashion.configuration.FashionModuleEnabled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 只读任务投影；各来源继续持有自己的任务真相与权限。 */
@Service @FashionModuleEnabled
public class FashionTaskQuery {
    private static final List<String> PROJECTION_COLUMNS=List.of("id","kind","taskType","relatedName","poolId","combinationId","status","reviewStatus","adopted","createdAt","finishedAt","billingStatus","actualCost","currency","errorMessage","detailPath","productId","ownerId","ownerName","sourceId","submittedAt","providerCode","model","estimatedCost");
    private static final Set<String> TEXT_COLUMNS=Set.of("id","kind","taskType","relatedName","poolId","combinationId","status","reviewStatus","billingStatus","currency","errorMessage","detailPath","productId","ownerId","ownerName","sourceId","providerCode","model");
    private final NamedParameterJdbcTemplate jdbc;
    public FashionTaskQuery(@Qualifier("fashionJdbcTemplate") NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    public Map<String,Object> list(long owner,boolean canReadRuns,boolean canReadTryOn,int page,int pageSize){
        if(page<1||pageSize<1||pageSize>100)throw new ServiceException("任务分页参数无效");
        List<String> arms=new ArrayList<>();
        if(canReadRuns)arms.add("""
            select cast(r.id as char) id,'ai_run' kind,coalesce(r.output_type,r.trigger_type) taskType,
                c.title relatedName,null poolId,null combinationId,r.status status,r.apply_status reviewStatus,
                case when r.apply_status='applied' then 1 else 0 end adopted,
                r.create_time createdAt,r.finished_at finishedAt,
                case when r.total_cost is null then 'unknown' else 'settled' end billingStatus,
                r.total_cost actualCost,r.cost_currency currency,r.error_message errorMessage,
                concat('/fashion/ai/runs/',r.id) detailPath,
                json_unquote(json_extract(r.context_snapshot_json,'$.product_id')) productId,
                cast(r.create_by as char) ownerId,coalesce(nullif(u.nick_name,''),u.user_name) ownerName,
                cast(r.conversation_id as char) sourceId,r.started_at submittedAt,
                null providerCode,null model,null estimatedCost
            from fq_ai_run r join fq_ai_conversation c on c.id=r.conversation_id
            left join sys_user u on u.user_id=r.create_by
            where c.owner_user_id=:owner
            """+(canReadTryOn?"":" and r.quote_id is null"));
        if(canReadTryOn)arms.add("""
            select t.id id,'try_on' kind,'model_try_on' taskType,p.title relatedName,t.pool_id poolId,t.combo_id combinationId,
                t.status status,t.status reviewStatus,case when t.status='adopted' then 1 else 0 end adopted,
                t.create_time createdAt,t.finished_at finishedAt,t.billing_status billingStatus,t.actual_cost actualCost,
                t.cost_currency currency,t.error_message errorMessage,
                concat('/fashion/pools/',t.pool_id,'/proposal/images/',t.id) detailPath,null productId,
                cast(t.owner_id as char) ownerId,coalesce(nullif(u.nick_name,''),u.user_name) ownerName,
                t.pool_id sourceId,t.submitted_at submittedAt,t.provider_code providerCode,t.model model,t.estimated_cost estimatedCost
            from fq_proposal_image_task t join fq_selection_pool p on p.id=t.pool_id
            left join sys_user u on u.user_id=t.owner_id
            where t.owner_id=:owner and p.owner_id=:owner
            """);
        if(arms.isEmpty())return Map.of("items",List.of(),"total",0,"page",page,"pageSize",pageSize);
        String union=String.join(" union all ",arms.stream().map(FashionTaskQuery::normalizeTextProjection).toList());Map<String,Object> params=new HashMap<>();params.put("owner",owner);params.put("offset",(long)(page-1)*pageSize);params.put("limit",pageSize);
        Long total=jdbc.queryForObject("select count(*) from ("+union+") tasks",params,Long.class);
        var rows=jdbc.queryForList("select * from ("+union+") tasks order by createdAt desc,id desc limit :limit offset :offset",params);
        for(var row:rows){for(String key:List.of("createdAt","finishedAt","submittedAt")){Object value=row.get(key);if(value instanceof java.sql.Timestamp t)row.put(key,t.toInstant().toString());}row.put("adopted",row.get("adopted") instanceof Number n&&n.intValue()==1);}
        return Map.of("items",rows,"total",total==null?0:total,"page",page,"pageSize",pageSize);
    }
    private static String normalizeTextProjection(String source){
        // 原表与增量表可能采用不同 utf8mb4 排序规则；只统一只读投影，保留原表和权限条件。
        String columns=String.join(",",PROJECTION_COLUMNS.stream().map(column->TEXT_COLUMNS.contains(column)?"cast(source."+column+" as char character set utf8mb4) collate utf8mb4_unicode_ci "+column:"source."+column+" "+column).toList());
        return "select "+columns+" from ("+source+") source";
    }
}
