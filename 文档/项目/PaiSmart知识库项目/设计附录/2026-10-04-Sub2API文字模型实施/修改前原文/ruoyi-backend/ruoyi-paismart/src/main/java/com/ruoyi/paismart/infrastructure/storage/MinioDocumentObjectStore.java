package com.ruoyi.paismart.infrastructure.storage;

import com.ruoyi.common.exception.ServiceException;
import io.minio.*;
import java.io.InputStream;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.ruoyi.system.secret.ManagedSecretService;

/** 不创建桶、不公开对象；客户端延迟创建，模块装配不触发网络。 */
@Component
@ConditionalOnProperty(prefix="paismart",name="enabled",havingValue="true")
public class MinioDocumentObjectStore implements DocumentObjectStore {
    private final String endpoint,bucket,accessKeyRef,secretKeyRef;
    private final ManagedSecretService secrets;
    private final boolean enabled,writesEnabled,probeEnabled;
    private volatile MinioClient client;
    private volatile String credentialVersion;
    @Autowired
    public MinioDocumentObjectStore(
      @Value("${paismart.object-store.endpoint:}") String endpoint,
      @Value("${paismart.object-store.bucket:}") String bucket,
      @Value("${paismart.object-store.access-key-secret-ref:platform.paismart.minio.access-key}") String accessKeyRef,
      @Value("${paismart.object-store.secret-key-secret-ref:platform.paismart.minio.secret-key}") String secretKeyRef,
      @Value("${paismart.object-store.enabled:false}") boolean enabled,
      @Value("${paismart.object-store.writes-enabled:false}") boolean writesEnabled,
      @Value("${paismart.object-store.probe-enabled:false}") boolean probeEnabled,
      ManagedSecretService secrets) {
        this.endpoint=endpoint;this.bucket=bucket;this.accessKeyRef=accessKeyRef;this.secretKeyRef=secretKeyRef;this.secrets=secrets;
        this.enabled=enabled;this.writesEnabled=writesEnabled;this.probeEnabled=probeEnabled;
    }
    public MinioDocumentObjectStore(String endpoint,String bucket,String accessKeyRef,String secretKeyRef,
      boolean enabled,boolean writesEnabled,ManagedSecretService secrets) {
        this(endpoint,bucket,accessKeyRef,secretKeyRef,enabled,writesEnabled,false,secrets);
    }
    MinioClient client(String key,boolean write) {
        if(!key.startsWith("paismart/") || key.contains("..") || key.contains("\\")) throw new ServiceException("对象路径超出本项目范围",400);
        if(!enabled || (write&&!writesEnabled)) throw new ServiceException(write?"知识库对象写入未启用":"知识库对象存储未启用",503);
        if(endpoint.isBlank() || bucket.isBlank() || accessKeyRef.isBlank() || secretKeyRef.isBlank()) throw new ServiceException("知识库对象存储尚未配置",503);
        var access=secret(accessKeyRef);var secret=secret(secretKeyRef);
        String version=access.metadata().activeVersion()+":"+secret.metadata().activeVersion();
        if(client==null || !version.equals(credentialVersion)) synchronized(this) {
            if(client==null || !version.equals(credentialVersion)) {
                client=MinioClient.builder().endpoint(endpoint).credentials(access.value(),secret.value()).build();credentialVersion=version;
            }
        }
        return client;
    }
    private ManagedSecretService.Resolved secret(String ref) {
        if(!ref.startsWith("platform.paismart.")) throw new ServiceException("对象存储密钥用途超出本项目",400);
        try {var resolved=secrets.resolve("PLATFORM",ref);
            if(!"paismart".equals(resolved.metadata().projectCode())) throw new ServiceException("对象存储密钥项目不匹配",400);
            return resolved;
        } catch(ServiceException failure) {throw failure;}
        catch(RuntimeException failure) {throw new ServiceException("知识库对象存储托管密钥不可用",503);}
    }
    private ServiceException failed(Exception failure) {
        // SDK 异常可能含签名/地址；保留服务端 cause，返回值不含凭据或原始请求。
        return new ServiceException("知识库对象存储操作失败："+failure.getClass().getSimpleName(),503);
    }
    public void put(String key,InputStream bytes,long length,String contentType) {
        MinioClient c=client(key,true);
        try { c.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(bytes,length,-1).contentType(contentType).build()); }
        catch(Exception e) { throw failed(e); }
    }
    public InputStream open(String key) {
        MinioClient c=client(key,false);
        try { return c.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw failed(e); }
    }
    public long size(String key) {
        MinioClient c=client(key,false);
        try { return c.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size(); }
        catch(Exception e) { throw failed(e); }
    }
    public boolean exists(String key) {
        MinioClient c=client(key,false);
        try {c.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());return true;}
        catch(io.minio.errors.ErrorResponseException missing) {
            if(java.util.Set.of("NoSuchKey","NoSuchObject").contains(missing.errorResponse().code())) return false;
            throw failed(missing);
        } catch(Exception failure){throw failed(failure);}
    }
    public void remove(String key) {
        MinioClient c=client(key,true);
        try { c.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
        catch(Exception e) { throw failed(e); }
    }
    public Map<String,Object> probe() {
        boolean configured=!endpoint.isBlank()&&!bucket.isBlank()&&!accessKeyRef.isBlank()&&!secretKeyRef.isBlank();
        if(!configured) return probeResult("NOT_CONFIGURED",false,false,"对象存储地址、桶或托管密钥引用缺失");
        if(!enabled||!probeEnabled) return probeResult("DISABLED",true,false,"对象存储或只读探测开关未启用");
        MinioClient target;
        try {target=client("paismart/probe",false);}
        catch(RuntimeException unavailable) {return probeResult("NOT_CONFIGURED",true,false,"对象存储或托管密钥配置不可用");}
        try {
            boolean exists=target.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return probeResult(exists?"READY":"UNAVAILABLE",true,true,exists?"配置桶HEAD成功":"配置桶不存在或无法读取");
        } catch(Exception unavailable) {return probeResult("UNAVAILABLE",true,true,"配置桶只读探测失败");}
    }
    private Map<String,Object> probeResult(String state,boolean configured,boolean tested,String reason) {
        return Map.of("state",state,"configured",configured,"enabled",enabled,"probeEnabled",probeEnabled,
            "tested",tested,"scope","仅PaiSmart已配置桶HEAD；未核对文档对象或写入能力","reason",reason);
    }
}
