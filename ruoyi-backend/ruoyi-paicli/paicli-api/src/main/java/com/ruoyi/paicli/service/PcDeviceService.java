package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.paicli.configuration.PcProperties;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

public class PcDeviceService {
    private final PcStore store;private final PcAccess access;private final PcCrypto crypto;private final PcIdempotency idem;private final PcEvidence evidence;private final PcProperties properties;private final ISysUserService users;private PcPersonalService personal;public void setPersonal(PcPersonalService p){personal=p;}
    public PcDeviceService(PcStore store,PcAccess access,PcCrypto crypto,PcIdempotency idem,PcEvidence evidence,PcProperties properties,ISysUserService users){this.store=store;this.access=access;this.crypto=crypto;this.idem=idem;this.evidence=evidence;this.properties=properties;this.users=users;}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> enroll(long user,String key,Map<String,Object> input){return idem.run("user:"+user+":enroll",key,input,()->{
        PcPersonalService.rejectLegacy(input);long workspace=0L;
        String name=PcJson.text(input,"name");if(name.isBlank()||name.length()>128)throw PcException.invalid("宿主名称非法");
        PcRecord runner=store.insert(PcTable.RUNNER,null,user,null,null,null,"RUNNER","PENDING",null,Map.of("name",name,"capabilities",List.of(),"sandboxVerified",false));
        String token=crypto.token("pcd_");PcRecord cred=credential(runner,"DEVICE",token,300);evidence.audit(workspace,user,null,null,"RUNNER_ENROLL","ACCEPTED",Map.of("runnerId",runner.getId()));
        return Map.of("runnerId",String.valueOf(runner.getId()),"directoryContextId",String.valueOf(workspace),"deviceToken",token,"enrollmentId",String.valueOf(runner.getId()),"code",token,"expiresAt",cred.getExpiresAt().toString(),"command","paicli runner（通过安全输入提供中心地址及单次绑定码）");
    });}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> exchange(PcRunnerPrincipal principal,String key,Map<String,Object> input){return idem.run("device:"+principal.credentialId()+":exchange",key,input,()->{
        if(input.containsKey("workspaceId"))throw new PcException(410,"PC_WORKSPACE_REMOVED","请升级个人目录客户端");
        if(input.containsKey("ownerUserId")&&PcJson.number(input,"ownerUserId")!=principal.ownerId())throw PcException.deny("宿主声明必须匹配设备凭据的稳定用户身份");
        if(!principal.kind().equals("DEVICE"))throw PcException.deny("仅设备绑定凭证可交换短期会话");
        PcRecord runner=store.require(PcTable.RUNNER,principal.runnerId(),true);if(principal.directoryContextId()>0)access.directory(principal.directoryContextId(),principal.ownerId(),false);
        long administration=store.data(runner).get("administrationVersion") instanceof Number n?n.longValue():1;
        if("REVOKED".equals(runner.getState())||administration!=principal.runnerVersion()||!Objects.equals(runner.getOwnerId(),principal.ownerId())||principal.directoryContextId()>0&&!Objects.equals(runner.getDirectoryContextId(),principal.directoryContextId()))throw PcException.deny("设备在认证后已撤销或身份改变，不能交换会话");
        PcRecord binding=store.require(PcTable.RUNNER_CREDENTIAL,principal.credentialId(),true);if(!binding.getState().equals("ACTIVE")||!binding.getExpiresAt().isAfter(store.clock.instant()))throw PcException.deny("设备绑定凭证已撤销或过期");
        var account=users.selectUserById(principal.ownerId());if(account==null||!"0".equals(account.getStatus())||!"0".equals(account.getDelFlag()))throw PcException.deny("绑定账号已停用");
        Map<String,Object> p=store.data(runner);String root=PcJson.text(input,"canonicalRootPath"),fingerprint=PcJson.text(input,"rootFingerprint");
        if(personal==null)throw new IllegalStateException("个人目录服务未装配");
        String runtime=PcPersonalService.canonical(PcJson.text(input,"runtimeCanonicalPath")),runtimeFingerprint=PcJson.text(input,"runtimeFingerprint");
        if(!runtime.endsWith("/users/"+principal.ownerId()+"/runtime")||!runtimeFingerprint.matches("[a-f0-9]{64}"))throw PcException.deny("宿主运行根必须位于本人独占runtime目录并提供真实证明");
        p.put("runtimeCanonicalPath",runtime);p.put("runtimeFingerprint",runtimeFingerprint);store.save(PcTable.RUNNER,runner,p);
        Map<String,Object> proof=null;
        if(!root.isBlank()){root=PcPersonalService.canonical(root);if(!root.endsWith("/users/"+principal.ownerId()+"/files")||!fingerprint.matches("[a-f0-9]{64}"))throw PcException.deny("宿主文件根必须属于稳定用户目录并提供真实证明");p.put("canonicalRootPath",root);p.put("rootFingerprint",fingerprint);store.save(PcTable.RUNNER,runner,p);proof=personal.prove(principal,"exchange:"+key,Map.of("canonicalPath",root,"rootFingerprint",fingerprint));}
        runner=store.require(PcTable.RUNNER,runner.getId(),true);p=store.data(runner);
        Object caps=input.getOrDefault("capabilities",List.of());if(!(caps instanceof List<?> list)||list.size()>32)throw PcException.invalid("能力列表非法");
        List<String> allowed=List.of("files","command","memory","rag","web","mcp","browser","skills","snapshot","images","wechat","terminal","sub2api.responses.v1","sub2api.chat_completions.v1","sub2api.responses.images.v1","embedding.identity.v1","embedding.release.v1");
        for(Object c:(List<?>)caps)if(!allowed.contains(String.valueOf(c)))throw PcException.invalid("未批准的设备能力");
        for(String field:List.of("platform","distributionVersion"))if(input.get(field)!=null){String value=PcJson.text(input,field);if(value.length()>128)throw PcException.invalid("设备平台或版本过长");p.put(field,value);}p.put("capabilities",caps);p.put("capacity",Math.max(1,Math.min(4,input.get("capacity") instanceof Number n?n.intValue():1)));p.put("lastHeartbeatAt",store.clock.instant().toString());
        // 注册版本代表管理员撤销/配置版本；上线与心跳不得改变它使其他会话失效。
        long rv=runner.getVersion();runner.setState("ONLINE");store.save(PcTable.RUNNER,runner,p);
        // 设备凭证和已有会话关联administrationVersion；运行心跳版本不作为吊销版本。
        p.putIfAbsent("administrationVersion",1L);store.save(PcTable.RUNNER,runner,p);
        String boundToken="";if("ENROLL".equals(store.data(binding).get("purpose"))){binding.setState("CONSUMED");store.save(PcTable.RUNNER_CREDENTIAL,binding,store.data(binding));boundToken=crypto.token("pcd_");PcRecord bound=credential(runner,"DEVICE",boundToken,86400*30);Map<String,Object> bp=store.data(bound);bp.put("purpose","BOUND");store.save(PcTable.RUNNER_CREDENTIAL,bound,bp);}
        String token=crypto.token("pcs_");PcRecord cred=credential(runner,"SESSION",token,properties.getDeviceSessionSeconds());
        Map<String,Object> response=new LinkedHashMap<>(Map.of("runnerId",String.valueOf(runner.getId()),"directoryContextId",String.valueOf(runner.getDirectoryContextId()),"sessionToken",token,"expiresAt",cred.getExpiresAt().toString(),"heartbeatSeconds",properties.getHeartbeatSeconds(),"leaseSeconds",properties.getLeaseSeconds()));response.put("ownerUserId",String.valueOf(principal.ownerId()));response.put("directoryContext",proof);response.put("directoryContextId",runner.getDirectoryContextId()==null?null:String.valueOf(runner.getDirectoryContextId()));response.put("runtimeCanonicalPath",runtime);response.put("runtimeFingerprint",runtimeFingerprint);if(!boundToken.isBlank())response.put("deviceToken",boundToken);return response;
    });}
    private PcRecord credential(PcRecord runner,String kind,String token,int ttl){
        long administration=store.data(runner).get("administrationVersion") instanceof Number n?n.longValue():1;
        PcRecord r=store.insert(PcTable.RUNNER_CREDENTIAL,runner.getDirectoryContextId(),runner.getOwnerId(),null,null,runner.getId(),kind,"ACTIVE",PcJson.digest(token),Map.of("administrationVersion",administration,"purpose",kind.equals("DEVICE")&&ttl==300?"ENROLL":kind));
        r.setExpiresAt(store.clock.instant().plusSeconds(ttl));store.save(PcTable.RUNNER_CREDENTIAL,r,store.data(r));return r;
    }
    public PcRunnerPrincipal authenticate(String token){
        if(token==null||!token.matches("pc[dsw]_[A-Za-z0-9_-]{43}"))throw new PcException(401,"PC_RUNNER_CREDENTIAL","缺少合法设备凭证");
        PcRecord c=store.mapper.findKey(PcTable.RUNNER_CREDENTIAL,PcJson.digest(token),false);
        if(c==null||(!"ACTIVE".equals(c.getState())&&!("CONSUMED".equals(c.getState())&&"ENROLL".equals(store.data(c).get("purpose"))))||c.getExpiresAt()==null||!c.getExpiresAt().isAfter(store.clock.instant()))throw new PcException(401,"PC_RUNNER_CREDENTIAL","设备凭证已过期或撤销");
        PcRecord r=store.require(PcTable.RUNNER,c.getRunnerId(),false);Map<String,Object> p=store.data(r);long version=p.get("administrationVersion") instanceof Number n?n.longValue():1;
        if("REVOKED".equals(r.getState())||version!=PcJson.number(store.data(c),"administrationVersion"))throw new PcException(401,"PC_RUNNER_REVOKED","设备已撤销");
        var account=users.selectUserById(c.getOwnerId());if(account==null||!"0".equals(account.getStatus())||!"0".equals(account.getDelFlag()))throw new PcException(401,"PC_ACCOUNT_DISABLED","绑定若依账号已停用");
        if(c.getDirectoryContextId()!=null)access.directory(c.getDirectoryContextId(),c.getOwnerId(),false);
        if(c.getKind().equals("WECHAT")){PcRecord binding=store.require(PcTable.WECHAT_BINDING,c.getParentId(),false);if(!Set.of("BOUND","RUNNING","LOGIN_REQUIRED").contains(binding.getState())||(store.data(binding).get("channelVersion") instanceof Number n?n.longValue():binding.getVersion())!=PcJson.number(store.data(c),"configVersion"))throw new PcException(401,"PC_WECHAT_CHANNEL_REVOKED","微信通道配置已改变或已暂停");}
        return new PcRunnerPrincipal(r.getId(),c.getDirectoryContextId()==null?0L:c.getDirectoryContextId(),c.getOwnerId(),c.getId(),version,c.getKind());
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> sandbox(long user,long id,long version,String key,Map<String,Object> input){return idem.run("user:"+user+":runner-sandbox:"+id,key,input,()->{PcRecord runner=store.require(PcTable.RUNNER,id,true);if(!Objects.equals(runner.getOwnerId(),user))throw PcException.deny("无权管理该宿主");store.version(runner,version);String reason=PcJson.text(input,"reason");boolean verified=Boolean.TRUE.equals(input.get("verified"));if(reason.isBlank()||reason.length()>8192)throw PcException.invalid("须填写核验或停用依据");if(store.mapper.activeTasks(runner.getDirectoryContextId(),null,null,id)>0)throw PcException.conflict("活跃执行期间不能改变隔离核验");Map<String,Object> p=store.data(runner);Map<String,Object> audit=new LinkedHashMap<>();audit.put("runnerId",id);audit.put("reasonHash",PcJson.digest(reason));if(verified){Map<String,Object> attestation=PcJson.object(input,"attestation");String provider=PcJson.text(attestation,"provider"),hash=PcJson.text(attestation,"sha256");java.time.Instant checked;try{checked=java.time.Instant.parse(PcJson.text(attestation,"checkedAt"));}catch(Exception e){throw PcException.invalid("沙箱核验时间非法");}if(!Set.of("seatbelt","bubblewrap").contains(provider)||!hash.matches("[a-f0-9]{64}")||checked.isAfter(store.clock.instant().plusSeconds(30))||checked.isBefore(store.clock.instant().minusSeconds(300)))throw PcException.invalid("须明确真实provider、核验哈希和5分钟内时间");String platform=PcJson.text(p,"platform").toLowerCase(Locale.ROOT);if(!(platform.contains("linux")&&provider.equals("bubblewrap"))&&!(platform.contains("mac")&&provider.equals("seatbelt")))throw PcException.deny("沙箱provider须匹配已上报Linux/macOS，Windows或未知平台不可批准");p.put("sandboxAttestation",Map.of("provider",provider,"sha256",hash,"checkedAt",checked.toString(),"approvedBy",String.valueOf(user),"reasonHash",PcJson.digest(reason)));audit.put("attestationHash",hash);audit.put("provider",provider);}else{p.remove("sandboxAttestation");p.put("sandboxRevokedAt",store.clock.instant().toString());p.put("sandboxRevokeReasonHash",PcJson.digest(reason));}p.put("sandboxVerified",verified);store.save(PcTable.RUNNER,runner,p);evidence.audit(runner.getDirectoryContextId(),user,null,null,"RUNNER_SANDBOX",verified?"APPROVED":"DISABLED",audit);return store.view(runner);});}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> revoke(long user,long runnerId,long version,String key){return idem.run("user:"+user+":revoke:"+runnerId,key,Map.of("version",version),()->{
        PcRecord runner=store.require(PcTable.RUNNER,runnerId,true);if(!Objects.equals(runner.getOwnerId(),user))throw PcException.deny("无权管理该宿主");store.version(runner,version);Map<String,Object> p=store.data(runner);
        p.put("administrationVersion",(p.get("administrationVersion") instanceof Number n?n.longValue():1)+1);runner.setState("REVOKED");store.save(PcTable.RUNNER,runner,p);
        for(PcRecord c:store.mapper.list(PcTable.RUNNER_CREDENTIAL,null,user,null,"ACTIVE")){if(Objects.equals(c.getRunnerId(),runnerId)){c.setState("REVOKED");store.save(PcTable.RUNNER_CREDENTIAL,c,store.data(c));}}
        evidence.audit(runner.getDirectoryContextId(),user,null,null,"RUNNER_REVOKE","ACCEPTED",Map.of("runnerId",runnerId));return store.view(runner);
    });}
    public List<Map<String,Object>> runners(Long workspace,long user){PcRecord directory=workspace==null?null:access.directory(workspace,user,false);return store.mapper.list(PcTable.RUNNER,null,user,null,null).stream().filter(r->directory==null||Objects.equals(r.getId(),directory.getRunnerId())).toList().stream().map(r->{Map<String,Object> view=store.view(r);Object seen=view.get("lastHeartbeatAt");if(!r.getState().equals("REVOKED")&&(seen==null||java.time.Instant.parse(seen.toString()).plusSeconds(properties.getDeviceSessionSeconds()).isBefore(store.clock.instant())))view.put("status","OFFLINE");return view;}).toList();}
}
