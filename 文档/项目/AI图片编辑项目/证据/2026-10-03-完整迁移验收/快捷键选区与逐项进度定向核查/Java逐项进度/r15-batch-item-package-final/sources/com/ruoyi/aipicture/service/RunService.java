package com.ruoyi.aipicture.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.TransactionDefinition;
import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.persistence.*;
import com.ruoyi.aipicture.storage.*;
import com.ruoyi.aipicture.tool.*;
import com.ruoyi.aipicture.worker.*;

/** MySQL 是派发、租约、冻结输入、回执采用和重启恢复的唯一事实源。 */
@Service @AiPictureEnabled
public class RunService {
    private static final Set<String> INTERNAL=Set.of("prepare_selection","selection","export","evaluation");
    private static final Set<String> EXTERNAL=Set.of("generate_image","replace_background","expand_canvas","upscale_image","erase_region","replace_region","split_layers","promote_object_to_layer","generate_marketing");
    private final AiPictureRepository db;private final SessionService sessions;private final AssetService assets;
    private final ToolRegistry registry;private final AiPictureStorage storage;private final AiPictureProperties properties;private final TransactionTemplate tx;
    private ObjectProvider<BatchExportEvaluationService> batches;
    @Autowired public void batchProvider(ObjectProvider<BatchExportEvaluationService> batches) {this.batches=batches;}
    public RunService(AiPictureRepository db,SessionService sessions,AssetService assets,ToolRegistry registry,AiPictureStorage storage,AiPictureProperties properties,@Qualifier("aipictureTransactionTemplate") TransactionTemplate tx) {
        this.db=db;this.sessions=sessions;this.assets=assets;this.registry=registry;this.storage=storage;this.properties=properties;this.tx=tx;
    }
    public Map<String,Object> submit(long owner,String session,String tool,Map<String,Object> raw,Map<String,Object> metadata) {
        if("batch_process".equals(tool)) {
            var request=Json.copy(metadata);request.remove("tool");request.remove("params");request.putAll(raw);var batch=batches.getObject().createBatch(owner,session,request);return get(owner,Json.string(batch.get("id")));
        }
        return submitPinned(owner,session,tool,raw,metadata,null);
    }
    Map<String,Object> submitBatchRun(long owner,String session,Map<String,Object> raw,Map<String,Object> metadata) {return submitPinned(owner,session,"batch_process",raw,metadata,null);}
    private Map<String,Object> submitPinned(long owner,String session,String tool,Map<String,Object> raw,Map<String,Object> metadata,String pinnedMask) {
        registry.versions(metadata);String key=Json.idem(metadata);var params=INTERNAL.contains(tool)?Json.copy(raw):registry.validate(tool,raw);
        if(params.get("prompt") instanceof String prompt) {
            params.put("prompt",prompt.strip());
            // 空值是否允许由原工具的唯一参数契约决定；消除默认空，必填描述仍拒绝空白。
            if(!INTERNAL.contains(tool)) registry.validate(tool,params);
        }
        for(String forbidden:List.of("owner","user_id","object_key","input_revision","mask_asset_id","selection_markers","selection_strokes","current_asset_id")) if(raw.containsKey(forbidden)) throw AiPictureException.invalid("客户端不能指定服务字段 "+forbidden);
        String requestHash=hash(Json.of("session",session,"tool",tool,"params",params));
        return tx.execute(status->{
            var prior=db.one("SELECT * FROM aip_run WHERE user_id=:owner AND idempotency_key=:key FOR UPDATE",Json.of("owner",owner,"key",key));
            if(prior!=null) {
                if(!requestHash.equals(prior.get("request_hash"))) throw new AiPictureException(409,"IDEMPOTENCY_CONFLICT","幂等标识已用于不同请求");return dto(prior);
            }
            Map<String,Object> record=null;
            if(session!=null) { record=sessions.lock(owner,session,Json.expected(metadata));sessions.ensureIdle(record); }
            if(session==null&&!INTERNAL.contains(tool)&&Json.bool(registry.tool(tool).get("sessionRequired"),false)) throw AiPictureException.invalid("此工具需要编辑会话");
            var frozen=freeze(owner,record,params,tool);
            if("selection".equals(tool)&&record!=null) {var selection=sessions.selection(owner,session);params.put("selection_markers",selection==null?List.of():selection.getOrDefault("markers",List.of()));}
            if(pinnedMask!=null) {params.put("mask_asset_id",pinnedMask);var mask=assets.record(owner,pinnedMask);var refs=Json.list(frozen.get("assets"));if(refs.stream().noneMatch(ref->pinnedMask.equals(Json.map(ref).get("id")))) refs.add(Json.of("id",pinnedMask,"width",mask.get("width"),"height",mask.get("height"),"format",mask.get("format"),"sha256",mask.get("sha256")));frozen.put("selection_asset_id",pinnedMask);}
            String id=Json.id();long now=System.currentTimeMillis();
            var p=Json.of("id",id,"owner",owner,"session",session,"tool",tool,"params",Json.write(params),"snapshot",Json.write(frozen),"version",record==null?null:record.get("row_version"),"revision",record==null?null:record.get("revision"),"key",key,"hash",requestHash,"now",now);
            db.execute("INSERT INTO aip_run(id,user_id,session_id,tool,status,params,input_snapshot,input_version,input_revision,idempotency_key,request_hash,created_at) VALUES(:id,:owner,:session,:tool,'queued',:params,:snapshot,:version,:revision,:key,:hash,:now)",p);
            if(DocumentCommandService.COMMANDS.contains(tool)) {
                var document=new DocumentCommandService().apply(tool,Json.map(record.get("document")),params);
                sessions.saveDocument(owner,record,document,Json.string(record.get("current_asset_id")),tool,params);
                db.execute("UPDATE aip_run SET status='succeeded',progress=100,result=:result,finished_at=:now WHERE id=:id",Json.of("id",id,"now",now,"result",Json.write(Json.of("document",document))));
            } else {
                db.execute("INSERT INTO aip_dispatch(run_id,available_at) VALUES(:id,:now)",Json.of("id",id,"now",now));
                if(session!=null) db.execute("UPDATE aip_session SET active_run_id=:run WHERE id=:id AND user_id=:owner",Json.of("run",id,"id",session,"owner",owner));
            }
            event(id,owner);return get(owner,id);
        });
    }
    public Map<String,Object> freeze(long owner,Map<String,Object> session,Map<String,Object> params) {
        return freeze(owner,session,params,null);
    }
    public Map<String,Object> freeze(long owner,Map<String,Object> session,Map<String,Object> params,String tool) {
        var ids=new LinkedHashSet<String>();var selectedIds=new HashSet<String>();var document=session==null?null:Json.map(session.get("document"));
        if("export".equals(tool)) {
            var selected=Json.list(params.get("asset_ids")).stream().map(Json::uuid).toList();
            if(selected.size()>200||new HashSet<>(selected).size()!=selected.size()) throw AiPictureException.invalid("一次导出最多选择 200 个不同素材");
            if("png".equals(params.get("format"))) {params.put("asset_ids",List.of());selected=List.of();}
            selectedIds.addAll(selected);
            String current=session==null?null:Json.uuid(session.get("current_asset_id"));
            boolean render="png".equals(params.get("format"))||selected.isEmpty()||current!=null&&selected.contains(current);
            if(session!=null) params.put("current_asset_id",current);
            if(!render) document=null;
        }
        if(document!=null) for(Object object:Json.list(document.get("layers"))) {var layer=Json.map(object);if(layer.get("asset_id")!=null) ids.add(Json.uuid(layer.get("asset_id")));}
        if("export".equals(tool)&&ids.stream().filter(id->!selectedIds.contains(id)).count()>64) throw AiPictureException.invalid("画布导出最多额外引用 64 个文档素材");
        collectIds(params,ids);
        if(session!=null) {
            if(!"export".equals(tool)) {var selection=sessions.selection(owner,Json.string(session.get("id")));if(selection!=null) {params.put("mask_asset_id",selection.get("mask_asset_id"));ids.add(Json.uuid(selection.get("mask_asset_id")));}}
            params.put("revision",session.get("revision"));
        }
        var references=new ArrayList<Object>();for(String id:ids) {var asset=assets.record(owner,id);var ref=Json.of("id",id,"width",asset.get("width"),"height",asset.get("height"),"format",asset.get("format"),"sha256",asset.get("sha256"));if("export".equals(tool)) ref.put("kind",asset.get("kind"));references.add(ref);}
        int maximum="export".equals(tool)||"evaluation".equals(tool)?264:64;
        if(references.size()>maximum) throw AiPictureException.invalid("一次"+("export".equals(tool)?"导出":"计算")+"最多引用 "+maximum+" 个素材");
        return Json.of("document",document,"assets",references,"schema_version","1","tool_registry_version","2026.10-r2","selection_asset_id",params.get("mask_asset_id"));
    }
    private void collectIds(Object value,Set<String> ids) {
        if(value instanceof Map<?,?>) for(var e:Json.map(value).entrySet()) {
            if(Set.of("asset_id","input_asset_id","expect_asset_id","output_asset_id","mask_asset_id").contains(e.getKey())) {if(e.getValue()!=null) ids.add(Json.uuid(e.getValue()));}
            else if(e.getKey().equals("asset_ids")||e.getKey().equals("reference_asset_ids")) for(Object id:Json.list(e.getValue())) ids.add(Json.uuid(id));
            else collectIds(e.getValue(),ids);
        } else if(value instanceof List<?>) for(Object child:Json.list(value)) collectIds(child,ids);
    }
    public static String hash(Object object) { return WorkerSignatures.sha256(Json.write(Json.canonical(object)).getBytes(StandardCharsets.UTF_8)); }
    public Map<String,Object> get(long owner,String id) { return dto(db.owned("aip_run",id,owner,false)); }
    private Map<String,Object> dto(Map<String,Object> row) {
        var result=new LinkedHashMap<>(row);for(String hidden:List.of("user_id","lease_token","request_hash","input_snapshot")) result.remove(hidden);
        result.put("error",row.get("message"));result.put("prompt",Json.map(row.get("params")).get("prompt"));
        var candidates=new ArrayList<Object>();for(Object asset:Json.list(Json.map(row.get("result")).get("asset_ids"))) {
            try {candidates.add(assets.get(Json.number(row.get("user_id")),Json.uuid(asset)));}
            catch(AiPictureException unavailable) {if(unavailable.status()!=404) throw unavailable;}
        }
        result.put("candidates",candidates);if(result.get("result")==null) result.put("result",Map.of());return result;
    }
    public List<Map<String,Object>> events(long owner,String id,long after) {
        db.owned("aip_run",id,owner,false);return db.query("SELECT event_seq,payload,created_at FROM aip_run_event WHERE run_id=:id AND user_id=:owner AND event_seq>:seq ORDER BY event_seq LIMIT 200",Json.of("id",id,"owner",owner,"seq",after));
    }
    private void event(String id,long owner) {
        db.execute("UPDATE aip_run SET event_seq=event_seq+1 WHERE id=:id",Json.of("id",id));
        var row=db.owned("aip_run",id,owner,false);
        db.execute("INSERT INTO aip_run_event(run_id,event_seq,user_id,payload,created_at) VALUES(:id,:seq,:owner,:payload,:now)",Json.of("id",id,"seq",row.get("event_seq"),"owner",owner,"payload",Json.write(dto(row)),"now",System.currentTimeMillis()));
    }
    public Map<String,Object> cancel(long owner,String id) {
        return AiPictureTransactions.execute(tx,status->{
            var run=db.owned("aip_run",id,owner,true);String state=Json.string(run.get("status"));
            if(Set.of("succeeded","partial_success","failed","cancelled","external_result_unknown").contains(state)) return dto(run);
            db.execute("UPDATE aip_run SET status='cancelled',stage=:message,finished_at=:now WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner,"now",System.currentTimeMillis(),"message",state.equals("running")?"已停止后续采用，正在调用的供应商请求可能仍产生费用":"已取消排队"));
            if("batch_process".equals(run.get("tool"))&&run.get("batch_item_id")==null) {
                // 先固定母任务取消状态，子任务回执和汇总不能将它恢复为 running/succeeded。
                db.execute("UPDATE aip_batch SET status='cancelled',updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("id",id,"owner",owner,"now",System.currentTimeMillis()));
                var children=db.query("SELECT run_id FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner AND status IN ('queued','running') FOR UPDATE",Json.of("id",id,"owner",owner));
                for(var child:children) cancel(owner,Json.string(child.get("run_id")));
                refreshBatch(id,owner);
            }
            db.execute("UPDATE aip_dispatch SET status='cancelled' WHERE run_id=:id",Json.of("id",id));release(run);completeRelations(run,"cancelled",Map.of(),"CANCELLED","用户取消");event(id,owner);return get(owner,id);
        });
    }
    public Map<String,Object> claim(String workerId) {
        return claimForRun(workerId,null);
    }
    /** 定向技术检查只领取明确 UUID，避免测试消费用户的其他排队任务。 */
    public Map<String,Object> claimForRun(String workerId,String requestedRunId) {
        if(workerId.isBlank()||workerId.length()>128) throw AiPictureException.invalid("Worker标识无效");
        return AiPictureTransactions.execute(tx,status->{
            recoverExpired(requestedRunId);long now=System.currentTimeMillis();
            var run=db.one("SELECT r.* FROM aip_dispatch d JOIN aip_run r ON r.id=d.run_id WHERE d.status='pending' AND d.available_at<=:now AND r.status='queued' AND (:requested IS NULL OR r.id=:requested) ORDER BY d.available_at,r.id LIMIT 1 FOR UPDATE SKIP LOCKED",Json.of("now",now,"requested",requestedRunId==null?null:Json.uuid(requestedRunId)));
            if(run==null) return null;String id=Json.string(run.get("id")),lease=Json.id();long attempt=Json.number(run.get("attempt"))+1,expires=now+properties.getLeaseSeconds()*1000L;
            db.execute("UPDATE aip_run SET status='running',attempt=:attempt,lease_token=:lease,lease_expires_at=:expires,heartbeat_at=:now,started_at=COALESCE(started_at,:now) WHERE id=:id",Json.of("attempt",attempt,"lease",lease,"expires",expires,"now",now,"id",id));
            transitionBatchItem(run,"queued","running");
            db.execute("UPDATE aip_dispatch SET status='claimed',worker_id=:worker,attempt=:attempt WHERE run_id=:id",Json.of("id",id,"worker",workerId,"attempt",attempt));
            var snapshot=Json.copy(Json.map(run.get("input_snapshot")));var refs=Json.list(snapshot.get("assets"));
            String base=properties.getPublicInternalBaseUrl().replaceAll("/$","");
            if(base.isBlank()) throw new AiPictureException(503,"INTERNAL_URL_MISSING","未配置 Worker 读取中心服务的私有地址");
            for(Object object:refs) {var ref=Json.map(object);ref.put("read_url",base+"/aipicture/internal/v1/execution/assets/"+ref.get("id")+"?run_id="+id+"&attempt="+attempt+"&lease_token="+lease);}
            snapshot.putAll(Json.of("run_id",id,"attempt",attempt,"lease_token",lease,"lease_expires_at",expires,"tool",run.get("tool"),"params",run.get("params"),"input_version",run.get("input_version")==null?0:run.get("input_version"),"input_revision",run.get("input_revision")==null?0:run.get("input_revision"),"session_id",run.get("session_id"),"provider_task_id",run.get("provider_task_id")));
            event(id,Json.number(run.get("user_id")));return snapshot;
        });
    }
    private void recoverExpired(String requestedRun) {
        long now=System.currentTimeMillis();var expired=db.query("SELECT * FROM aip_run WHERE status='running' AND lease_expires_at<:now AND (:requested IS NULL OR id=:requested) LIMIT 100 FOR UPDATE SKIP LOCKED",Json.of("now",now,"requested",requestedRun));
        for(var run:expired) {
            String id=Json.string(run.get("id"));long owner=Json.number(run.get("user_id"));
            boolean external=EXTERNAL.contains(Json.string(run.get("tool")))||"batch_process".equals(run.get("tool"))&&Json.list(Json.map(run.get("params")).get("operations")).stream().anyMatch(step->EXTERNAL.contains(Json.string(Json.map(step).get("tool"))));
            boolean queryExisting="generate_image".equals(run.get("tool"))&&!Json.string(run.get("provider_task_id")).isBlank()&&Json.number(run.get("attempt"))<=2;
            String next=external&&!queryExisting?"external_result_unknown":Json.number(run.get("attempt"))<=2?"queued":"failed";
            db.execute("UPDATE aip_run SET status=:status,error_code='LEASE_EXPIRED',message=:message,lease_token=NULL,lease_expires_at=NULL WHERE id=:id",Json.of("status",next,"message",external?"Worker租约过期，外部结果待核对；不会自动重复调用付费模型":"Worker租约过期","id",id));
            db.execute("UPDATE aip_dispatch SET status=:status,available_at=:now WHERE run_id=:id",Json.of("status",next.equals("queued")?"pending":"failed","now",now+5000,"id",id));
            if(next.equals("queued")) transitionBatchItem(run,"running","queued");
            else {release(run);completeRelations(run,next,Map.of(),"LEASE_EXPIRED","Worker租约过期");}event(id,owner);
        }
    }
    /** 逐项详情与租约同事务推进；旧任务、其他用户及终态不能被领取或恢复倒写。 */
    private void transitionBatchItem(Map<String,Object> run,String prior,String state) {
        if(run.get("batch_item_id")==null) return;
        db.execute("UPDATE aip_batch_item SET status=:status WHERE id=:item AND user_id=:owner AND run_id=:run AND status=:prior",Json.of("status",state,"item",run.get("batch_item_id"),"owner",run.get("user_id"),"run",run.get("id"),"prior",prior));
    }
    private Map<String,Object> lease(Map<String,Object> p,boolean allowTerminal) {
        String id=Json.uuid(p.get("run_id"));var run=db.one("SELECT * FROM aip_run WHERE id=:id FOR UPDATE",Json.of("id",id));
        if(run==null) throw AiPictureException.missing();
        if(Json.number(run.get("attempt"))!=Json.number(p.get("attempt"))||!Objects.equals(run.get("lease_token"),p.get("lease_token"))) throw new AiPictureException(409,"STALE_ATTEMPT","任务租约或尝试版本已失效");
        if(!allowTerminal&&(!"running".equals(run.get("status"))||run.get("lease_expires_at")==null||Json.number(run.get("lease_expires_at"))<System.currentTimeMillis())) throw new AiPictureException(409,"LEASE_EXPIRED","任务不处于有效执行租约");
        return run;
    }
    public Map<String,Object> heartbeat(Map<String,Object> input) {
        return tx.execute(status->{
            var run=lease(input,true);String state=Json.string(run.get("status"));if(!state.equals("running")) return Json.of("status",state,"cancelled",state.equals("cancelled"));
            if(run.get("lease_expires_at")==null||Json.number(run.get("lease_expires_at"))<System.currentTimeMillis()) throw new AiPictureException(409,"LEASE_EXPIRED","任务租约已过期，迟到心跳不能恢复租约");
            long progress=Math.max(Json.number(run.get("progress")),Math.min(99,Math.max(0,input.get("progress") instanceof Number?Json.number(input.get("progress")):0)));
            String stage=Json.string(input.getOrDefault("stage",run.get("stage")));if(stage.length()>160) stage=stage.substring(0,160);
            db.execute("UPDATE aip_run SET progress=:progress,stage=:stage,heartbeat_at=:now,lease_expires_at=:expires,provider_task_id=COALESCE(:provider,provider_task_id) WHERE id=:id",Json.of("progress",progress,"stage",stage,"now",System.currentTimeMillis(),"expires",System.currentTimeMillis()+properties.getLeaseSeconds()*1000L,"provider",input.get("provider_task_id"),"id",run.get("id")));
            event(Json.string(run.get("id")),Json.number(run.get("user_id")));return Json.of("status","running","cancelled",false);
        });
    }
    public Map<String,Object> uploadArtifact(Map<String,Object> input) throws IOException {
        byte[] bytes;
        try {bytes=Base64.getDecoder().decode(Json.string(input.get("content_base64")));} catch(Exception e) {throw AiPictureException.invalid("产物编码错误");}
        return tx.execute(status->{
            var run=lease(input,false);String id=input.get("id")==null?Json.id():Json.uuid(input.get("id"));long owner=Json.number(run.get("user_id"));
            var old=db.one("SELECT * FROM aip_asset WHERE id=:id AND user_id=:owner AND run_id=:run AND attempt=:attempt",Json.of("id",id,"owner",owner,"run",run.get("id"),"attempt",run.get("attempt")));
            if(old!=null) {if(!Objects.equals(old.get("sha256"),WorkerSignatures.sha256(bytes))) throw new AiPictureException(409,"ARTIFACT_COLLISION","产物重交内容不一致");return assets.dto(old);}
            Map<String,Object> asset;
            try {
                String kind=Json.string(input.getOrDefault("kind","image"));
                if(kind.equals("export")&&"zip".equalsIgnoreCase(Json.string(input.get("format")))) {
                    if(!"export".equals(run.get("tool"))) throw AiPictureException.invalid("仅导出任务可上传 ZIP");
                    asset=storage.binary(bytes,id,"zip","staging/"+run.get("id")+"/"+run.get("attempt"));asset.putAll(Json.of("id",id,"width",0,"height",0,"has_alpha",false,"size_bytes",bytes.length));
                } else asset=storage.artifactImage(bytes,id,"staging/"+run.get("id")+"/"+run.get("attempt"));
                asset.putAll(Json.of("kind",Set.of("original","generated","subject","background","mask","marketing","export").contains(kind)?kind:"generated","source",Json.string(input.getOrDefault("source","tool")),"user_id",owner,"created_at",System.currentTimeMillis(),"status","staged"));
            } catch(IOException e) {throw new AiPictureException(503,"STORAGE_UNAVAILABLE","图片产物保存失败");}
            int index=(int)db.count("SELECT COUNT(*) FROM aip_asset WHERE run_id=:id AND attempt=:attempt",Json.of("id",run.get("id"),"attempt",run.get("attempt")));
            assets.insert(asset,Json.string(run.get("id")),(int)Json.number(run.get("attempt")),index);return assets.dto(asset);
        });
    }
    public byte[] inputAsset(String asset,Map<String,Object> input) throws IOException {
        var run=tx.execute(status->lease(input,false));var snapshot=Json.map(run.get("input_snapshot"));
        if(Json.list(snapshot.get("assets")).stream().noneMatch(ref->asset.equals(Json.map(ref).get("id")))) throw AiPictureException.missing();
        return assets.download(Json.number(run.get("user_id")),asset);
    }
    public Map<String,Object> receipt(Map<String,Object> input) {
        return AiPictureTransactions.execute(tx,status->{
            var run=lease(input,true);long owner=Json.number(run.get("user_id"));String id=Json.string(run.get("id"));
            if(Set.of("succeeded","failed","cancelled","external_result_unknown").contains(Json.string(run.get("status")))) return get(owner,id);
            if(!"running".equals(run.get("status"))||Json.number(run.get("lease_expires_at"))<System.currentTimeMillis()) throw new AiPictureException(409,"STALE_ATTEMPT","迟到结果不采用");
            String terminal=Json.string(input.get("status"));if(!Set.of("succeeded","failed","cancelled","external_result_unknown").contains(terminal)) throw AiPictureException.invalid("回执终态错误");
            String providerTask=Json.string(input.getOrDefault("provider_task_id",run.get("provider_task_id")));
            if(!providerTask.isBlank()&&!providerTask.matches("[A-Za-z0-9._:-]{1,255}")) throw AiPictureException.invalid("供应商任务标识无效");
            if(terminal.equals("external_result_unknown")&&"generate_image".equals(run.get("tool"))&&!providerTask.isBlank()&&"PROVIDER_TASK_PENDING".equals(input.get("error_code"))&&Json.number(run.get("attempt"))<=2) {
                // 已取得任务号仅恢复查询同一供应商任务，不创建新任务或释放会话执行占用。
                db.execute("UPDATE aip_run SET status='queued',provider_task_id=:provider,provider=:metadata,error_code='PROVIDER_TASK_PENDING',message='供应商任务尚未完成，将查询原任务结果',lease_token=NULL,lease_expires_at=NULL WHERE id=:id",Json.of("provider",providerTask,"metadata",Json.write(input.getOrDefault("provider",Map.of())),"id",id));
                db.execute("UPDATE aip_dispatch SET status='pending',available_at=:available WHERE run_id=:id",Json.of("available",System.currentTimeMillis()+5000,"id",id));event(id,owner);return get(owner,id);
            }
            var result=Json.copy(Json.map(input.get("result")));var produced=db.query("SELECT * FROM aip_asset WHERE run_id=:id AND attempt=:attempt AND user_id=:owner",Json.of("id",id,"attempt",run.get("attempt"),"owner",owner));
            var producedIds=new HashSet<String>();for(var asset:produced) producedIds.add(Json.string(asset.get("id")));
            for(Object asset:Json.list(result.get("asset_ids"))) if(!producedIds.contains(Json.uuid(asset))) throw AiPictureException.invalid("结果资产不是此任务的已上传产物");
            if(result.get("adopt_asset_id")!=null&&!producedIds.contains(Json.uuid(result.get("adopt_asset_id")))) throw AiPictureException.invalid("采用资产不是此任务产物");
            if(result.get("mask_asset_id")!=null&&!producedIds.contains(Json.uuid(result.get("mask_asset_id")))) throw AiPictureException.invalid("结果遮罩不是此任务产物");
            if(result.get("document")!=null) {
                var allowed=new HashSet<>(producedIds);for(Object ref:Json.list(Json.map(run.get("input_snapshot")).get("assets"))) allowed.add(Json.uuid(Json.map(ref).get("id")));
                for(Object object:Json.list(Json.map(result.get("document")).get("layers"))) {var layer=Json.map(object);if(layer.get("asset_id")!=null&&!allowed.contains(Json.uuid(layer.get("asset_id")))) throw AiPictureException.invalid("文档引用超出冻结输入和本次输出");}
            }
            if(terminal.equals("succeeded")) {
                db.execute("UPDATE aip_asset SET status='ready',provider=:provider WHERE run_id=:id AND attempt=:attempt AND user_id=:owner",Json.of("id",id,"attempt",run.get("attempt"),"owner",owner,"provider",Json.write(input.getOrDefault("provider",Map.of()))));
                if(run.get("session_id")!=null) {
                    String session=Json.string(run.get("session_id"));var s=db.owned("aip_session",session,owner,true);
                    if(Json.number(s.get("row_version"))!=Json.number(run.get("input_version"))||!Objects.equals(s.get("active_run_id"),id)) throw AiPictureException.conflict(Json.number(s.get("row_version")));
                    var ids=Json.list(result.get("asset_ids")).stream().map(Json::uuid).toList();
                    if("selection".equals(run.get("tool"))) commitSelection(owner,s,result,produced);
                    else if("export".equals(run.get("tool"))) commitExport(owner,run,result,produced);
                    else if(result.get("document")!=null||result.get("adopt_asset_id")!=null) {
                        sessions.addWall(owner,session,ids);
                        String current=result.get("adopt_asset_id")!=null?Json.uuid(result.get("adopt_asset_id")):Json.string(s.get("current_asset_id"));
                        var document=result.get("document")!=null?Json.map(result.get("document")):DocumentCommandService.documentOf(assets.record(owner,current));
                        sessions.saveDocument(owner,s,document,current,Json.string(run.get("tool")),Json.map(run.get("params")));
                    } else sessions.recordResult(owner,s,ids,Json.string(run.get("tool")),Json.map(run.get("params")),result);
                } else if("export".equals(run.get("tool"))) commitExport(owner,run,result,produced);
            }
            String message=Json.string(input.getOrDefault("message",""));if(message.length()>1000) message=message.substring(0,1000);
            db.execute("UPDATE aip_run SET status=:status,progress=:progress,result=:result,provider=:provider,provider_task_id=COALESCE(:providerTask,provider_task_id),error_code=:error,message=:message,finished_at=:now WHERE id=:id",Json.of("status",terminal,"progress",terminal.equals("succeeded")?100:run.get("progress"),"result",Json.write(result),"provider",Json.write(input.getOrDefault("provider",Map.of())),"providerTask",providerTask.isBlank()?null:providerTask,"error",input.get("error_code"),"message",message,"now",System.currentTimeMillis(),"id",id));
            run.put("provider",input.getOrDefault("provider",Map.of()));
            db.execute("UPDATE aip_dispatch SET status='finished' WHERE run_id=:id",Json.of("id",id));release(run);completeRelations(run,terminal,result,Json.string(input.get("error_code")),message);event(id,owner);return get(owner,id);
        });
    }
    private void commitSelection(long owner,Map<String,Object> s,Map<String,Object> result,List<Map<String,Object>> produced) {
        String mask=Json.string(result.get("mask_asset_id"));if(mask.isBlank()) mask=produced.stream().filter(a->"mask".equals(a.get("kind"))).map(a->Json.string(a.get("id"))).findFirst().orElseThrow(()->AiPictureException.invalid("选区结果缺少遮罩"));
        var image=assets.record(owner,mask);if(!"mask".equals(image.get("kind"))) throw AiPictureException.invalid("选区产物必须为遮罩类型");validateMaskDimensions(image,Json.map(s.get("document")));
        db.execute("INSERT INTO aip_selection(id,session_id,user_id,revision,mask_asset_id,markers,strokes,expires_at,state) VALUES(:id,:session,:owner,:revision,:mask,:markers,:strokes,:expires,'ready') ON DUPLICATE KEY UPDATE revision=VALUES(revision),mask_asset_id=VALUES(mask_asset_id),markers=VALUES(markers),strokes=VALUES(strokes),expires_at=VALUES(expires_at),state='ready'",Json.of("id",Json.id(),"session",s.get("id"),"owner",owner,"revision",s.get("revision"),"mask",mask,"markers",Json.write(result.getOrDefault("markers",List.of())),"strokes",Json.write(result.getOrDefault("strokes",List.of())),"expires",System.currentTimeMillis()+24L*60*60*1000));
        result.put("mask_asset_id",mask);result.put("revision",s.get("revision"));
    }
    public static void validateMaskDimensions(Map<String,Object> mask,Map<String,Object> document) {
        if(Json.number(mask.get("width"))!=Json.number(document.get("width"))||Json.number(mask.get("height"))!=Json.number(document.get("height"))) throw AiPictureException.invalid("遮罩尺寸与冻结画布不一致");
    }
    private void commitExport(long owner,Map<String,Object> run,Map<String,Object> result,List<Map<String,Object>> produced) {
        var output=produced.stream().filter(a->"export".equals(a.get("kind"))).findFirst().orElseThrow(()->AiPictureException.invalid("导出没有文件产物"));String export=Json.id();
        db.execute("INSERT INTO aip_export(id,user_id,session_id,run_id,revision,manifest,object_key,sha256,format,created_at) VALUES(:id,:owner,:session,:run,:revision,:manifest,:key,:hash,:format,:now)",Json.of("id",export,"owner",owner,"session",run.get("session_id"),"run",run.get("id"),"revision",run.get("input_revision"),"manifest",Json.write(result.getOrDefault("manifest",List.of())),"key",output.get("object_key"),"hash",output.get("sha256"),"format",output.get("format"),"now",System.currentTimeMillis()));result.put("export_id",export);
        db.execute("UPDATE aip_asset SET status='exported' WHERE run_id=:run AND user_id=:owner",Json.of("run",run.get("id"),"owner",owner));result.remove("asset_ids");
    }
    private void release(Map<String,Object> run) { if(run.get("session_id")!=null) db.execute("UPDATE aip_session SET active_run_id=NULL WHERE id=:session AND user_id=:owner AND active_run_id=:run",Json.of("session",run.get("session_id"),"owner",run.get("user_id"),"run",run.get("id"))); }
    private void completeRelations(Map<String,Object> run,String terminal,Map<String,Object> result,String error,String message) {
        if(run.get("batch_item_id")!=null) {
            db.execute("UPDATE aip_batch_item SET status=:status,output_ids=:outputs,error_code=:error,message=:message,attempt=GREATEST(attempt,:attempt) WHERE id=:item AND user_id=:owner",Json.of("status",terminal,"outputs",Json.write(result.getOrDefault("asset_ids",List.of())),"error",error,"message",message,"attempt",run.get("attempt"),"item",run.get("batch_item_id"),"owner",run.get("user_id")));
            refreshBatch(Json.string(run.get("batch_id")),Json.number(run.get("user_id")));
        }
        if("evaluation".equals(run.get("tool"))) db.execute("UPDATE aip_evaluation SET status=:status,metrics=:metrics,provider=:provider WHERE run_id=:run AND user_id=:owner",Json.of("status",terminal,"metrics",Json.write(result),"provider",Json.write(run.getOrDefault("provider",Map.of())),"run",run.get("id"),"owner",run.get("user_id")));
        if(run.get("turn_id")!=null) {
            db.execute("UPDATE aip_plan_step SET status=:status,attempt=:attempt WHERE turn_id=:turn AND step_id=:step",Json.of("status",terminal.equals("partial_success")?"failed":terminal,"attempt",run.get("attempt"),"turn",run.get("turn_id"),"step",run.get("step_id")));
            db.execute("UPDATE aip_agent_turn SET status=:status,updated_at=:now WHERE id=:turn AND status<>'cancelled'",Json.of("status",terminal.equals("succeeded")?"executing":"failed","now",System.currentTimeMillis(),"turn",run.get("turn_id")));
            if(terminal.equals("succeeded")) scheduleContinuation(Json.string(run.get("turn_id")),Json.number(run.get("user_id")));
        }
    }
    /** 计划重试保留原批量与成功项，只重新派发已知失败项。 */
    public void resumePlanBatches(String turn,long owner,Map<String,Object> request,Map<String,Object> session) {
        var steps=db.query("SELECT step_id,run_id FROM aip_plan_step WHERE turn_id=:turn AND tool='batch_process' AND status IN ('failed','partial_success')",Json.of("turn",turn));
        for(var step:steps) {
            String batch=Json.uuid(step.get("run_id"));var parent=db.owned("aip_run",batch,owner,true);if(!"batch_process".equals(parent.get("tool"))) throw AiPictureException.invalid("计划批量步骤缺少正式母任务");
            var items=db.query("SELECT id,status FROM aip_batch_item WHERE batch_id=:batch AND user_id=:owner ORDER BY position",Json.of("batch",batch,"owner",owner));
            if(items.stream().anyMatch(item->"external_result_unknown".equals(item.get("status")))) throw new AiPictureException(409,"EXTERNAL_RESULT_UNKNOWN","批量存在外部结果待核对项，计划不能重新调用模型");
            var failed=items.stream().filter(item->"failed".equals(item.get("status"))).toList();if(failed.isEmpty()) throw new AiPictureException(409,"BATCH_ITEM_STATE","原批量没有可重试失败项，请核对取消项或重新规划");
            sessions.ensureIdle(session);db.execute("UPDATE aip_session SET active_run_id=:run WHERE id=:id AND user_id=:owner",Json.of("run",batch,"id",session.get("id"),"owner",owner));
            db.execute("UPDATE aip_run SET input_version=:version,input_revision=:revision,attempt=attempt+1,finished_at=NULL WHERE id=:id AND user_id=:owner",Json.of("id",batch,"owner",owner,"version",session.get("row_version"),"revision",session.get("revision")));
            for(var item:failed) batches.getObject().retryItem(owner,batch,Json.string(item.get("id")),Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey","plan-batch:"+hash(Json.of("turn",turn,"item",item.get("id"),"request",Json.idem(request)))));
            db.execute("UPDATE aip_plan_step SET status='running',attempt=attempt+1 WHERE turn_id=:turn AND step_id=:step",Json.of("turn",turn,"step",step.get("step_id")));
        }
    }
    public void refreshBatch(String batch,long owner) {
        var parent=db.owned("aip_run",batch,owner,true);boolean cancelled="cancelled".equals(parent.get("status"));
        var states=db.query("SELECT id,status,output_ids,error_code,message FROM aip_batch_item WHERE batch_id=:id AND user_id=:owner ORDER BY position",Json.of("id",batch,"owner",owner));
        long success=states.stream().filter(s->"succeeded".equals(s.get("status"))).count(),pending=states.stream().filter(s->Set.of("queued","running").contains(Json.string(s.get("status")))).count();
        String state=cancelled?"cancelled":pending>0?"running":success==states.size()?"succeeded":success>0?"partial_success":states.stream().allMatch(s->"cancelled".equals(s.get("status")))?"cancelled":"failed";
        db.execute("UPDATE aip_batch SET status=:status,updated_at=:now WHERE id=:id AND user_id=:owner",Json.of("status",state,"now",System.currentTimeMillis(),"id",batch,"owner",owner));
        var outputs=new ArrayList<Object>();for(var item:states) if("succeeded".equals(item.get("status"))) outputs.addAll(Json.list(item.get("output_ids")));
        Object finished=cancelled?(parent.get("finished_at")==null?System.currentTimeMillis():parent.get("finished_at")):pending==0?System.currentTimeMillis():null;
        db.execute("UPDATE aip_run SET status=:status,progress=:progress,result=:result,finished_at=:finished WHERE id=:id AND user_id=:owner",Json.of("status",state,"progress",pending==0?100:Math.min(99,states.isEmpty()?0:(states.size()-pending)*100/states.size()),"result",Json.write(Json.of("asset_ids",outputs,"items",states)),"finished",finished,"id",batch,"owner",owner));event(batch,owner);
        if(cancelled) return;
        if(pending==0) {
            if(parent.get("session_id")!=null) {
                var session=db.owned("aip_session",Json.string(parent.get("session_id")),owner,true);
                if(Objects.equals(session.get("active_run_id"),batch)&&Json.number(session.get("row_version"))==Json.number(parent.get("input_version"))) {
                    sessions.recordResult(owner,session,outputs.stream().map(Json::uuid).toList(),"batch_process",Json.map(parent.get("params")),Json.of("asset_ids",outputs,"items",states));
                }
            }
            release(parent);completeRelations(parent,state,Json.of("asset_ids",outputs,"items",states),state.equals("succeeded")?"":"BATCH_PARTIAL_OR_FAILED","批量逐项结果已完成");
        }
    }
    public void continuePlan(String turn,long owner) {
        var plan=db.one("SELECT * FROM aip_agent_turn WHERE id=:id AND user_id=:owner FOR UPDATE",Json.of("id",turn,"owner",owner));if(plan==null||"cancelled".equals(plan.get("status"))) return;
        var steps=db.query("SELECT * FROM aip_plan_step WHERE turn_id=:turn ORDER BY position",Json.of("turn",turn));
        for(var step:steps) {
            if("succeeded".equals(step.get("status"))) continue;
            if(!"pending".equals(step.get("status"))) return;
            String session=Json.string(plan.get("session_id"));var s=db.owned("aip_session",session,owner,true);
            var metadata=Json.of("schemaVersion","1","toolRegistryVersion","2026.10-r2","idempotencyKey","plan:"+turn+":"+step.get("step_id")+":"+step.get("attempt"),"expectedVersion",s.get("row_version"));
            String pinned=Json.string(plan.get("mask_asset_id"));
            if(!pinned.isBlank()&&(Json.number(Json.map(s.get("document")).get("width"))!=Json.number(plan.get("mask_width"))||Json.number(Json.map(s.get("document")).get("height"))!=Json.number(plan.get("mask_height")))) {
                db.execute("UPDATE aip_plan_step SET status='failed' WHERE turn_id=:turn AND step_id=:step",Json.of("turn",turn,"step",step.get("step_id")));
                db.execute("UPDATE aip_agent_turn SET status='failed',error_code='STALE_SELECTION',message='计划前一步已改变画幅，固定选区坐标已失效，请重新规划' WHERE id=:turn",Json.of("turn",turn));return;
            }
            var run="batch_process".equals(step.get("tool"))?submit(owner,session,"batch_process",Json.map(step.get("params")),metadata):submitPinned(owner,session,Json.string(step.get("tool")),Json.map(step.get("params")),metadata,pinned.isBlank()?null:pinned);
            db.execute("UPDATE aip_run SET turn_id=:turn,step_id=:step WHERE id=:run",Json.of("turn",turn,"step",step.get("step_id"),"run",run.get("id")));
            db.execute("UPDATE aip_plan_step SET run_id=:run,status=:status WHERE turn_id=:turn AND step_id=:step",Json.of("run",run.get("id"),"status",run.get("status"),"turn",turn,"step",step.get("step_id")));
            if("succeeded".equals(run.get("status"))) scheduleContinuation(turn,owner);return;
        }
        db.execute("UPDATE aip_agent_turn SET status='succeeded',updated_at=:now WHERE id=:turn AND user_id=:owner",Json.of("now",System.currentTimeMillis(),"turn",turn,"owner",owner));
    }
    private void scheduleContinuation(String turn,long owner) {
        if(TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {@Override public void afterCommit() {continueInNewTransaction(turn,owner);}});
        else continueInNewTransaction(turn,owner);
    }
    private void continueInNewTransaction(String turn,long owner) {
        var next=new TransactionTemplate(tx.getTransactionManager());next.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {next.execute(status->{continuePlan(turn,owner);return null;});}
        catch(Exception e) {
            String code=e instanceof AiPictureException failure?failure.errorCode():"PLAN_CONTINUATION_FAILED";
            String message=e instanceof AiPictureException?e.getMessage():"下一计划步骤无法执行，请核对当前文档后重试";
            next.execute(status->{
                db.execute("UPDATE aip_agent_turn SET status='failed',error_code=:code,message=:message,updated_at=:now WHERE id=:turn AND user_id=:owner AND status<>'cancelled'",Json.of("code",code,"message",message,"now",System.currentTimeMillis(),"turn",turn,"owner",owner));
                var step=db.one("SELECT step_id FROM aip_plan_step WHERE turn_id=:turn AND status='pending' ORDER BY position LIMIT 1",Json.of("turn",turn));
                if(step!=null) db.execute("UPDATE aip_plan_step SET status='failed' WHERE turn_id=:turn AND step_id=:step",Json.of("turn",turn,"step",step.get("step_id")));return null;
            });
        }
    }
}
