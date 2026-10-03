package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 统计和轨迹只读取中心已落库事实，不由worker进度或已保存画布推定成功。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class RunQueryService {
    private static final Set<String> STATES=Set.of("QUEUED","RUNNING","CANCEL_REQUESTED","COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private final RunMapper mapper;
    private final ResourceAccessService access;
    private final WorkflowService workflows;
    public RunQueryService(RunMapper mapper,ResourceAccessService access,WorkflowService workflows){this.mapper=mapper;this.access=access;this.workflows=workflows;}
    private long scope(long spaceId,Long workflowId){long actor=access.requirePermission("paiflow:run:query");access.requireSpace(spaceId,"paiflow:run:query",3,false);if(workflowId!=null)workflows.detail(workflowId,spaceId);return actor;}
    private void current(long actor,long spaceId){if(scope(spaceId,null)!=actor)throw new ServiceException("运行查询主体已变化",403);}
    private static JSONObject view(RunRecord run) {
        RunJsonCodec.require(run);
        return JSONObject.of("runId",run.getId(),"workflowId",run.getWorkflowId().toString(),"workflowVersionId",run.getWorkflowVersionId().toString(),"spaceId",run.getSpaceId().toString(),"ownerUid",IdentityMappingService.legacyUid(run.getOwnerUserId()),"engineProfile",run.getEngineProfile(),"state",run.getState(),"attempt",run.getAttempt(),"lastEventSeq",run.getLastEventSeq(),"revision",run.getRevision(),"createTime",run.getCreateTime(),"updateTime",run.getUpdateTime());
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public JSONObject page(long spaceId,Long workflowId,String state,int page,int size) {
        long actor=scope(spaceId,workflowId);
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE || state!=null && !STATES.contains(state))throw new ServiceException("运行查询分页或状态无效",400);
        var records=mapper.page(spaceId,workflowId,state,(long)(page-1)*size,size).stream().map(RunQueryService::view).toList();long count=mapper.count(spaceId,workflowId,state);current(actor,spaceId);
        return JSONObject.of("records",records,"total",count,"current",page,"size",size,"pages",(count+size-1)/size);
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public JSONObject statistics(long spaceId,Long workflowId) {
        long actor=scope(spaceId,workflowId);var counts=new TreeMap<String,Long>();for(String state:STATES)counts.put(state,0L);long total=0;
        for(var row:mapper.statistics(spaceId,workflowId)){String state=Objects.toString(row.get("state"),"");if(!STATES.contains(state) || !(row.get("total") instanceof Number number))throw new ServiceException("中心运行状态统计记录无效",409);long count=number.longValue();if(count<0)throw new ServiceException("中心运行计数无效",409);counts.put(state,count);total=Math.addExact(total,count);}
        current(actor,spaceId);return JSONObject.of("total",total,"counts",counts);
    }
    @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
    public byte[] export(String runId) {
        long actor=access.requirePermission("paiflow:run:query");var run=mapper.find(runId);if(run==null)throw new ServiceException("运行不存在或不可访问",404);scope(run.getSpaceId(),null);RunJsonCodec.require(run);
        long through=run.getLastEventSeq();if(through>10000)throw new ServiceException("轨迹超过单次导出一万事件预算，请按游标分段读取",413);
        var output=new java.io.ByteArrayOutputStream();long cursor=0;
        while(cursor<through) {
            var rows=mapper.eventsThrough(runId,cursor,through,500);if(rows.isEmpty())throw new ServiceException("中心事件水位缺行，不能导出不完整轨迹",409);
            for(var row:rows) {
                if(row.seq()!=cursor+1)throw new ServiceException("中心事件序号不连续，不能导出不完整轨迹",409);
                JSONObject event;
                try{event=JSON.parseObject(row.eventJson());}catch(RuntimeException error){throw new ServiceException("中心事件JSON损坏",409);}
                if(event==null || !runId.equals(event.getString("runId")) || event.getLongValue("seq")!=row.seq())throw new ServiceException("中心轨迹身份或序号不一致",409);
                RunJsonBoundary.check(event,RunProtocolLimits.EVENT_BYTES);
                byte[] bytes=(row.eventJson()+"\n").getBytes(StandardCharsets.UTF_8);if((long)output.size()+bytes.length>16*1024*1024)throw new ServiceException("轨迹超过单次导出16MiB预算，请按游标分段读取",413);
                output.writeBytes(bytes);cursor=row.seq();
            }
        }
        current(actor,run.getSpaceId());return output.toByteArray();
    }
}
