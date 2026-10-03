package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.persistence.*;
import com.ruoyi.aippt.infrastructure.storage.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
@Service @AipptEnabled public class AssetService  {
    private final AipptRepository db;
    private final AipptService service;
    private final TaskLeaseService leases;
    private final TransactionTemplate tx;
    private final AipptStoragePort storage;
    private final AipptProperties props;
    @org.springframework.beans.factory.annotation.Autowired
    public AssetService(AipptRepository d,AipptService s,TaskLeaseService l,@Qualifier("aipptTransactions")TransactionTemplate t,AipptProperties p) {
        this(d,s,l,t,p,null);
    }
    AssetService(AipptRepository d,AipptService s,TaskLeaseService l,TransactionTemplate t,AipptProperties p,AipptStoragePort storageOverride)  {
        db=d;
        service=s;
        leases=l;
        tx=t;
        props=p;
        AipptRules.require(Set.of("local","cos").contains(p.getStorageDriver()),"存储驱动无效");
        String publicDir=com.ruoyi.common.config.RuoYiConfig.getProfile();
        if("local".equals(p.getStorageDriver())&&publicDir!=null&&!publicDir.isBlank())AipptRules.require(!java.nio.file.Path.of(p.getStoragePath()).toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(publicDir).toAbsolutePath().normalize()),"私有资产目录不能位于平台公开profile目录");
        storage=storageOverride!=null?storageOverride:"cos".equals(p.getStorageDriver())?new CosAipptStorage(p.getCos().getBucket(),p.getCos().getRegion(),p.getCos().getSecretId(),p.getCos().getSecretKey()):new LocalAipptStorage(p.getStoragePath());
    }
    private AssetCompensationJournal journal() {
        String path=props.getCompensationPath();
        if(path==null||path.isBlank()) {
            String storagePath=props.getStoragePath();
            if(storagePath!=null&&!storagePath.isBlank())path=java.nio.file.Path.of(storagePath).resolve(".aippt-compensation").toString();
        }
        return new AssetCompensationJournal(path,com.ruoyi.common.config.RuoYiConfig.getProfile());
    }
    public static String bytesHash(byte[] data)  {
        try  {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch(Exception e)  {
            throw new IllegalStateException(e);
        }
    }
    public static String validate(byte[] data,String filename,String role)  {
        AipptRules.require(data.length>0,"文件为空");
        String name=filename==null?"":filename.toLowerCase(Locale.ROOT);
        if("image".equals(role))  {
            AipptRules.require(data.length<=8*1024*1024,"图片最多8MiB");
            String mime;
            if(data.length>=12&&data[0]==(byte)0x89&&new String(data,1,3,StandardCharsets.US_ASCII).equals("PNG"))mime="image/png";
            else if(data.length>=3&&data[0]==(byte)0xff&&data[1]==(byte)0xd8&&data[2]==(byte)0xff)mime="image/jpeg";
            else if(data.length>=12&&new String(data,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(data,8,4,StandardCharsets.US_ASCII).equals("WEBP"))mime="image/webp";
            else throw AipptException.invalid("仅允许PNG/JPEG/WebP图片");
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(data)))  {
                var readers=ImageIO.getImageReaders(input);
                AipptRules.require(readers.hasNext(),"图片损坏");
                var reader=readers.next();
                try  {
                    reader.setInput(input);
                    long pixels=(long)reader.getWidth(0)*reader.getHeight(0);
                    AipptRules.require(pixels>0&&pixels<=40_000_000,"图片解码尺寸超限");
                    reader.read(0);
                }
                finally  {
                    reader.dispose();
                }
            }
            catch(IOException e)  {
                throw AipptException.invalid("图片损坏");
            }
            return mime;
        }
        if("export".equals(role))  {
            AipptRules.require(data.length<=100*1024*1024&&name.endsWith(".pptx"),"PPTX文件类型/大小无效");
            zipStructure(data,"ppt/presentation.xml");
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        AipptRules.require(data.length<=10*1024*1024,"文档最多10MiB");
        if(name.endsWith(".docx"))  {
            zipStructure(data,"word/document.xml");
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if(name.endsWith(".pdf"))  {
            AipptRules.require(data.length>=5&&new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-"),"PDF魔数不符");
            return "application/pdf";
        }
        AipptRules.require(name.endsWith(".txt")||name.endsWith(".md"),"仅允许TXT/Markdown/DOCX/PDF");
        AipptRules.require(data.length<2||!(data[0]=='P'&&data[1]=='K'),"文本文件不能是压缩包");
        return name.endsWith(".md")?"text/markdown":"text/plain";
    }
    private static void zipStructure(byte[] data,String required)  {
        boolean found=false;
        long total=0;
        int entries=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data)))  {
            ZipEntry e;
            byte[] buffer=new byte[8192];
            while((e=zip.getNextEntry())!=null)  {
                AipptRules.require(++entries<=5000&&!e.getName().startsWith("/")&&!e.getName().contains(".."),"压缩包路径或数量无效");
                if(e.getName().equals(required))found=true;
                int read;
                while((read=zip.read(buffer))!=-1)  {
                    total+=read;
                    AipptRules.require(total<=200*1024*1024,"文档解压总量超限");
                }
            }
        }
        catch(IOException e)  {
            throw AipptException.invalid("Office文档损坏");
        }
        AipptRules.require(found,"Office文档内部结构不符");
    }
    public JSONObject upload(String p,long owner,MultipartFile file,String role)  {
        service.owned(p,owner,false);
        return write(p,owner,file,role,null);
    }
    /** 上传采用独立暂存幂等记录；命令重放复用同一资产，失败立即释放未引用对象。 */
    public JSONObject uploadCommand(String p, long owner, MultipartFile file, String role, String key, String scope, JSONObject request)  {
        AipptRules.text(key, 1, 128, "Idempotency-Key");
        final String digest;
        try  {
            long limit = "image".equals(role) ? 8 * 1024 * 1024L : 10 * 1024 * 1024L;
            AipptRules.require(file.getSize() > 0 && file.getSize() <= limit, "文件大小超限或为空");
            digest = AipptRules.hash(bytesHash(file.getBytes()) + ":" + file.getOriginalFilename() + ":" + JSON.toJSONString(request, JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
        }
        catch (IOException e)  {
            throw new AipptException(503, "UPLOAD_UNAVAILABLE", "上传内容不可读");
        }
        String cacheId = AipptRules.hash(owner + ":upload:" + scope + ":" + key).substring(0, 36);
        JSONObject cached = tx.execute(status ->  {
            service.owned(p, owner, true);
            JSONObject row = db.idempotent(cacheId);
            if (row != null)  {
                AipptRules.require(digest.equals(row.getString("digest")), "同一上传幂等键不能提交不同文件");
                return row.getJSONObject("response");
            }
            if (request.containsKey("input_revision")) AipptRules.expected(service.owned(p, owner, false), request, "input_revision");
            if (request.containsKey("slide_id")) AipptRules.expected(service.child("slide", p, request.getString("slide_id")), request, "revision");
            return null;
        }
        );
        if (cached != null) return cached;
        JSONObject staged = write(p, owner, file, role, null);
        JSONObject chosen;
        try  {
            chosen = tx.execute(status ->  {
                service.owned(p, owner, true);
                JSONObject race = db.idempotent(cacheId);
                if (race != null)  {
                    AipptRules.require(digest.equals(race.getString("digest")), "同一上传幂等键不能提交不同文件");
                    return race.getJSONObject("response");
                }
                staged.put("upload_cache_id", cacheId);
                JSONObject row = service.record(p, owner, "ready");
                row.put("id", cacheId);
                row.put("digest", digest);
                row.put("response", staged);
                db.insert("idempotency", row);
                return staged;
            }
            );
        }
        catch (RuntimeException e)  {
            discard(staged);
            throw e;
        }
        if (!staged.getString("id").equals(chosen.getString("id"))) discard(staged);
        return chosen;
    }
    public void discard(JSONObject staged)  {
        String project = staged.getString("project_id");
        if (purge(project, staged.getString("id"), false))  {
            String cache = staged.getString("upload_cache_id");
            if (cache != null) tx.execute(status ->  {
                db.lock("project", project);
                JSONObject row = db.get("idempotency", cache);
                if (row != null && staged.getString("id").equals(row.getJSONObject("response").getString("id"))) db.delete("idempotency", cache);
                return null;
            }
            );
        }
    }
    private JSONObject write(String p,long owner,MultipartFile file,String role,String task)  {
        try  {
            long limit="export".equals(role)?100*1024*1024L:"image".equals(role)?8*1024*1024L:10*1024*1024L;
            AipptRules.require(file.getSize()>0&&file.getSize()<=limit,"文件大小超限或为空");
            byte[] bytes=file.getBytes();
            String mime=validate(bytes,file.getOriginalFilename(),role);
            JSONObject asset=service.record(p,owner,"ready");
            String key=p+"/"+asset.getString("id");
            asset.putAll(JSONObject.of("object_key",key,"filename",file.getOriginalFilename(),"mime",mime,"size",bytes.length,"sha256",bytesHash(bytes),"role",role,"task_id",task,"ref_count",0));
            AssetCompensationJournal journal=journal();
            journal.started(asset);
            try { storage.put(key,bytes,mime); }
            catch(IOException failure) { compensateStage(journal,asset); throw failure; }
            try  {
                tx.execute(s->  {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { journal.mark(asset.getString("id"),"registered"); }
                        @Override public void afterCompletion(int status) {
                            if(status!=STATUS_COMMITTED)compensateStage(journal,asset);
                        }
                    });
                    service.owned(p,owner,true);
                    JSONObject cleanup=db.get("outbox",AipptRules.hash("asset-stage:"+asset.getString("id")).substring(0,36));
                    AipptRules.require(cleanup==null||!cleanup.getBooleanValue("cleanup_started"),"暂存对象已进入补偿清理，拒绝迟到登记");
                    db.insert("asset",asset);
                    JSONObject out=service.record(p,owner,"pending");
                    out.put("type","asset_orphan");
                    out.put("asset_id",asset.getString("id"));
                    out.put("not_before",Instant.now().plusSeconds(props.getCleanup().getOrphanHours()*3600L).toString());
                    db.insert("outbox",out);
                    return null;
                }
                );
            }
            catch(RuntimeException e)  {
                compensateStage(journal,asset);
                throw e;
            }
            asset.put("asset_id",asset.getString("id"));
            asset.put("uploadId",asset.getString("id"));
            asset.put("url","/aippt/assets/"+asset.getString("id"));
            return asset;
        }
        catch(IOException e)  {
            throw new AipptException(503,"STORAGE_UNAVAILABLE","私有文件存取失败");
        }
    }
    /** 回滚与重试均以正式同ID/同key行作保留证据；只处理本次私有对象。 */
    private void compensateStage(AssetCompensationJournal journal,JSONObject asset) {
        try {
            JSONObject formal=db.get("asset",asset.getString("id"));
            if(formal!=null&&Objects.equals(formal.getString("object_key"),asset.getString("object_key"))) {
                journal.mark(asset.getString("id"),"registered");
                return;
            }
            storage.delete(asset.getString("object_key"));
            journal.mark(asset.getString("id"),"deleted");
        }
        catch(IOException|RuntimeException failure) {
            // started 已在对象写入前强制落盘；即便磁盘故障使状态更新失败，仍可按原凭据恢复。
            try { journal.mark(asset.getString("id"),"cleanup_pending"); } catch(RuntimeException ignored) { }
        }
    }
    public void enqueueCompensations() {
        if(!props.getCleanup().isEnabled())return;
        AssetCompensationJournal journal=journal();
        for(JSONObject entry:journal.pending(Instant.now().minusSeconds(props.getCleanup().getOrphanHours()*3600L))) {
            tx.execute(status->{
                JSONObject project=db.lock("project",entry.getString("project_id"));
                if(project==null)return null;
                String id=AipptRules.hash("asset-stage:"+entry.getString("asset_id")).substring(0,36);
                if(db.get("outbox",id)==null) {
                    JSONObject out=service.record(project.getString("id"),project.getLongValue("owner_user_id"),"pending");
                    out.putAll(JSONObject.of("id",id,"type","asset_stage_cleanup","asset_id",entry.getString("asset_id"),"object_key",entry.getString("object_key"),"not_before",Instant.now().toString()));
                    db.insert("outbox",out);
                }
                return null;
            });
        }
    }
    public void cleanCompensation(JSONObject out) {
        if(!props.getCleanup().isEnabled())return;
        AssetCompensationJournal journal=journal();
        JSONObject entry=journal.get(out.getString("asset_id"));
        AipptRules.require(Objects.equals(entry.getString("project_id"),out.getString("project_id"))&&Objects.equals(entry.getString("object_key"),out.getString("object_key")),"补偿对象清单不符");
        boolean protectedRow=Boolean.TRUE.equals(tx.execute(status->{
            JSONObject project=db.lock("project",entry.getString("project_id"));
            if(project==null)throw AipptException.missing();
            JSONObject formal=db.get("asset",entry.getString("asset_id"));
            if(formal!=null&&Objects.equals(formal.getString("object_key"),entry.getString("object_key")))return true;
            JSONObject current=db.lock("outbox",out.getString("id"));
            if(current==null)throw AipptException.missing();
            current.put("cleanup_started",true);
            db.save("outbox",current,current.getLongValue("revision"));
            return false;
        }));
        if(protectedRow) {
            journal.mark(entry.getString("asset_id"),"registered");
            return;
        }
        try {
            storage.delete(entry.getString("object_key"));
            journal.mark(entry.getString("asset_id"),"deleted");
        }
        catch(IOException e) { throw new AipptException(503,"CLEANUP_FAILED","所属暂存对象清理失败，保留清单重试"); }
    }
    public JSONObject workerUpload(String taskId,JSONObject lease,MultipartFile file,String role)  {
        AipptRules.require(Set.of("image","export").contains(role),"Worker仅可提交图片/PPTX");
        JSONObject task=leases.withLease(taskId,lease,AipptRules::copy);
        JSONObject asset=write(task.getString("project_id"),task.getLongValue("owner_user_id"),file,role,taskId);
        leases.withLease(taskId,lease,t->  {
            JSONObject stored=db.get("asset",asset.getString("id"));
            stored.put("attempt",t.getIntValue("attempt"));
            stored.put("fencing_token",t.getLongValue("fencing_token"));
            db.save("asset",stored,stored.getLongValue("revision"));
            return asset;
        }
        );
        return asset;
    }
    public boolean purge(String project,String assetId,boolean deletedProject)  {
        JSONObject candidate=tx.execute(status->  {
            JSONObject p=db.lock("project",project);
            if(p==null)return null;
            service.reconcileAssets(project);
            JSONObject asset=service.child("asset",project,assetId);
            if("deleted".equals(asset.getString("status")))return null;
            if(!deletedProject&&asset.getIntValue("ref_count")>0)return null;
            String taskId=asset.getString("task_id");
            if(!deletedProject&&taskId!=null)  {
                JSONObject task=db.get("task",taskId);
                if(task!=null&&Set.of("queued","running").contains(task.getString("status")))return null;
            }
            asset.put("status","deleting");
            db.save("asset",asset,asset.getLongValue("revision"));
            return asset;
        }
        );
        if(candidate==null)return false;
        try  {
            storage.delete(candidate.getString("object_key"));
            tx.execute(status->  {
                JSONObject asset=db.lock("asset",assetId);
                asset.put("status","deleted");
                db.save("asset",asset,asset.getLongValue("revision"));
                return null;
            }
            );
            return true;
        }
        catch(IOException e)  {
            tx.execute(status->  {
                JSONObject asset=db.lock("asset",assetId);
                asset.put("status","delete_failed");
                asset.put("error","对象清理失败，保留清单待重试");
                db.save("asset",asset,asset.getLongValue("revision"));
                return null;
            }
            );
            throw new AipptException(503,"CLEANUP_FAILED","私有对象清理失败");
        }
    }
    public JSONObject metadata(String id,long owner)  {
        JSONObject asset=db.get("asset",id);
        if(asset==null)throw AipptException.missing();
        service.owned(asset.getString("project_id"),owner,false);
        AipptRules.require("ready".equals(asset.getString("status")),"资产不可读取");
        return asset;
    }
    public void reconcile(String project)  {
        service.reconcileAssets(project);
    }
    public byte[] read(String id,long owner)  {
        JSONObject a=metadata(id,owner);
        return readStored(a);
    }
    private byte[] readStored(JSONObject asset)  {
        try  {
            byte[] bytes=storage.get(asset.getString("object_key"));
            if(!bytesHash(bytes).equals(asset.getString("sha256")))throw new AipptException(503,"ASSET_CORRUPT","私有文件哈希不符");
            return bytes;
        }
        catch(IOException e)  {
            throw new AipptException(503,"ASSET_MISSING","私有文件不可读取");
        }
    }
    public byte[] workerRead(String taskId,String assetId,JSONObject lease)  {
        JSONObject metadata=leases.withLease(taskId,lease,t->  {
            Set<String> allowed=new HashSet<>();
            collectIds(t.getJSONObject("payload"),allowed);
            JSONObject a=service.child("asset",t.getString("project_id"),assetId);
            AipptRules.require(allowed.contains(assetId)||(taskId.equals(a.getString("task_id"))&&t.getIntValue("attempt")==a.getIntValue("attempt")&&t.getLongValue("fencing_token")==a.getLongValue("fencing_token")),"任务没有该资产读取许可");
            AipptRules.require("ready".equals(a.getString("status")),"资产不可读取");
            return AipptRules.copy(a);
        }
        );
        byte[] bytes=readStored(metadata);
        leases.withLease(taskId,lease,t->JSONObject.of("accepted",true));
        return bytes;
    }
    public static void collectIds(Object raw,Set<String> ids)  {
        if(raw instanceof JSONObject object)  {
            for(String field:object.keySet())  {
                Object value=object.get(field);
                if("asset_id".equals(field)&&value instanceof String)ids.add((String)value);
                if("url".equals(field)&&value instanceof String url&&url.startsWith("/aippt/assets/"))ids.add(url.substring("/aippt/assets/".length()));
                collectIds(value,ids);
            }
        }
        else if(raw instanceof JSONArray array)  {
            for(Object item:array)collectIds(item,ids);
        }
    }
    @jakarta.annotation.PreDestroy public void close()  {
        if(storage instanceof CosAipptStorage cos)cos.close();
    }
    public void validateTicket(String assetId,long owner,String ticketId)  {
        if(ticketId==null||ticketId.isBlank())return;
        JSONObject ticket=db.get("outbox",ticketId);
        JSONObject asset=metadata(assetId,owner);
        if(ticket==null||!"download_ticket".equals(ticket.getString("type"))||ticket.getLongValue("owner_user_id")!=owner||!assetId.equals(ticket.getString("asset_id"))||!asset.getString("project_id").equals(ticket.getString("project_id"))||Instant.parse(ticket.getString("expires_at")).isBefore(Instant.now()))throw new AipptException(403,"TICKET_EXPIRED","下载许可无效或已过期");
    }
    public JSONObject ticket(String id,long owner)  {
        JSONObject a=metadata(id,owner);
        return tx.execute(s->  {
            JSONObject ticket=service.record(a.getString("project_id"),owner,"ready");
            ticket.put("type","download_ticket");
            ticket.put("asset_id",id);
            ticket.put("expires_at",Instant.now().plusSeconds(300).toString());
            db.insert("outbox",ticket);
            return JSONObject.of("ticket",ticket.getString("id"),"expires_at",ticket.getString("expires_at"),"url","/aippt/assets/"+id+"?ticket="+ticket.getString("id"));
        }
        );
    }
}
