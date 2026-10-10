package com.ruoyi.paismart.application.documents;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.billing.RateLimitService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import com.ruoyi.paismart.infrastructure.Jsons;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 以数据库 manifest 作为续传事实源，缓存丢失不会丢上传状态。 */
@Service
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class UploadService {
    public record Create(String filename,long byteSize,String sha256,Integer chunkSize,String spaceId,String visibility,String requestKey) {}
    public record Upload(String uploadId,String filename,long byteSize,String sha256,int chunkSize,int totalChunks,
       List<Integer> completedChunks,String state,String documentId,long estimatedTokens,Instant expiresAt,String spaceId,String visibility,Instant createdAt) {}
    public record Page(List<Upload> rows,long total) {}
    public record SupportedType(String extension,String mimeType) {}
    private static final Map<String,String> TYPES=types();
    private final JdbcTemplate jdbc;
    private final SpaceService spaces;
    private final MembershipService memberships;
    private final DocumentService documents;
    private final DocumentObjectStore objects;
    private final JobService jobs;
    private final long maxBytes;
    private final RateLimitService limits;
    private org.springframework.transaction.support.TransactionTemplate cleanupTransactions;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(UploadService.class);
    @Autowired
    public void configureCleanupTransactions(@Qualifier("paismartTransactionTemplate") org.springframework.transaction.support.TransactionTemplate template) {
        var isolated=new org.springframework.transaction.support.TransactionTemplate(Objects.requireNonNull(template.getTransactionManager()));
        isolated.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        isolated.setIsolationLevel(template.getIsolationLevel());cleanupTransactions=isolated;
    }
    @Autowired
    public UploadService(@Qualifier("paismartJdbc") JdbcTemplate jdbc,SpaceService spaces,MembershipService memberships,
      DocumentService documents,DocumentObjectStore objects,JobService jobs,RateLimitService limits,
      @Value("${paismart.upload.max-file-bytes:536870912}") long maxBytes) {
        this.jdbc=jdbc;this.spaces=spaces;this.memberships=memberships;this.documents=documents;
        this.objects=objects;this.jobs=jobs;this.maxBytes=maxBytes;this.limits=limits;
    }
    /** 离线测试构造器；Spring真实装配始终使用必需的限流服务。 */
    public UploadService(JdbcTemplate jdbc,SpaceService spaces,MembershipService memberships,
      DocumentService documents,DocumentObjectStore objects,JobService jobs,long maxBytes) {
        this(jdbc,spaces,memberships,documents,objects,jobs,null,maxBytes);
    }
    private void requireSize(long size) {
        long allowed=maxBytes;
        if(limits!=null) {
            Object configured=limits.configuration().get("maxFileBytes");
            if(!(configured instanceof Number n) || n.longValue()<1) throw new ServiceException("上传大小策略未就绪",503);
            allowed=Math.min(allowed,n.longValue());
        }
        if(size<=0 || size>allowed) throw new ServiceException("文件为空或超过上传大小限制",400);
    }
    private static Map<String,String> types() {
        Map<String,String> types=new LinkedHashMap<>();
        types.put("pdf","application/pdf");
        for(String ext:List.of("doc","docx","xls","xlsx","ppt","pptx","odt","ods","odp","pages","numbers","key","keynote","epub")) types.put(ext,"application/octet-stream");
        for(String ext:List.of("txt","md","markdown","csv","json","xml","html","htm","rtf")) types.put(ext,"text/plain");
        return Collections.unmodifiableMap(types);
    }
    public List<SupportedType> supportedTypes() {return TYPES.entrySet().stream().map(e->new SupportedType(e.getKey(),e.getValue())).toList();}
    private void eligible(Scope actor) {if(!actor.administrator()) memberships.requireEligible(actor.userId());}
    private static void hash(String hash) {if(hash==null || !hash.matches("[a-fA-F0-9]{64}")) throw new ServiceException("必须提供SHA-256摘要",400);}
    private static String extension(String filename) {
        DocumentService.validFilename(filename);int dot=filename.lastIndexOf('.');
        String ext=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!TYPES.containsKey(ext)) throw new ServiceException("不支持该文件格式",400);return ext;
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Upload create(Scope actor,Create input) {
        eligible(actor);extension(input.filename());hash(input.sha256());spaces.validateVisibility(input.spaceId(),input.visibility());
        requireSize(input.byteSize());
        if(input.requestKey()==null || !input.requestKey().matches("[A-Za-z0-9._:-]{1,100}")) throw new ServiceException("请求幂等标识无效",400);
        int chunkSize=input.chunkSize()==null?1024*1024:input.chunkSize();
        if(chunkSize<64*1024 || chunkSize>10*1024*1024) throw new ServiceException("分片大小必须为64KiB至10MiB",400);
        long count=(input.byteSize()+chunkSize-1)/chunkSize;if(count>10000) throw new ServiceException("分片数量超过限制",400);
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",actor.userId());
        String payloadHash=DocumentService.sha256(Jsons.write(input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<Map<String,Object>> requests=jdbc.queryForList("SELECT upload_id,payload_hash FROM ps_upload_request WHERE user_id=? AND request_key=?",actor.userId(),input.requestKey());
        if(!requests.isEmpty()) {
            if(!payloadHash.equals(requests.get(0).get("payload_hash"))) throw new ServiceException("同一请求标识不能用于不同上传",409);
            return status(actor,(String)requests.get(0).get("upload_id"));
        }
        List<String> ids=jdbc.queryForList("SELECT id FROM ps_upload WHERE user_id=? AND request_key=?",String.class,actor.userId(),input.requestKey());
        if(!ids.isEmpty()) {
            Upload old=status(actor,ids.get(0));
            Map<String,Object> row=jdbc.queryForMap("SELECT space_id,visibility FROM ps_upload WHERE id=?",old.uploadId());
            if(!old.sha256().equalsIgnoreCase(input.sha256()) || old.byteSize()!=input.byteSize() || !old.filename().equals(input.filename()) || old.chunkSize()!=chunkSize || !Objects.equals(row.get("space_id"),input.spaceId()) || !Objects.equals(row.get("visibility"),input.visibility())) throw new ServiceException("同一请求标识不能用于不同上传",409);
            return old;
        }
        // 同用户、内容及可见范围复用，不能用全局摘要把不同owner的资源身份合并。
        List<String> same=jdbc.queryForList("SELECT u.id FROM ps_upload u LEFT JOIN ps_document d ON d.id=u.document_id WHERE u.user_id=? AND u.sha256=? AND u.byte_size=? AND ((u.space_id IS NULL AND ? IS NULL) OR u.space_id=?) AND u.visibility=? AND ((u.status='UPLOADING' AND u.expires_at>CURRENT_TIMESTAMP) OR (u.status='MERGED' AND d.deleted_at IS NULL AND d.status<>'PURGED')) ORDER BY u.created_at DESC,u.id",String.class,actor.userId(),input.sha256().toLowerCase(Locale.ROOT),input.byteSize(),input.spaceId(),input.spaceId(),input.visibility());
        for(String match:same) {
            Upload existing=status(actor,match);
            if(!extension(existing.filename()).equals(extension(input.filename()))) continue;
            spaces.requireUploadPermission(actor,input.spaceId());
            jdbc.update("INSERT INTO ps_upload_request(user_id,request_key,upload_id,payload_hash) VALUES(?,?,?,?)",actor.userId(),input.requestKey(),match,payloadHash);
            return existing;
        }
        spaces.requireUpload(actor,input.spaceId(),input.byteSize());String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ps_upload(id,user_id,space_id,visibility,file_name,byte_size,sha256,chunk_size,total_chunks,request_key,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,actor.userId(),input.spaceId(),input.visibility(),input.filename(),input.byteSize(),input.sha256().toLowerCase(Locale.ROOT),chunkSize,(int)count,input.requestKey(),Timestamp.from(Instant.now().plusSeconds(72*3600)));
        jdbc.update("INSERT INTO ps_upload_request(user_id,request_key,upload_id,payload_hash) VALUES(?,?,?,?)",actor.userId(),input.requestKey(),id,payloadHash);
        return status(actor,id);
    }
    private Map<String,Object> row(Scope actor,String id,boolean lock) {
        eligible(actor);List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM ps_upload WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new ServiceException("上传任务不存在",404);
        Map<String,Object> row=rows.get(0);
        if(((Number)row.get("user_id")).longValue()!=actor.userId()) throw new ServiceException("没有上传任务访问权限",403);
        return row;
    }
    public Upload status(Scope actor,String id) {
        Map<String,Object> r=row(actor,id,false);
        return new Upload(id,(String)r.get("file_name"),((Number)r.get("byte_size")).longValue(),(String)r.get("sha256"),
          ((Number)r.get("chunk_size")).intValue(),((Number)r.get("total_chunks")).intValue(),
          completed(r,id),
          (String)r.get("status"),(String)r.get("document_id"),DocumentService.estimateTokens(((Number)r.get("byte_size")).longValue()),((Timestamp)r.get("expires_at")).toInstant(),(String)r.get("space_id"),(String)r.get("visibility"),((Timestamp)r.get("created_at")).toInstant());
    }
    private List<Integer> completed(Map<String,Object> row,String id) {
        List<Map<String,Object>> chunks=jdbc.queryForList("SELECT chunk_no,object_key,byte_size FROM ps_upload_chunk WHERE upload_id=? ORDER BY chunk_no",id);
        if(!"UPLOADING".equals(row.get("status"))) return chunks.stream().map(chunk->((Number)chunk.get("chunk_no")).intValue()).toList();
        return chunks.stream().filter(chunk->objects.exists((String)chunk.get("object_key")) && objects.size((String)chunk.get("object_key"))==((Number)chunk.get("byte_size")).longValue()).map(chunk->((Number)chunk.get("chunk_no")).intValue()).toList();
    }
    /** 本人的持久上传清单；分页与筛选均由服务端裁决，不能枚举其他人的manifest。 */
    public Page list(Scope actor,String state,String sha256,String spaceId,String visibility,String keyword,int pageNum,int pageSize) {
        eligible(actor);if(state!=null&&!Set.of("UPLOADING","MERGED","EXPIRED","TERMINATED").contains(state)) throw new ServiceException("上传状态无效",400);
        if(sha256!=null) hash(sha256);
        StringBuilder where=new StringBuilder(" WHERE user_id=?");List<Object> parameters=new ArrayList<>();parameters.add(actor.userId());
        if("EXPIRED".equals(state)) where.append(" AND (status='EXPIRED' OR (status='UPLOADING' AND expires_at<=CURRENT_TIMESTAMP))");
        else if(state!=null){where.append(" AND status=?");parameters.add(state);if("UPLOADING".equals(state)) where.append(" AND expires_at>CURRENT_TIMESTAMP");}
        if(sha256!=null){where.append(" AND sha256=?");parameters.add(sha256.toLowerCase(Locale.ROOT));}
        if(spaceId!=null){where.append(" AND space_id=?");parameters.add(spaceId);}
        if(visibility!=null){if(!Set.of("PRIVATE","ORGANIZATION","PUBLIC").contains(visibility)) throw new ServiceException("可见范围无效",400);where.append(" AND visibility=?");parameters.add(visibility);}
        if(keyword!=null&&!keyword.isBlank()){where.append(" AND file_name LIKE ? ESCAPE '!'");parameters.add("%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        long total=Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ps_upload"+where,Long.class,parameters.toArray()));
        int size=Math.max(1,Math.min(100,pageSize));parameters.add(size);parameters.add((long)(Math.max(1,pageNum)-1)*size);
        List<String> page=jdbc.queryForList("SELECT id FROM ps_upload"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",String.class,parameters.toArray());
        return new Page(page.stream().map(id->status(actor,id)).toList(),total);
    }
    private void active(Map<String,Object> r) {
        if(!"UPLOADING".equals(r.get("status"))) throw new ServiceException("上传任务已合并或终止",409);
        if(((Timestamp)r.get("expires_at")).toInstant().isBefore(Instant.now())) throw new ServiceException("上传任务已过期",409);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Upload chunk(Scope actor,String id,int number,String digest,InputStream body) {
        hash(digest);Map<String,Object> row=row(actor,id,true);active(row);
        spaces.requireUpload(actor,(String)row.get("space_id"),((Number)row.get("byte_size")).longValue());requireSize(((Number)row.get("byte_size")).longValue());
        int total=((Number)row.get("total_chunks")).intValue(),piece=((Number)row.get("chunk_size")).intValue();
        if(number<0 || number>=total) throw new ServiceException("分片序号无效",400);
        long expected=number==total-1?((Number)row.get("byte_size")).longValue()-(long)number*piece:piece;
        byte[] bytes;
        try {bytes=body.readNBytes((int)expected+1);} catch(IOException e) {throw new ServiceException("读取分片失败",400);}
        if(bytes.length!=expected) throw new ServiceException("分片字节数不符",400);
        String actual=DocumentService.sha256(bytes);if(!actual.equalsIgnoreCase(digest)) throw new ServiceException("分片摘要校验失败",400);
        List<String> hashes=jdbc.queryForList("SELECT sha256 FROM ps_upload_chunk WHERE upload_id=? AND chunk_no=?",String.class,id,number);
        if(!hashes.isEmpty()) {
            if(!actual.equals(hashes.get(0))) throw new ServiceException("同一分片不能覆盖为不同内容",409);
            String existingKey=jdbc.queryForObject("SELECT object_key FROM ps_upload_chunk WHERE upload_id=? AND chunk_no=?",String.class,id,number);
            // 相同字节重发仍写同一受控对象，能够修复丢失或损坏的片；DB摘要保持不可覆盖。
            objects.put(existingKey,new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
            return status(actor,id);
        }
        if(number==0) sniff((String)row.get("file_name"),bytes);
        String key="paismart/uploads/"+id+"/"+number;
        objects.put(key,new ByteArrayInputStream(bytes),bytes.length,"application/octet-stream");
        jdbc.update("INSERT INTO ps_upload_chunk(upload_id,chunk_no,byte_size,sha256,object_key) VALUES(?,?,?,?,?)",id,number,bytes.length,actual,key);
        return status(actor,id);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public DocumentService.Document merge(Scope actor,String id) {
        return merge(actor,id,()->{});
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public DocumentService.Document merge(Scope actor,String id,Runnable requireLease) {
        Map<String,Object> row=row(actor,id,true);
        if("MERGED".equals(row.get("status"))) return documents.getForProcessing((String)row.get("document_id"));
        active(row);
        // 对同一用户的合并串行裁决上传上限，不能由并发请求越过额度。
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",actor.userId());
        spaces.requireUpload(actor,(String)row.get("space_id"),((Number)row.get("byte_size")).longValue());
        List<Map<String,Object>> chunks=jdbc.queryForList("SELECT * FROM ps_upload_chunk WHERE upload_id=? ORDER BY chunk_no",id);
        if(chunks.size()!=((Number)row.get("total_chunks")).intValue()) throw new ServiceException("分片尚未上传完整",409);
        Path temporary=null;
        try {
            temporary=Files.createTempFile("paismart-merge-",".bin");
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long count=0;
            try(OutputStream target=Files.newOutputStream(temporary)) {
                byte[] buffer=new byte[64*1024];
                for(Map<String,Object> chunk:chunks) {
                    MessageDigest piece=MessageDigest.getInstance("SHA-256");long pieceCount=0;
                    try(InputStream stream=objects.open((String)chunk.get("object_key"))) {
                        int n;while((n=stream.read(buffer))!=-1) {
                            count+=n;pieceCount+=n;if(count>((Number)row.get("byte_size")).longValue()) throw new ServiceException("合并字节数超过声明大小",409);
                            sha.update(buffer,0,n);piece.update(buffer,0,n);target.write(buffer,0,n);
                        }
                    }
                    if(pieceCount!=((Number)chunk.get("byte_size")).longValue() || !HexFormat.of().formatHex(piece.digest()).equals(chunk.get("sha256"))) throw new ServiceException("存储分片已损坏，请重新上传",409);
                }
            }
            String actual=HexFormat.of().formatHex(sha.digest());
            if(count!=((Number)row.get("byte_size")).longValue() || !actual.equals(row.get("sha256"))) throw new ServiceException("完整文件摘要或字节数不符",409);
            String ext=extension((String)row.get("file_name")),key="paismart/documents/"+id+"/source."+ext;
            try(InputStream data=Files.newInputStream(temporary)) {objects.put(key,data,count,TYPES.get(ext));}
            documents.insert(actor,id,(String)row.get("space_id"),(String)row.get("visibility"),(String)row.get("file_name"),actual,(String)row.get("legacy_md5"),key,count,TYPES.get(ext),DocumentService.estimateTokens(count));
            jdbc.update("UPDATE ps_upload SET status='MERGED',document_id=? WHERE id=?",id,id);
            documents.bindProcessingJob(id,jobs.submit(actor.userId(),"DOCUMENT_PROCESS",id,"document:"+id+":parse:1"));
            recycleAfterCommit(actor,id,requireLease);
            return documents.getForProcessing(id);
        } catch(IOException|NoSuchAlgorithmException e) {throw new ServiceException("文件合并失败："+e.getClass().getSimpleName(),503);}
        finally {if(temporary!=null) try {Files.deleteIfExists(temporary);} catch(IOException ignored) { /* 无业务数据，仅受控临时合并字节；运维临时目录清理负责残留。 */ }}
    }
    private void recycleAfterCommit(Scope actor,String id,Runnable requireLease) {
        if(cleanupTransactions==null) return; // 仅无Spring事务设施的测试构造器可缺少；正式bean必需注入。
        Runnable action=()->{
            try {cleanupTransactions.executeWithoutResult(transaction->{
                Map<String,Object> current=row(actor,id,true);
                if("MERGED".equals(current.get("status"))) recycle(current,id,requireLease);
            });} catch(RuntimeException failure){LOG.warn("已合并上传的暂存片回收失败，保留待重试回执 uploadId={} failureType={}",id,failure.getClass().getSimpleName());}
        };
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){public void afterCommit(){action.run();}});
        else action.run();
    }
    public void requireCleanupAccess(Scope actor,String id) {
        eligible(actor);Map<String,Object> upload=jdbc.queryForMap("SELECT * FROM ps_upload WHERE id=?",id);
        if(!spaces.canManageDocument(actor,((Number)upload.get("user_id")).longValue(),(String)upload.get("space_id"),(String)upload.get("visibility"))) throw new ServiceException("临时上传对象超出管理范围",403);
    }
    public List<Map<String,Object>> cleanupCandidates(Scope actor) {
        eligible(actor);List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> upload:jdbc.queryForList("SELECT * FROM ps_upload WHERE status='MERGED' OR (status='UPLOADING' AND expires_at<=CURRENT_TIMESTAMP) ORDER BY created_at,id")) {
            if(!spaces.canManageDocument(actor,((Number)upload.get("user_id")).longValue(),(String)upload.get("space_id"),(String)upload.get("visibility"))) continue;
            Map<String,Object> entry=cleanupSnapshot(upload);
            if(!((List<?>)entry.get("chunks")).isEmpty()) result.add(entry);
            if(result.size()==1000) break;
        }
        return List.copyOf(result);
    }
    private Map<String,Object> cleanupSnapshot(Map<String,Object> upload) {
        Map<String,Object> entry=new LinkedHashMap<>();String id=(String)upload.get("id");
        entry.put("uploadId",id);entry.put("state",upload.get("status"));entry.put("expiresAt",((Timestamp)upload.get("expires_at")).toInstant().toString());entry.put("sha256",upload.get("sha256"));entry.put("byteSize",upload.get("byte_size"));entry.put("userId",upload.get("user_id"));entry.put("spaceId",upload.get("space_id"));entry.put("visibility",upload.get("visibility"));
        List<Map<String,Object>> pieces=new ArrayList<>();
        for(Map<String,Object> chunk:jdbc.queryForList("SELECT * FROM ps_upload_chunk WHERE upload_id=? ORDER BY chunk_no",id)) {
            Map<String,Object> item=new LinkedHashMap<>();item.put("chunkNo",chunk.get("chunk_no"));item.put("objectKey",chunk.get("object_key"));item.put("sha256",chunk.get("sha256"));item.put("byteSize",chunk.get("byte_size"));pieces.add(item);
        }
        entry.put("chunks",pieces);return entry;
    }
    public void validateCleanup(Scope actor,Map<String,Object> approved) {
        String id=Objects.toString(approved.get("uploadId"),"");requireCleanupAccess(actor,id);
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ps_upload WHERE id=?",id);
        if(!"MERGED".equals(row.get("status")) && !("UPLOADING".equals(row.get("status"))&&((Timestamp)row.get("expires_at")).toInstant().isBefore(Instant.now()))) throw new ServiceException("只能清理已合并或过期上传",409);
        if(!Jsons.map(Jsons.write(cleanupSnapshot(row))).equals(Jsons.map(Jsons.write(approved)))) throw new ServiceException("上传清理清单已变化，请重新预览",409);
    }
    /** 调用方已持有事务及Job租约；删除只使用DB回执的精确本项目暂存片路径。 */
    public Map<String,Object> cleanup(Scope actor,Map<String,Object> approved,Runnable requireLease) {
        String id=Objects.toString(approved.get("uploadId"),"");jdbc.queryForList("SELECT id FROM ps_upload WHERE id=? FOR UPDATE",id);validateCleanup(actor,approved);
        Map<String,Object> row=jdbc.queryForMap("SELECT * FROM ps_upload WHERE id=?",id);int removed=recycle(row,id,requireLease);
        requireLease.run();if("UPLOADING".equals(row.get("status"))) jdbc.update("UPDATE ps_upload SET status='EXPIRED' WHERE id=? AND status='UPLOADING'",id);
        return Map.of("uploadId",id,"removedChunks",removed,"manifestRetained",true,"billingRetained",true);
    }
    private int recycle(Map<String,Object> row,String id,Runnable requireLease) {
        int removed=0;
        for(Map<String,Object> chunk:jdbc.queryForList("SELECT * FROM ps_upload_chunk WHERE upload_id=? ORDER BY chunk_no",id)) {
            int number=((Number)chunk.get("chunk_no")).intValue();String key=(String)chunk.get("object_key");
            if(number<0||number>=((Number)row.get("total_chunks")).intValue()||!key.equals("paismart/uploads/"+id+"/"+number)) throw new ServiceException("暂存片对象键不属于当前上传",409);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE object_key=?",Long.class,key)>0) throw new ServiceException("暂存片仍被正式文档引用",409);
            requireLease.run();objects.remove(key);requireLease.run();
            jdbc.update("DELETE FROM ps_upload_chunk WHERE upload_id=? AND chunk_no=? AND object_key=? AND sha256=?",id,number,key,chunk.get("sha256"));removed++;
        }
        return removed;
    }
    public static void sniff(String filename,byte[] bytes) {
        String ext=extension(filename);
        if(ext.equals("pdf") && !(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))) throw new ServiceException("PDF文件内容与扩展名不符",400);
        if(Set.of("docx","xlsx","pptx","odt","ods","odp","epub","pages","numbers","key","keynote").contains(ext) && !(bytes.length>=4 && bytes[0]=='P' && bytes[1]=='K' && bytes[2]==3 && bytes[3]==4)) throw new ServiceException("文档容器与扩展名不符",400);
        if(Set.of("doc","xls","ppt").contains(ext) && !(bytes.length>=8 && (bytes[0]&255)==0xd0 && (bytes[1]&255)==0xcf && (bytes[2]&255)==0x11 && (bytes[3]&255)==0xe0)) throw new ServiceException("Office文件内容与扩展名不符",400);
        if(Set.of("txt","md","markdown","csv","json","xml","html","htm","rtf").contains(ext)) {
            int checks=Math.min(bytes.length,8192);
            // BOM声明的UTF-16/32按文本编码验证，不能把正常字符的零字节误作二进制。
            int offset=0,width=1;java.nio.charset.Charset charset=java.nio.charset.StandardCharsets.UTF_8;
            if(bytes.length>=4&&bytes[0]==0&&bytes[1]==0&&(bytes[2]&255)==254&&(bytes[3]&255)==255){width=4;offset=4;charset=java.nio.charset.Charset.forName("UTF-32BE");}
            else if(bytes.length>=4&&(bytes[0]&255)==255&&(bytes[1]&255)==254&&bytes[2]==0&&bytes[3]==0){width=4;offset=4;charset=java.nio.charset.Charset.forName("UTF-32LE");}
            else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){width=2;offset=2;charset=java.nio.charset.StandardCharsets.UTF_16BE;}
            else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){width=2;offset=2;charset=java.nio.charset.StandardCharsets.UTF_16LE;}
            else if(bytes.length>=4&&bytes[0]!=0&&bytes[1]==0&&bytes[2]!=0&&bytes[3]==0){width=2;charset=java.nio.charset.StandardCharsets.UTF_16LE;}
            else if(bytes.length>=4&&bytes[0]==0&&bytes[1]!=0&&bytes[2]==0&&bytes[3]!=0){width=2;charset=java.nio.charset.StandardCharsets.UTF_16BE;}
            if(width==1) {for(int i=0;i<checks;i++) if(bytes[i]==0) throw new ServiceException("文本格式包含二进制内容",400);}
            else try {
                int length=((checks-offset)/width)*width;
                String text=charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes,offset,length)).toString();
                if(text.chars().anyMatch(c->c==0 || (c<32&&c!='\n'&&c!='\r'&&c!='\t'))) throw new ServiceException("文本格式包含二进制控制字符",400);
            } catch(java.nio.charset.CharacterCodingException invalid){throw new ServiceException("文本编码无效",400);}
        }
    }
}
