package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import java.util.*;

/** 追加工具审计和会话事件；session行锁保证序号递增，与业务状态在同一事务提交。 */
public final class PcEvidence {
    private final PcStore store;private final int retentionDays;
    public PcEvidence(PcStore store,int retentionDays){this.store=store;this.retentionDays=retentionDays;}
    public void audit(Long workspace,Long user,Long task,Long session,String action,String decision,Map<String,Object> details){
        Map<String,Object> p=new LinkedHashMap<>();p.put("action",action);p.put("decision",decision);p.put("details",details);p.put("evidenceComplete",true);p.put("occurredAt",store.clock.instant().toString());
        store.insert(PcTable.SECURITY_AUDIT,workspace,user,task,session,null,"AUDIT","APPENDED",null,p);
    }
    public Map<String,Object> event(PcRecord session,Long task,String key,String type,Map<String,Object> payload){
        PcRecord previous=key==null?null:store.mapper.findKey(PcTable.EVENT,key,false);if(previous!=null)return eventView(previous);
        PcRecord locked=store.require(PcTable.SESSION,session.getId(),true);locked.setSequenceNumber(locked.getSequenceNumber()+1);store.save(PcTable.SESSION,locked,store.data(locked));
        PcRecord row=store.insert(PcTable.EVENT,session.getWorkspaceId(),session.getOwnerId(),task,session.getId(),null,type,"APPENDED",key,Map.of("type",type,"payload",payload));
        row.setSequenceNumber(locked.getSequenceNumber());row.setExpiresAt(store.clock.instant().plusSeconds(86400L*retentionDays));store.save(PcTable.EVENT,row,store.data(row));return eventView(row);
    }
    public Map<String,Object> eventView(PcRecord r){Map<String,Object> payload=new LinkedHashMap<>(PcJson.object(store.data(r),"payload"));if(r.getParentId()!=null)payload.put("taskId",String.valueOf(r.getParentId()));Map<String,Object> view=new LinkedHashMap<>();view.put("eventId",String.valueOf(r.getId()));view.put("sequence",r.getSequenceNumber());view.put("type",r.getKind());view.put("payload",payload);view.put("occurredAt",r.getCreatedAt().toString());if(r.getParentId()!=null)view.put("taskId",String.valueOf(r.getParentId()));return view;}
}
