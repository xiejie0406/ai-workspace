package com.ruoyi.paicli.service;

import com.ruoyi.paicli.configuration.PcProperties;
import com.ruoyi.paicli.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 个人上下文元数据在中心；文件创建、realpath及目录指纹证明由已认证的本人宿主完成。 */
public class PcPersonalService {
    private final PcStore store; private final PcAccess access; private final PcIdempotency idem; private final PcProperties properties;
    private com.ruoyi.system.secret.ManagedSecretService secrets;
    private java.util.function.LongFunction<Map<String,Object>> terminalStatus=id->Map.of("ready",false,"code","PC_TERMINAL_DISABLED","message","网页终端功能尚未启用");
    public void setSecrets(com.ruoyi.system.secret.ManagedSecretService value){secrets=value;}
    public void setTerminalStatus(java.util.function.LongFunction<Map<String,Object>> value){terminalStatus=value;}
    public PcPersonalService(PcStore s,PcAccess a,PcIdempotency i,PcProperties p){store=s;access=a;idem=i;properties=p;}
    public static void rejectLegacy(Map<String,Object> input){
        if(input.containsKey("workspaceId"))throw new PcException(410,"PC_CLIENT_UPGRADE_REQUIRED","工作区协议已退出，请升级客户端");
        for(String field:List.of("ownerId","ownerUserId","userId","runnerId"))if(input.containsKey(field))throw PcException.deny("个人归属和执行宿主由中心确定，不接受客户端指定："+field);
    }
    public PcRecord settings(long user){return store.mapper.findKey(PcTable.PERSONAL_SETTINGS,"owner:"+user,false);}
    public Map<String,Object> policy(long user){return store.view(store.ownerPolicy(user,false));}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> policy(long user,String key,long version,Map<String,Object> input){rejectLegacy(input);return idem.run("user:"+user+":personal-policy",key,Map.of("version",version,"input",input),()->{
        PcRecord row=store.ownerPolicy(user,true);store.version(row,version);Map<String,Object> data=store.data(row);
        Map<String,Object> updates=new LinkedHashMap<>(input);if(updates.get("directoryContextId")!=null){access.directory(PcJson.number(updates,"directoryContextId"),user,false);updates.remove("directoryContextId");}
        Set<String> fields=PcWorkspaceService.defaultPolicy().keySet();if(!fields.containsAll(updates.keySet()))throw PcException.invalid("策略含不支持字段");
        for(var item:updates.entrySet()){Object defaultValue=PcWorkspaceService.defaultPolicy().get(item.getKey()),value=item.getValue();if(defaultValue instanceof Number){if(!(value instanceof Number n)||n.longValue()<1||n.longValue()>100000000)throw PcException.invalid("策略数值超出允许范围");}else if(defaultValue instanceof Boolean&&!(value instanceof Boolean))throw PcException.invalid("策略开关必须为布尔值");else if(defaultValue instanceof List<?>){if(!(value instanceof List<?> list)||list.size()>128||list.stream().anyMatch(v->!(v instanceof String text)||text.isBlank()||text.length()>512))throw PcException.invalid("策略列表无效");if(item.getKey().equals("allowedCapabilities")&&!((List<?>)defaultValue).containsAll((List<?>)value))throw PcException.deny("不能授权未知宿主能力");}else if(item.getKey().equals("commandSandbox")&&!Set.of("required","disabled").contains(value))throw PcException.invalid("命令沙箱策略无效");}
        data.putAll(updates);if(!"required".equals(data.get("commandSandbox")))throw PcException.deny("命令沙箱是硬要求，不可通过个人设置关闭");
        if(PcJson.number(data,"maxReadTools")>4||PcJson.number(data,"maxWriteTasks")!=1||PcJson.number(data,"commandTimeoutSeconds")>60||PcJson.number(data,"approvalTimeoutSeconds")>900||PcJson.number(data,"maxQueuedTasks")>100||PcJson.number(data,"retentionDays")>3650)throw PcException.invalid("策略超出已批准执行边界");
        if(!(data.get("hardDeniedPaths") instanceof List<?> denied)||!denied.containsAll((List<?>)PcWorkspaceService.defaultPolicy().get("hardDeniedPaths")))throw PcException.deny("不能移除平台固定禁止路径");
        store.save(PcTable.POLICY_VERSION,row,data);PcRecord preferences=settings(user);if(preferences!=null){preferences=store.require(PcTable.PERSONAL_SETTINGS,preferences.getId(),true);Map<String,Object> settings=store.data(preferences);settings.put("policyVersion",row.getVersion());store.save(PcTable.PERSONAL_SETTINGS,preferences,settings);}return store.view(row);
    });}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> bootstrap(long user,String key,Map<String,Object> input){
        rejectLegacy(input);return idem.run("user:"+user+":personal-bootstrap",key,input,()->{
            if(settings(user)==null){
                store.insert(PcTable.PERSONAL_SETTINGS,null,user,null,null,null,"PERSONAL_SETTINGS","ACTIVE","owner:"+user,Map.of("policyVersion",1L,"defaultDirectoryContextId",0L,"defaultModelId",0L));
                store.insert(PcTable.POLICY_VERSION,null,user,null,null,null,"POLICY","ACTIVE","owner:"+user,PcWorkspaceService.defaultPolicy());
            }
            return context(user);
        });
    }
    public List<Map<String,Object>> directories(long user){return store.mapper.list(PcTable.DIRECTORY_CONTEXT,null,user,null,"VERIFIED").stream().map(this::directoryView).toList();}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> saveSettings(long user,String key,long version,Map<String,Object> input,PcConnectionService connections){rejectLegacy(input);return idem.run("user:"+user+":personal-settings",key,Map.of("version",version,"input",input),()->{PcRecord preferences=settings(user);if(preferences==null)throw new PcException(409,"PC_PERSONAL_NOT_READY","请先完成个人上下文准备");preferences=store.require(PcTable.PERSONAL_SETTINGS,preferences.getId(),true);store.version(preferences,version);if(!Set.of("modelId","searchConfig","embeddingConfig","embeddingMode","embeddingReleaseId").containsAll(input.keySet()))throw PcException.invalid("个人设置含不支持字段");Map<String,Object> data=store.data(preferences);if(input.get("modelId")!=null){long id=PcJson.number(input,"modelId");PcModelRelease.model(store,id,user);data.put("defaultModelId",String.valueOf(id));}connections.validateWorkspaceConfig(input,user);updateEmbeddingSelection(user,data,input,true);for(String field:List.of("searchConfig","embeddingConfig"))if(input.containsKey(field))data.put(field,input.get(field));if(input.containsKey("embeddingConfig")){data.put("embeddingConfigurationVersion",PcEmbeddingPolicy.definition(store.data(preferences))+1);data.remove("embeddingVerification");}store.save(PcTable.PERSONAL_SETTINGS,preferences,data);return context(user);});}
    private void updateEmbeddingSelection(long user,Map<String,Object> data,Map<String,Object> input,boolean manage){
        String mode=Objects.toString(input.getOrDefault("embeddingMode",data.getOrDefault("embeddingMode","AUTO")));
        if(!(manage?Set.of("AUTO","PLATFORM","PERSONAL","DISABLED"):Set.of("AUTO","PLATFORM","DISABLED")).contains(mode))throw PcException.invalid("向量使用方式非法");
        if(input.containsKey("embeddingReleaseId")){Object id=input.get("embeddingReleaseId");if(id==null||String.valueOf(id).equals("0"))data.remove("embeddingReleaseId");else{PcEmbeddingPolicy.release(store,secrets,PcJson.number(input,"embeddingReleaseId"),user);data.put("embeddingReleaseId",String.valueOf(id));}}
        if(mode.equals("PLATFORM")&&!data.containsKey("embeddingReleaseId"))throw PcException.invalid("平台方式须选择有效发布");data.put("embeddingMode",mode);
    }
    @Transactional("paicliTransactionManager") public Map<String,Object> selectEmbedding(long user,String key,long version,Map<String,Object> input){
        rejectLegacy(input);if(!Set.of("embeddingMode","embeddingReleaseId").containsAll(input.keySet()))throw PcException.deny("个人选择只接受向量使用方式和发布引用");
        return idem.run("user:"+user+":embedding-selection",key,Map.of("version",version,"input",input),()->{PcRecord row=settings(user);if(row==null)throw new PcException(409,"PC_PERSONAL_NOT_READY","请先准备个人上下文");row=store.require(PcTable.PERSONAL_SETTINGS,row.getId(),true);store.version(row,version);Map<String,Object> data=store.data(row);updateEmbeddingSelection(user,data,input,false);store.save(PcTable.PERSONAL_SETTINGS,row,data);return context(user);});
    }
    public List<Map<String,Object>> embeddingReleases(long user){return PcEmbeddingPolicy.releases(store,secrets,user);}
    public PcRecord defaultDirectory(long user){
        PcRecord prefs=settings(user);if(prefs!=null&&PcJson.number(store.data(prefs),"defaultDirectoryContextId")>0)return access.directory(PcJson.number(store.data(prefs),"defaultDirectoryContextId"),user,false);
        return store.mapper.list(PcTable.DIRECTORY_CONTEXT,null,user,null,"VERIFIED").stream().filter(d->Boolean.TRUE.equals(store.data(d).get("personalDefault"))).findFirst().orElse(null);
    }
    public PcRecord directoryFor(long user,Map<String,Object> input){
        rejectLegacy(input);return input.get("directoryContextId")==null?defaultDirectory(user):access.directory(PcJson.number(input,"directoryContextId"),user,false);
    }
    public PcRecord runnerFor(PcRecord directory,long user){
        List<PcRecord> rows=directory!=null&&directory.getRunnerId()!=null?List.of(store.require(PcTable.RUNNER,directory.getRunnerId(),false)):store.mapper.list(PcTable.RUNNER,null,user,null,"ONLINE");
        return rows.stream().filter(r->Objects.equals(r.getOwnerId(),user)&&"ONLINE".equals(r.getState())).filter(r->{
            Object heartbeat=store.data(r).get("lastHeartbeatAt");if(!(heartbeat instanceof String seen))return false;
            try{return Instant.parse(seen).plusSeconds(properties.getDeviceSessionSeconds()).isAfter(store.clock.instant());}catch(RuntimeException e){return false;}
        }).findFirst().orElse(null);
    }
    public PcRecord modelFor(long user,Map<String,Object> input){
        rejectLegacy(input);List<PcRecord> models=PcModelRelease.visible(store,user).stream().filter(m->Boolean.TRUE.equals(store.data(m).get("enabled"))&&!Set.of("FROZEN","REVOKED_IMPORT").contains(m.getState())).toList();
        long id=input.get("modelId")!=null?PcJson.number(input,"modelId"):settings(user)==null?0:PcJson.number(store.data(settings(user)),"defaultModelId");
        if(id>0)try{PcRecord selected=PcModelRelease.model(store,id,user);if(Boolean.TRUE.equals(store.data(selected).get("enabled"))&&!Set.of("FROZEN","REVOKED_IMPORT").contains(selected.getState()))return selected;if(input.containsKey("modelId"))throw new PcException(409,"PC_MODEL_NOT_READY","指定模型尚未启用");}catch(PcException unavailable){if(input.containsKey("modelId"))throw unavailable;}
        return models.stream().filter(m->Boolean.TRUE.equals(store.data(m).get("defaultModel"))||store.mapper.findKey(PcTable.MODEL_RELEASE,"model:"+m.getId(),false)!=null&&Boolean.TRUE.equals(store.data(store.mapper.findKey(PcTable.MODEL_RELEASE,"model:"+m.getId(),false)).get("defaultModel"))).findFirst().orElse(models.isEmpty()?null:models.get(0));
    }
    public Map<String,Object> context(long user){
        PcRecord directory=defaultDirectory(user),runner=runnerFor(directory,user),model;try{model=modelFor(user,Map.of());}catch(PcException invalidDefault){model=null;}
        Map<String,Object> result=new LinkedHashMap<>();result.put("ownerUserId",String.valueOf(user));result.put("directoryContext",directory==null?null:directoryView(directory));result.put("directoryState",directory==null?"NOT_PREPARED":"VERIFIED");
        result.put("models",PcModelRelease.visible(store,user).stream().filter(m->!Set.of("FROZEN","REVOKED_IMPORT").contains(m.getState())).map(m->PcModelRelease.publicModel(store,m,user)).toList());result.put("modelId",model==null?null:String.valueOf(model.getId()));
        boolean modelReady=modelReady(model,user);
        String code=runner==null?"PC_RUNTIME_NOT_READY":!modelReady?"PC_MODEL_NOT_READY":"READY";
        String message=runner==null?"执行环境尚未准备，请管理员连接该用户的可信宿主":!modelReady?"模型或托管凭据尚未准备，请在模型与设置中完成配置和所需验证":"执行宿主已联机，模型配置和凭据有效；每次发送仍核对版本，实际调用结果另行报告";
        Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("ready",runner!=null);runtime.put("code",code);runtime.put("message",message);runtime.put("runnerId",runner==null?null:String.valueOf(runner.getId()));runtime.put("directoryReady",directory!=null);runtime.put("modelReady",modelReady);result.put("runtime",runtime);
        Map<String,Object> terminal=runner==null?Map.of("ready",false,"code","PC_RUNTIME_NOT_READY","message","执行宿主尚未联机"):directory==null?Map.of("ready",false,"code","PC_DIRECTORY_NOT_READY","message","个人文件目录尚未准备，文字问答仍可使用"):!(store.data(runner).get("capabilities") instanceof List<?> c&&c.contains("terminal"))?Map.of("ready",false,"code","PC_TERMINAL_CAPABILITY","message","宿主尚未安装真实PTY终端能力"):terminalStatus.apply(runner.getId());result.put("terminal",terminal);result.put("canChat",runner!=null&&modelReady);result.put("canOpenTerminal",Boolean.TRUE.equals(terminal.get("ready")));result.put("bootstrapped",settings(user)!=null);result.put("settings",settings(user)==null?Map.of("version",0):store.view(settings(user)));result.put("embeddingReleases",PcEmbeddingPolicy.releases(store,secrets,user));try{PcEmbeddingPolicy.Selection embedding=PcEmbeddingPolicy.select(store,secrets,user);Map<String,Object> status=embedding==null?new LinkedHashMap<>():PcEmbeddingPolicy.publicView(store,embedding);status.put("status",embedding==null?settings(user)!=null&&"DISABLED".equals(store.data(settings(user)).get("embeddingMode"))?"DISABLED":"NOT_CONFIGURED":"READY");result.put("embedding",status);}catch(PcException invalid){Map<String,Object> pending=new LinkedHashMap<>();pending.put("status","UNVERIFIED");pending.put("message",invalid.getMessage());PcRecord own=settings(user);if(own!=null&&store.data(own).get("embeddingConfig") instanceof Map<?,?>){Map<String,Object> config=PcJson.object(store.data(own),"embeddingConfig");try{pending.put("configFingerprint",PcEmbeddingPolicy.fingerprint(store,config));pending.put("configurationVersion",PcEmbeddingPolicy.definition(store.data(own)));}catch(PcException legacy){}}result.put("embedding",pending);}return result;
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> requestDirectory(long user,String key,Map<String,Object> input,PcSessionService sessions){
        rejectLegacy(input);return idem.run("user:"+user+":directory-request",key,input,()->{
            PcRecord home=defaultDirectory(user);if(home==null)throw new PcException(409,"PC_DIRECTORY_NOT_READY","个人文件根尚未由可信宿主准备");
            PcRecord runner=runnerFor(home,user);if(runner==null)throw new PcException(503,"PC_RUNTIME_NOT_READY","本人可信宿主尚未联机");
            String path=canonical(PcJson.text(input,"path")),base=canonical(PcJson.text(store.data(home),"rootPath"));
            if(!(path.equals(base)||path.startsWith(base+"/")))throw PcException.deny("本期只支持本人文件根内的已有项目目录");
            Map<String,Object> result=new LinkedHashMap<>(sessions.control(user,home.getId(),runner.getId(),null,"DIRECTORY_VERIFY",Map.of("canonicalPath",path)));result.put("taskId",result.get("id"));result.put("directoryReady",false);return result;
        });
    }
    private boolean modelReady(PcRecord model,long user){
        if(model==null||secrets==null||!Boolean.TRUE.equals(store.data(model).get("enabled")))return false;
        try{Map<String,Object> data=store.data(model);PcRecord provider=store.require(PcTable.PROVIDER_CONFIG,PcJson.number(data,"providerId"),false);PcModelRelease.active(store,model,user);Map<String,Object> config=store.data(provider);String ref=PcJson.text(config,"secretRef");var metadata=secrets.metadata("AI",ref);if(!ref.startsWith("ai.paicli.user."+model.getOwnerId()+".")||!"paicli".equals(metadata.projectCode())||!"ACTIVE".equals(metadata.status())||metadata.activeVersion()==null)return false;PcModelRelease.verifySecretVersion(store,model,user,metadata.activeVersion());if(PcSub2ApiPolicy.applies(config)){PcSub2ApiPolicy.validateMetadata(metadata,ref,model.getOwnerId());return PcSub2ApiPolicy.verified(data,model.getVersion(),config,config.get("configurationVersion") instanceof Number n?n.longValue():provider.getVersion(),metadata.activeVersion());}return true;}catch(RuntimeException failure){return false;}
    }
    public Map<String,Object> directoryView(PcRecord directory){Map<String,Object> result=store.view(directory);Map<String,Object> data=store.data(directory);result.put("path",data.get("rootPath"));result.put("label",data.getOrDefault("label",Boolean.TRUE.equals(data.get("personalDefault"))?"我的文件":"项目目录"));result.put("rootFingerprint",data.get("rootFingerprint"));return result;}
    /** 宿主凭证限定owner；元数据证明不代替原生侧每次执行realpath/owner/指纹重验。 */
    @Transactional("paicliTransactionManager")
    public Map<String,Object> prove(PcRunnerPrincipal actor,String key,Map<String,Object> input){return idem.run("runner:"+actor.runnerId()+":directory-proof",key,input,()->{
        if(input.containsKey("workspaceId")||input.containsKey("ownerUserId")||input.containsKey("ownerId"))throw PcException.deny("目录证明归属只能来自宿主凭证");
        PcRecord runner=store.require(PcTable.RUNNER,actor.runnerId(),true);if(!Objects.equals(runner.getOwnerId(),actor.ownerId())||"REVOKED".equals(runner.getState()))throw PcException.deny("宿主已撤销或不属于当前用户");
        String base=PcJson.text(store.data(runner),"canonicalRootPath"),path=canonical(PcJson.text(input,"canonicalPath")),fingerprint=PcJson.text(input,"rootFingerprint");
        if(base.isBlank())base=path;
        String suffix="/users/"+actor.ownerId()+"/files";base=canonical(base);
        if(!base.endsWith(suffix)||!(path.equals(base)||path.startsWith(base+"/"))||!fingerprint.matches("[a-f0-9]{64}"))throw PcException.deny("目录必须位于已绑定用户的个人文件根内，且提供真实宿主指纹");
        if(settings(actor.ownerId())==null){store.insert(PcTable.PERSONAL_SETTINGS,null,actor.ownerId(),null,null,null,"PERSONAL_SETTINGS","ACTIVE","owner:"+actor.ownerId(),Map.of("policyVersion",1L,"defaultDirectoryContextId",0L,"defaultModelId",0L));store.insert(PcTable.POLICY_VERSION,null,actor.ownerId(),null,null,null,"POLICY","ACTIVE","owner:"+actor.ownerId(),PcWorkspaceService.defaultPolicy());}
        String identity="directory:"+actor.ownerId()+":"+actor.runnerId()+":"+PcJson.digest(path);PcRecord existing=store.mapper.findKey(PcTable.DIRECTORY_CONTEXT,identity,true);
        Map<String,Object> data=new LinkedHashMap<>();data.put("rootPath",path);data.put("path",path);data.put("rootFingerprint",fingerprint);data.put("personalDefault",path.equals(base));data.put("verifiedAt",store.clock.instant().toString());data.put("policyVersion",PcJson.number(store.data(settings(actor.ownerId())),"policyVersion"));data.put("commandSandbox","required");data.put("label",path.equals(base)?"我的文件":path.substring(path.lastIndexOf('/')+1));
        PcRecord directory=existing;if(directory==null)directory=store.insert(PcTable.DIRECTORY_CONTEXT,null,actor.ownerId(),null,null,actor.runnerId(),"DIRECTORY_CONTEXT","VERIFIED",identity,data);
        else{if(!fingerprint.equals(PcJson.text(store.data(directory),"rootFingerprint"))&&store.mapper.activeTasks(directory.getId(),null,null,null)>0)throw PcException.conflict("目录仍有执行或未知副作用，不能替换目录证明");if(!fingerprint.equals(PcJson.text(store.data(directory),"rootFingerprint")))store.save(PcTable.DIRECTORY_CONTEXT,directory,data);}
        if(path.equals(base)){PcRecord prefs=store.require(PcTable.PERSONAL_SETTINGS,settings(actor.ownerId()).getId(),true);Map<String,Object> pd=store.data(prefs);pd.put("defaultDirectoryContextId",String.valueOf(directory.getId()));store.save(PcTable.PERSONAL_SETTINGS,prefs,pd);Map<String,Object> rd=store.data(runner);rd.put("canonicalRootPath",base);rd.put("rootFingerprint",fingerprint);runner.setDirectoryContextId(directory.getId());store.save(PcTable.RUNNER,runner,rd);}
        return directoryView(directory);
    });}
    public static String canonical(String value){String path=value.replace('\\','/');if(path.isBlank()||path.length()>4096||path.indexOf('\0')>=0||path.contains("/../")||path.endsWith("/..")||path.contains("/./")||path.endsWith("/.")||path.contains("//"))throw PcException.invalid("宿主目录路径非法");if(!(path.startsWith("/")||path.matches("^[A-Za-z]:/.*")))throw PcException.invalid("须提供宿主规范化绝对路径");while(path.endsWith("/")&&path.length()>1)path=path.substring(0,path.length()-1);return path;}
}
