package com.paicli.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;

/** 私有启动监管信号；只允许进程身份与连接状态，不保存任何凭证或执行内容。 */
final class RunnerReadiness {
    static final class PublicationBusy extends IOException{PublicationBusy(IOException cause){super("就绪状态暂时被读取占用",cause);}}
    static synchronized void write(Path state,JsonNode identity,Instant expiry,boolean terminalReady)throws IOException{
        var report=CanonicalJson.MAPPER.createObjectNode().put("pid",ProcessHandle.current().pid())
            .put("checkedAt",Instant.now().toString()).put("sessionExpiresAt",expiry.toString()).put("terminalReady",terminalReady);
        for(String field:new String[]{"runnerId","ownerUserId","directoryContextId"}){
            if(identity!=null&&identity.hasNonNull(field))report.put(field,identity.path(field).asText());else report.putNull(field);
        }
        RunnerPrivateDirectory.requireDirectory(state);Path target=state.resolve("runner-readiness.json");
        Path temporary=RunnerPrivateDirectory.createTempFile(state,".readiness-",".tmp");
        try{
            byte[] bytes=report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)){
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            for(int attempt=0;;attempt++){
                RunnerPrivateDirectory.requireDirectory(state);
                if(java.nio.file.Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS))RunnerPrivateDirectory.requireRegularFile(target);
                requireReplaceable(state,target);
                try{java.nio.file.Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);break;}
                catch(java.nio.file.AccessDeniedException sharing){
                    if(!System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win"))throw sharing;
                    if(attempt>=3)throw new PublicationBusy(sharing);
                    try{Thread.sleep(20L<<attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("就绪状态发布被中断",interrupted);}
                }
            }
            RunnerPrivateDirectory.requireRegularFile(target);
        }finally{java.nio.file.Files.deleteIfExists(temporary);}
    }
    private static void requireReplaceable(Path state,Path target)throws IOException{
        var view=java.nio.file.Files.getFileAttributeView(state,java.nio.file.attribute.AclFileAttributeView.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if(view==null)return;
        var user=state.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        boolean canDeleteChild=view.getAcl().stream().anyMatch(e->e.type()==java.nio.file.attribute.AclEntryType.ALLOW&&e.principal().equals(user)&&e.permissions().contains(java.nio.file.attribute.AclEntryPermission.DELETE_CHILD)&&!e.flags().contains(java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY));
        if(!canDeleteChild)throw new IOException("就绪状态目录不允许原子替换文件");
        if(java.nio.file.Files.exists(target,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            var dos=java.nio.file.Files.getFileAttributeView(target,java.nio.file.attribute.DosFileAttributeView.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if(dos!=null&&dos.readAttributes().isReadOnly())throw new IOException("就绪状态文件已变为只读");
        }
    }
}
