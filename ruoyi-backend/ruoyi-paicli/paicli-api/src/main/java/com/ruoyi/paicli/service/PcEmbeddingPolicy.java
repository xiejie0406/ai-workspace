package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.system.secret.ManagedSecretService;
import java.net.URI;
import java.util.*;

/** 向量用途的单一解析规则。发布引用来源配置，文件、预算和任务始终归使用者。 */
public final class PcEmbeddingPolicy {
    private PcEmbeddingPolicy(){}
    public record Selection(PcRecord settings,Map<String,Object> config,PcRecord release) {}
    private static long optionalNumber(Map<String,Object> data,String key){return data.get(key)==null?0:PcJson.number(data,key);}
    public static String fingerprint(PcStore store,Map<String,Object> config){
        String url=normalizeBaseUrl(PcJson.text(config,"baseUrl"));
        return store.json.hash(List.of(PcJson.text(config,"provider").trim().toLowerCase(Locale.ROOT),url,PcJson.text(config,"model").trim(),PcJson.number(config,"expectedDimensions"),"code-chunker-v1"));
    }
    public static long definition(Map<String,Object> settings){return settings.get("embeddingConfigurationVersion") instanceof Number n?n.longValue():1L;}
    /** 与Runner的EmbeddingIdentity canonical URL协议保持一致，不引入执行模块依赖。 */
    static String normalizeBaseUrl(String value){
        try{String root=value.trim().replaceAll("/+$","");URI uri=URI.create(root);String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
            if(!Set.of("http","https").contains(scheme)||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException();
            uri=uri.normalize();String path=uri.getRawPath().replaceAll("/+$","");if(path.endsWith("/embeddings"))throw new IllegalArgumentException();int port=uri.getPort();if(scheme.equals("https")&&port==443||scheme.equals("http")&&port==80)port=-1;String host=uri.getHost().toLowerCase(Locale.ROOT);if(host.contains(":")&&!host.startsWith("["))host="["+host+"]";return scheme+"://"+host+(port<0?"":":"+port)+path;
        }catch(Exception invalid){throw PcException.invalid("向量服务须使用合法API根地址");}
    }
    public static void validate(Map<String,Object> config){
        if(!Set.of("provider","baseUrl","model","secretRef","expectedDimensions").containsAll(config.keySet()))throw PcException.invalid("向量配置含未支持字段");
        String provider=PcJson.text(config,"provider"),model=PcJson.text(config,"model");
        if(!Set.of("ollama","openai","zhipu","glm").contains(provider)||model.isBlank()||model.length()>256)throw PcException.invalid("向量供应商或精确模型非法");
        Object rawDimensions=config.get("expectedDimensions");if(!(rawDimensions instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.longValue())throw PcException.invalid("向量预期维度须为整数");long dims=PcJson.number(config,"expectedDimensions");if(dims<1||dims>8192||model.equals("text-embedding-v4")&&dims!=1024)throw PcException.invalid("须登记有效预期维度，text-embedding-v4须为1024维");
        PcConnectionService.validatePublicUrl(PcJson.text(config,"baseUrl"),provider.equals("ollama"));
        URI uri=URI.create(PcJson.text(config,"baseUrl"));if(uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getUserInfo()!=null||uri.getPath().replaceAll("/+$","").endsWith("/embeddings"))throw PcException.invalid("向量须使用不含认证或完整业务端点的API根地址");
        if(!provider.equals("ollama"))PcSub2ApiPolicy.provider(Map.of("provider","sub2api","wireApi","responses","baseUrl",PcJson.text(config,"baseUrl")));
    }
    public static int secretVersion(ManagedSecretService secrets,Map<String,Object> config,long owner){
        String ref=PcJson.text(config,"secretRef");if("ollama".equals(config.get("provider"))&&ref.isBlank())return 0;
        if(secrets==null)throw new PcException(422,"PC_EMBEDDING_UNVERIFIED","向量托管凭据尚未准备");
        return validateMetadata(secrets.metadata("AI",ref),config,owner);
    }
    public static int validateMetadata(ManagedSecretService.Metadata m,Map<String,Object> config,long owner){
        String ref=PcJson.text(config,"secretRef");if(m==null)throw PcException.deny("向量密钥用途元数据缺失");String provider=PcJson.text(config,"provider");
        boolean sameProvider=provider.equals(m.providerCode())||provider.equals("openai")&&"aliyun".equals(m.providerCode())||Set.of("zhipu","glm").contains(provider)&&Set.of("zhipu","glm").contains(m.providerCode());
        if(!ref.startsWith("ai.paicli.user."+owner+".")||!ref.equals(m.alias())||!"AI".equals(m.kind())||!"paicli".equals(m.projectCode())||!"ACTIVE".equals(m.status())||!"embedding".equals(m.capabilityCode())||!sameProvider||!Set.of("api-key","bearer").contains(m.authMode())||m.activeVersion()==null||m.activeVersion()<1)throw PcException.deny("向量密钥必须属于来源用户的PaiCLI供应商/embedding用途");
        return m.activeVersion();
    }
    public static boolean verified(PcStore store,PcRecord settings,Map<String,Object> config,int secretVersion){
        Map<String,Object> data=store.data(settings);if(!(data.get("embeddingVerification") instanceof Map<?,?> proof))return false;
        return Boolean.TRUE.equals(proof.get("embedding"))&&Boolean.TRUE.equals(proof.get("finite"))&&Boolean.TRUE.equals(proof.get("nonZero"))
                &&fingerprint(store,config).equals(proof.get("configFingerprint"))&&String.valueOf(definition(data)).equals(String.valueOf(proof.get("configurationVersion")))
                &&String.valueOf(secretVersion).equals(String.valueOf(proof.get("secretVersion")))&&String.valueOf(PcJson.number(config,"expectedDimensions")).equals(String.valueOf(proof.get("dimensions")));
    }
    public static boolean embedding(PcStore store,PcRecord release){return "EMBEDDING".equals(store.data(release).get("releaseKind"));}
    public static boolean audience(Map<String,Object> data,long user){return "AUTHENTICATED".equals(data.get("audience"))||data.get("userIds") instanceof List<?> ids&&ids.stream().map(String::valueOf).anyMatch(String.valueOf(user)::equals);}
    public static Selection release(PcStore store,ManagedSecretService secrets,long id,long user){
        PcRecord release=store.require(PcTable.MODEL_RELEASE,id,false);Map<String,Object> data=store.data(release);
        if(!embedding(store,release)||!"PUBLISHED".equals(release.getState())||!audience(data,user))throw PcException.deny("向量发布已撤销或当前用户不在受众内");
        PcRecord settings=store.mapper.findKey(PcTable.PERSONAL_SETTINGS,"owner:"+release.getOwnerId(),false);
        if(settings==null||!(store.data(settings).get("embeddingConfig") instanceof Map<?,?>))throw PcException.conflict("平台向量来源配置已移除");
        Map<String,Object> config=PcJson.object(store.data(settings),"embeddingConfig");int version=secretVersion(secrets,config,settings.getOwnerId());
        if(PcJson.number(data,"configurationVersion")!=definition(store.data(settings))||!fingerprint(store,config).equals(data.get("configFingerprint"))||PcJson.number(data,"secretVersion")!=version||!verified(store,settings,config,version))throw PcException.conflict("平台向量来源配置或凭据版本已改变，请管理员重新验证发布");
        return new Selection(settings,config,release);
    }
    public static Selection select(PcStore store,ManagedSecretService secrets,long user){
        PcRecord own=store.mapper.findKey(PcTable.PERSONAL_SETTINGS,"owner:"+user,false);Map<String,Object> data=own==null?Map.of():store.data(own);
        String mode=Objects.toString(data.getOrDefault("embeddingMode","AUTO"));if(mode.equals("DISABLED"))return null;
        if(mode.equals("PERSONAL")||mode.equals("AUTO")&&data.get("embeddingConfig") instanceof Map<?,?> c&&!c.isEmpty()){
            if(own==null||!(data.get("embeddingConfig") instanceof Map<?,?> c)||c.isEmpty())throw new PcException(422,"PC_EMBEDDING_NOT_READY","个人向量配置尚未准备");
            Map<String,Object> config=PcJson.object(data,"embeddingConfig");validate(config);int version=secretVersion(secrets,config,user);
            if(!verified(store,own,config,version))throw new PcException(422,"PC_EMBEDDING_UNVERIFIED","当前个人向量配置须先完成验证");return new Selection(own,config,null);
        }
        if(optionalNumber(data,"embeddingReleaseId")>0)return release(store,secrets,optionalNumber(data,"embeddingReleaseId"),user);
        if(mode.equals("PLATFORM"))throw new PcException(422,"PC_EMBEDDING_NOT_READY","须明确选择平台向量发布");
        for(PcRecord release:store.mapper.list(PcTable.MODEL_RELEASE,null,null,null,"PUBLISHED"))if(embedding(store,release)&&Boolean.TRUE.equals(store.data(release).get("defaultModel"))&&audience(store.data(release),user))return release(store,secrets,release.getId(),user);
        return null;
    }
    public static Map<String,Object> publicView(PcStore store,Selection selected){
        Map<String,Object> value=new LinkedHashMap<>();for(String field:List.of("provider","model","expectedDimensions"))value.put(field,selected.config().get(field));
        value.put("configurationVersion",definition(store.data(selected.settings())));value.put("configFingerprint",fingerprint(store,selected.config()));value.put("sourceOwnerId",String.valueOf(selected.settings().getOwnerId()));value.put("source",selected.release()==null?"PERSONAL":"PLATFORM");
        if(selected.release()!=null){value.put("releaseId",String.valueOf(selected.release().getId()));value.put("version",selected.release().getVersion());value.put("releaseVersion",PcModelRelease.releaseVersion(store,selected.release()));value.put("defaultModel",Boolean.TRUE.equals(store.data(selected.release()).get("defaultModel")));}
        return value;
    }
    public static List<Map<String,Object>> releases(PcStore store,ManagedSecretService secrets,long user){
        List<Map<String,Object>> values=new ArrayList<>();for(PcRecord release:store.mapper.list(PcTable.MODEL_RELEASE,null,null,null,null)){
            if(!embedding(store,release))continue;boolean owner=Objects.equals(release.getOwnerId(),user);if(!owner&&(!"PUBLISHED".equals(release.getState())||!audience(store.data(release),user)))continue;
            try{Map<String,Object> value=publicView(store,release(store,secrets,release.getId(),user));value.put("available",true);value.put("status",release.getState());values.add(value);}
            catch(PcException invalid){if(owner){Map<String,Object> value=new LinkedHashMap<>(),data=store.data(release);for(String field:List.of("provider","model","expectedDimensions","configurationVersion","configFingerprint","defaultModel"))if(data.containsKey(field))value.put(field,data.get(field));value.put("source","PLATFORM");value.put("sourceOwnerId",String.valueOf(release.getOwnerId()));value.put("releaseId",String.valueOf(release.getId()));value.put("releaseVersion",PcModelRelease.releaseVersion(store,release));value.put("version",release.getVersion());value.put("available",false);value.put("status",release.getState());value.put("message",invalid.getMessage());values.add(value);}}
        }return values;
    }
    public static void bindForRunner(PcStore store,ManagedSecretService secrets,long user,Map<String,Object> task,boolean required,PcRecord runner){
        Object value=store.data(runner).get("capabilities");List<?> caps=value instanceof List<?> list?list:List.of();if(!caps.contains("embedding.identity.v1")){if(required)throw new PcException(422,"PC_RUNNER_CAPABILITY","语义检索须升级支持向量身份的宿主");return;}
        bind(store,secrets,user,task,required);if(task.containsKey("embeddingReleaseId")&&!caps.contains("embedding.release.v1")){if(required)throw new PcException(422,"PC_RUNNER_CAPABILITY","平台向量须升级支持发布身份的宿主");for(String field:List.of("embeddingProvider","embeddingModel","embeddingSecretOwnerId","embeddingConfigurationVersion","embeddingConfigFingerprint","embeddingExpectedDimensions","embeddingReleaseId","embeddingReleaseVersion"))task.remove(field);}
    }
    public static void bind(PcStore store,ManagedSecretService secrets,long user,Map<String,Object> task,boolean required){
        Selection selected;try{selected=select(store,secrets,user);}catch(PcException unavailable){if(required)throw unavailable;return;}
        if(selected==null){if(required)throw new PcException(422,"PC_EMBEDDING_NOT_READY","语义检索未配置或已明确停用");return;}
        task.put("embeddingProvider",selected.config().get("provider"));task.put("embeddingModel",selected.config().get("model"));task.put("embeddingSecretOwnerId",String.valueOf(selected.settings().getOwnerId()));task.put("embeddingConfigurationVersion",definition(store.data(selected.settings())));task.put("embeddingConfigFingerprint",fingerprint(store,selected.config()));task.put("embeddingExpectedDimensions",selected.config().get("expectedDimensions"));
        if(selected.release()!=null){task.put("embeddingReleaseId",String.valueOf(selected.release().getId()));task.put("embeddingReleaseVersion",PcModelRelease.releaseVersion(store,selected.release()));}
    }
    public static void checkTask(PcStore store,Selection selected,Map<String,Object> task){
        if(!task.containsKey("embeddingConfigFingerprint")||!fingerprint(store,selected.config()).equals(task.get("embeddingConfigFingerprint"))||PcJson.number(task,"embeddingConfigurationVersion")!=definition(store.data(selected.settings()))||PcJson.number(task,"embeddingSecretOwnerId")!=selected.settings().getOwnerId())throw PcException.conflict("任务固定向量配置已改变，请重新提交");
        PcRecord release=selected.release();if(release==null&&task.containsKey("embeddingReleaseId")||release!=null&&(!task.containsKey("embeddingReleaseId")||PcJson.number(task,"embeddingReleaseId")!=release.getId()||PcJson.number(task,"embeddingReleaseVersion")!=PcModelRelease.releaseVersion(store,release)))throw PcException.conflict("任务固定向量发布已撤销或改变");
    }
}
