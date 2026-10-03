package com.ruoyi.paismart.application.documents;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.application.MembershipService;
import com.ruoyi.paismart.application.jobs.JobService;
import com.ruoyi.paismart.application.spaces.SpaceService;
import com.ruoyi.paismart.application.spaces.SpaceService.Scope;
import com.ruoyi.paismart.infrastructure.storage.DocumentObjectStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;

/** 文档正式状态归 MySQL；列表、下载、预览与检索统一校验范围。 */
@Service
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class DocumentService {
    public record Document(String id,String spaceId,String title,String filename,String state,int contentVersion,
       long rowVersion,String visibility,long byteSize,String errorMessage,long estimatedTokens,long actualTokens,
       long ownerId,String objectKey,String mimeType,Instant deletedAt,Instant createdAt,Integer pageCount,String jobId) {}
    public record Page(List<Document> rows,long total) {}
    private final JdbcTemplate jdbc;
    private final SpaceService spaces;
    private final MembershipService memberships;
    private final DocumentObjectStore objects;
    private final JobService jobs;
    public DocumentService(@Qualifier("paismartJdbc") JdbcTemplate jdbc,SpaceService spaces,
      MembershipService memberships,DocumentObjectStore objects,JobService jobs) {
        this.jdbc=jdbc;this.spaces=spaces;this.memberships=memberships;this.objects=objects;this.jobs=jobs;
    }
    private void eligible(Scope scope) { if(!scope.administrator()) memberships.requireEligible(scope.userId()); }
    private Document map(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        Timestamp deleted=r.getTimestamp("deleted_at");
        return new Document(r.getString("id"),r.getString("space_id"),r.getString("file_name"),r.getString("file_name"),
          r.getString("status"),r.getInt("version"),r.getLong("row_version"),r.getString("visibility"),
          r.getLong("byte_size"),r.getString("error_message"),r.getLong("estimated_tokens"),r.getLong("actual_tokens"),
          r.getLong("user_id"),r.getString("object_key"),r.getString("mime_type"),deleted==null?null:deleted.toInstant(),r.getTimestamp("created_at").toInstant(),r.getObject("page_count")==null?null:r.getInt("page_count"),r.getString("process_job_id"));
    }
    /** 只允许受中心授权的作业调用；不公开为HTTP接口。 */
    public Document getForProcessing(String id) {
        List<Document> rows=jdbc.query("SELECT * FROM ps_document WHERE id=?",this::map,id);
        if(rows.isEmpty()) throw new ServiceException("文档不存在",404);
        return rows.get(0);
    }
    public Document getReadable(Scope scope,String id) {
        eligible(scope);Document doc=getForProcessing(id);
        if(doc.deletedAt()!=null) throw new ServiceException("文档不存在或已删除",404);
        spaces.requireReadable(scope,doc.ownerId(),doc.spaceId(),doc.visibility());
        if(scope.administrator()&&scope.userId()!=doc.ownerId()&&"PRIVATE".equals(doc.visibility()))jdbc.update("INSERT INTO ps_audit_event(id,user_id,action,resource_id,reason) VALUES(?,?,'PRIVATE_DOCUMENT_READ',?,'平台管理员读取其他用户私有文档')",UUID.randomUUID().toString(),scope.userId(),doc.id());
        return doc;
    }
    private Document manageable(Scope scope,String id) {
        eligible(scope);Document doc=getForProcessing(id);
        if(!spaces.canManageDocument(scope,doc.ownerId(),doc.spaceId(),doc.visibility())) throw new ServiceException("没有文档管理权限",403);
        return doc;
    }
    public Page list(Scope scope,String title,String spaceId,String visibility,boolean mine,boolean deleted,int page,int size) {
        return list(scope,title,spaceId,visibility,mine,deleted,page,size,null);
    }
    public Page list(Scope scope,String title,String spaceId,String visibility,boolean mine,boolean deleted,int page,int size,String state) {
        eligible(scope);page=Math.max(1,page);size=Math.max(1,Math.min(size,100));
        StringBuilder where=new StringBuilder(" WHERE ");List<Object> args=new ArrayList<>();
        where.append(deleted?"deleted_at IS NOT NULL":"deleted_at IS NULL");
        if(mine) {where.append(" AND user_id=?");args.add(scope.userId());}
        else if(deleted && !scope.administrator()) {
            Set<String> managed=spaces.manageableSpaceIds(scope);
            where.append(" AND (user_id=?");args.add(scope.userId());
            if(!managed.isEmpty()) {where.append(" OR (visibility<>'PRIVATE' AND space_id IN (");where.append(String.join(",",Collections.nCopies(managed.size(),"?"))).append("))");args.addAll(managed);}
            where.append(")");
        }
        if(!scope.administrator() && !deleted) {
            Set<String> visible=spaces.visibleSpaceIds(scope);
            where.append(" AND (user_id=? OR visibility='PUBLIC'"); args.add(scope.userId());
            if(!visible.isEmpty()) {where.append(" OR (visibility='ORGANIZATION' AND space_id IN (");
              where.append(String.join(",",Collections.nCopies(visible.size(),"?"))).append("))");args.addAll(visible);}
            where.append(")");
        }
        if(title!=null && !title.isBlank()) {where.append(" AND LOWER(file_name) LIKE ? ESCAPE '!'");args.add("%"+title.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(spaceId!=null && !spaceId.isBlank()) {where.append(" AND space_id=?");args.add(spaceId);}
        if(visibility!=null && !visibility.isBlank()) {where.append(" AND visibility=?");args.add(visibility);}
        if(state!=null && !state.isBlank()) {where.append(" AND status=?");args.add(state);}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document"+where,Long.class,args.toArray());
        List<Object> pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((long)(page-1)*size);
        List<Document> rows=jdbc.query("SELECT * FROM ps_document"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",this::map,pageArgs.toArray());
        return new Page(rows,total==null?0:total);
    }
    public InputStream download(Scope scope,String id) { return objects.open(getReadable(scope,id).objectKey()); }
    public String content(Scope scope,String id) {
        Document d=getReadable(scope,id);
        String content=jdbc.queryForObject("SELECT content_text FROM ps_document WHERE id=? AND version=?",String.class,id,d.contentVersion());
        if(content==null) throw new ServiceException("文档尚未完成解析",409);return content;
    }
    public byte[] pdfPage(Scope scope,String id,int page) {
        Document doc=getReadable(scope,id);
        if(!"application/pdf".equals(doc.mimeType())) throw new ServiceException("该文档不是PDF",400);
        if(page<1 || doc.byteSize()>100L*1024*1024) throw new ServiceException("PDF页码无效或在线预览大小超过100MiB",400);
        try(InputStream input=objects.open(doc.objectKey());RandomAccessReadBuffer buffer=new RandomAccessReadBuffer(input);
            PDDocument original=Loader.loadPDF(buffer);PDDocument result=new PDDocument();ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            if(page>original.getNumberOfPages()) throw new ServiceException("PDF页码超出文档范围",404);
            result.importPage(original.getPage(page-1));result.save(output);return output.toByteArray();
        } catch(IOException e) {throw new ServiceException("PDF单页预览失败",422);}
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Document createText(Scope scope,String title,String content,String spaceId,String visibility,String requestKey) {
        eligible(scope);validFilename(title);spaces.validateVisibility(spaceId,visibility);
        if(content==null || content.isBlank() || content.length()>5_000_000) throw new ServiceException("文本为空或超出500万字符限制",400);
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9._:-]{1,100}")) throw new ServiceException("请求幂等标识无效",400);
        // 文本也复用上传请求表，保证重试不创建多份文档。
        jdbc.queryForList("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",scope.userId());
        List<String> existing=jdbc.queryForList("SELECT document_id FROM ps_upload WHERE user_id=? AND request_key=?",String.class,scope.userId(),"text:"+requestKey);
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);String hash=sha256(bytes);
        if(!existing.isEmpty()) {
            Document old=getForProcessing(existing.get(0));
            String oldHash=jdbc.queryForObject("SELECT sha256 FROM ps_document WHERE id=?",String.class,old.id());
            if(!hash.equals(oldHash)||!title.equals(old.filename())||!Objects.equals(spaceId,old.spaceId())||!visibility.equals(old.visibility())) throw new ServiceException("同一请求标识不能用于不同文本",409);
            return old;
        }
        spaces.requireUpload(scope,spaceId,bytes.length);
        String id=UUID.randomUUID().toString(),uploadId=UUID.randomUUID().toString();
        String key="paismart/documents/"+id+"/source.txt";
        objects.put(key,new ByteArrayInputStream(bytes),bytes.length,"text/plain; charset=utf-8");
        insert(scope,id,spaceId,visibility,title,hash,null,key,bytes.length,"text/plain",estimateTokens(bytes.length));
        jdbc.update("UPDATE ps_document SET content_text=? WHERE id=?",content,id);
        jdbc.update("INSERT INTO ps_upload(id,user_id,space_id,visibility,file_name,byte_size,sha256,chunk_size,total_chunks,status,document_id,request_key,expires_at) VALUES(?,?,?,?,?,?,?,1,1,'MERGED',?,?,?)",uploadId,scope.userId(),spaceId,visibility,title,bytes.length,hash,id,"text:"+requestKey,Timestamp.from(Instant.now().plusSeconds(86400)));
        bindProcessingJob(id,jobs.submit(scope.userId(),"DOCUMENT_PROCESS",id,"document:"+id+":parse:1")); return getForProcessing(id);
    }
    void insert(Scope scope,String id,String spaceId,String visibility,String filename,String hash,String md5,String objectKey,long size,String mime,long estimated) {
        jdbc.update("INSERT INTO ps_document(id,user_id,space_id,visibility,file_name,sha256,legacy_md5,object_key,byte_size,mime_type,estimated_tokens) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,scope.userId(),spaceId,visibility,filename,hash,md5,objectKey,size,mime,estimated);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Document delete(Scope scope,String id,long rowVersion) {
        jdbc.queryForList("SELECT id FROM ps_document WHERE id=? FOR UPDATE",id);
        Document d=manageable(scope,id);
        if(d.deletedAt()!=null) return d;
        if(jdbc.update("UPDATE ps_document SET status='DELETED',deleted_at=CURRENT_TIMESTAMP,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND row_version=? AND deleted_at IS NULL",id,rowVersion)!=1) throw new ServiceException("文档已被修改，请刷新",409);
        jobs.submit(scope.userId(),"DOCUMENT_DELETE",id,"document:"+id+":delete:"+(rowVersion+1));return getForProcessing(id);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Document restore(Scope scope,String id,long rowVersion) {
        jdbc.queryForList("SELECT id FROM ps_document WHERE id=? FOR UPDATE",id);
        Document d=manageable(scope,id);
        if(d.deletedAt()==null) return d;
        if(d.deletedAt().isBefore(Instant.now().minusSeconds(30L*86400))) throw new ServiceException("文档已超过30天恢复期限",409);
        spaces.requireUpload(scope,d.spaceId());
        if(jdbc.update("UPDATE ps_document SET status='QUEUED',deleted_at=NULL,version=version+1,row_version=row_version+1,error_message=NULL,actual_tokens=0,updated_at=CURRENT_TIMESTAMP WHERE id=? AND row_version=? AND deleted_at IS NOT NULL",id,rowVersion)!=1) throw new ServiceException("文档已被修改，请刷新",409);
        Document restored=getForProcessing(id);bindProcessingJob(id,jobs.enqueue(scope.userId(),"DOCUMENT_PROCESS",Map.of("documentId",id,"contentVersion",restored.contentVersion()),"document:"+id+":restore:"+restored.contentVersion()));return getForProcessing(id);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Document reprocess(Scope scope,String id,String type,String requestKey) {
        return reprocess(scope,id,type,requestKey,null);
    }
    @Transactional(transactionManager="paismartTransactionManager")
    public Document reprocess(Scope scope,String id,String type,String requestKey,Long expectedVersion) {
        jdbc.queryForList("SELECT id FROM ps_document WHERE id=? FOR UPDATE",id);
        Document d=manageable(scope,id);if(d.deletedAt()!=null) throw new ServiceException("已删除文档不能处理",409);
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9._:-]{1,100}")) throw new ServiceException("请求标识无效",400);
        String key="document:"+id+":"+type+":"+sha256(requestKey.getBytes(StandardCharsets.UTF_8));
        Integer old=jdbc.queryForObject("SELECT COUNT(*) FROM ps_job WHERE user_id=? AND type=? AND request_key=?",Integer.class,scope.userId(),type,key);
        if(old!=null && old>0) return d;
        if(expectedVersion!=null && d.rowVersion()!=expectedVersion) throw new ServiceException("文档已被修改，请刷新",409);
        if(Set.of("QUEUED","PARSING","INDEXING").contains(d.state())) throw new ServiceException("文档正在处理中，请等待当前任务完成",409);
        jdbc.update("UPDATE ps_document SET status='QUEUED',version=version+1,error_message=NULL,actual_tokens=0,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        bindProcessingJob(id,jobs.enqueue(scope.userId(),type,Map.of("documentId",id,"contentVersion",d.contentVersion()+1),key));return getForProcessing(id);
    }
    void bindProcessingJob(String id,JobService.Job job) {
        if(job!=null) jdbc.update("UPDATE ps_document SET process_job_id=? WHERE id=?",job.id(),id);
    }
    /** 中心Job取消/重试事务调用；只作用于文档当前版本及其唯一处理任务。 */
    public void onProcessingCancelled(JobService.Job job) {processingTransition(job,"CANCELED","文档处理已取消");}
    public void onProcessingRetry(JobService.Job job) {processingTransition(job,"QUEUED",null);}
    public boolean isCurrentProcessingJob(JobService.Job job) {
        if(job.documentId()==null) return false;
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM ps_document WHERE id=? AND version=? AND process_job_id=? AND deleted_at IS NULL",Integer.class,job.documentId(),processingVersion(job),job.id());
        return count!=null && count==1;
    }
    private void processingTransition(JobService.Job job,String state,String reason) {
        if(job.documentId()==null || !Set.of("DOCUMENT_PROCESS","DOCUMENT_PARSE","DOCUMENT_REINDEX").contains(job.type())) return;
        jdbc.update("UPDATE ps_document SET status=?,error_message=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=? AND process_job_id=? AND deleted_at IS NULL",state,reason,job.documentId(),processingVersion(job),job.id());
    }
    private int processingVersion(JobService.Job job) {
        Object expected=job.payload().getOrDefault("contentVersion",1);
        if(!(expected instanceof Number number)) throw new ServiceException("处理任务内容版本无效",409);
        int version;
        try {version=new java.math.BigDecimal(number.toString()).intValueExact();}
        catch(ArithmeticException|NumberFormatException bad) {throw new ServiceException("处理任务内容版本无效",409);}
        if(version<1) throw new ServiceException("处理任务内容版本无效",409);return version;
    }
    public void markProcessingResult(String id,int version,String state,String content,String error,long actualTokens) {
        if(!Set.of("PARSING","INDEXING","READY","FAILED","CANCELED").contains(state)) throw new ServiceException("非法文档处理状态",400);
        if(actualTokens<0) throw new ServiceException("用量不能为负数",400);
        int rows=jdbc.update("UPDATE ps_document SET status=?,content_text=COALESCE(?,content_text),error_message=?,actual_tokens=?,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=? AND deleted_at IS NULL AND status<>'CANCELED'",state,content,error==null?null:error.substring(0,Math.min(2000,error.length())),actualTokens,id,version);
        if(rows!=1) throw new ServiceException("文档版本已变更或已删除",409);
    }
    public void markPageCount(String id,int version,int count) {
        if(count<=0 || count>10000) throw new ServiceException("文档页数超出解析范围",400);
        if(jdbc.update("UPDATE ps_document SET page_count=? WHERE id=? AND version=? AND deleted_at IS NULL AND status<>'CANCELED'",count,id,version)!=1) throw new ServiceException("文档内容版本已变化",409);
    }
    public static void validFilename(String name) {
        if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\") || name.contains("\u0000") || name.contains("\r") || name.contains("\n") || name.equals(".") || name.equals("..")) throw new ServiceException("文件名称无效",400);
    }
    public static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    public static long estimateTokens(long bytes) {return Math.max(1,(bytes+2)/3);}
}
