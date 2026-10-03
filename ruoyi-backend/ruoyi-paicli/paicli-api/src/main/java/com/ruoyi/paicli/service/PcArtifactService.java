package com.ruoyi.paicli.service;

import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.paicli.domain.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** 以若依profile确定存储卷，私密产物放其并列目录，避免公开/profile资源映射绕过鉴权。 */
public class PcArtifactService {
    public static final int MAX_API_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_DATA_BYTES = 50 * 1024 * 1024;
    public static final int OUTPUT_CHUNK_BYTES = 4 * 1024 * 1024;
    public static final long MAX_TOOL_OUTPUT_BYTES = 128L * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = 40_000_000;
    private final PcStore store;
    private final PcAccess access;
    private final PcIdempotency idem;
    private final PcEvidence evidence;
    private final Path storageRoot;
    public record ArtifactContent(Path file, String fileName, String mimeType, String sha256, long size) { }
    record ImageBytes(byte[] bytes, String mimeType, int width, int height) { }

    public PcArtifactService(PcStore store, PcAccess access, PcIdempotency idem, PcEvidence evidence) {
        this(store, access, idem, evidence, privateRoot(RuoYiConfig.getProfile()));
    }
    public PcArtifactService(PcStore store, PcAccess access, PcIdempotency idem, PcEvidence evidence, Path root) {
        this.store = store; this.access = access; this.idem = idem; this.evidence = evidence; storageRoot = root.toAbsolutePath().normalize();
    }
    static Path privateRoot(String profile) {
        if(profile==null||profile.isBlank())throw new IllegalStateException("若依profile存储根未配置");
        Path publicRoot=Path.of(profile).toAbsolutePath().normalize();
        if(publicRoot.getFileName()==null)throw new IllegalStateException("profile不能配置为文件系统根");
        return publicRoot.resolveSibling(publicRoot.getFileName()+".paicli-private").resolve("artifacts");
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> upload(long user, String key, long workspace, String purpose, String name, String mime, byte[] bytes) {
        access.workspace(workspace, user, false);
        if (!Set.of("TURN_IMAGE", "LEGACY_IMPORT", "EVALUATION_REPORT", "PAI_SOURCE").contains(purpose)) throw PcException.invalid("附件用途不受支持");
        String fileName = safeName(name);
        if (bytes == null || bytes.length == 0 || bytes.length > (purpose.equals("TURN_IMAGE") ? MAX_API_IMAGE_BYTES : MAX_DATA_BYTES)) throw new PcException(413,"PC_ARTIFACT_SIZE","附件超出该用途的容量上限");
        String originalHash = digest(bytes);
        return idem.run("user:" + user + ":artifact", key, Map.of("workspaceId",workspace,"purpose",purpose,"fileName",fileName,"hash",originalHash), () -> {
            byte[] data = bytes; String contentType = "application/octet-stream"; Map<String,Object> details = new LinkedHashMap<>();
            if (purpose.equals("TURN_IMAGE")) {
                ImageBytes image = normalizeImage(bytes,mime); data = image.bytes(); contentType = image.mimeType(); details.put("width",image.width()); details.put("height",image.height());
            } else if (purpose.equals("PAI_SOURCE")) {
                validateSourceUtf8(bytes);contentType="text/plain; charset=utf-8";details.put("encoding","UTF-8");
            }
            details.put("sourceSha256",originalHash);
            PcRecord artifact = persist(workspace,user,null,null,purpose,fileName,contentType,data,details);
            evidence.audit(workspace,user,null,null,"ARTIFACT_UPLOAD","ACCEPTED",Map.of("artifactId",artifact.getId(),"sha256",store.data(artifact).get("sha256"),"purpose",purpose));
            return view(artifact);
        });
    }
    public ArtifactContent content(long user, long id) {
        PcRecord artifact = access.resource(PcTable.ARTIFACT,id,user,true,false);
        return verified(artifact);
    }
    /** 用户凭证读取完整输出的有界字节分页，单行JSON也不会因行长而无法查看。 */
    public Map<String,Object> outputText(long user,long id,long session,long offset,int limit) {
        PcRecord row=access.resource(PcTable.ARTIFACT,id,user,true,false);
        if(!"TOOL_OUTPUT".equals(row.getKind())||!Objects.equals(row.getSessionId(),session))throw PcException.deny("输出不属于当前会话");
        return outputText(row,offset,limit);
    }
    /** 仅供已核验审批权限和冻结回执的中心服务读取；普通用户接口仍保持owner边界。 */
    public Map<String,Object> scopedOutputText(long id,long workspace,long owner,long session,long task,String call,String sha,long size,long offset,int limit) {
        PcRecord row=runnerOutput(id,workspace,owner,session);Map<String,Object> data=store.data(row);
        if(!Objects.equals(row.getParentId(),task)||call==null||!call.equals(data.get("toolCallId"))
                ||sha==null||!sha.matches("[a-f0-9]{64}")||!sha.equals(data.get("sha256"))
                ||size<1||size>MAX_TOOL_OUTPUT_BYTES||size!=PcJson.number(data,"size"))throw PcException.deny("预览产物与已冻结审批回执范围不同");
        return outputText(row,offset,limit);
    }
    private Map<String,Object> outputText(PcRecord row,long offset,int limit) {
        if(offset<0||limit<4||limit>1024*1024)throw PcException.invalid("输出字节游标或分段上限非法");
        ArtifactContent artifact=verified(row);
        if(offset>artifact.size())throw PcException.invalid("输出字节游标超过完整产物");
        int count=(int)Math.min(limit,artifact.size()-offset);
        try(var channel=Files.newByteChannel(artifact.file(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            channel.position(offset);ByteBuffer bytes=ByteBuffer.allocate(count);
            while(bytes.hasRemaining())if(channel.read(bytes)<0)throw PcException.conflict("输出读取期间大小改变");
            bytes.flip();var chars=java.nio.CharBuffer.allocate(count);
            var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            var decoded=decoder.decode(bytes,chars,offset+count==artifact.size());
            if(decoded.isError())throw PcException.invalid("输出游标不在UTF-8边界或产物文本无效");
            long next=offset+bytes.position();
            if(next==offset&&offset<artifact.size())throw PcException.conflict("输出分段未推进");
            chars.flip();
            return Map.of("artifactId",String.valueOf(row.getId()),"sessionId",String.valueOf(row.getSessionId()),"sha256",artifact.sha256(),"size",artifact.size(),"offset",offset,"nextOffset",next,"hasMore",next<artifact.size(),"text",chars.toString());
        }catch(IOException failure){throw unavailable();}
    }
    /** 已验证租约的上下文服务只能读取同工作区、同任务用户的图片。 */
    public Map<String,Object> runnerImage(long id,long workspace,long owner) {
        PcRecord row = store.require(PcTable.ARTIFACT,id,false);
        if (!Objects.equals(row.getWorkspaceId(),workspace) || !Objects.equals(row.getOwnerId(),owner) || !Set.of("TURN_IMAGE","LEDGER_IMAGE").contains(row.getKind())) throw PcException.deny("图片不属于当前任务用户和工作区");
        ArtifactContent data = verified(row);
        try { return Map.of("name",data.fileName(),"mimeType",data.mimeType(),"base64Data",Base64.getEncoder().encodeToString(Files.readAllBytes(data.file())),"artifactId",String.valueOf(id)); }
        catch(IOException failure) { throw unavailable(); }
    }
    /** 正式账本存artifact引用，避免每个消息和事件重复保留图片base64。调用方须有事务。 */
    public Map<String,Object> storeLedgerImages(Map<String,Object> payload,long workspace,long owner,Long session,Long task) {
        @SuppressWarnings("unchecked") Map<String,Object> copied=(Map<String,Object>) rewrite(payload,workspace,owner,session,task,0);
        return copied;
    }
    /** 调用方必须先验证已消费工具许可并处于中心事务；输出属于审计产物，不能成为另一套任务事实。 */
    public Map<String,Object> storeRunnerOutput(long workspace,long owner,long session,long task,String toolCallId,String content) {
        if(toolCallId==null||!toolCallId.matches("[A-Za-z0-9._:-]{1,128}")||content==null)throw PcException.invalid("工具输出标识非法");
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if(bytes.length>50*1024*1024)throw new PcException(413,"PC_ARTIFACT_TOO_LARGE","工具输出超过50MB产物上限");
        PcRecord artifact=persist(workspace,owner,session,task,"TOOL_OUTPUT",toolCallId+".txt","text/plain; charset=utf-8",bytes,Map.of("toolCallId",toolCallId,"lines",content.lines().count()));
        return view(artifact);
    }
    /** 上传冻结总摘要和执行归属；重试只能继续同一已消费调用，不创建第二份输出。 */
    public Map<String,Object> beginRunnerOutput(long workspace,long owner,long session,long task,String call,long lease,long fence,long size,String sha) {
        if(call==null||!call.matches("[A-Za-z0-9._:-]{1,128}")||size<1||size>MAX_TOOL_OUTPUT_BYTES||sha==null||!sha.matches("[a-f0-9]{64}"))throw PcException.invalid("分块输出标识、摘要或容量非法");
        String key="output:"+task+":"+call;
        PcRecord old=store.mapper.findKey(PcTable.ARTIFACT,key,true);
        if(old!=null){outputUpload(old,workspace,owner,session,task,call,lease,fence);Map<String,Object> d=store.data(old);if(PcJson.number(d,"size")!=size||!sha.equals(d.get("sha256")))throw PcException.conflict("同一工具调用的输出摘要或容量改变");return outputUploadView(old);}
        Map<String,Object> d=new LinkedHashMap<>();d.put("toolCallId",call);d.put("size",size);d.put("sha256",sha);d.put("leaseId",lease);d.put("fencingToken",fence);d.put("chunks",List.of());d.put("chunkSize",OUTPUT_CHUNK_BYTES);d.put("uploadedBytes",0);d.put("fileName",call+".txt");d.put("mimeType","text/plain; charset=utf-8");d.put("purpose","TOOL_OUTPUT");
        PcRecord row=store.insert(PcTable.ARTIFACT,workspace,owner,task,session,null,"TOOL_OUTPUT","UPLOADING",key,d);
        row.setExpiresAt(store.clock.instant().plusSeconds(86400L*retentionDays(workspace)));store.save(PcTable.ARTIFACT,row,d);return outputUploadView(row);
    }
    public Map<String,Object> appendRunnerOutput(long id,long workspace,long owner,long session,long task,String call,long lease,long fence,int index,byte[] bytes,String sha) {
        PcRecord row=store.require(PcTable.ARTIFACT,id,true);outputUpload(row,workspace,owner,session,task,call,lease,fence);
        if(index<0||index>=32||bytes==null||bytes.length<1||bytes.length>OUTPUT_CHUNK_BYTES||!digest(bytes).equals(sha))throw PcException.invalid("输出分块范围或摘要非法");
        Map<String,Object> d=store.data(row);List<Object> chunks=new ArrayList<>((List<?>)d.get("chunks"));
        if(index<chunks.size()){PcRecord prior=store.require(PcTable.ARTIFACT,Long.parseLong(String.valueOf(chunks.get(index))),false);ArtifactContent verified=verified(prior);if(verified.size()!=bytes.length||!verified.sha256().equals(sha))throw PcException.conflict("重复分块内容发生变化");return Map.of("uploadId",String.valueOf(id),"index",index,"sha256",sha,"size",bytes.length);}
        if(!"UPLOADING".equals(row.getState())||index!=chunks.size())throw PcException.conflict("分块顺序与冻结上传不同");
        long uploaded=PcJson.number(d,"uploadedBytes"),size=PcJson.number(d,"size");if(bytes.length!=Math.min(OUTPUT_CHUNK_BYTES,size-uploaded))throw PcException.conflict("分块大小与冻结总量不符");
        PcRecord part=persist(workspace,owner,session,task,"OUTPUT_CHUNK",call+"-"+index+".part","application/octet-stream",bytes,Map.of("uploadId",id,"index",index,"toolCallId",call));chunks.add(part.getId());d.put("chunks",chunks);d.put("uploadedBytes",uploaded+bytes.length);store.save(PcTable.ARTIFACT,row,d);return Map.of("uploadId",String.valueOf(id),"index",index,"sha256",sha,"size",bytes.length);
    }
    /** 逐块读盘核对总摘要，最后才公开完成产物；不把大正文重新放入事件JSON。 */
    public Map<String,Object> completeRunnerOutput(long id,long workspace,long owner,long session,long task,String call,long lease,long fence) {
        PcRecord row=store.require(PcTable.ARTIFACT,id,true);outputUpload(row,workspace,owner,session,task,call,lease,fence);
        if("ACTIVE".equals(row.getState())){verified(row);return outputUploadView(row);}
        Map<String,Object> d=store.data(row);long expectedSize=PcJson.number(d,"size");if(PcJson.number(d,"uploadedBytes")!=expectedSize)throw PcException.conflict("完整输出尚未上传，不能确认");
        Path temp=null,finalFile=null;
        try{
            Path root=privateStorage(true);temp=root.resolve(UUID.randomUUID()+".tmp");MessageDigest all=MessageDigest.getInstance("SHA-256");long total=0;
            try(FileChannel out=FileChannel.open(temp,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),ownerAttribute(root,false))){
                protectNewNode(temp,false);byte[] buffer=new byte[64*1024];int index=0;
                for(Object partId:(List<?>)d.get("chunks")){
                    PcRecord part=store.require(PcTable.ARTIFACT,Long.parseLong(String.valueOf(partId)),false);Map<String,Object> pd=store.data(part);
                    if(!"OUTPUT_CHUNK".equals(part.getKind())||!Objects.equals(part.getParentId(),task)||!Objects.equals(part.getWorkspaceId(),workspace)||!Objects.equals(part.getOwnerId(),owner)||!Objects.equals(part.getSessionId(),session)||PcJson.number(pd,"uploadId")!=id||PcJson.number(pd,"index")!=index++)throw PcException.deny("分块不属于冻结上传");
                    ArtifactContent value=verified(part);MessageDigest piece=MessageDigest.getInstance("SHA-256");long count=0;
                    try(InputStream in=Files.newInputStream(value.file(),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                        for(int n;(n=in.read(buffer))!=-1;){count+=n;total+=n;if(count>value.size()||total>expectedSize)throw PcException.conflict("分块读取期间容量变化");piece.update(buffer,0,n);all.update(buffer,0,n);ByteBuffer data=ByteBuffer.wrap(buffer,0,n);while(data.hasRemaining())out.write(data);}
                    }
                    if(count!=value.size()||!HexFormat.of().formatHex(piece.digest()).equals(value.sha256()))throw PcException.conflict("分块读取期间摘要变化");
                }
                out.force(true);
            }
            if(total!=expectedSize||!HexFormat.of().formatHex(all.digest()).equals(PcJson.text(d,"sha256")))throw PcException.conflict("完整输出总摘要或大小不一致");
            long lineCount=validatedTextLines(temp);String storageKey=UUID.randomUUID()+".data";finalFile=root.resolve(storageKey);Files.move(temp,finalFile,StandardCopyOption.ATOMIC_MOVE);requirePrivate(finalFile,false);
            Path committed=finalFile;if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(committed);}catch(IOException ignored){}}});
            d.put("storageKey",storageKey);d.put("lines",lineCount);row.setState("ACTIVE");store.save(PcTable.ARTIFACT,row,d);return outputUploadView(row);
        }catch(Exception failure){if(finalFile!=null)try{Files.deleteIfExists(finalFile);}catch(IOException ignored){}if(failure instanceof PcException pc)throw pc;throw unavailable();}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    private void outputUpload(PcRecord row,long workspace,long owner,long session,long task,String call,long lease,long fence){
        if(!"TOOL_OUTPUT".equals(row.getKind())||!Set.of("UPLOADING","ACTIVE").contains(row.getState())||!Objects.equals(row.getWorkspaceId(),workspace)||!Objects.equals(row.getOwnerId(),owner)||!Objects.equals(row.getSessionId(),session)||!Objects.equals(row.getParentId(),task)||row.getExpiresAt()==null||!row.getExpiresAt().isAfter(store.clock.instant()))throw PcException.deny("输出上传不属于当前执行或已过期");
        Map<String,Object> d=store.data(row);if(!call.equals(d.get("toolCallId"))||PcJson.number(d,"leaseId")!=lease||PcJson.number(d,"fencingToken")!=fence)throw PcException.conflict("输出上传执行绑定改变");
    }
    private Map<String,Object> outputUploadView(PcRecord row){Map<String,Object> d=view(row);d.remove("chunks");d.put("uploadId",String.valueOf(row.getId()));if("ACTIVE".equals(row.getState()))d.put("artifactId",String.valueOf(row.getId()));return d;}
    private long retentionDays(long workspace){
        Map<String,Object> wd=store.data(store.require(PcTable.WORKSPACE,workspace,false));Map<String,Object> policy=wd.get("policy") instanceof Map<?,?>?PcJson.object(wd,"policy"):wd;PcRecord current=store.mapper.findKey(PcTable.POLICY_VERSION,"workspace:"+workspace,false);if(current!=null)policy=store.data(current);
        Object configured=policy.getOrDefault("retentionDays",90);long days=configured instanceof Number number?number.longValue():Long.parseLong(String.valueOf(configured));if(days<1||days>3650)throw PcException.invalid("产物保留时间应为1至3650天");return days;
    }
    private static long validatedTextLines(Path file)throws IOException{
        var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);long lines=0,chars=0;boolean cr=false,ended=true;char[] buffer=new char[8192];
        try(Reader reader=new InputStreamReader(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS),decoder)){for(int n;(n=reader.read(buffer))!=-1;)for(int i=0;i<n;i++){char c=buffer[i];chars++;if(c=='\r'){lines++;cr=true;ended=true;}else if(c=='\n'){if(!cr)lines++;cr=false;ended=true;}else{cr=false;ended=false;}}}
        return lines+(chars>0&&!ended?1:0);
    }
    private static void validateSourceUtf8(byte[] bytes) {
        var decoder=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try(Reader reader=new InputStreamReader(new ByteArrayInputStream(bytes),decoder)) {
            char[] buffer=new char[8192];while(reader.read(buffer)!=-1){ /* 完整严格核验，不构造第二份原文字符串。 */ }
        }catch(IOException invalid){throw PcException.invalid("项目规则原文须为完整有效的UTF-8文本");}
    }
    /** 只返回同会话、同工作区及当前任务用户的不可变产物元数据。 */
    public Map<String,Object> runnerOutputMetadata(long id,long workspace,long owner,long session) {
        PcRecord row=runnerOutput(id,workspace,owner,session);verified(row);return view(row);
    }
    public Map<String,Object> runnerOutputText(long id,long workspace,long owner,long session,int offset,int limit) {
        if(offset<1||limit<1||limit>2000)throw PcException.invalid("输出分段范围非法");
        PcRecord row=runnerOutput(id,workspace,owner,session);ArtifactContent artifact=verified(row);
        try(Reader reader=Files.newBufferedReader(artifact.file(),java.nio.charset.StandardCharsets.UTF_8)){
            String text=boundedOutputLines(reader,offset,limit);
            return Map.of("text",text,"sha256",store.data(row).get("sha256"),"artifactId",String.valueOf(id),"offset",offset,"limit",limit);
        }catch(IOException failure){throw unavailable();}
    }
    /** 跳过巨型行不分配其正文；选中部分在构造字符串时就限制UTF-8字节。 */
    static String boundedOutputLines(Reader reader,int offset,int limit)throws IOException{
        StringBuilder output=new StringBuilder(),current=new StringBuilder();char[] buffer=new char[8192];long line=1,end=(long)offset+limit,bytes=0;boolean cr=false,hasCharacters=false;int selected=0;
        for(int n;(n=reader.read(buffer))!=-1;){for(int i=0;i<n;i++){
            char c=buffer[i];if(cr&&c=='\n'){cr=false;continue;}cr=false;
            if(c=='\r'||c=='\n'){
                if(line>=offset&&line<end){if(selected++>0){output.append('\n');bytes++;}output.append(current);if(bytes>2*1024*1024)throw outputRange();}
                current.setLength(0);hasCharacters=false;line++;cr=c=='\r';if(line>=end)return output.toString();
            }else{
                hasCharacters=true;if(line>=offset&&line<end){bytes+=c<=0x7f?1:c<=0x7ff?2:Character.isSurrogate(c)?2:3;if(bytes>2*1024*1024)throw outputRange();current.append(c);}
            }
        }}
        if(hasCharacters&&line>=offset&&line<end){if(selected>0){output.append('\n');bytes++;}if(bytes>2*1024*1024)throw outputRange();output.append(current);}return output.toString();
    }
    private static PcException outputRange(){return new PcException(413,"PC_OUTPUT_RANGE_TOO_LARGE","当前行内容超过2MB，请缩小分段范围或下载完整产物");}
    private PcRecord runnerOutput(long id,long workspace,long owner,long session) {
        PcRecord row=store.require(PcTable.ARTIFACT,id,false);
        if(!Objects.equals(row.getWorkspaceId(),workspace)||!Objects.equals(row.getOwnerId(),owner)||!Objects.equals(row.getSessionId(),session)||!"TOOL_OUTPUT".equals(row.getKind()))throw PcException.deny("工具产物不属于当前任务用户、工作区和会话");
        return row;
    }
    /** 仅为已校验的Runner任务构造内存传输视图，正式记录仍保持artifact引用。 */
    public Map<String,Object> hydrateLedgerImages(Map<String,Object> payload,long workspace,long owner) {
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>) hydrate(payload,workspace,owner,0);
        return result;
    }
    private Object hydrate(Object value,long workspace,long owner,int depth) {
        if(depth>32)throw PcException.invalid("账本嵌套超过上限");
        if(value instanceof Map<?,?> map){
            if("image_artifact".equals(map.get("type"))){
                long id;try{id=Long.parseLong(String.valueOf(map.get("artifactId")));}catch(RuntimeException invalid){throw PcException.invalid("图片引用无效");}
                Map<String,Object> image=runnerImage(id,workspace,owner);
                return Map.of("type","image_base64","imageBase64",image.get("base64Data"),"mimeType",image.get("mimeType"));
            }
            Map<String,Object> result=new LinkedHashMap<>();for(var entry:map.entrySet())result.put(String.valueOf(entry.getKey()),hydrate(entry.getValue(),workspace,owner,depth+1));return result;
        }
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();for(Object item:list)result.add(hydrate(item,workspace,owner,depth+1));return result;}
        return value;
    }
    private Object rewrite(Object value,long workspace,long owner,Long session,Long task,int depth) {
        if(depth>32)throw PcException.invalid("图片账本嵌套超过上限");
        if(value instanceof Map<?,?> map){
            if(map.get("imageBase64") instanceof String encoded && !encoded.isBlank()){
                if(encoded.length()>MAX_API_IMAGE_BYTES*4L/3+8)throw new PcException(413,"PC_IMAGE_SIZE","账本图片超出5MB");
                byte[] original;
                try{original=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw PcException.invalid("图片base64无效");}
                ImageBytes image=normalizeImage(original,String.valueOf(map.getOrDefault("mimeType",null)));
                PcRecord record=persist(workspace,owner,session,task,"LEDGER_IMAGE","模型图片",image.mimeType(),image.bytes(),Map.of("width",image.width(),"height",image.height()));
                return Map.of("type","image_artifact","artifactId",String.valueOf(record.getId()),"mimeType",image.mimeType());
            }
            Map<String,Object> result=new LinkedHashMap<>();for(var entry:map.entrySet())result.put(String.valueOf(entry.getKey()),rewrite(entry.getValue(),workspace,owner,session,task,depth+1));return result;
        }
        if(value instanceof List<?> list){if(list.size()>10000)throw PcException.invalid("账本列表超过上限");List<Object> result=new ArrayList<>();for(Object item:list)result.add(rewrite(item,workspace,owner,session,task,depth+1));return result;}
        return value;
    }
    private PcRecord persist(long workspace,long owner,Long session,Long task,String purpose,String name,String mime,byte[] bytes,Map<String,Object> extra) {
        if(bytes.length>MAX_DATA_BYTES)throw new PcException(413,"PC_ARTIFACT_SIZE","附件超出50MB");
        Path file=null;
        try {
            Path root=privateStorage(true);
            String key=UUID.randomUUID()+".data";file=root.resolve(key);
            Path temp=root.resolve(UUID.randomUUID()+".tmp");
            try{try(FileChannel channel=FileChannel.open(temp,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),ownerAttribute(root,false))){protectNewNode(temp,false);ByteBuffer content=ByteBuffer.wrap(bytes);while(content.hasRemaining())channel.write(content);channel.force(true);}Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);requirePrivate(file,false);}
            finally{Files.deleteIfExists(temp);}
            Path created=file;
            if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(created);}catch(IOException ignored){}}});
            Map<String,Object> data=new LinkedHashMap<>(extra);data.put("storageKey",key);data.put("fileName",name);data.put("mimeType",mime);data.put("size",bytes.length);data.put("sha256",digest(bytes));data.put("purpose",purpose);
            PcRecord record=store.insert(PcTable.ARTIFACT,workspace,owner,task,session,null,purpose,"ACTIVE",null,data);
            Map<String,Object> workspaceData=store.data(store.require(PcTable.WORKSPACE,workspace,false));
            Map<String,Object> policy=workspaceData.get("policy") instanceof Map<?,?>?PcJson.object(workspaceData,"policy"):workspaceData;
            PcRecord currentPolicy=store.mapper.findKey(PcTable.POLICY_VERSION,"workspace:"+workspace,false);if(currentPolicy!=null)policy=store.data(currentPolicy);
            Object configured=policy.getOrDefault("retentionDays",90);long days=configured instanceof Number number?number.longValue():Long.parseLong(String.valueOf(configured));
            if(days<1||days>3650)throw PcException.invalid("产物保留时间应为1至3650天");
            record.setExpiresAt(store.clock.instant().plusSeconds(86400L*days));store.save(PcTable.ARTIFACT,record,data);return record;
        }catch(Exception failure){if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){}if(failure instanceof PcException pc)throw pc;throw unavailable();}
    }
    private ArtifactContent verified(PcRecord row) {
        if(!"ACTIVE".equals(row.getState())||row.getExpiresAt()==null||!row.getExpiresAt().isAfter(store.clock.instant()))throw new PcException(410,"PC_ARTIFACT_EXPIRED","附件已过期或撤销");
        Map<String,Object> data=store.data(row);String key=PcJson.text(data,"storageKey");
        if(!key.matches("[0-9a-f-]{36}\\.data"))throw PcException.deny("附件存储标识不合法");
        try{
            Path root=privateStorage(false),file=root.resolve(key);requirePrivate(file,false);
            if(Files.isSymbolicLink(file)||!file.toRealPath().startsWith(root)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw PcException.deny("附件文件越界或不再有效");
            long size=Files.size(file);if(size!=PcJson.number(data,"size")||size>("TOOL_OUTPUT".equals(row.getKind())?MAX_TOOL_OUTPUT_BYTES:MAX_DATA_BYTES))throw PcException.conflict("附件文件大小已改变");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];try(InputStream in=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}String hash=HexFormat.of().formatHex(digest.digest());if(!hash.equals(PcJson.text(data,"sha256")))throw PcException.conflict("附件摘要已改变");
            return new ArtifactContent(file,safeName(PcJson.text(data,"fileName")),PcJson.text(data,"mimeType"),hash,size);
        }catch(IOException|java.security.NoSuchAlgorithmException failure){throw unavailable();}
    }
    public Map<String,Object> view(PcRecord row){Map<String,Object> result=store.view(row);result.remove("storageKey");result.put("contentType",result.get("mimeType"));return result;}
    /** 新节点在写入正文前获得真实私有权限；既有目录权限改变时拒绝，不静默修复。 */
    private Path privateStorage(boolean create)throws IOException {
        Deque<Path> missing=new ArrayDeque<>();Path existing=storageRoot;
        while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS)){if(!create)throw new IOException("artifact root missing");missing.addFirst(existing);existing=existing.getParent();if(existing==null)throw new IOException("artifact parent missing");}
        requireCanonicalAncestors(existing);requireSafeParentChain(existing);
        for(Path path:missing){Path parent=path.getParent();requireSafeParentChain(parent);Files.createDirectory(path,ownerAttribute(parent,true));protectNewNode(path,true);}
        requireSafeParentChain(storageRoot.getParent());requirePrivate(storageRoot,true);return storageRoot;
    }
    private static void requireCanonicalAncestors(Path path)throws IOException {
        for(Path node=path.toAbsolutePath().normalize();node!=null;node=node.getParent())if(Files.isSymbolicLink(node)||!node.equals(node.toRealPath()))throw new IOException("artifact path contains link or reparse redirect");
    }
    /** 私有根的祖先不能让普通本机用户替换节点；仅系统管理员仍属于操作系统特权边界。 */
    private static void requireSafeParentChain(Path path)throws IOException {
        for(Path node=path;node!=null;node=node.getParent()){
            if(Files.isSymbolicLink(node)||!node.toAbsolutePath().normalize().equals(node.toRealPath()))throw new IOException("artifact parent contains reparse redirect");
            if(!Files.isDirectory(node,LinkOption.NOFOLLOW_LINKS))throw new IOException("artifact parent is not a directory");
            UserPrincipal current=currentOwner(node),owner=Files.getOwner(node,LinkOption.NOFOLLOW_LINKS);
            if(!owner.equals(current)&&!systemAuthority(node,owner))throw new IOException("artifact parent owner is outside trusted local boundary");
            PosixFileAttributeView posix=Files.getFileAttributeView(node,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(posix!=null){Set<PosixFilePermission> mode=posix.readAttributes().permissions();if(mode.contains(PosixFilePermission.GROUP_WRITE)||mode.contains(PosixFilePermission.OTHERS_WRITE))throw new IOException("artifact parent permits non-owner replacement");continue;}
            AclFileAttributeView acl=Files.getFileAttributeView(node,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(acl==null)throw new IOException("artifact parent ACL unavailable");
            for(AclEntry entry:acl.getAcl()){
                if(entry.type()!=AclEntryType.ALLOW||entry.flags().contains(AclEntryFlag.INHERIT_ONLY)||entry.principal().equals(current)||systemAuthority(node,entry.principal()))continue;
                EnumSet<AclEntryPermission> changes=EnumSet.of(AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.DELETE,AclEntryPermission.DELETE_CHILD,AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.WRITE_NAMED_ATTRS,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER);
                // Windows卷根默认允许创建旁支目录；单独ADD_SUBDIRECTORY不授予删改既有子节点的权限。
                if(node.getParent()==null)changes.remove(AclEntryPermission.APPEND_DATA);
                if(entry.permissions().stream().anyMatch(changes::contains))throw new IOException("artifact parent grants non-owner mutation authority");
            }
        }
    }
    private static boolean systemAuthority(Path path,UserPrincipal principal){
        boolean posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null;
        for(String name:posix?List.of("root"):List.of("NT AUTHORITY\\SYSTEM","BUILTIN\\Administrators","NT SERVICE\\TrustedInstaller","S-1-5-18","S-1-5-32-544"))try{if(principal.equals(path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name)))return true;}catch(IOException unavailable){/* 未解析主体不会被默认为系统特权账号。 */}
        return false;
    }
    private static UserPrincipal currentOwner(Path path)throws IOException {return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));}
    private static Set<PosixFilePermission> privateMode(boolean directory){return PosixFilePermissions.fromString(directory?"rwx------":"rw-------");}
    private static List<AclEntry> ownerAcl(UserPrincipal owner,boolean directory){
        EnumSet<AclEntryPermission> data=EnumSet.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA,AclEntryPermission.READ_NAMED_ATTRS,AclEntryPermission.WRITE_NAMED_ATTRS,AclEntryPermission.READ_ATTRIBUTES,AclEntryPermission.WRITE_ATTRIBUTES,AclEntryPermission.READ_ACL,AclEntryPermission.WRITE_ACL,AclEntryPermission.WRITE_OWNER,AclEntryPermission.DELETE,AclEntryPermission.SYNCHRONIZE);
        AclEntry file=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(data).build();if(!directory)return List.of(file);
        EnumSet<AclEntryPermission> traversable=EnumSet.copyOf(data);traversable.add(AclEntryPermission.EXECUTE);traversable.add(AclEntryPermission.DELETE_CHILD);
        AclEntry dirs=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(traversable).setFlags(AclEntryFlag.DIRECTORY_INHERIT).build();
        AclEntry files=AclEntry.newBuilder(file).setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.INHERIT_ONLY).build();return List.of(dirs,files);
    }
    private static FileAttribute<?> ownerAttribute(Path parent,boolean directory)throws IOException {
        if(Files.getFileAttributeView(parent,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)return PosixFilePermissions.asFileAttribute(privateMode(directory));
        if(Files.getFileAttributeView(parent,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)==null)throw new IOException("artifact filesystem cannot prove private ACL");
        List<AclEntry> entries=ownerAcl(currentOwner(parent),directory);return new FileAttribute<List<AclEntry>>(){public String name(){return "acl:acl";}public List<AclEntry> value(){return entries;}};
    }
    private static void protectNewNode(Path path,boolean directory)throws IOException {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)Files.setPosixFilePermissions(path,privateMode(directory));
        else {AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(view==null||!view.getOwner().equals(currentOwner(path)))throw new IOException("artifact node owner cannot be proved");view.setAcl(ownerAcl(currentOwner(path),directory));}
        requirePrivate(path,directory);
    }
    static void requirePrivate(Path path,boolean directory)throws IOException {
        requireCanonicalAncestors(path);
        if(directory?!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("artifact node type changed");
        UserPrincipal owner=currentOwner(path);if(!Files.getOwner(path,LinkOption.NOFOLLOW_LINKS).equals(owner))throw new IOException("artifact node belongs to another local account");
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null){if(!Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).equals(privateMode(directory)))throw new IOException("artifact private mode changed");return;}
        AclFileAttributeView view=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(view==null)throw new IOException("artifact ACL unavailable");
        EnumSet<AclEntryPermission> actual=EnumSet.noneOf(AclEntryPermission.class);for(AclEntry entry:view.getAcl()){
            if(entry.type()==AclEntryType.ALLOW&&!entry.principal().equals(owner)&&!entry.permissions().isEmpty())throw new IOException("artifact has non-owner ACL grants");
            if(entry.type()==AclEntryType.DENY&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY))throw new IOException("artifact private ACL cannot prove owner access");
            if(entry.type()==AclEntryType.ALLOW&&entry.principal().equals(owner)&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY))actual.addAll(entry.permissions());
        }
        if(!actual.containsAll(Set.of(AclEntryPermission.READ_DATA,AclEntryPermission.WRITE_DATA,AclEntryPermission.APPEND_DATA))||actual.contains(AclEntryPermission.EXECUTE)!=directory)throw new IOException("artifact owner data permissions differ from private node role");
    }
    static ImageBytes normalizeImage(byte[] bytes,String declaredMime) {
        if(bytes==null||bytes.length==0||bytes.length>MAX_API_IMAGE_BYTES)throw new PcException(413,"PC_IMAGE_SIZE","API图片上限为5MB");
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw PcException.invalid("附件不是可解码的图片");ImageReader reader=readers.next();
            try{
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||(long)width*height>MAX_IMAGE_PIXELS)throw new PcException(413,"PC_IMAGE_PIXELS","图片超过四千万像素上限");
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);String detected=format.equals("jpeg")||format.equals("jpg")?"image/jpeg":format.equals("png")?"image/png":format.equals("gif")?"image/gif":null;
                if(detected==null||declaredMime==null||!detected.equalsIgnoreCase(declaredMime))throw PcException.invalid("图片实际格式与MIME不一致或不受支持");
                BufferedImage image=reader.read(0);ByteArrayOutputStream output=new ByteArrayOutputStream();String target=detected.equals("image/jpeg")?"jpeg":"png";
                // 不带源metadata编码，移除EXIF位置、注释及潜在隐私；PNG保留透明通道。
                if(!ImageIO.write(image,target,output))throw PcException.invalid("图片编码器不可用");byte[] normalized=output.toByteArray();
                if(normalized.length>MAX_API_IMAGE_BYTES)throw new PcException(413,"PC_IMAGE_SIZE","去除元数据后的图片超过5MB，请压缩再上传");
                return new ImageBytes(normalized,target.equals("jpeg")?"image/jpeg":"image/png",width,height);
            }finally{reader.dispose();}
        }catch(IOException failure){throw PcException.invalid("图片损坏或无法完整解码");}
    }
    static String safeName(String name){if(name==null||name.isBlank()||name.length()>255||name.contains("/")||name.contains("\\")||name.chars().anyMatch(c->c<32))throw PcException.invalid("附件名称非法");return name;}
    static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static PcException unavailable(){return new PcException(503,"PC_ARTIFACT_STORAGE","附件存储不可用，未确认上传或下载");}
}
