package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 托管密钥版本、运维精确用途配置与模型版本的私有接线。绝不读取任意env或文件。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ManagedModelRuntime implements ModelRuntimeCatalog.ProfilePort,CredentialMaterialPort,ModelService.CredentialReferencePort {
    private final ModelRuntimeProperties properties;private final ManagedSecretService secrets;
    public ManagedModelRuntime(ModelRuntimeProperties properties,ManagedSecretService secrets){this.properties=properties;this.secrets=secrets;}
    public ModelRuntimeProperties.Profile profile(String provider,String domain,String endpoint,String reference){
        var matches=properties.getProfiles().stream().filter(p->p.isEnabled() && Objects.equals(provider,p.getSourceProvider())
            && Objects.equals(domain,p.getDomain()) && Objects.equals(endpoint,p.getEndpoint()) && Objects.equals(reference,p.getCredentialRef())).toList();
        if(matches.size()!=1)throw error("模型用途未配置或不是唯一精确批准目标",503);var p=matches.get(0);
        if(!Set.of("openai","xinghuo","local-deployment").contains(p.getRuntimeProvider()) || p.getMaxCalls()<1 || p.getMaxCalls()>1000
            || p.getMaxTokens()<1 || p.getMaxRequestBytes()<1 || p.getMaxRequestBytes()>REQUEST_BYTES
            || p.getTimeoutSeconds()<1 || p.getTimeoutSeconds()>1800 || p.getSecretAlias()==null || !p.getSecretAlias().startsWith("ai.")
            || p.getSecretProvider()==null || !"chat".equals(p.getCapability()) || p.getAuthMode()==null)throw error("模型用途配置缺少正式预算或密钥用途",503);
        if("openai".equals(p.getRuntimeProvider()))ModelRuntimeCatalog.openAiBase(endpoint);
        else if("xinghuo".equals(p.getRuntimeProvider()))sparkEndpoint(endpoint);
        else if(!endpoint.startsWith("local-model:") || !p.isDeploymentAllowed())throw error("本地部署用途未批准",503);
        return p;
    }
    public ModelRuntimeProperties.Profile profile(ModelRuntimeCatalog.FixedModel model){return profile(model.sourceProvider(),model.domain(),model.privateEndpoint(),model.credentialRef());}
    private ManagedSecretService.Metadata metadata(ModelRuntimeProperties.Profile profile){
        var m=secrets.metadata("AI",profile.getSecretAlias());
        if(!"ACTIVE".equals(m.status()) || m.activeVersion()==null || !"paiflow".equals(m.projectCode())
            || !Objects.equals(profile.getSecretProvider(),m.providerCode()) || !Objects.equals(profile.getCapability(),m.capabilityCode())
            || !Objects.equals(profile.getAuthMode(),m.authMode()))throw error("托管密钥版本/项目/供应商/能力用途不匹配",503);
        return m;
    }
    private static String version(ManagedSecretService.Metadata m){return "managed:"+m.alias()+":version:"+m.activeVersion()+":row:"+m.rowVersion();}
    @Override public ModelRuntimeCatalog.RuntimeProfile resolve(ModelRuntimeCatalog.SourceModel source){
        var p=profile(source.sourceProvider(),source.domain(),source.privateEndpoint(),source.credentialRef());var m=metadata(p);
        if(!Objects.equals(version(m),source.credentialVersion()))throw error("固定模型密钥版本已经轮换",409);
        return new ModelRuntimeCatalog.RuntimeProfile(source.sourceFingerprint(),p.getRuntimeProvider(),p.isFunctionCall()?Set.of("chat","function_call"):Set.of("chat"));
    }
    @Override public boolean available(ModelRuntimeCatalog.FixedModel model){try{return Objects.equals(version(metadata(profile(model))),model.credentialVersion());}catch(RuntimeException rejected){return false;}}
    public PrivateMaterial material(ModelRuntimeProperties.Profile p,String reference,String expectedVersion){
        var before=metadata(p);if(!Objects.equals(version(before),expectedVersion))throw error("托管密钥准确版本已失效",409);
        var resolved=secrets.resolve("AI",p.getSecretAlias());var actual=resolved.metadata();
        if(!Objects.equals(version(actual),expectedVersion) || !Objects.equals(actual.projectCode(),before.projectCode())
            || !Objects.equals(actual.providerCode(),before.providerCode()) || !Objects.equals(actual.capabilityCode(),before.capabilityCode())
            || !Objects.equals(actual.authMode(),before.authMode()))throw error("密钥读取期间发生轮换或用途变化",409);
        if("xinghuo".equals(p.getRuntimeProvider())){
            var parts=JSON.parseObject(resolved.value());if(parts==null || !parts.keySet().equals(Set.of("appId","apiKey","apiSecret")))throw error("Spark托管材料形状不匹配",503);
            return new PrivateMaterial(reference,expectedVersion,p.getEndpoint(),text(parts.get("apiKey"),16384,"Spark key"),text(parts.get("appId"),256,"Spark app"),text(parts.get("apiSecret"),16384,"Spark secret"));
        }
        String base="openai".equals(p.getRuntimeProvider())?ModelRuntimeCatalog.openAiBase(p.getEndpoint()):p.getDeploymentEndpoint();
        return new PrivateMaterial(reference,expectedVersion,base,text(resolved.value(),16384,"托管密钥"));
    }
    @Override public PrivateMaterial resolve(ModelRuntimeCatalog.FixedModel model){return material(profile(model),model.credentialRef(),model.credentialVersion());}
    @Override public ModelService.Authorization authorize(ModelService.Candidate c){
        var configuration=JSON.parseObject(c.configurationJson());var p=profile(c.provider(),configuration.getString("domain"),c.endpoint(),c.credentialRef());
        if(c.type()==1 && !p.isValidationAllowed() || c.type()==2 && !p.isDeploymentAllowed())throw error("模型校验/部署用途未获批准",403);
        var m=metadata(p);return new ModelService.Authorization(c.spaceId(),c.ownerUserId(),c.modelId(),c.candidateHash(),c.endpoint(),c.provider(),c.credentialRef(),version(m),"managed-model:"+sha(c.candidateHash()+version(m)),Instant.now().plusSeconds(300));
    }
    @Override public boolean current(ModelService.Authorization a){
        if(a==null || a.validUntil()==null || !a.validUntil().isAfter(Instant.now()))return false;
        try{var matches=properties.getProfiles().stream().filter(p->p.isEnabled() && Objects.equals(a.provider(),p.getSourceProvider())
            && Objects.equals(a.endpoint(),p.getEndpoint()) && Objects.equals(a.credentialRef(),p.getCredentialRef())).toList();
            if(matches.size()!=1)return false;var p=matches.get(0);profile(p.getSourceProvider(),p.getDomain(),p.getEndpoint(),p.getCredentialRef());
            return Objects.equals(version(metadata(p)),a.credentialVersion()) && Objects.equals(a.authorizationId(),"managed-model:"+sha(a.candidateHash()+a.credentialVersion()));
        }catch(RuntimeException rejected){return false;}
    }
    static URI sparkEndpoint(String endpoint){
        try{var u=URI.create(endpoint);if(!"wss".equals(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null
            || u.getQuery()!=null || u.getFragment()!=null || u.getRawPath()==null || u.getRawPath().isEmpty()
            || u.getRawPath().contains("%") || !u.normalize().equals(u) || u.getPort()!=-1 && u.getPort()!=443)throw new IllegalArgumentException();return u;
        }catch(RuntimeException rejected){throw error("Spark必须使用已批准固定WSS目标",503);}
    }
}
