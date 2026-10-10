package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import com.ruoyi.paiflow.mapper.ModelMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 固定模型版本与当前权限的唯一运行目录；不解析任意环境变量，不执行Provider调用。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelRuntimeCatalog {
    /** 仅本地读取已批准配置；没有默认实现，不能在版本事务中进行网络探测。 */
    public interface ProfilePort { RuntimeProfile resolve(SourceModel source); }
    public record RuntimeProfile(String sourceFingerprint,String provider,Set<String> capabilities,String privateBaseUrl) {
        public RuntimeProfile(String sourceFingerprint,String provider,Set<String> capabilities){this(sourceFingerprint,provider,capabilities,null);}
        public RuntimeProfile { capabilities=capabilities==null?Set.of():Set.copyOf(capabilities); }
        @Override public String toString(){return "RuntimeProfile[redacted]";}
    }
    /** 私有源不是Bean/record，避免诊断或默认序列化泄露endpoint/凭据引用。 */
    public static final class SourceModel {
        private final long modelId,versionId,spaceId,ownerUserId,revision;
        private final String candidateHash,credentialVersion;
        private final String configuration,sourceProvider,domain,endpoint,credentialRef,fingerprint;
        private SourceModel(ModelVersionRecord v,String configuration,String sourceProvider,String domain,String endpoint,String ref) {
            modelId=v.getModelId();versionId=v.getId();spaceId=v.getSpaceId();ownerUserId=v.getOwnerUserId();revision=v.getRevision();candidateHash=v.getCandidateHash();credentialVersion=v.getCredentialVersion();
            this.configuration=configuration;this.sourceProvider=sourceProvider;this.domain=domain;this.endpoint=endpoint;credentialRef=ref;
            var tuple=new JSONObject();tuple.put("modelId",modelId);tuple.put("versionId",versionId);tuple.put("spaceId",spaceId);tuple.put("ownerUserId",ownerUserId);tuple.put("revision",revision);tuple.put("configurationJson",configuration);tuple.put("candidateHash",candidateHash);tuple.put("credentialVersion",credentialVersion);fingerprint=sha(tuple);
        }
        public long modelId(){return modelId;} public long modelVersionId(){return versionId;}
        public long spaceId(){return spaceId;} public long ownerUserId(){return ownerUserId;} public long revision(){return revision;}
        public String configurationJson(){return configuration;} public String sourceProvider(){return sourceProvider;}
        public String domain(){return domain;} public String privateEndpoint(){return endpoint;}
        public String credentialRef(){return credentialRef;} public String credentialVersion(){return credentialVersion;}
        public String candidateHash(){return candidateHash;} public String sourceFingerprint(){return fingerprint;}
    }
    public static final class FixedModel {
        private final SourceModel source;private final String baseUrl;private final RuntimeProfile profile;
        private FixedModel(SourceModel source,String baseUrl,RuntimeProfile profile){this.source=source;this.baseUrl=baseUrl;this.profile=profile;}
        public String resourceId(){return resourceIdOf(modelId(),modelVersionId());}
        public long modelId(){return source.modelId();} public long modelVersionId(){return source.modelVersionId();}
        public long spaceId(){return source.spaceId();} public long ownerUserId(){return source.ownerUserId();}
        public String sourceProvider(){return source.sourceProvider();} public String provider(){return profile.provider();}
        public String domain(){return source.domain();} public String candidateHash(){return source.candidateHash();}
        public String sourceFingerprint(){return source.sourceFingerprint();} public String credentialVersion(){return source.credentialVersion();}
        public String privateEndpoint(){return source.privateEndpoint();} public String privateBaseUrl(){return baseUrl;}
        public String credentialRef(){return source.credentialRef();} public Set<String> capabilities(){return profile.capabilities();}
        public JSONObject publicDescriptor(){var d=new JSONObject();d.put("kind","model");d.put("provider",provider());d.put("version",String.valueOf(modelVersionId()));d.put("modelName",domain());d.put("enabled",true);d.put("capabilities",new com.alibaba.fastjson2.JSONArray(profile.capabilities().stream().sorted().toList()));return d;}
    }
    private final ModelMapper models;private final SpaceMapper spaces;private final ResourceAccessService access;
    private final ISysUserService users;private final SysPermissionService permissions;
    private ObjectProvider<ProfilePort> profiles;
    private org.mybatis.spring.SqlSessionTemplate sessions;
    @Autowired public void useCurrentSession(org.mybatis.spring.SqlSessionTemplate template){sessions=template;}
    private void currentRead(){if(sessions!=null)sessions.clearCache();}
    public ModelRuntimeCatalog(ModelMapper models,SpaceMapper spaces,ResourceAccessService access,ISysUserService users,SysPermissionService permissions){this.models=models;this.spaces=spaces;this.access=access;this.users=users;this.permissions=permissions;}
    @Autowired public void useProfiles(ObjectProvider<ProfilePort> provider){profiles=provider;}
    public FixedModel bindCurrent(long spaceId,long modelId,String declaredSource,String declaredDomain){
        access.requireSpace(spaceId,"paiflow:model:query",3,false);
        var row=current(spaceId,modelId);if(row.getLatestVersionId()==null)throw unavailable();
        var fixed=resolve(spaceId,modelId,row.getLatestVersionId());
        access.requireSpace(spaceId,"paiflow:model:query",3,false);
        if(!Objects.equals(current(spaceId,modelId).getLatestVersionId(),fixed.modelVersionId()))throw new ServiceException("模型当前版本已变化，请重新编译",409);
        if(!Objects.equals(declaredSource,fixed.provider()) || !Objects.equals(declaredDomain,fixed.domain()))throw new ServiceException("模型节点与当前固定模型来源不匹配",409);
        return fixed;
    }
    /** Worker身份不是人；显式核对运行发起者的当前平台账号、菜单和空间成员，不伪造登录上下文。 */
    public FixedModel resolvePinned(long spaceId,long runOwnerUserId,String resourceId,JSONObject descriptor){
        requireRunOwner(spaceId,runOwnerUserId);
        long[] ids=parseResourceId(resourceId);current(spaceId,ids[0]);var fixed=resolve(spaceId,ids[0],ids[1]);
        if(descriptor==null || !fixed.publicDescriptor().equals(descriptor))throw new ServiceException("模型固定快照与真实版本不匹配",409);
        // 本地批准端口也可能等待；返回前重新检查，不沿用等待前权限/启用事实。
        requireRunOwner(spaceId,runOwnerUserId);current(spaceId,ids[0]);
        return fixed;
    }
    private void requireRunOwner(long spaceId,long userId){
        currentRead();
        var user=users.selectUserById(userId);
        if(user==null || !Objects.equals(user.getUserId(),userId) || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag()))throw new ServiceException("运行发起者已失效",403);
        var granted=permissions.getMenuPermission(user);if(granted==null || (!granted.contains("*:*:*") && !granted.contains("paiflow:model:query")))throw new ServiceException("运行发起者模型权限已撤销",403);
        var space=spaces.find(spaceId);var member=spaces.member(spaceId,userId);
        if(space==null || space.getDeleted()!=0 || member==null || !"active".equals(member.getStatus()) || member.getRole()==null || member.getRole()<1 || member.getRole()>3 || (space.isDefaultPersonal() && (!Objects.equals(space.getOwnerUserId(),userId) || space.getTeamId()!=null || member.getRole()!=1)))throw new ServiceException("运行发起者空间访问已失效",403);
    }
    private com.ruoyi.paiflow.domain.ModelRecord current(long space,long id){
        currentRead();
        var row=models.find(id);if(row==null || row.getDeleted()!=0 || !Objects.equals(row.getSpaceId(),space))throw new ServiceException("模型不存在或不可访问",404);
        if(!(Objects.equals(row.getType(),1) || Objects.equals(row.getType(),2)) || !Objects.equals(row.getEnabled(),1) || !"VALIDATED".equals(row.getState()))throw unavailable();return row;
    }
    private FixedModel resolve(long space,long modelId,long versionId){
        var version=models.version(versionId);
        if(version==null || !Objects.equals(version.getModelId(),modelId) || !Objects.equals(version.getSpaceId(),space))throw new ServiceException("模型版本不存在或不可访问",404);
        if(!(Objects.equals(version.getType(),1) || Objects.equals(version.getType(),2)) || !Objects.equals(version.getEnabled(),1) || !"VALIDATED".equals(version.getState()) || blank(version.getCredentialVersion()) || blank(version.getCandidateHash()))throw unavailable();
        var config=JSON.parseObject(version.getConfigurationJson());if(config==null)throw unavailable();
        String domain=config.getString("domain"),endpoint=config.getString("endpoint"),ref=config.getString("credentialRef"),provider=config.getString("provider");
        if(blank(domain)||blank(endpoint)||blank(ref)||blank(provider))throw unavailable();
        var source=new SourceModel(version,version.getConfigurationJson(),provider,domain,endpoint,ref);
        var port=profiles==null?null:profiles.getIfAvailable();if(port==null)throw unavailable();
        RuntimeProfile profile;
        try { profile=port.resolve(source); }
        catch(RuntimeException rejected) { throw unavailable(); } // 私有本地配置错误不向HTTP回执暴露endpoint/引用。
        if(profile==null || !Objects.equals(profile.sourceFingerprint(),source.sourceFingerprint()) || !Set.of("openai","xinghuo").contains(profile.provider()) || !profile.capabilities().contains("chat") || !Set.of("chat","function_call").containsAll(profile.capabilities()) || "openai".equals(profile.provider()) && profile.capabilities().contains("function_call"))throw unavailable();
        return new FixedModel(source,profile.privateBaseUrl()!=null?profile.privateBaseUrl():"openai".equals(profile.provider())?openAiBase(endpoint):ManagedModelRuntime.sparkEndpoint(endpoint).toString(),profile);
    }
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static ServiceException unavailable(){return new ServiceException("固定模型运行配置/批准能力尚不可用",503);}
    static String openAiBase(String endpoint){
        try {
            URI uri=URI.create(endpoint);String path=uri.getRawPath();String suffix="/chat/completions";
            if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getRawQuery()!=null || uri.getRawFragment()!=null || path==null || !path.endsWith(suffix) || path.contains("%") || path.contains("//") || !uri.normalize().equals(uri) || endpoint.chars().anyMatch(c->Character.isWhitespace(c)||c<32||c==127))throw unavailable();
            String base=endpoint.substring(0,endpoint.length()-suffix.length());
            if(!Objects.equals(base+suffix,endpoint) || !URI.create(base+suffix).equals(uri))throw unavailable();return base;
        }catch(IllegalArgumentException ex){throw unavailable();}
    }
    public static String resourceIdOf(long modelId,long versionId){if(modelId<=0||versionId<=0)throw new ServiceException("模型版本ID无效",409);return "model:"+modelId+":version:"+versionId;}
    public static long[] parseResourceId(String id){try {if(id==null || !id.matches("model:[1-9][0-9]*:version:[1-9][0-9]*"))throw new IllegalArgumentException();var parts=id.split(":");return new long[]{Long.parseLong(parts[1]),Long.parseLong(parts[3])};}catch(IllegalArgumentException ex){throw new ServiceException("模型资源ID无效",409);}}
    private static String sha(JSONObject value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(DslCodec.canonicalDslBase64(value))));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}
