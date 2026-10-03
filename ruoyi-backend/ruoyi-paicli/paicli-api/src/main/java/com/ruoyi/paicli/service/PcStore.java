package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.mapper.PcLedgerMapper;
import java.time.Clock;
import java.util.*;

/** 共享账本操作均落 MySQL；事务由业务服务包围，乐观版本不能静默覆盖。 */
public final class PcStore {
    public final PcLedgerMapper mapper;
    public final PcJson json;
    public final Clock clock;
    public PcStore(PcLedgerMapper mapper,PcJson json,Clock clock){this.mapper=mapper;this.json=json;this.clock=clock;}
    public PcRecord require(PcTable t,long id,boolean lock){PcRecord r=mapper.find(t,id,lock);if(r==null)throw new PcException(404,"PC_NOT_FOUND","对象不存在或不可访问");return r;}
    public Map<String,Object> data(PcRecord r){return json.read(r.getPayload());}
    public PcRecord insert(PcTable t,Long workspace,Long owner,Long parent,Long session,Long runner,String kind,String state,String key,Map<String,Object> data){
        PcRecord r=new PcRecord();r.setWorkspaceId(workspace);r.setOwnerId(owner);r.setParentId(parent);r.setSessionId(session);r.setRunnerId(runner);
        r.setKind(kind);r.setState(state);r.setUniqueKey(key);r.setPayload(json.write(data));r.setContentHash(json.hash(data));r.setCreatedAt(clock.instant());r.setUpdatedAt(clock.instant());
        mapper.insert(t,r);return r;
    }
    public void save(PcTable t,PcRecord r,Map<String,Object> data){long v=r.getVersion();r.setPayload(json.write(data));r.setContentHash(json.hash(data));r.setUpdatedAt(clock.instant());if(mapper.update(t,r,v)!=1)throw PcException.conflict("对象已变化，请刷新后重试");r.setVersion(v+1);}
    public Map<String,Object> view(PcRecord r){Map<String,Object> v=new LinkedHashMap<>(data(r));for(String key:List.of("permitCipher","permitHash","tokenHash","response","storageKey","_ledger"))v.remove(key);for(String key:List.of("modelId","providerId","taskId","turnId","userId","approvalId"))if(v.get(key)!=null)v.put(key,String.valueOf(v.get(key)));v.put("id",sid(r.getId()));v.put("workspaceId",sid(r.getWorkspaceId()));v.put("ownerId",sid(r.getOwnerId()));v.put("parentId",sid(r.getParentId()));v.put("sessionId",sid(r.getSessionId()));v.put("runnerId",sid(r.getRunnerId()));v.put("kind",r.getKind());v.put("state",r.getState());v.put("status",r.getState());v.put("version",r.getVersion());v.put("createdAt",r.getCreatedAt());v.put("updatedAt",r.getUpdatedAt());v.put("expiresAt",r.getExpiresAt());return v;}
    private String sid(Long id){return id==null?null:String.valueOf(id);}
    public void version(PcRecord r,long expected){if(r.getVersion()!=expected)throw PcException.conflict("版本已失效，请刷新后重新确认");}
}
