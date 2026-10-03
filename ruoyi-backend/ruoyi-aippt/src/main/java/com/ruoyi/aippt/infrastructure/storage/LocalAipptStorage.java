package com.ruoyi.aippt.infrastructure.storage;
import java.io.IOException;
import java.nio.file.*;
/** 只接收服务端 UUID key；禁止符号链接与公开 profile 目录。 */
public class LocalAipptStorage implements AipptStoragePort  {
    private final Path root;
    public LocalAipptStorage(String path)  {
        if(path==null||path.isBlank())throw new IllegalArgumentException("aippt.storage-path必须是批准的私有目录");
        root=Path.of(path).toAbsolutePath().normalize();
    }
    private Path resolve(String key)throws IOException  {
        if(key==null||!key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}"))throw new IOException("非法对象key");
        Path target=root.resolve(key).normalize();
        if(!target.startsWith(root))throw new IOException("对象路径越界");
        Path cursor=root;
        for(Path part:root.relativize(target))  {
            if(Files.isSymbolicLink(cursor))throw new IOException("拒绝符号链接路径");
            cursor=cursor.resolve(part);
        }
        if(Files.isSymbolicLink(target))throw new IOException("拒绝符号链接对象");
        return target;
    }
    public void put(String key,byte[] data,String mime)throws IOException  {
        Path target=resolve(key);
        Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),"aippt-",".tmp");
        try  {
            Files.write(temporary,data);
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        }
        finally  {
            Files.deleteIfExists(temporary);
        }
    }
    public byte[] get(String key)throws IOException  {
        Path path=resolve(key);
        if(!Files.isRegularFile(path))throw new IOException("对象缺失");
        if(Files.size(path)>100*1024*1024L)throw new IOException("对象超限");
        return Files.readAllBytes(path);
    }
    public void delete(String key)throws IOException  {
        Files.deleteIfExists(resolve(key));
    }
}
