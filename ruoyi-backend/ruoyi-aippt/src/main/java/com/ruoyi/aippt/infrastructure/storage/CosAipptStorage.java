package com.ruoyi.aippt.infrastructure.storage;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.CannedAccessControlList;
import com.qcloud.cos.region.Region;
import java.io.ByteArrayInputStream;
import java.io.IOException;
/** 复用参考工程声明的官方 COS SDK；对象 key 只能由中心 UUID 生成。 */
public class CosAipptStorage implements AipptStoragePort  {
    private final String bucket;
    private final COSClient client;
    public CosAipptStorage(String bucket, String region, String id, String key)  {
        if (!bucket.matches("[a-z0-9-]+-[0-9]+") || !region.matches("[a-z0-9-]+") || id.isBlank() || key.isBlank()) throw new IllegalArgumentException("COS 配置不完整");
        this.bucket = bucket;
        ClientConfig config = new ClientConfig(new Region(region));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(10000);
        config.setSocketTimeout(60000);
        client = new COSClient(new BasicCOSCredentials(id, key), config);
    }
    private String objectKey(String key) throws IOException  {
        if (key == null || !key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}")) throw new IOException("非法对象 key");
        return "aippt/" + key;
    }
    public void put(String key, byte[] data, String mime) throws IOException  {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(data.length);
        metadata.setContentType(mime);
        try  {
            PutObjectRequest request = new PutObjectRequest(bucket, objectKey(key), new ByteArrayInputStream(data), metadata);
            request.setCannedAcl(CannedAccessControlList.Private);
            client.putObject(request);
        }
        catch (RuntimeException e)  {
            throw new IOException("COS 私有对象写入失败", e);
        }
    }
    public byte[] get(String key) throws IOException  {
        try (var content = client.getObject(bucket, objectKey(key)).getObjectContent())  {
            byte[] bytes = content.readNBytes(100 * 1024 * 1024 + 1);
            if (bytes.length > 100 * 1024 * 1024) throw new IOException("COS 对象超限");
            return bytes;
        }
        catch (RuntimeException e)  {
            throw new IOException("COS 私有对象读取失败", e);
        }
    }
    public void delete(String key) throws IOException  {
        try  {
            client.deleteObject(bucket, objectKey(key));
        }
        catch (RuntimeException e)  {
            throw new IOException("COS 私有对象删除失败", e);
        }
    }
    public void close()  {
        client.shutdown();
    }
}
