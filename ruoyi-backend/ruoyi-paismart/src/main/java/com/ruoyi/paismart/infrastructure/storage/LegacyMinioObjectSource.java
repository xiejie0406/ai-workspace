package com.ruoyi.paismart.infrastructure.storage;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import com.ruoyi.system.secret.ManagedSecretService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只按部署配置读取已批准旧桶，manifest不能指定地址、桶或凭据。 */
@Component
@PaismartEnabled
public class LegacyMinioObjectSource implements LegacyObjectSource {
    private final String endpoint,bucket,accessRef,secretRef;
    private final boolean enabled;
    private final ManagedSecretService secrets;
    private volatile MinioClient client;
    private volatile String version;
    public LegacyMinioObjectSource(@Value("${paismart.operations.legacy-source.enabled:false}") boolean enabled,
      @Value("${paismart.operations.legacy-source.endpoint:}") String endpoint,
      @Value("${paismart.operations.legacy-source.bucket:}") String bucket,
      @Value("${paismart.operations.legacy-source.access-key-secret-ref:platform.paismart.minio.access-key}") String accessRef,
      @Value("${paismart.operations.legacy-source.secret-key-secret-ref:platform.paismart.minio.secret-key}") String secretRef,ManagedSecretService secrets) {
        this.enabled=enabled;this.endpoint=endpoint;this.bucket=bucket;this.accessRef=accessRef;this.secretRef=secretRef;this.secrets=secrets;
    }
    public InputStream open(String key) {
        if(!enabled) throw new ServiceException("旧对象只读迁移来源未启用",503);
        if(endpoint.isBlank()||bucket.isBlank()) throw new ServiceException("旧对象只读迁移来源未配置",503);
        if(key==null||key.length()>500||key.contains("..")||key.contains("\\")||key.contains(":")||key.chars().anyMatch(c->c<32)||
          !(key.matches("chunks/[a-fA-F0-9]{32}/[0-9]{1,5}") || (key.startsWith("merged/")&&!key.substring(7).isBlank()&&!key.substring(7).contains("/")))) throw new ServiceException("旧对象来源键超出PaiSmart原命名空间",400);
        var access=resolve(accessRef);var secret=resolve(secretRef);
        String current=access.metadata().activeVersion()+":"+secret.metadata().activeVersion();
        if(client==null||!current.equals(version)) synchronized(this) {
            if(client==null||!current.equals(version)){client=MinioClient.builder().endpoint(endpoint).credentials(access.value(),secret.value()).build();version=current;}
        }
        try {return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());}
        catch(Exception failure){throw new ServiceException("旧对象只读获取失败："+failure.getClass().getSimpleName(),503);}
    }
    private ManagedSecretService.Resolved resolve(String reference) {
        if(!reference.startsWith("platform.paismart.")) throw new ServiceException("旧对象来源密钥用途不属于本项目",400);
        try {var value=secrets.resolve("PLATFORM",reference);if(!"paismart".equals(value.metadata().projectCode()))throw new ServiceException("旧对象来源密钥项目不匹配",400);return value;}
        catch(ServiceException denied){throw denied;}
        catch(RuntimeException unavailable){throw new ServiceException("旧对象来源托管密钥不可用",503);}
    }
}
