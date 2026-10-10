package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 工具返回后先保存完整原始结果，含全部图片；只供对账，不能据此执行或批准。 */
final class PendingImageEvidence {
    // 64MB原始协议/文件输出的JSON最坏转义可达6倍，另保留多图元数据空间。
    private static final long MAX_ITEM=512L*1024*1024;
    private static final long MAX_TOTAL=2048L*1024*1024;
    private static final int MAX_FILES=256;
    private static final ConcurrentHashMap<Path,Quota> QUOTAS=new ConcurrentHashMap<>();
    private final Path directory;
    private final Quota quota;
    /** 记录的是尚未落盘的保留量；实际已存文件由目录扫描核对，不能两次计费。 */
    private static final class Quota { long remainingBytes;int remainingFiles; }
    private record Stored(long bytes,int files) {}

    PendingImageEvidence(Path state)throws IOException {
        directory=RunnerPrivateDirectory.createDirectories(state.resolve("pending-derived-images")).toRealPath();
        RunnerPrivateDirectory.requireDirectory(directory);
        quota=QUOTAS.computeIfAbsent(directory,ignored->new Quota());
    }
    Reservation reserve()throws IOException {
        synchronized(quota){
            Stored stored=stored();requireCapacity(stored,0,0,quota.remainingBytes+MAX_ITEM,quota.remainingFiles+2);
            quota.remainingBytes+=MAX_ITEM;quota.remainingFiles+=2;return new Reservation();
        }
    }
    final class Reservation implements AutoCloseable {
        private boolean released;
        private long retainedBytes;
        private final Set<Path> retained=new HashSet<>();
        Path preserveOutput(ObjectNode binding,ToolOutput output)throws IOException{
            return persist(outputEvidence(binding,output),this);
        }
        Path preserveModification(ObjectNode binding,ToolOutput output,JsonNode modification)throws IOException{
            ObjectNode evidence=outputEvidence(binding,output);
            evidence.set("fileModification",modification.deepCopy());
            return persist(evidence,this);
        }
        @Override public void close(){synchronized(quota){if(!released){quota.remainingBytes-=MAX_ITEM-retainedBytes;quota.remainingFiles-=2-retained.size();released=true;}}}
    }
    Path preserve(ObjectNode binding,int index,JsonNode part)throws IOException {
        ObjectNode evidence=identity(binding);
        evidence.put("phase","IMAGE_UPLOAD_PENDING");evidence.put("partIndex",index);evidence.set("part",part.deepCopy());
        return persist(evidence,null);
    }
    Path preserveOutput(ObjectNode binding,ToolOutput output)throws IOException {
        return persist(outputEvidence(binding,output),null);
    }
    private ObjectNode outputEvidence(ObjectNode binding,ToolOutput output){
        ObjectNode evidence=identity(binding);evidence.put("phase","EXECUTION_RESULT_UNCONFIRMED");
        evidence.set("result",CanonicalJson.MAPPER.valueToTree(output));return evidence;
    }
    private ObjectNode identity(ObjectNode binding){
        ObjectNode evidence=CanonicalJson.MAPPER.createObjectNode();
        for(String key:new String[]{"taskId","sessionId","ownerUserId","directoryContextId","runnerId","leaseId","fencingToken","version","policyVersion","rootFingerprint","toolCallId","argsHash","resourceHash","planVersion"})
            if(binding.has(key))evidence.set(key,binding.get(key).deepCopy());
        return evidence;
    }
    private Path persist(ObjectNode evidence,Reservation reservation)throws IOException {
        // 单次序列化，避免在容量检查与写入时同时持有两份最多512MB的结果。
        byte[] bytes=CanonicalJson.MAPPER.writeValueAsBytes(evidence);
        if(bytes.length>MAX_ITEM)throw new IOException("原始工具证据超过本机有界缓存上限，停止并对账");
        String contentHash=CanonicalJson.sha256(bytes);Path destination=directory.resolve(contentHash+".json");
        synchronized(quota){
            RunnerPrivateDirectory.requireDirectory(directory);
            if(reservation!=null&&reservation.released)throw new IOException("执行证据的预留容量已经释放");
            boolean retained=reservation!=null&&reservation.retained.contains(destination);
            if(reservation!=null&&!retained&&(reservation.retained.size()>=2||bytes.length>MAX_ITEM-reservation.retainedBytes))throw new IOException("单次执行完整证据超过已预留容量，停止并对账");
            boolean exists=Files.exists(destination,LinkOption.NOFOLLOW_LINKS);
            if(exists){requirePath(destination);if(!contentHash.equals(digest(destination)))throw new IOException("既有图片证据摘要改变");}
            long creditBytes=reservation!=null&&!retained?bytes.length:0;int creditFiles=reservation!=null&&!retained?1:0;
            requireCapacity(stored(),exists?0:bytes.length,exists?0:1,quota.remainingBytes-creditBytes,quota.remainingFiles-creditFiles);
            if(!exists)RunnerPrivateDirectory.writeAtomic(directory,destination.getFileName().toString(),bytes);
            if(creditFiles!=0){reservation.retained.add(destination);reservation.retainedBytes+=bytes.length;quota.remainingBytes-=creditBytes;quota.remainingFiles--;}
            return destination;
        }
    }
    /** 崩溃遗留的私有tmp也占容量，不能靠文件后缀绕过总量限制。 */
    private Stored stored()throws IOException{
        RunnerPrivateDirectory.requireDirectory(directory);long bytes=0;int files=0;
        try(var entries=Files.newDirectoryStream(directory)){for(Path path:entries){requirePath(path);bytes=Math.addExact(bytes,Files.size(path));files++;if(bytes>MAX_TOTAL||files>MAX_FILES)throw new IOException("待确认完整证据超过有界缓存容量");}}
        return new Stored(bytes,files);
    }
    private void requireCapacity(Stored stored,long bytes,int files,long reservedBytes,int reservedFiles)throws IOException{
        if(reservedBytes<0||reservedFiles<0||stored.bytes()+bytes+reservedBytes>MAX_TOTAL||stored.files()+files+reservedFiles>MAX_FILES||Files.getFileStore(directory).getUsableSpace()<bytes+reservedBytes)throw new IOException("完整工具证据缓存或磁盘容量不足，执行前停止并对账");
    }
    void acknowledged(Path path)throws IOException{synchronized(quota){requirePath(path);Files.delete(path);RunnerPrivateDirectory.requireDirectory(directory);}}
    private void requirePath(Path path)throws IOException{
        if(!directory.equals(path.toAbsolutePath().normalize().getParent()))throw new IOException("图片证据路径非法");RunnerPrivateDirectory.requireRegularFile(path);
        if(Files.size(path)>MAX_ITEM)throw new IOException("图片证据超过读取上限");
    }
    private String digest(Path path)throws IOException{
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)digest.update(buffer,0,count);}requirePath(path);return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
