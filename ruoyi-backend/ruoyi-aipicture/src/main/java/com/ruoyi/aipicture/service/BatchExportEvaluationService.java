package com.ruoyi.aipicture.service;

import java.io.IOException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;

@Service @AiPictureEnabled
public class BatchExportEvaluationService {
    private static final Set<String> BATCH_TOOLS=Set.of("remove_background","replace_background","adjust_image","upscale_image","expand_canvas","prepare_delivery_sizes");
    private final AiPictureRepository db;private final RunService runs;private final AssetService assets;private final ToolRegistry registry;private final AiPictureStorage storage;private final TransactionTemplate tx;private final CommandIdempotency commands;
    public BatchExportEvaluationService(AiPictureRepository db,RunService runs,AssetService assets,ToolRegistry registry,AiPictureStorage storage,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx,CommandIdempotency commands) {this.db=db;this.runs=runs;this.assets=assets;this.registry=registry;this.storage=storage;this.tx=tx;this.commands=commands;}
    public Map<String,Object> createBatch(long owner,Map<String,Object> request) {
        return createBatch(owner,null,request);
    }
    public Map<String,Object> createBatch(long owner,String session,Map<String,Object> request) {
        registry.versions(request);var params=parameters(request);var checked=registry.validate("batch_process",params);
        var ids=Json.list(checked.get("asset_ids")).stream().map(Json::uuid).distinct().toList();if(ids.size()!=Json.list(checked.get("asset_ids")).size()) throw AiPictureException.invalid("批量输入不能重复");
        for(String id:ids) assets.record(owner,id);var operations=Json.list(checked.get("operations"));
        for(Object object:operations) {var op=Json.map(object);String name=Json.string(op.get("tool"));if(!BATCH_TOOLS.contains(name)) throw AiPictureException.invalid("批量只允许六个像素工具");op.put("params",registry.validate(name,Json.map(op.get("params"))));}
        return tx.execute(status->{
            var prior=db.one("SELECT id FROM aip_run WHERE user_id=:owner AND idempotency_key=:key",Json.of("owner",owner,"key",Json.idem(request)));
            if(prior!=null) {runs.submitBatchRun(owner,session,checked,request);return batch(owner,Json.string(prior.get("id")));}
            var parent=runs.submitBatchRun(owner,session,checked,request);String id=Json.string(parent.get("id"));long now=System.currentTimeMillis();
            // 母任务只汇总逐项任务，不能被 Worker 当作整批重复领取。
            db.execute("DELETE FROM aip_dispatch WHERE run_id=:id",Json.of("id",id));db.execute("UPDATE aip_run SET status='running',started_at=:now WHERE id=:id",Json.of("id",id,"now",now));
            db.execute("INSERT INTO aip_batch(id,user_id,run_id,status,pipeline,pipeline_hash,formats,created_at,updated_at) VALUES(:id,:owner,:id,'running',:pipeline,:hash,:formats,:now,:now)",Json.of("id",id,"owner",owner,"pipeline",Json.write(operations),"hash",RunService.hash(operations),"formats",Json.write(checked.get("formats")),"now",now));
            int position=0;for(String asset:ids) {
                String item=Json.id();var input=Json.of("asset_ids",List.of(asset),"operations",operations,"formats",checked.get("formats"));
                var metadata=Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey","batch:"+id+":"+item+":0");var run=runs.submitBatchRun(owner,null,input,metadata);
                db.execute("INSERT INTO aip_batch_item(id,batch_id,user_id,position,source_asset_id,run_id,status,output_ids) VALUES(:item,:batch,:owner,:position,:asset,:run,'queued','[]')",Json.of("item",item,"batch",id,"owner",owner,"position",position++,"asset",asset,"run",run.get("id")));
                db.execute("UPDATE aip_run SET batch_id=:batch,batch_item_id=:item WHERE id=:run",Json.of("batch",id,"item",item,"run",run.get("id")));
            }
            return batch(owner,id);
        });
    }
    public Map<String,Object> batch(long owner,String id) {
        var batch=db.owned("aip_batch",id,owner,false);batch.remove("user_id");batch.put("run",runs.get(owner,id));
        var items=db.query("SELECT * FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner ORDER BY position",Json.of("id",id,"owner",owner));
        for(var item:items) {item.remove("user_id");item.put("source",assets.get(owner,Json.string(item.get("source_asset_id"))));item.put("outputs",Json.list(item.get("output_ids")).stream().map(asset->assets.get(owner,Json.uuid(asset))).toList());item.put("error",item.get("message"));}
        batch.put("items",items);return batch;
    }
    public List<Map<String,Object>> batches(long owner,int offset,int limit) {
        return db.query("SELECT id FROM aip_batch WHERE user_id=:owner ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset",Json.of("owner",owner,"limit",limit,"offset",offset)).stream().map(r->batch(owner,Json.string(r.get("id")))).toList();
    }
    public long batchCount(long owner) {return db.count("SELECT COUNT(*) FROM aip_batch WHERE user_id=:owner",Json.of("owner",owner));}
    public Map<String,Object> cancelBatch(long owner,String id) {
        db.owned("aip_batch",id,owner,false);runs.cancel(owner,id);return batch(owner,id);
    }
    public Map<String,Object> retryItem(long owner,String id,String itemId,Map<String,Object> request) {
        registry.versions(request);return commands.execute(owner,"batch:retry:"+id+":"+itemId,request,()->tx.execute(status->{
            var batch=db.owned("aip_batch",id,owner,true);var item=db.one("SELECT * FROM aip_batch_item WHERE id=:item AND batch_id=:batch AND user_id=:owner FOR UPDATE",Json.of("item",Json.uuid(itemId),"batch",id,"owner",owner));if(item==null) throw AiPictureException.missing();
            if(!"failed".equals(item.get("status"))) throw new AiPictureException(409,"BATCH_ITEM_STATE","仅失败项可重试，成功项保持原结果");
            long attempt=Json.number(item.get("attempt"))+1;var metadata=Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey","batch:"+id+":"+itemId+":"+attempt);
            var run=runs.submitBatchRun(owner,null,Json.of("asset_ids",List.of(item.get("source_asset_id")),"operations",batch.get("pipeline"),"formats",batch.get("formats")),metadata);
            db.execute("UPDATE aip_batch_item SET run_id=:run,status='queued',attempt=:attempt,error_code=NULL,message=NULL WHERE id=:item AND user_id=:owner",Json.of("run",run.get("id"),"attempt",attempt,"item",itemId,"owner",owner));
            db.execute("UPDATE aip_run SET batch_id=:batch,batch_item_id=:item WHERE id=:run",Json.of("batch",id,"item",itemId,"run",run.get("id")));
            // 仅用户明确、幂等的失败项重试能重新打开母任务；迟到回执不能恢复 cancelled。
            db.execute("UPDATE aip_run SET status='running',finished_at=NULL WHERE id=:id AND user_id=:owner AND status='cancelled'",Json.of("id",id,"owner",owner));
            runs.refreshBatch(id,owner);return batch(owner,id);
        }));
    }
    public Map<String,Object> exportSession(long owner,String session,Map<String,Object> request) {
        var s=db.owned("aip_session",session,owner,false);var wall=assets.wall(owner,session);var chosen=new LinkedHashSet<String>();
        for(Object id:Json.list(request.get("asset_ids"))) chosen.add(Json.uuid(id));
        if(!chosen.isEmpty()&&wall.stream().map(a->Json.string(a.get("id"))).filter(chosen::contains).count()!=chosen.size()) throw AiPictureException.invalid("导出素材不在本会话图片墙中");
        String format=Json.string(request.getOrDefault("format","zip")).toLowerCase(Locale.ROOT);if(!Set.of("png","zip").contains(format)) throw AiPictureException.invalid("画布支持 PNG，物料支持 ZIP");
        if(chosen.isEmpty()&&format.equals("zip")) for(var asset:wall) if(Set.of("marketing","export").contains(Json.string(asset.get("kind")))) chosen.add(Json.string(asset.get("id")));
        String background=Json.string(request.getOrDefault("backgroundMode",request.getOrDefault("background_mode","white")));if(!Set.of("white","transparent").contains(background)) throw AiPictureException.invalid("背景只允许 white/transparent");
        return runs.submit(owner,session,"export",Json.of("format",format,"background_mode",background,"asset_ids",new ArrayList<>(chosen),"title",s.get("title")),request);
    }
    public Map<String,Object> exportBatch(long owner,String id,Map<String,Object> request) {
        db.owned("aip_batch",id,owner,false);var items=db.query("SELECT output_ids FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner AND status='succeeded' ORDER BY position",Json.of("id",id,"owner",owner));var outputs=new ArrayList<Object>();for(var item:items) outputs.addAll(Json.list(item.get("output_ids")));
        if(outputs.isEmpty()) throw AiPictureException.invalid("没有成功项可导出");return runs.submit(owner,null,"export",Json.of("format","zip","asset_ids",outputs,"batch_id",id,"title","批量处理成功项"),request);
    }
    public byte[] exportDownload(long owner,String id) throws IOException {var e=db.owned("aip_export",id,owner,false);return storage.read(Json.string(e.get("object_key")));}
    public Map<String,Object> exportRecord(long owner,String id) {return db.owned("aip_export",id,owner,false);}
    public Map<String,Object> createEvaluation(long owner,Map<String,Object> request) {
        registry.versions(request);var manifest=EvaluationAssessment.prepare(owner,Json.list(request.get("manifest")),db,assets,registry);
        return tx.execute(status->{
            var run=runs.submit(owner,null,"evaluation",Json.of("manifest",manifest),request);String runId=Json.string(run.get("id"));var previous=db.one("SELECT * FROM aip_evaluation WHERE run_id=:run AND user_id=:owner",Json.of("run",runId,"owner",owner));if(previous!=null) return previous;
            String id=Json.id();db.execute("INSERT INTO aip_evaluation(id,user_id,run_id,status,manifest,dataset_hash,created_at) VALUES(:id,:owner,:run,'queued',:manifest,:hash,:now)",Json.of("id",id,"owner",owner,"run",runId,"manifest",Json.write(manifest),"hash",RunService.hash(manifest),"now",System.currentTimeMillis()));return evaluation(owner,id);
        });
    }
    public List<Map<String,Object>> evaluations(long owner,int offset,int limit) {return db.query("SELECT id,run_id,status,manifest,dataset_hash,metrics,provider,created_at FROM aip_evaluation WHERE user_id=:owner ORDER BY created_at DESC LIMIT :limit OFFSET :offset",Json.of("owner",owner,"offset",offset,"limit",limit));}
    public long evaluationCount(long owner) {return db.count("SELECT COUNT(*) FROM aip_evaluation WHERE user_id=:owner",Json.of("owner",owner));}
    public Map<String,Object> evaluation(long owner,String id) {var e=db.owned("aip_evaluation",id,owner,false);e.remove("user_id");return e;}
    public static Map<String,Object> parameters(Map<String,Object> request) {
        var params=Json.copy(request);for(String key:List.of("schemaVersion","schema_version","toolRegistryVersion","tool_registry_version","idempotencyKey","idempotency_key","expectedVersion","expected_version")) params.remove(key);return params;
    }
}
