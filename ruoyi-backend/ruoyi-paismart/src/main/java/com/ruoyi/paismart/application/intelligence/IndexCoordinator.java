package com.ruoyi.paismart.application.intelligence;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.Map;

/** 解析索引写入和重建/切换共用持久租约，不以内存锁决定索引版本。 */
@Service
@ConditionalOnProperty(name="paismart.enabled",havingValue="true")
public class IndexCoordinator {
    public record Fence(String owner,long revision){}
    private final JdbcTemplate db;private final TransactionTemplate tx;
    public IndexCoordinator(@Qualifier("paismartJdbc")JdbcTemplate db,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx){this.db=db;this.tx=tx;}
    public Fence acquire(String owner){return tx.execute(s->{Map<String,Object> row=db.queryForMap("SELECT * FROM ps_intelligence_lock WHERE id='KNOWLEDGE_INDEX' FOR UPDATE");Object current=row.get("owner");Timestamp until=(Timestamp)row.get("lease_until");if(current!=null&&!owner.equals(current)&&until!=null&&until.toInstant().isAfter(Instant.now()))throw new IllegalStateException("JOB_BUSY:索引正在由另一个任务处理");long revision=((Number)row.get("fence_revision")).longValue()+1;db.update("UPDATE ps_intelligence_lock SET owner=?,fence_revision=?,lease_until=? WHERE id='KNOWLEDGE_INDEX'",owner,revision,Timestamp.from(Instant.now().plusSeconds(600)));return new Fence(owner,revision);});}
    public void heartbeat(Fence fence){if(db.update("UPDATE ps_intelligence_lock SET lease_until=? WHERE id='KNOWLEDGE_INDEX' AND owner=? AND fence_revision=? AND lease_until>=CURRENT_TIMESTAMP",Timestamp.from(Instant.now().plusSeconds(600)),fence.owner(),fence.revision())!=1)throw new IllegalStateException("索引fencing租约失效");}
    public void verify(Fence fence){Long count=db.queryForObject("SELECT COUNT(*) FROM ps_intelligence_lock WHERE id='KNOWLEDGE_INDEX' AND owner=? AND fence_revision=? AND lease_until>=CURRENT_TIMESTAMP",Long.class,fence.owner(),fence.revision());if(count!=1)throw new IllegalStateException("索引fencing租约失效");}
    public void verifyOwner(String owner){Long count=db.queryForObject("SELECT COUNT(*) FROM ps_intelligence_lock WHERE id='KNOWLEDGE_INDEX' AND owner=? AND lease_until>=CURRENT_TIMESTAMP",Long.class,owner);if(count!=1)throw new IllegalStateException("索引任务租约失效");}
    public void renewOwner(String owner){if(db.update("UPDATE ps_intelligence_lock SET lease_until=? WHERE id='KNOWLEDGE_INDEX' AND owner=? AND lease_until>=CURRENT_TIMESTAMP",Timestamp.from(Instant.now().plusSeconds(600)),owner)!=1)throw new IllegalStateException("索引任务租约失效");}
    public void release(Fence fence){db.update("UPDATE ps_intelligence_lock SET owner=NULL,lease_until=NULL WHERE id='KNOWLEDGE_INDEX' AND owner=? AND fence_revision=?",fence.owner(),fence.revision());}

