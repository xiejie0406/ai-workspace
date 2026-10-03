package com.ruoyi.aippt.infrastructure.storage;

import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.domain.AipptException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 私有对象写入的磁盘补偿凭据；不保存正文、Provider参数或机器凭据。 */
public final class AssetCompensationJournal {
    private static final Set<String> FIELDS=Set.of("asset_id","project_id","object_key","owner_user_id","mime","size","sha256","role","created_at","status");
    private final Path directory;

    public AssetCompensationJournal(String privatePath,String publicPath) {
        try {
            if(privatePath==null||privatePath.isBlank())throw new IOException("missing private path");
            Path path=Path.of(privatePath);
            if(!path.isAbsolute())throw new IOException("private path must be absolute");
            directory=path.normalize();
            if(directory.getParent()==null)throw new IOException("filesystem root is not private storage");
            if(publicPath!=null&&!publicPath.isBlank()&&directory.startsWith(Path.of(publicPath).toAbsolutePath().normalize()))throw new IOException("public profile");
            checkAncestors();
            Files.createDirectories(directory);
            checkAncestors();
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!Files.isWritable(directory))throw new IOException("unwritable private directory");
        }
        catch(IOException|IllegalArgumentException e) { throw unavailable(); }
    }

    private void checkAncestors() throws IOException {
        for(Path path=directory;path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw new IOException("symbolic link");
    }
    private Path file(String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("invalid stage id");
        checkAncestors();
        Path result=directory.resolve(id+".json");
        if(Files.isSymbolicLink(result))throw new IOException("symbolic link");
        return result;
    }

    public void started(JSONObject asset) {
        JSONObject entry=new JSONObject();
        entry.put("asset_id",asset.getString("id"));
        for(String field:List.of("project_id","object_key","owner_user_id","mime","size","sha256","role"))entry.put(field,asset.get(field));
        entry.put("created_at",Instant.now().toString());
        entry.put("status","stage_started");
        store(entry);
    }
    public synchronized void mark(String assetId,String status) {
        try {
            JSONObject entry=JSON.parseObject(Files.readString(file(assetId),StandardCharsets.UTF_8));
            entry.put("status",status);
            store(entry);
        }
        catch(IOException e) { throw unavailable(); }
    }
    public JSONObject get(String assetId) {
        try { return JSON.parseObject(Files.readString(file(assetId),StandardCharsets.UTF_8)); }
        catch(IOException e) { throw unavailable(); }
    }

    private synchronized void store(JSONObject entry) {
        Path temporary=null;
        try {
            if(!entry.keySet().equals(FIELDS)||!Objects.equals(entry.getString("object_key"),entry.getString("project_id")+"/"+entry.getString("asset_id")))throw new IOException("invalid stage metadata");
            Path destination=file(entry.getString("asset_id"));
            temporary=Files.createTempFile(directory,"stage-",".tmp");
            byte[] bytes=JSON.toJSONString(entry,JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining())channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        catch(IOException e) { throw unavailable(); }
        finally { if(temporary!=null)try { Files.deleteIfExists(temporary); } catch(IOException ignored) { } }
    }
    public List<JSONObject> pending(Instant before) {
        try {
            checkAncestors();
            List<JSONObject> entries=new ArrayList<>();
            try(var files=Files.list(directory)) {
                for(Path path:files.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted().toList()) {
                    JSONObject entry=get(path.getFileName().toString().substring(0,36));
                    if(Set.of("stage_started","cleanup_pending").contains(entry.getString("status"))&&!Instant.parse(entry.getString("created_at")).isAfter(before))entries.add(entry);
                    if(entries.size()==100)break;
                }
            }
            return entries;
        }
        catch(IOException e) { throw unavailable(); }
    }
    private static AipptException unavailable() { return new AipptException(503,"COMPENSATION_UNAVAILABLE","私有对象补偿目录缺失、不可写或不安全"); }
}
