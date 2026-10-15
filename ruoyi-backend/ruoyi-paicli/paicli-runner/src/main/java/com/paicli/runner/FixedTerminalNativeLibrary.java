package com.paicli.runner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;

/** 仅当前固定分发包的JLine Windows DLL可执行；个人状态及通用临时文件仍不可执行。 */
public final class FixedTerminalNativeLibrary {
    private static final String RESOURCE="org/jline/nativ/Windows/x86_64/jlinenative.dll";
    private static final String NAME="jlinenative.dll";
    private static final int MAX_BYTES=5*1024*1024;
    private FixedTerminalNativeLibrary(){}

    public static synchronized Path prepare(Path artifact,String artifactHash,Path nativeCacheRoot)throws IOException{
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")
                ||!Set.of("amd64","x86_64").contains(System.getProperty("os.arch","")))throw new IOException("固定JLine DLL仅支持Windows x86_64");
        Path jar=artifact.toAbsolutePath().normalize();
        if(!artifactHash.matches("[a-f0-9]{64}")||!jar.equals(jar.toRealPath())||!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS)
                )throw new IOException("JLine固定分发包身份已改变");
        // 固定部署包可位于既有工具运行目录；仅叶文件须本人私有，派生DLL另走严格个人状态父链。
        requireOwnerOnly(jar,false);
        byte[] artifactBytes=Files.readAllBytes(jar);
        if(!artifactHash.equals(CanonicalJson.sha256(artifactBytes))||!jar.equals(jar.toRealPath())||!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))throw new IOException("JLine固定分发包身份已改变");
        requireOwnerOnly(jar,false);
        byte[] library=null;
        // 提取必须来自已核摘要的同一快照，不能再按可变部署路径重新打开JAR。
        try(ZipInputStream archive=new ZipInputStream(new ByteArrayInputStream(artifactBytes))){
            java.util.zip.ZipEntry entry;
            while((entry=archive.getNextEntry())!=null)if(RESOURCE.equals(entry.getName())){
                if(entry.isDirectory()||entry.getSize()>MAX_BYTES)throw new IOException("固定分发缺少受支持的JLine DLL");
                library=archive.readNBytes(MAX_BYTES+1);break;
            }
        }
        if(library==null||library.length==0||library.length>MAX_BYTES)throw new IOException("固定JLine DLL缺失或大小无效");
        String libraryHash=CanonicalJson.sha256(library);
        Path directory=RunnerPrivateDirectory.createDirectories(nativeCacheRoot.resolve(artifactHash));Path target=directory.resolve(NAME);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){requireLibrary(target,libraryHash);return directory;}
        Path temporary=RunnerPrivateDirectory.createTempFile(directory,".jline-",".tmp");
        boolean executableStaged=false;
        try{
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){
                ByteBuffer buffer=ByteBuffer.wrap(library);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            RunnerPrivateDirectory.requireRegularFile(temporary);
            if(!libraryHash.equals(CanonicalJson.sha256(Files.readAllBytes(temporary))))throw new IOException("JLine DLL写入校验失败");
            // 仅刚创建且已核hash的固定DLL增加Execute；已有文件从不修补ACL。
            var acl=Files.getFileAttributeView(temporary,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(acl==null)throw new IOException("JLine DLL缺少Windows私有ACL");
            List<AclEntry> entries=new ArrayList<>();for(AclEntry entry:acl.getAcl()){
                var permissions=EnumSet.copyOf(entry.permissions());permissions.add(AclEntryPermission.EXECUTE);
                entries.add(AclEntry.newBuilder(entry).setPermissions(permissions).build());
            }
            acl.setAcl(entries);executableStaged=true;requireLibrary(temporary,libraryHash);
            RunnerPrivateDirectory.requireDirectory(directory);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("固定JLine DLL并发发布冲突");
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);requireLibrary(target,libraryHash);return directory;
        }finally{if(Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)){
            if(executableStaged)requireLibrary(temporary,libraryHash);else RunnerPrivateDirectory.requireRegularFile(temporary);
            Files.delete(temporary);
        }}
    }

    private static void requireLibrary(Path file,String expectedHash)throws IOException{
        RunnerPrivateDirectory.requireDirectory(file.getParent());Path normalized=file.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(file)||!normalized.equals(file.toRealPath())||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(file)>MAX_BYTES)throw new IOException("固定JLine DLL节点身份无效");
        requireOwnerOnly(file,true);
        if(!expectedHash.equals(CanonicalJson.sha256(Files.readAllBytes(file))))throw new IOException("固定JLine DLL hash发生变化");
    }
    private static void requireOwnerOnly(Path file,boolean executable)throws IOException{
        var owner=file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        if(!Files.getOwner(file,LinkOption.NOFOLLOW_LINKS).equals(owner))throw new IOException("固定JLine DLL不属于当前用户");
        var acl=Files.getFileAttributeView(file,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(acl==null)throw new IOException("固定JLine DLL缺少ACL");
        EnumSet<AclEntryPermission> actual=EnumSet.noneOf(AclEntryPermission.class);
        for(AclEntry entry:acl.getAcl()){
            if(entry.type()==AclEntryType.DENY&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
                    ||entry.type()==AclEntryType.ALLOW&&!entry.principal().equals(owner)&&!entry.permissions().isEmpty())throw new IOException("固定JLine DLL不是私有权限");
            if(entry.type()==AclEntryType.ALLOW&&entry.principal().equals(owner)&&!entry.flags().contains(AclEntryFlag.INHERIT_ONLY))actual.addAll(entry.permissions());
        }
        if(!actual.contains(AclEntryPermission.READ_DATA)||executable&&!actual.contains(AclEntryPermission.EXECUTE))throw new IOException("固定JLine分发文件权限发生变化");
    }
}
