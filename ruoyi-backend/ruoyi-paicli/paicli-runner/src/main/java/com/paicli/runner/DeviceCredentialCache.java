package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 只保存中心签发的受限设备凭证；目录归本机用户独占，不保存模型Key或执行任务。 */
public final class DeviceCredentialCache {
    private final Path directory;
    private final Path file;
    public DeviceCredentialCache(Path state,String centerUrl,String rootFingerprint)throws IOException{
        Path realState=RunnerPrivateDirectory.createDirectories(state);
        directory=RunnerPrivateDirectory.createDirectories(realState.resolve("device-credentials"));
        file=directory.resolve(CanonicalJson.hash(CanonicalJson.MAPPER.getNodeFactory().textNode(centerUrl.replaceAll("/+$","")+"\n"+rootFingerprint))+".json");
    }
    public String select(String supplied)throws IOException{
        JsonNode saved=read();
        if(supplied==null||supplied.isBlank()){
            if(saved==null)throw new IOException("首次连接须从安全输入提供 PAICLI_RUNNER_TOKEN 绑定码");
            return credential(saved.path("deviceToken").asText());
        }
        supplied=credential(supplied);
        if(saved!=null&&!saved.path("deviceToken").asText().isBlank()&&(hash(supplied).equals(saved.path("bootstrapHash").asText())||supplied.equals(saved.path("deviceToken").asText())))return credential(saved.path("deviceToken").asText());
        return supplied;
    }
    public void store(String token,String bootstrap)throws IOException{
        token=credential(token);JsonNode previous=read();var saved=CanonicalJson.MAPPER.createObjectNode();saved.put("deviceToken",token);
        saved.put("bootstrapHash",previous!=null&&bootstrap.equals(previous.path("deviceToken").asText())&&!previous.path("bootstrapHash").asText().isBlank()?previous.path("bootstrapHash").asText():hash(credential(bootstrap)));
        save(saved);
    }
    public String exchangeKey(String token)throws IOException{
        JsonNode previous=read();
        String fingerprint=hash(credential(token));
        if(previous!=null&&fingerprint.equals(previous.path("exchangeTokenHash").asText(previous.path("bootstrapHash").asText()))&&!previous.path("exchangeKey").asText().isBlank())return previous.path("exchangeKey").asText();
        var pending=previous==null?CanonicalJson.MAPPER.createObjectNode():(com.fasterxml.jackson.databind.node.ObjectNode)previous.deepCopy();
        if(!pending.has("bootstrapHash"))pending.put("bootstrapHash",fingerprint);
        pending.put("exchangeTokenHash",fingerprint).put("exchangeKey",UUID.randomUUID().toString());pending.remove("exchangeRequest");save(pending);return pending.path("exchangeKey").asText();
    }
    /** 相同键总是配合同一请求体，进程中断后也不能用变化的目录证明复用旧键。 */
    public JsonNode exchangeRequest(String token,JsonNode request)throws IOException{
        exchangeKey(token);var pending=(com.fasterxml.jackson.databind.node.ObjectNode)read();
        if(pending.has("exchangeRequest"))return pending.get("exchangeRequest").deepCopy();
        pending.set("exchangeRequest",request.deepCopy());save(pending);return request;
    }
    public void completeExchange(String token)throws IOException{
        JsonNode previous=read();if(previous==null)return;
        var saved=(com.fasterxml.jackson.databind.node.ObjectNode)previous.deepCopy();
        if(!hash(credential(token)).equals(saved.path("exchangeTokenHash").asText()))throw new IOException("设备凭证交换状态不符");
        saved.remove(List.of("exchangeKey","exchangeTokenHash","exchangeRequest"));save(saved);
    }
    private void save(JsonNode saved)throws IOException{
        RunnerPrivateDirectory.requireDirectory(directory);
        Path temporary=RunnerPrivateDirectory.createTempFile(directory,".credential-",".tmp");
        try{
            byte[] bytes=saved.toString().getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            RunnerPrivateDirectory.requireDirectory(directory);
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireRegularFile(file);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);RunnerPrivateDirectory.requireRegularFile(file);
        }finally{Files.deleteIfExists(temporary);}
    }
    private JsonNode read()throws IOException{
        RunnerPrivateDirectory.requireDirectory(directory);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16384)throw new IOException("设备凭证缓存非法");
        RunnerPrivateDirectory.requireRegularFile(file);JsonNode saved=CanonicalJson.MAPPER.readTree(Files.readAllBytes(file));
        if(saved==null||!saved.isObject())throw new IOException("设备凭证缓存无法读取");return saved;
    }
    private static String credential(String token)throws IOException{
        if(token==null||token.isBlank()||token.length()>2048||token.chars().anyMatch(c->c<=32||c>126))throw new IOException("设备凭证格式非法");return token;
    }
    private static String hash(String token)throws IOException{return CanonicalJson.hash(CanonicalJson.MAPPER.getNodeFactory().textNode(token));}
    static void restrict(Path path)throws IOException{
        PosixFileAttributeView posix=Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));return;}
        AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl==null)throw new IOException("当前文件系统无法保护设备凭证");
        UserPrincipal current=path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!acl.getOwner().equals(current))throw new IOException("设备凭证目录不属于当前本机用户");
        AclEntry.Builder entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if(Files.isDirectory(path))entry.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }
}
