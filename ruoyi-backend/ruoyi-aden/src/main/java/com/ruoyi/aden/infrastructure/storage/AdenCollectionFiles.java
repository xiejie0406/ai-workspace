package com.ruoyi.aden.infrastructure.storage;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** 路径只由内部 UUID/哈希构成；不接受页面文件名，不执行网络请求。 */
public final class AdenCollectionFiles {
    private final Path root;
    public AdenCollectionFiles(String configuredRoot) {
        if (configuredRoot == null || configuredRoot.isBlank()) throw new IllegalArgumentException("必须配置 aden.collection.storage-root");
        root = Path.of(configuredRoot).toAbsolutePath().normalize();
    }
    public Path path(String workspace, String key) {
        return path(workspace, key, true);
    }
    public boolean exists(String workspace, String key) {
        return Files.isRegularFile(path(workspace, key, false), LinkOption.NOFOLLOW_LINKS);
    }
    private Path path(String workspace, String key, boolean createParent) {
        if (!UUID.fromString(workspace).toString().equals(workspace) || !key.matches("[a-zA-Z0-9.-]{1,100}")) throw new IllegalArgumentException("非法存储键");
        Path result = root.resolve(workspace).resolve(key).normalize();
        if (!result.startsWith(root)) throw new IllegalArgumentException("非法存储路径");
        try {
            if (createParent) Files.createDirectories(result.getParent());
            Path p = result.getParent();
            while (p != null) {
                if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("附件目录不能使用符号链接");
                p = p.getParent();
            }
            if (Files.isSymbolicLink(result)) throw new IllegalArgumentException("附件不能使用符号链接");
        } catch (IOException e) { throw new IllegalStateException("附件目录不可用", e); }
        return result;
    }
    public void chunk(String workspace, String uploadId, long offset, byte[] bytes) {
        try (RandomAccessFile file = new RandomAccessFile(path(workspace, uploadId + ".part").toFile(), "rw")) {
            // DB offset 是真相。事务回滚留下的尾部字节可安全覆盖。
            if (file.length() < offset) throw new IllegalStateException("暂存文件不完整，请重新采集");
            file.setLength(offset); file.seek(offset); file.write(bytes);
        } catch (IOException e) { throw new IllegalStateException("图片分片写入失败", e); }
    }
    public boolean matches(String ws, String uploadId, long offset, byte[] bytes) {
        try (RandomAccessFile file = new RandomAccessFile(path(ws, uploadId + ".part").toFile(), "r")) {
            if (file.length() < offset + bytes.length) return false;
            byte[] old = new byte[bytes.length]; file.seek(offset); file.readFully(old); return MessageDigest.isEqual(old, bytes);
        } catch (IOException e) { return false; }
    }
    public boolean matchesAsset(String ws,String hash,long offset,byte[] bytes) {
        try(RandomAccessFile file=new RandomAccessFile(path(ws,hash+".asset").toFile(),"r")) {
            if(file.length()<offset+bytes.length)return false;
            byte[] old=new byte[bytes.length];file.seek(offset);file.readFully(old);return MessageDigest.isEqual(old,bytes);
        }catch(IOException e){return false;}
    }
    public void discardTemporary(String ws,String uploadId) {
        try { Files.deleteIfExists(path(ws,uploadId+".part")); }
        catch(IOException e) { /* 清理失败不撤销已成功的元数据提交，文件仍留在受控目录。 */ }
    }
    public void discardTemporaryAfterCommit(String ws,String uploadId) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit(){discardTemporary(ws,uploadId);}
            });
    }
    public ImageInfo finish(String ws, String uploadId, String hash, String mime, long total) {
        try {
            Path source = path(ws, uploadId + ".part");
            byte[] bytes = Files.readAllBytes(source);
            if (bytes.length != total || !sha256(bytes).equals(hash)) throw new IllegalArgumentException("图片长度或 SHA-256 不匹配");
            var decoded=decodeImage(bytes,mime);
            int width=decoded.getWidth(),height=decoded.getHeight();
            double ratio=Math.min(1.0,320.0/Math.max(width,height));
            var thumbnail=new java.awt.image.BufferedImage(Math.max(1,(int)(width*ratio)),Math.max(1,(int)(height*ratio)),java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var graphics=thumbnail.createGraphics();
            try { graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(decoded,0,0,thumbnail.getWidth(),thumbnail.getHeight(),null); }
            finally { graphics.dispose(); decoded.flush(); }
            try(var output=new ByteArrayOutputStream()) {
                if(!ImageIO.write(thumbnail,"png",output))throw new IllegalStateException("PNG编码器不可用");
                Path thumbnailPath=path(ws,hash+".thumb.png");
                if(!Files.exists(thumbnailPath))Files.write(thumbnailPath,output.toByteArray(),StandardOpenOption.CREATE_NEW);
            } finally { thumbnail.flush(); }
            Path destination = path(ws, hash + ".asset");
            if (!Files.exists(destination)) {
                Path staging = path(ws, UUID.randomUUID() + ".ready");
                Files.write(staging, bytes, StandardOpenOption.CREATE_NEW);
                try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException e) { Files.move(staging, destination); }
            }
            return new ImageInfo(width,height);
        } catch (IOException e) { throw new IllegalStateException("图片保存失败", e); }
    }
    public static void validateImage(byte[] bytes, String mime) {
        var image=decodeImage(bytes,mime); image.flush();
    }
    private static java.awt.image.BufferedImage decodeImage(byte[] bytes,String mime) {
        if(bytes.length<12 || bytes.length>10*1024*1024)throw new IllegalArgumentException("图片大小超限或被截断");
        boolean signature=switch(mime) {
            case "image/png" -> bytes.length>=36 && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}) && java.util.Arrays.equals(java.util.Arrays.copyOfRange(bytes,bytes.length-12,bytes.length),new byte[]{0,0,0,0,73,69,78,68,(byte)174,66,96,(byte)130});
            case "image/jpeg" -> bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255&&bytes[bytes.length-2]==(byte)255&&bytes[bytes.length-1]==(byte)217;
            case "image/webp" -> new String(bytes,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(bytes,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")&&Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes,4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt())+8==bytes.length;
            default -> false;
        };
        if(!signature)throw new IllegalArgumentException("图片魔数或容器长度与声明不符");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片格式不支持或无法解码");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase();
                String actual = switch(format) { case "png" -> "image/png"; case "jpeg", "jpg" -> "image/jpeg"; case "webp" -> "image/webp"; default -> "unsupported"; };
                if (!actual.equals(mime)) throw new IllegalArgumentException("图片声明 MIME 与实际格式不一致");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long)width * height > 25_000_000L) throw new IllegalArgumentException("图片像素超限");
                var image=reader.read(0);
                if (image == null) throw new IllegalArgumentException("图片无法解码");
                return image;
            } finally { reader.dispose(); }
        } catch (IOException e) { throw new IllegalArgumentException("图片无法解码", e); }
    }
    public record ImageInfo(int width,int height) { }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public byte[] read(String ws, String key) {
        try { return Files.readAllBytes(path(ws, key)); } catch (IOException e) { throw new IllegalStateException("附件读取失败", e); }
    }
    public void write(String ws, String key, byte[] bytes) {
        try { Files.write(path(ws, key), bytes, StandardOpenOption.CREATE_NEW); }
        catch (IOException e) { throw new IllegalStateException("导出文件保存失败", e); }
    }
}
