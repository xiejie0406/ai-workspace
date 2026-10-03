package com.ruoyi.paiflow.service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.config.PaiflowWorkerProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkerIdentityService {
    public record Principal(String id,String engineProfile) {}
    private final PaiflowWorkerProperties properties;
    public WorkerIdentityService(PaiflowWorkerProperties properties) { this.properties=properties; }
    public Principal authenticate(String authorization) {
        if(authorization==null || !authorization.startsWith("Bearer ")) throw denied();
        String token=authorization.substring(7);
        if(!token.matches("[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{32,256}")) throw denied();
        if(properties.getIdentities()==null)throw denied();
        String id=token.substring(0,token.indexOf('.'));var credential=properties.getIdentities().get(id);
        if(credential==null || credential.getExpiresAt()==null || !credential.getExpiresAt().isAfter(Instant.now()) ||
           credential.getEngineProfile()==null || !java.util.Set.of("java","python").contains(credential.getEngineProfile()) || credential.getTokenSha256()==null ||
           !credential.getTokenSha256().matches("[0-9a-f]{64}")) throw denied();
        try {
            byte[] actual=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(actual,HexFormat.of().parseHex(credential.getTokenSha256()))) throw denied();
        } catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("运行环境缺少SHA-256",ex); }
        return new Principal(id,credential.getEngineProfile());
    }
    private static ServiceException denied() { return new ServiceException("Worker服务身份无效或已过期",401); }
}
