package com.ruoyi.aippt.infrastructure.persistence;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.domain.AipptException;
import java.util.*;
import org.springframework.stereotype.Repository;
@Repository @AipptEnabled public class AipptRepository  {
    private static final Set<String> TABLES=Set.of("project","source","source_section","outline","slide","task","proposal","export","asset","event","audit","outbox","idempotency","snapshot");
    private final AipptMapper mapper;
    public AipptRepository(AipptMapper m)  {
        mapper=m;
    }
    private String table(String t)  {
        if(!TABLES.contains(t))throw new IllegalArgumentException("表名不在业务白名单");
        return "aippt_"+t;
    }
    public List<JSONObject> pendingOutbox()  {
        return mapper.pendingOutbox().stream().map(this::unpack).toList();
    }
    public List<JSONObject> pendingDeliveryOutbox() {
        return mapper.pendingDeliveryOutbox().stream().map(this::unpack).toList();
    }
    public void lockDispatch()  {
        mapper.lockDispatch();
    }
    public JSONObject unpack(Map<String,Object> row)  {
        if(row==null)return null;
        JSONObject j=JSON.parseObject(String.valueOf(row.get("body")));
        j.put("revision",((Number)row.get("revision")).longValue());
        j.put("status",row.get("status"));
        return j;
    }
    public JSONObject get(String t,String id)  {
        return unpack(mapper.get(table(t),id));
    }
    public JSONObject lock(String t,String id)  {
        return unpack(mapper.lock(table(t),id));
    }
    public List<JSONObject> children(String t,String p)  {
        return mapper.children(table(t),p).stream().map(this::unpack).toList();
    }
    public void insert(String t,JSONObject j)  {
        mapper.insert(table(t),j.getString("id"),j.getString("project_id"),j.getLongValue("owner_user_id"),j.getString("status"),j.getLongValue("revision"),JSON.toJSONString(j,JSONWriter.Feature.WriteMapNullValue));
    }
    public JSONObject save(String t,JSONObject j,long expected)  {
        long next=expected+1;
        j.put("revision",next);
        if(mapper.update(table(t),j.getString("id"),j.getString("status"),JSON.toJSONString(j,JSONWriter.Feature.WriteMapNullValue),expected,next)!=1)throw AipptException.conflict("数据版本已变化，请保留草稿后重新加载",expected);
        return j;
    }
    public void delete(String t,String id)  {
        mapper.delete(table(t),id);
    }
    public List<JSONObject> projects(long owner,String status,String q,int size,int offset)  {
        return mapper.projects(owner,status,q,size,offset).stream().map(this::unpack).toList();
    }
    public long count(long owner,String status,String q)  {
        return mapper.projectCount(owner,status,q);
    }
    public List<JSONObject> running()  {
        return mapper.running().stream().map(this::unpack).toList();
    }
    public List<JSONObject> agedExports(int days)  {
        return mapper.agedExports(days).stream().map(this::unpack).toList();
    }
    public List<JSONObject> agedSnapshots(int days)  {
        return mapper.agedSnapshots(days).stream().map(this::unpack).toList();
    }
    public List<JSONObject> claimable()  {
        return claimable(null);
    }
    public List<JSONObject> claimable(Set<String> types) {
        if(types != null && types.isEmpty())return List.of();
        return mapper.claimable(types).stream().map(this::unpack).toList();
    }
    public List<JSONObject> expired(String now)  {
        return mapper.expired(now).stream().map(this::unpack).toList();
    }
    public List<JSONObject> events(String p,long seq)  {
        return mapper.events(p,seq).stream().map(this::unpack).toList();
    }
    public JSONObject idempotent(String id)  {
        String s=mapper.idempotent(id);
        return s==null?null:JSON.parseObject(s);
    }
    public JSONObject sample(long owner,String theme)  {
        return unpack(mapper.sample(owner,theme));
    }
    public long advanceSequence(String project)  {
        mapper.advanceSequence(project);
        return mapper.lastSequence(project);
    }
    public long firstSequence(String project)  {
        return mapper.firstSequence(project);
    }
    public int purgeEvents(int days)  {
        return mapper.purgeEvents(days);
    }
    public long lastSequence(String project)  {
        return mapper.lastSequence(project);
    }
}
