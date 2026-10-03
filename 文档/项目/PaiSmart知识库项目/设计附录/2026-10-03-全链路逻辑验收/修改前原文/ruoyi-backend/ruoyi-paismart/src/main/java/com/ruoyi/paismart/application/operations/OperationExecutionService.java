package com.ruoyi.paismart.application.operations;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.paismart.application.documents.DocumentService;
import com.ruoyi.paismart.application.documents.UploadService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.jobs.PaismartJobHandler;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.paismart.infrastructure.Jsons;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 只执行已获另一操作人批准的固定清单；不接受服务器文件路径、主机或SQL。 */
@Service
@PaismartEnabled
public class OperationExecutionService implements PaismartJobHandler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DocumentService documents;
    private final UploadService uploads;
    private final SpaceService spaces;
    private final DocumentObjectStore objects;
    private final JobService jobs;
    private final boolean enabled;
    private com.ruoyi.paismart.infrastructure.storage.LegacyObjectSource legacy;
    @org.springframework.beans.factory.annotation.Autowired
    public void legacySource(com.ruoyi.paismart.infrastructure.storage.LegacyObjectSource source){this.legacy=source;}
    private com.ruoyi.paismart.application.intelligence.SearchService search;
    @org.springframework.beans.factory.annotation.Autowired
    public void indexReadiness(com.ruoyi.paismart.application.intelligence.SearchService search){this.search=search;}
    public OperationExecutionService(@Qualifier("paismartJdbc")JdbcTemplate jdbc,
      @Qualifier("paismartTransactionTemplate")TransactionTemplate tx,DocumentService documents,
      UploadService uploads,SpaceService spaces,DocumentObjectStore objects,JobService jobs,
      @Value("${paismart.operations.execute-enabled:false}")boolean enabled) {
        this.jdbc=jdbc;this.tx=tx;this.documents=documents;this.uploads=uploads;this.spaces=spaces;
        this.objects=objects;this.jobs=jobs;this.enabled=enabled;
    }
    public boolean supports(String type) {return "OPERATION_EXECUTE".equals(type);}
    public Map<String,Object> processJob(JobService.Job job) throws Exception {
        if(!enabled) throw new ServiceException("真实运维执行未启用",503);
        if(!supports(job.type()) || job.leaseToken()==null) throw new ServiceException("运维任务类型或租约无效",409);
        lease(job);
        String id=text(job.payload().get("planId"));
        Map<String,Object> plan=jdbc.queryForMap("SELECT * FROM ps_operation_plan WHERE id=?",id);
        String json=text(plan.get("payload_json"));
        if(!hash(json).equals(plan.get("manifest_hash"))) throw new ServiceException("运维清单摘要不一致",409);
        if(plan.get("approved_by")==null || Objects.equals(plan.get("approved_by"),plan.get("created_by")) || !job.id().equals(plan.get("job_id"))) throw new ServiceException("运维计划未独立批准或任务不匹配",403);
        String state=text(plan.get("state"));
        if("SUCCEEDED".equals(state)) return summary(id);
        if(!Set.of("QUEUED","EXECUTING","FAILED").contains(state)) throw new ServiceException("运维计划不处于可执行状态",409);
        long creator=((Number)plan.get("created_by")).longValue();Scope actor=new Scope(creator,SecurityUtils.isAdmin(creator));
        Map<String,Object> manifest=Jsons.map(json);String kind=text(plan.get("type"));
        rejectUncontrolledInputs(manifest);
        jdbc.update("UPDATE ps_operation_plan SET state='EXECUTING',row_version=row_version+1 WHERE id=?",id);
        try {
            List<Map<String,Object>> entries=new ArrayList<>(entries(manifest.get(kind.equals("bootstrap-import")?"files":"documents")));
            if(kind.equals("cleanup-plans")&&manifest.containsKey("uploads")) entries.addAll(entries(manifest.get("uploads")));
            if(entries.isEmpty() || entries.size()>1000) throw new ServiceException("运维对象清单为空或超过1000项",400);
            Set<String> seen=new HashSet<>();
            Set<String> bootstrapLogicals=new HashSet<>();
            // 在首个对象副作用前检查整份清单；重试已成功的条目只检查输入收据。
            for(var entry:entries) {
                lease(job);String key=itemKey(kind,entry);
                if(!seen.add(key)) throw new ServiceException("运维清单重复",400);
                if(done(id,key,entry)) continue;
                if(kind.equals("cleanup-plans")){if(entry.containsKey("uploadId"))uploads.validateCleanup(actor,entry);else validateCleanup(actor,entry);}
                else if(kind.equals("object-migrations")){if(entry.containsKey("legacySourceKey"))validateLegacyMigration(actor,entry);else validateMigration(actor,entry);}
                else if(kind.equals("bootstrap-import")) {
                    String logical=text(entry.getOrDefault("ownerId",actor.userId()))+":"+nullable(entry.get("spaceId"))+":"+text(entry.getOrDefault("logicalKey",entry.get("filename")));
                    if(!bootstrapLogicals.add(logical))throw new ServiceException("同一计划不能重复导入相同内置知识逻辑标识",400);
                    validateImport(actor,entry);
                }
                else throw new ServiceException("不支持的运维计划类型",400);
            }
            for(int n=0;n<entries.size();n++) {
                Map<String,Object> entry=entries.get(n);String key=itemKey(kind,entry);
                jobs.heartbeat(job.id(),job.leaseToken(),600);
                String digest=hash(Jsons.write(entry));
                if(done(id,key,entry)) continue;
                lease(job);
                Map<String,Object> result;
                if(kind.equals("cleanup-plans")&&entry.containsKey("uploadId")) {
                    result=tx.execute(transaction->{var output=uploads.cleanup(actor,entry,()->lease(job));lease(job);saveReceipt(id,key,digest,output);return output;});
                } else {result=switch(kind) {
                    case "cleanup-plans" -> cleanup(job,actor,entry);
                    case "object-migrations" -> entry.containsKey("legacySourceKey")?migrateLegacy(job,actor,entry):migrate(job,actor,id,key,digest,entry);
                    case "bootstrap-import" -> bootstrap(job,actor,id,n,entry);
                    default -> throw new IllegalStateException("计划类型变化");
                };lease(job);if(!kind.equals("object-migrations")||entry.containsKey("legacySourceKey"))saveReceipt(id,key,digest,result);}
                jobs.checkpoint(job.id(),job.leaseToken(),"operation-item-"+n,Map.of("planId",id,"itemKey",key,"result",result));
            }
            jobs.heartbeat(job.id(),job.leaseToken(),600);
            tx.executeWithoutResult(status->{lease(job);jdbc.update("UPDATE ps_operation_plan SET state='SUCCEEDED',row_version=row_version+1 WHERE id=?",id);
                jdbc.update("INSERT INTO ps_audit_event(id,user_id,action,resource_id,reason) VALUES(?,?,'OPERATION_COMPLETE',?,?)",UUID.randomUUID().toString(),job.userId(),id,"已完成批准清单，源对象及账务按保留策略处理");});
            return summary(id);
        } catch(Exception failure) {jdbc.update("UPDATE ps_operation_plan SET state='FAILED',row_version=row_version+1 WHERE id=? AND state='EXECUTING'",id);throw failure;}
    }
    private void lease(JobService.Job job) {jobs.requireLease(job.id(),job.leaseToken());}
    private void saveReceipt(String id,String key,String digest,Map<String,Object> result){
        if(jdbc.update("UPDATE ps_operation_item SET state='SUCCEEDED',result_json=?,updated_at=CURRENT_TIMESTAMP WHERE plan_id=? AND item_key=? AND payload_hash=? AND state='UNCERTAIN'",Jsons.write(result),id,key,digest)==0)
            jdbc.update("INSERT INTO ps_operation_item(plan_id,item_key,payload_hash,state,result_json) VALUES(?,?,?,'SUCCEEDED',?)",id,key,digest,Jsons.write(result));
    }
    private String itemKey(String kind,Map<String,Object> entry) {
        String key=kind+":"+(entry.containsKey("uploadId")?"upload:"+text(entry.get("uploadId"))+(entry.containsKey("chunkNo")?":"+text(entry.get("chunkNo")):""):(kind.equals("bootstrap-import")?hash(text(entry.get("sourceKey"))):text(entry.get("id"))));
        if(key.length()>160 || key.endsWith(":")) throw new ServiceException("运维对象标识缺失或过长",400);
        return key;
    }
    private boolean done(String plan,String key,Map<String,Object> entry) {
        List<Map<String,Object>> receipts=jdbc.queryForList("SELECT * FROM ps_operation_item WHERE plan_id=? AND item_key=?",plan,key);
        if(receipts.isEmpty()) return false;
        if(!hash(Jsons.write(entry)).equals(receipts.get(0).get("payload_hash"))) throw new ServiceException("已执行对象输入已变化",409);
        String state=text(receipts.get(0).get("state"));
        if(!Set.of("SUCCEEDED","UNCERTAIN").contains(state))throw new ServiceException("运维对象回执状态需人工核对",409);
        return "SUCCEEDED".equals(state);
    }
    private Map<String,Object> summary(String id) {
        List<Map<String,Object>> items=jdbc.queryForList("SELECT item_key AS itemKey,state,result_json AS result FROM ps_operation_item WHERE plan_id=? ORDER BY item_key",id);
        return Map.of("planId",id,"state","SUCCEEDED","completedItems",items.size(),"items",items);
    }
    private void manageable(Scope actor,DocumentService.Document d) {
        if(!spaces.canManageDocument(actor,d.ownerId(),d.spaceId(),d.visibility())) throw new ServiceException("运维对象超出发起人的管理范围",403);
    }
    private void key(String key) {if(!key.startsWith("paismart/") || key.contains("..") || key.contains("\\") || key.contains(":")) throw new ServiceException("对象键超出本项目命名空间",400);}
    private DocumentService.Document validateCleanup(Scope actor,Map<String,Object> entry) {
        var d=documents.getForProcessing(text(entry.get("id")));manageable(actor,d);key(d.objectKey());
        if(d.deletedAt()==null || !d.deletedAt().isBefore(Instant.now().minusSeconds(30L*86400))) throw new ServiceException("清理对象尚未超过30天软删保留期",409);
        if(!d.objectKey().equals(entry.get("objectKey")) || d.byteSize()!=number(entry.get("byteSize"))) throw new ServiceException("清理对象元数据已变化",409);
        String digest=jdbc.queryForObject("SELECT sha256 FROM ps_document WHERE id=?",String.class,d.id());
        if(!digest.equals(entry.get("sha256"))) throw new ServiceException("清理对象摘要已变化",409);
        Long references=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE object_key=? AND id<>? AND status<>'PURGED'",Long.class,d.objectKey(),d.id());
        if(references!=null && references>0) throw new ServiceException("清理对象仍被其他文档引用",409);return d;
    }
    private Map<String,Object> cleanup(JobService.Job job,Scope actor,Map<String,Object> entry) {
        return tx.execute(status->{jdbc.queryForList("SELECT id FROM ps_document WHERE id=? FOR UPDATE",entry.get("id"));
            var d=validateCleanup(actor,entry);
            if(!"PURGED".equals(d.state())) {lease(job);objects.remove(d.objectKey());lease(job);jdbc.update("UPDATE ps_document SET status='PURGED',content_text=NULL,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",d.id());}
            return Map.of("documentId",d.id(),"state","PURGED","tombstoneRetained",true,"billingRetained",true);
        });
    }
    private DocumentService.Document validateMigration(Scope actor,Map<String,Object> entry) {
        var d=documents.getForProcessing(text(entry.get("id")));manageable(actor,d);
        String source=text(entry.get("sourceKey")),target=text(entry.get("targetKey"));key(source);key(target);
        if(source.equals(target) || !target.startsWith("paismart/documents/"+d.id()+"/")) throw new ServiceException("搬迁目标必须属于同一文档目录",400);
        if(!d.objectKey().equals(source)&&!d.objectKey().equals(target)) throw new ServiceException("搬迁源与文档当前对象不一致",409);
        String digest=jdbc.queryForObject("SELECT sha256 FROM ps_document WHERE id=?",String.class,d.id());
        if(!digest.equals(entry.get("sha256")) || d.byteSize()!=number(entry.get("byteSize"))) throw new ServiceException("搬迁清单摘要或大小不符",409);
        Long refs=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE object_key=? AND id<>?",Long.class,target,d.id());
        if(refs!=null && refs>0) throw new ServiceException("搬迁目标已属于其他文档",409);return d;
    }
    private Map<String,Object> migrate(JobService.Job job,Scope actor,String plan,String item,String digest,Map<String,Object> entry) throws IOException {
        var d=validateMigration(actor,entry);String source=text(entry.get("sourceKey")),target=text(entry.get("targetKey"));
        Path copy=Files.createTempFile("paismart-object-copy-",".bin");
        try {
            try(InputStream input=objects.open(d.objectKey());OutputStream output=Files.newOutputStream(copy)) {copyChecked(input,output,d.byteSize(),text(entry.get("sha256")));}
            if(!d.objectKey().equals(target)) {
                lease(job);
                if(objects.exists(target)) {
                    // 响应丢失后的目标先读回核验；不覆盖已存在但无法确认的对象。
                    try(InputStream input=objects.open(target)) {copyChecked(input,OutputStream.nullOutputStream(),d.byteSize(),text(entry.get("sha256")));}
                } else {
                    try {
                        lease(job);
                        try(InputStream input=Files.newInputStream(copy)) {objects.put(target,input,d.byteSize(),d.mimeType());}
                        lease(job);
                        try(InputStream input=objects.open(target)) {copyChecked(input,OutputStream.nullOutputStream(),d.byteSize(),text(entry.get("sha256")));}
                    } catch(IOException|RuntimeException failure) {
                        try {uncertainMigration(job,plan,item,digest,source,target);}catch(RuntimeException receiptFailure){if(receiptFailure!=failure)failure.addSuppressed(receiptFailure);}
                        throw failure;
                    }
                }
            }
            // 网络复制已完成；短事务中当前读并锁住作业及文档，取消/换手不能穿过最终切换。
            return tx.execute(status->{
                lease(job);
                jdbc.queryForList("SELECT id FROM ps_document WHERE id=? FOR UPDATE",d.id());
                var current=validateMigration(actor,entry);
                if(current.rowVersion()!=d.rowVersion()||!current.objectKey().equals(d.objectKey()))throw new ServiceException("文档在搬迁期间发生变更，源对象保留，请重新预览",409);
                if(!current.objectKey().equals(target)) {
                    int changed=jdbc.update("UPDATE ps_document SET object_key=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND object_key=? AND row_version=?",target,d.id(),source,d.rowVersion());
                    if(changed!=1)throw new ServiceException("文档在搬迁期间发生变更，源对象保留，请重新预览",409);
                }
                lease(job);
                Map<String,Object> result=Map.of("documentId",d.id(),"sourceKey",source,"targetKey",target,"sourceRetained",true);
                saveReceipt(plan,item,digest,result);
                return result;
            });
        } finally {Files.deleteIfExists(copy);}
    }
    private void uncertainMigration(JobService.Job job,String plan,String item,String digest,String source,String target) {
        tx.executeWithoutResult(status->{
            lease(job);
            var rows=jdbc.queryForList("SELECT payload_hash,state FROM ps_operation_item WHERE plan_id=? AND item_key=? FOR UPDATE",plan,item);
            if(!rows.isEmpty()) {
                if(!digest.equals(rows.get(0).get("payload_hash")))throw new ServiceException("未决运维对象输入已变化",409);
                return;
            }
            jdbc.update("INSERT INTO ps_operation_item(plan_id,item_key,payload_hash,state,result_json) VALUES(?,?,?,'UNCERTAIN',?)",plan,item,digest,
                Jsons.write(Map.of("sourceKey",source,"targetKey",target,"sourceRetained",true,"targetVerificationRequired",true)));
        });
    }
    private record LegacyCopy(String target,String source,long bytes,String sha,String mime) {}
    private LegacyCopy validateLegacyMigration(Scope actor,Map<String,Object> entry) {
        if(legacy==null)throw new ServiceException("旧对象只读迁移来源未配置",503);
        String target=text(entry.get("targetKey")),source=text(entry.get("legacySourceKey"));key(target);
        String sha=text(entry.get("sha256"));long bytes=number(entry.get("byteSize"));
        if(bytes<=0||bytes>512L*1024*1024||!sha.matches("[a-f0-9]{64}"))throw new ServiceException("旧对象搬迁摘要或大小无效",400);
        if(entry.containsKey("uploadId")) {
            String id=text(entry.get("uploadId"));uploads.requireCleanupAccess(actor,id);
            Map<String,Object> upload=jdbc.queryForMap("SELECT * FROM ps_upload WHERE id=?",id);int number=Math.toIntExact(number(entry.get("chunkNo")));
            if(!"UPLOADING".equals(upload.get("status"))||number<0||number>=((Number)upload.get("total_chunks")).intValue())throw new ServiceException("旧续传manifest状态或片号无效",409);
            Map<String,Object> chunk=jdbc.queryForMap("SELECT * FROM ps_upload_chunk WHERE upload_id=? AND chunk_no=?",id,number);
            String md5=text(upload.get("legacy_md5"));
            if(!md5.matches("[a-fA-F0-9]{32}")||!source.equals("chunks/"+md5+"/"+number)||!target.equals("paismart/uploads/"+id+"/"+number)||!target.equals(chunk.get("object_key"))||bytes!=((Number)chunk.get("byte_size")).longValue()||!sha.equals(chunk.get("sha256")))throw new ServiceException("旧分片搬迁清单与续传回执不符",409);
            return new LegacyCopy(target,source,bytes,sha,"application/octet-stream");
        }
        var d=documents.getForProcessing(text(entry.get("id")));manageable(actor,d);
        Map<String,Object> metadata=jdbc.queryForMap("SELECT legacy_md5,sha256 FROM ps_document WHERE id=?",d.id());
        String md5=text(metadata.get("legacy_md5"));
        if(!md5.matches("[a-fA-F0-9]{32}")||!(source.equals("merged/"+md5)||source.equals("merged/"+d.filename()))||!target.equals(d.objectKey())||!target.startsWith("paismart/documents/"+d.id()+"/")||bytes!=d.byteSize()||!sha.equals(metadata.get("sha256")))throw new ServiceException("旧正式对象搬迁与文档迁入清单不符",409);
        return new LegacyCopy(target,source,bytes,sha,d.mimeType());
    }
    private Map<String,Object> migrateLegacy(JobService.Job job,Scope actor,Map<String,Object> entry) throws IOException {
        LegacyCopy copy=validateLegacyMigration(actor,entry);lease(job);
        if(objects.exists(copy.target())) {
            try(InputStream current=objects.open(copy.target())){copyChecked(current,OutputStream.nullOutputStream(),copy.bytes(),copy.sha());}
            lease(job);return Map.of("targetKey",copy.target(),"legacySourceKey",copy.source(),"sourceRetained",true,"alreadyPresent",true);
        }
        Path temporary=Files.createTempFile("paismart-legacy-copy-",".bin");
        try {
            lease(job);try(InputStream input=legacy.open(copy.source());OutputStream output=Files.newOutputStream(temporary)){copyChecked(input,output,copy.bytes(),copy.sha());}lease(job);
            // 重新读取批准范围及元数据；不把来源流获取成功当成业务指针可写授权。
            validateLegacyMigration(actor,entry);lease(job);
            try(InputStream input=Files.newInputStream(temporary)){objects.put(copy.target(),input,copy.bytes(),copy.mime());}lease(job);
            try(InputStream result=objects.open(copy.target())){copyChecked(result,OutputStream.nullOutputStream(),copy.bytes(),copy.sha());}lease(job);
            return Map.of("targetKey",copy.target(),"legacySourceKey",copy.source(),"sourceRetained",true,"alreadyPresent",false);
        } finally {Files.deleteIfExists(temporary);}
    }
    private void validateImport(Scope actor,Map<String,Object> entry) {
        String source=text(entry.get("sourceKey"));key(source);
        if(!source.startsWith("paismart/imports/")) throw new ServiceException("内置导入只接受批准的imports对象",400);
        if(number(entry.get("byteSize"))<=0 || number(entry.get("byteSize"))>512L*1024*1024 || !text(entry.get("sha256")).matches("[a-f0-9]{64}")) throw new ServiceException("导入大小或摘要无效",400);
        DocumentService.validFilename(text(entry.get("filename")));
        long owner=entry.containsKey("ownerId")?number(entry.get("ownerId")):actor.userId();
        if(owner!=actor.userId() && !actor.administrator()) throw new ServiceException("不能将导入归属其他用户",403);
        String space=nullable(entry.get("spaceId"));spaces.validateVisibility(space,text(entry.get("visibility")));
        spaces.requireUpload(new Scope(owner,SecurityUtils.isAdmin(owner)),space,number(entry.get("byteSize")));
        String logical=text(entry.getOrDefault("logicalKey",entry.get("filename")));
        if(logical.isBlank()||logical.length()>255)throw new ServiceException("内置知识逻辑标识无效",400);
    }
    private Map<String,Object> bootstrap(JobService.Job job,Scope actor,String planId,int index,Map<String,Object> entry) throws IOException {
        try {return tx.execute(transaction->{try{return bootstrapLocked(job,actor,planId,index,entry);}catch(IOException failure){throw new UncheckedIOException(failure);}});}
        catch(UncheckedIOException failure){throw failure.getCause();}
    }
    private Map<String,Object> bootstrapLocked(JobService.Job job,Scope actor,String planId,int index,Map<String,Object> entry) throws IOException {
        validateImport(actor,entry);long owner=entry.containsKey("ownerId")?number(entry.get("ownerId")):actor.userId();Scope target=new Scope(owner,SecurityUtils.isAdmin(owner));
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",owner);
        String logical=hash(owner+":"+nullable(entry.get("spaceId"))+":"+text(entry.getOrDefault("logicalKey",entry.get("filename"))));
        List<Map<String,Object>> bindings=jdbc.queryForList("SELECT * FROM ps_bootstrap_document WHERE logical_key=? FOR UPDATE",logical);
        DocumentService.Document previous=bindings.isEmpty()?null:documents.getForProcessing(text(bindings.get(0).get("document_id")));
        if(previous!=null&&previous.deletedAt()==null&&text(entry.get("sha256")).equals(bindings.get(0).get("sha256"))) {
            if(bootstrapRepairNeeded(job,previous,Boolean.TRUE.equals(entry.get("repair")))) {
                lease(job);var repaired=documents.reprocess(target,previous.id(),"DOCUMENT_PROCESS","bootstrap-repair:"+planId+":"+index,previous.rowVersion());lease(job);
                return Map.of("documentId",repaired.id(),"state",repaired.state(),"sourceKey",entry.get("sourceKey"),"repairQueued",true);
            }
            return Map.of("documentId",previous.id(),"state",previous.state(),"sourceKey",entry.get("sourceKey"),"alreadyPresent",true);
        }
        if(previous!=null&&Set.of("QUEUED","PARSING","EMBEDDING","INDEXING").contains(previous.state()))throw new ServiceException("前一内置知识版本仍在处理，请先等待或取消",409);
        var input=new UploadService.Create(text(entry.get("filename")),number(entry.get("byteSize")),text(entry.get("sha256")),1024*1024,nullable(entry.get("spaceId")),text(entry.get("visibility")),"bootstrap:"+planId+":"+index);
        lease(job);var upload=uploads.create(target,input);lease(job);Set<Integer> completed=new HashSet<>(upload.completedChunks());
        try(InputStream body=objects.open(text(entry.get("sourceKey")))) {
            if("MERGED".equals(upload.state()))copyChecked(body,OutputStream.nullOutputStream(),upload.byteSize(),text(entry.get("sha256")));
            else {for(int n=0;n<upload.totalChunks();n++) {
                int length=(int)Math.min(upload.chunkSize(),upload.byteSize()-(long)n*upload.chunkSize());byte[] bytes=body.readNBytes(length);
                if(bytes.length!=length) throw new ServiceException("导入对象字节数不符",409);
                if(!completed.contains(n)) {lease(job);uploads.chunk(target,upload.uploadId(),n,DocumentService.sha256(bytes),new ByteArrayInputStream(bytes));lease(job);}
            }
            if(body.read()!=-1) throw new ServiceException("导入对象超出批准大小",409);}
        }
        lease(job);var result=uploads.merge(target,upload.uploadId(),()->lease(job));lease(job);
        if(previous!=null&&previous.deletedAt()==null&&!previous.id().equals(result.id())) {documents.delete(target,previous.id(),previous.rowVersion());lease(job);}
        if(bindings.isEmpty()) jdbc.update("INSERT INTO ps_bootstrap_document(logical_key,user_id,space_id,document_id,file_name,sha256) VALUES(?,?,?,?,?,?)",logical,owner,nullable(entry.get("spaceId")),result.id(),entry.get("filename"),entry.get("sha256"));
        else jdbc.update("UPDATE ps_bootstrap_document SET document_id=?,file_name=?,sha256=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE logical_key=?",result.id(),entry.get("filename"),entry.get("sha256"),logical);
        lease(job);
        return Map.of("documentId",result.id(),"state",result.state(),"sourceKey",entry.get("sourceKey"),"oldVersionRetained",previous!=null);
    }
    private boolean bootstrapRepairNeeded(JobService.Job job,DocumentService.Document document,boolean explicit) {
        if(explicit)return true;
        if(Set.of("QUEUED","PARSING","EMBEDDING","INDEXING").contains(document.state()))return false;
        if(!"READY".equals(document.state())||("application/pdf".equals(document.mimeType())&&(document.pageCount()==null||document.pageCount()<1)))return true;
        Long chunks=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document_chunk WHERE document_id=? AND content_version=?",Long.class,document.id(),document.contentVersion());
        if(chunks==null||chunks==0)return true;
        if(search==null)throw new ServiceException("内置知识索引完整性检查未配置",503);
        lease(job);String name=text(search.activeIndex().get("index_name"));
        Map<String,Object> count=search.esRequest("POST","/"+name+"/_count",Map.of("query",Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("document_id",document.id())),Map.of("term",Map.of("content_version",document.contentVersion())),Map.of("term",Map.of("state","READY")))))),30);lease(job);
        return number(count.get("count"))!=chunks;
    }
    private static void copyChecked(InputStream source,OutputStream target,long size,String expected) throws IOException {
        try {MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long count=0;int n;
            while((n=source.read(buffer))!=-1) {count+=n;if(count>size) throw new ServiceException("对象超出批准大小",409);sha.update(buffer,0,n);target.write(buffer,0,n);}
            if(count!=size || !HexFormat.of().formatHex(sha.digest()).equals(expected)) throw new ServiceException("对象字节数或摘要校验失败",409);
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> entries(Object value) {
        if(!(value instanceof List<?> list)) throw new ServiceException("缺少具体运维对象清单",400);
        List<Map<String,Object>> result=new ArrayList<>();for(Object row:list) {if(!(row instanceof Map<?,?>)) throw new ServiceException("运维对象清单结构错误",400);result.add((Map<String,Object>)row);}return result;
    }
    private static void rejectUncontrolledInputs(Map<String,Object> manifest) {
        for(String field:List.of("sql","path","directory","host","endpoint","bucket","command")) if(manifest.containsKey(field)) throw new ServiceException("运维计划不能指定任意SQL、主机、桶或本机路径",400);
    }
    private static String text(Object v) {return v==null?"":v.toString();}
    private static String nullable(Object v) {return v==null||v.toString().isBlank()?null:v.toString();}
    private static long number(Object v) {
        if(!(v instanceof Number n)) throw new ServiceException("运维清单数字字段缺失",400);
        try {return new java.math.BigDecimal(n.toString()).longValueExact();}
        catch(ArithmeticException|NumberFormatException bad) {throw new ServiceException("运维清单数字字段不是有效整数",400);}
    }
    private static String hash(String value) {return DocumentService.sha256(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
