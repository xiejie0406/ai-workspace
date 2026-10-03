package com.ruoyi.aipicture.storage;

import com.ruoyi.aipicture.config.*;
import com.ruoyi.aipicture.domain.*;
import com.ruoyi.aipicture.worker.*;
import com.ruoyi.aipicture.persistence.AiPictureRepository;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@AiPictureEnabled
public class AiPictureStorage implements DisposableBean {
    public static final long MAX_BYTES=20L*1024*1024;
    private static final Logger log=LoggerFactory.getLogger(AiPictureStorage.class);
    private static final Map<Path,ReentrantLock> MUTEXES=new ConcurrentHashMap<>();
    private final Path root;
    private final AiPictureWorkerClient worker;
    private final AiPictureRepository db;
    private final ReentrantLock mutex;
    private Stream<Path> sweep;
    private Iterator<Path> sweepCursor;
    private volatile long lastSweep;
    public AiPictureStorage(AiPictureProperties p,AiPictureWorkerClient worker,AiPictureRepository db) throws IOException {
        if(p.getStorageRoot().isBlank()) throw new IllegalStateException("图片私有存储目录未配置");
        root=Path.of(p.getStorageRoot()).toAbsolutePath().normalize();
        String publicProfile=com.ruoyi.common.config.RuoYiConfig.getProfile();
        if(publicProfile!=null&&!publicProfile.isBlank()&&root.startsWith(Path.of(publicProfile).toAbsolutePath().normalize())) throw new IllegalStateException("图片素材不能放进若依公开 /profile 目录");
        if(!Path.of(p.getStorageRoot()).isAbsolute()) throw new IllegalStateException("图片私有存储必须为绝对路径");
        Files.createDirectories(root);this.worker=worker;this.db=db;
        mutex=MUTEXES.computeIfAbsent(root.toRealPath(),ignored->new ReentrantLock());
    }
    public Map<String,Object> image(byte[] bytes,String suggestedId,String prefix) throws IOException {
        return image(bytes,suggestedId,prefix,32);
    }
    /** 分割对象的真实包围框可小于上传限制，私有图层产物不缩放原始像素。 */
    public Map<String,Object> artifactImage(byte[] bytes,String suggestedId,String prefix) throws IOException {
        return image(bytes,suggestedId,prefix,1);
    }
    private Map<String,Object> image(byte[] bytes,String suggestedId,String prefix,int minimumSide) throws IOException {
        if(bytes.length==0||bytes.length>MAX_BYTES) throw new AiPictureException(413,"IMAGE_TOO_LARGE","图片不能为空且不得超过 20 MB");
        String format;int width,height;boolean alpha;
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);
            if(readers.hasNext()) {
                var reader=readers.next();
                try {
                    reader.setInput(stream,true,true);format=reader.getFormatName().toLowerCase(Locale.ROOT);width=reader.getWidth(0);height=reader.getHeight(0);
                    limits(width,height,minimumSide);var image=reader.read(0);alpha=image.getColorModel().hasAlpha();
                    if(format.equals("jpeg")) format="jpg";
                } finally { reader.dispose(); }
            } else {
                var inspection=worker.call("compute",Json.of("schema_version","1","tool_registry_version","2026.10-r2","run_id",Json.id(),"attempt",1,"input_version",0,"tool","inspect_image","params",Json.of("content_base64",Base64.getEncoder().encodeToString(bytes),"minimum_side",minimumSide)));
                var metadata=Json.map(inspection.getOrDefault("result",inspection));format=Json.string(metadata.get("format")).toLowerCase(Locale.ROOT);if(format.equals("jpeg")) format="jpg";width=(int)Json.number(metadata.get("width"));height=(int)Json.number(metadata.get("height"));alpha=Json.bool(metadata.get("has_alpha"),false);limits(width,height,minimumSide);
            }
        } catch(AiPictureException e) { throw e; } catch(Exception e) { throw AiPictureException.invalid("图片实际解码失败，请使用有效 JPG/PNG/WebP"); }
        if(!Set.of("jpg","png","webp").contains(format)) throw AiPictureException.invalid("只支持 JPG/PNG/WebP");
        String id=suggestedId==null?Json.id():Json.uuid(suggestedId),key=prefix+"/"+id+"."+format;
        store(key,bytes);
        return Json.of("id",id,"object_key",key,"width",width,"height",height,"format",format,"has_alpha",alpha,"sha256",WorkerSignatures.sha256(bytes),"size_bytes",bytes.length);
    }
    public Map<String,Object> binary(byte[] bytes,String id,String format,String prefix) throws IOException {
        if(!Set.of("zip","png").contains(format)||bytes.length>200L*1024*1024) throw AiPictureException.invalid("导出格式或大小不合法");
        String key=prefix+"/"+Json.uuid(id)+"."+format;store(key,bytes);
        return Json.of("object_key",key,"sha256",WorkerSignatures.sha256(bytes),"format",format);
    }
    private void limits(int width,int height,int minimumSide) {
        if(Math.min(width,height)<minimumSide||(long)width*height>50_000_000L) throw AiPictureException.invalid("图片最短边至少 "+minimumSide+" 像素且总像素不得超过 5000 万");
    }
    public byte[] read(String key) throws IOException { return Files.readAllBytes(resolve(key)); }
    private FileChannel lockChannel() throws IOException {
        Path path=root.resolve(".aipicture-write.lock");
        if(Files.isSymbolicLink(path)) throw new IOException("非法私有存储锁路径");
        return FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
    }
    /** 文件锁保持到SQL事务结束，清理不能与其他中心节点的登记产生时间窗口。 */
    private void store(String key,byte[] bytes) throws IOException {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()) throw new IllegalStateException("图片对象登记必须处于所属业务事务");
        if(!mutex.tryLock()) throw new IOException("图片存储暂忙，请重交同一产物");
        FileChannel channel=null;FileLock guard=null;Path temporary=null;boolean deferred=false,created=false;
        try {
            Path target=resolve(key);
            channel=lockChannel();guard=channel.tryLock();if(guard==null) throw new IOException("图片存储暂忙，请重交同一产物");Files.createDirectories(target.getParent());
            if(Files.exists(target)) {
                if(!WorkerSignatures.sha256(Files.readAllBytes(target)).equals(WorkerSignatures.sha256(bytes))) throw new AiPictureException(409,"ARTIFACT_COLLISION","同一产物ID已有不同内容");
            } else {
                temporary=Files.createTempFile(target.getParent(),".aipicture-part-",".tmp");
                try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) output.write(buffer);output.force(true);
                }
                try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}
                catch(AtomicMoveNotSupportedException error) {Files.move(temporary,target);}
                created=true;
            }
            final FileChannel heldChannel=channel;final FileLock heldGuard=guard;final boolean newFile=created;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    try {
                        if(newFile&&status==STATUS_ROLLED_BACK&&!referenced(key)) Files.deleteIfExists(resolve(key));
                    } catch(Exception error) {log.warn("图片事务回滚文件待后续清理，未删除无法确认引用的对象");}
                    finally {unlock(heldGuard,heldChannel);mutex.unlock();}
                }
            });deferred=true;
        } finally {
            try {if(temporary!=null) Files.deleteIfExists(temporary);}
            catch(IOException error) {log.warn("图片写入临时文件保留，等待有界清理");}
            finally {if(!deferred) {unlock(guard,channel);mutex.unlock();}}
        }
    }
    private static void unlock(FileLock guard,FileChannel channel) {
        try {if(guard!=null&&guard.isValid()) guard.release();} catch(IOException ignored) {}
        try {if(channel!=null) channel.close();} catch(IOException ignored) {}
    }
    private boolean referenced(String key) {
        return db.count("SELECT (SELECT COUNT(*) FROM aip_asset WHERE object_key=:key)+(SELECT COUNT(*) FROM aip_export WHERE object_key=:key)",Json.of("key",key))>0;
    }
    /** 只清24小时以上、无任何DB引用的本项目临时对象；不删正式素材或历史登记。 */
    public void cleanUnreferenced() {
        long now=System.currentTimeMillis();if(now-lastSweep<60_000||!mutex.tryLock()) return;
        FileChannel channel=null;FileLock guard=null;
        try {
            channel=lockChannel();guard=channel.tryLock();if(guard==null||now-lastSweep<60_000) return;lastSweep=now;
            if(sweep==null) {sweep=Files.walk(root,5);sweepCursor=sweep.iterator();}
            for(int visited=0;visited<500&&sweepCursor.hasNext();visited++) {
                Path path=sweepCursor.next();if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)) continue;
                String key=root.relativize(path).toString().replace('\\','/');
                String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
                boolean object=key.matches("assets/[0-9]+/"+uuid+"\\.(png|jpg|webp)")||key.matches("staging/"+uuid+"/[0-9]+/"+uuid+"\\.(png|jpg|webp|zip)");
                boolean partial=key.matches("(assets/[0-9]+|staging/"+uuid+"/[0-9]+)/\\.aipicture-part-[A-Za-z0-9]+\\.tmp");
                if((object||partial)&&Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()<now-24L*60*60*1000&&!referenced(key)) Files.deleteIfExists(resolve(key));
            }
            if(!sweepCursor.hasNext()) closeSweep();
        } catch(Exception error) {closeSweep();log.warn("图片临时对象清理未完成，保留文件并等待下次定向清理");}
        finally {unlock(guard,channel);mutex.unlock();}
    }
    private void closeSweep() {if(sweep!=null) sweep.close();sweep=null;sweepCursor=null;}
    @Override public void destroy() {mutex.lock();try {closeSweep();} finally {mutex.unlock();}}
    private Path resolve(String key) throws IOException {
        Path target=root.resolve(key).normalize();
        if(!target.startsWith(root)||target.equals(root)||Path.of(key).isAbsolute()) throw AiPictureException.invalid("非法对象路径");
        Path ancestor=target;
        while(ancestor!=null&&!Files.exists(ancestor)) ancestor=ancestor.getParent();
        if(ancestor==null||!ancestor.toRealPath().startsWith(root.toRealPath())) throw AiPictureException.invalid("对象路径越界");
        return target;
    }
}