    /** 首次索引与重建/回滚复用同一持久切换，别名副作用不跨数据库事务假装原子。 */
    public void activate(String id,Runnable guard,ProviderService providers,SearchService search){
        guard.run();Map<String,Object> target=db.queryForMap("SELECT * FROM ps_index_revision WHERE id=?",id);
        var active=db.queryForList("SELECT * FROM ps_index_revision WHERE state='ACTIVE'");if(active.size()>1)throw new IllegalStateException("索引状态不唯一");
        Map<String,Object> old=active.isEmpty()?null:active.get(0);if(old!=null&&id.equals(old.get("id")))return;
        if(db.queryForObject("SELECT COUNT(*) FROM ps_index_switch WHERE state IN ('PREPARED','ALIAS_CHANGED','RECOVERY_REQUIRED')",Long.class)>0)throw new IllegalStateException("索引切换待恢复，拒绝新增切换");
        var provider=providers.fromSnapshot(com.ruoyi.paismart.infrastructure.intelligence.Values.text(target.get("provider_snapshot")));
        providers.secret(provider);if(providers.get(provider.id()).revision()!=provider.revision())throw new IllegalStateException("INDEX_MODEL_MISMATCH:目标供应商版本已变化");
        String transition=com.ruoyi.paismart.infrastructure.intelligence.Values.id();
        tx.executeWithoutResult(s->{guard.run();db.update("INSERT INTO ps_index_switch(id,from_revision,to_revision,state,created_at,updated_at) VALUES(?,?,?,'PREPARED',?,?)",transition,old==null?null:old.get("id"),id,LocalDateTime.now(),LocalDateTime.now());});
        java.util.List<Object> actions=new java.util.ArrayList<>();if(old!=null)actions.add(Map.of("remove",Map.of("index",old.get("index_name"),"alias","paismart-knowledge")));actions.add(Map.of("add",Map.of("index",target.get("index_name"),"alias","paismart-knowledge")));
        try{guard.run();search.esRequest("POST","/_aliases",Map.of("actions",actions));guard.run();Map<String,Object> aliases=search.esRequest("GET","/_alias/paismart-knowledge",null);guard.run();if(aliases.size()!=1||!aliases.containsKey(String.valueOf(target.get("index_name"))))throw new IllegalStateException("索引别名回读不一致");db.update("UPDATE ps_index_switch SET state='ALIAS_CHANGED',updated_at=? WHERE id=?",LocalDateTime.now(),transition);finalizeActivation(transition,id,old==null?null:String.valueOf(old.get("id")),guard,providers);}
        catch(RuntimeException failure){db.update("UPDATE ps_index_switch SET state='RECOVERY_REQUIRED',error_message=?,updated_at=? WHERE id=? AND state<>'COMPLETED'",com.ruoyi.paismart.infrastructure.intelligence.Values.safeError(failure),LocalDateTime.now(),transition);throw new IllegalStateException("索引切换需要恢复，查询已阻断",failure);}
    }
    public void finalizeActivation(String transition,String targetId,String oldId,Runnable guard,ProviderService providers){
        guard.run();tx.executeWithoutResult(s->{guard.run();
            Map<String,Object> target=db.queryForMap("SELECT * FROM ps_index_revision WHERE id=? FOR UPDATE",targetId);var provider=providers.fromSnapshot(String.valueOf(target.get("provider_snapshot")));
            providers.secret(provider);if(providers.get(provider.id()).revision()!=provider.revision())throw new IllegalStateException("INDEX_MODEL_MISMATCH:供应商版本已变化");
            if(oldId!=null)db.update("UPDATE ps_index_revision SET state='RETIRED',row_version=row_version+1,updated_at=? WHERE id=?",LocalDateTime.now(),oldId);
            db.update("UPDATE ps_index_revision SET state='ACTIVE',source_watermark=COALESCE(source_watermark,?),row_version=row_version+1,updated_at=? WHERE id=?",LocalDateTime.now(),LocalDateTime.now(),targetId);
            db.update("UPDATE ps_provider SET active=FALSE WHERE scope='EMBEDDING'");
            if(db.update("UPDATE ps_provider SET active=TRUE WHERE id=? AND revision=?",provider.id(),provider.revision())!=1)throw new IllegalStateException("目标供应商配置版本已经变化");
            if(db.update("UPDATE ps_index_switch SET state='COMPLETED',updated_at=? WHERE id=? AND to_revision=? AND state IN ('PREPARED','ALIAS_CHANGED','RECOVERY_REQUIRED')",LocalDateTime.now(),transition,targetId)!=1)throw new IllegalStateException("切换事实已变化");
        });
    }
}
