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
    public Map<String,Object> enroll(long user,String key,Map<String,Object> input){
        // runnerId只在本人待绑定设备恢复入口作为目标标识，不接受用户/目录等身份声明。
        Map<String,Object> identityInput=new LinkedHashMap<>(input);identityInput.remove("runnerId");PcPersonalService.rejectLegacy(identityInput);
        boolean recovery=input.containsKey("runnerId");long target=recovery?PcJson.number(input,"runnerId"):0;
        if(recovery)requirePendingRecovery(user,target);
        Map<String,Object> result=idem.run("user:"+user+":enroll",key,input,()->{
        long workspace=0L;PcRecord runner;
        if(recovery){
            long id=target;runner=requirePendingRecovery(user,id);
            List<PcRecord> credentials=store.mapper.list(PcTable.RUNNER_CREDENTIAL,null,user,null,null).stream().filter(c->Objects.equals(c.getRunnerId(),id)).toList();
            for(PcRecord prior:credentials)if("DEVICE".equals(prior.getKind())&&"ENROLL".equals(store.data(prior).get("purpose"))&&!"REVOKED".equals(prior.getState())){
                prior=store.require(PcTable.RUNNER_CREDENTIAL,prior.getId(),true);prior.setState("REVOKED");store.save(PcTable.RUNNER_CREDENTIAL,prior,store.data(prior));
            }
        }else{
            String name=PcJson.text(input,"name");if(name.isBlank()||name.length()>128)throw PcException.invalid("宿主名称非法");
            runner=store.insert(PcTable.RUNNER,null,user,null,null,null,"RUNNER","PENDING",null,Map.of("name",name,"capabilities",List.of(),"sandboxVerified",false));
        }
        String token=crypto.token("pcd_");PcRecord cred=credential(runner,"DEVICE",token,300);evidence.audit(workspace,user,null,null,recovery?"RUNNER_ENROLL_RECOVER":"RUNNER_ENROLL","ACCEPTED",Map.of("runnerId",runner.getId()));
        return Map.of("runnerId",String.valueOf(runner.getId()),"directoryContextId",String.valueOf(workspace),"deviceToken",token,"enrollmentId",String.valueOf(runner.getId()),"code",token,"expiresAt",cred.getExpiresAt().toString(),"command","paicli runner（通过安全输入提供中心地址及单次绑定码）");
        });
        // 加密幂等响应不代表旧码仍有效，返回前核对当前凭据摘要和归属。
        long id=PcJson.number(result,"runnerId");PcRecord runner=store.require(PcTable.RUNNER,id,true);
        if(recovery&&id!=target)throw PcException.deny("恢复响应目标已变化");
        requirePendingRecovery(user,id);
        PcRecord issued=store.mapper.findKey(PcTable.RUNNER_CREDENTIAL,PcJson.digest(PcJson.text(result,"deviceToken")),true);
        long administration=store.data(runner).get("administrationVersion") instanceof Number n?n.longValue():1;
        if(!Objects.equals(runner.getOwnerId(),user)||!"PENDING".equals(runner.getState())||issued==null||!Objects.equals(issued.getRunnerId(),id)||!Objects.equals(issued.getOwnerId(),user)
            ||!"DEVICE".equals(issued.getKind())||!"ENROLL".equals(store.data(issued).get("purpose"))||!"ACTIVE".equals(issued.getState())
            ||issued.getExpiresAt()==null||PcJson.number(store.data(issued),"administrationVersion")!=administration)
            throw PcException.deny("首次绑定码已过期、使用或被替换，不能重放为可用结果");
        if(!issued.getExpiresAt().isAfter(store.clock.instant())){
            if(recovery)throw PcException.deny("恢复的首次绑定码已过期，不能重放为可用结果");
            // 首次HTTP响应丢失时，客户端尚无runnerId；只返回原登记元数据以显式恢复，不返回过期秘密。
            boolean replacement=store.mapper.list(PcTable.RUNNER_CREDENTIAL,null,user,null,null).stream()
                .anyMatch(c->Objects.equals(c.getRunnerId(),id)&&!Objects.equals(c.getId(),issued.getId())&&"DEVICE".equals(c.getKind())&&"ACTIVE".equals(c.getState()));
            if(replacement)throw PcException.deny("首次绑定码已被替换，请核对现有绑定结果");
            Map<String,Object> expired=new LinkedHashMap<>(result);expired.put("deviceToken","");expired.put("code","");expired.put("state","EXPIRED");expired.put("expiresAt",issued.getExpiresAt().toString());return expired;
        }
        return result;
    }
    private PcRecord requirePendingRecovery(long user,long id){
        if(id<1)throw PcException.invalid("须指定原待绑定宿主");PcRecord runner=store.require(PcTable.RUNNER,id,true);
        if(!Objects.equals(runner.getOwnerId(),user)||!"PENDING".equals(runner.getState()))throw PcException.deny("仅能恢复本人尚未绑定的宿主");
        boolean proved=runner.getDirectoryContextId()!=null||store.mapper.list(PcTable.DIRECTORY_CONTEXT,null,user,null,null).stream().anyMatch(d->Objects.equals(d.getRunnerId(),id));
        boolean bound=store.mapper.list(PcTable.RUNNER_CREDENTIAL,null,user,null,null).stream().filter(c->Objects.equals(c.getRunnerId(),id))
            .anyMatch(c->"DEVICE".equals(c.getKind())&&("BOUND".equals(store.data(c).get("purpose"))||"CONSUMED".equals(c.getState())));
        if(proved||bound)throw PcException.deny("宿主曾完成设备绑定或目录证明，不能重置首次码");return runner;
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> exchange(PcRunnerPrincipal principal,String key,Map<String,Object> input){
        // 幂等响应也须受当前绑定有效期和管理员撤销约束；消费过的首次码仅允许重放原请求。
        PcRecord currentRunner=store.require(PcTable.RUNNER,principal.runnerId(),true);
        PcRecord currentBinding=store.require(PcTable.RUNNER_CREDENTIAL,principal.credentialId(),true);
        requireBinding(principal,currentRunner,currentBinding,true);
        return idem.run("device:"+principal.credentialId()+":exchange",key,input,()->{
        if(input.containsKey("workspaceId"))throw new PcException(410,"PC_WORKSPACE_REMOVED","请升级个人目录客户端");
        if(input.containsKey("ownerUserId")&&PcJson.number(input,"ownerUserId")!=principal.ownerId())throw PcException.deny("宿主声明必须匹配设备凭据的稳定用户身份");
        if(!principal.kind().equals("DEVICE"))throw PcException.deny("仅设备绑定凭证可交换短期会话");
        PcRecord runner=store.require(PcTable.RUNNER,principal.runnerId(),true);if(principal.directoryContextId()>0)access.directory(principal.directoryContextId(),principal.ownerId(),false);
        long administration=store.data(runner).get("administrationVersion") instanceof Number n?n.longValue():1;
        if("REVOKED".equals(runner.getState())||administration!=principal.runnerVersion()||!Objects.equals(runner.getOwnerId(),principal.ownerId())||principal.directoryContextId()>0&&!Objects.equals(runner.getDirectoryContextId(),principal.directoryContextId()))throw PcException.deny("设备在认证后已撤销或身份改变，不能交换会话");
        PcRecord binding=store.require(PcTable.RUNNER_CREDENTIAL,principal.credentialId(),true);requireBinding(principal,runner,binding,false);
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
        else if("BOUND".equals(store.data(binding).get("purpose")))boundToken=rotateBound(runner,binding);
        String token=crypto.token("pcs_");PcRecord cred=credential(runner,"SESSION",token,properties.getDeviceSessionSeconds());
        Map<String,Object> response=new LinkedHashMap<>(Map.of("runnerId",String.valueOf(runner.getId()),"directoryContextId",String.valueOf(runner.getDirectoryContextId()),"sessionToken",token,"expiresAt",cred.getExpiresAt().toString(),"heartbeatSeconds",properties.getHeartbeatSeconds(),"leaseSeconds",properties.getLeaseSeconds()));response.put("ownerUserId",String.valueOf(principal.ownerId()));response.put("directoryContext",proof);response.put("directoryContextId",runner.getDirectoryContextId()==null?null:String.valueOf(runner.getDirectoryContextId()));response.put("runtimeCanonicalPath",runtime);response.put("runtimeFingerprint",runtimeFingerprint);if(!boundToken.isBlank())response.put("deviceToken",boundToken);return response;
    });}
    private void requireBinding(PcRunnerPrincipal principal,PcRecord runner,PcRecord binding,boolean allowConsumedEnrollment){
        Map<String,Object> rp=store.data(runner),bp=store.data(binding);
        long administration=rp.get("administrationVersion") instanceof Number n?n.longValue():1;
        boolean consumed=allowConsumedEnrollment&&"CONSUMED".equals(binding.getState())&&"ENROLL".equals(bp.get("purpose"));
        if(!"DEVICE".equals(principal.kind())||!"DEVICE".equals(binding.getKind())
            ||!Objects.equals(binding.getRunnerId(),runner.getId())||!Objects.equals(binding.getOwnerId(),principal.ownerId())
            ||!Objects.equals(runner.getOwnerId(),principal.ownerId())||"REVOKED".equals(runner.getState())
            ||administration!=principal.runnerVersion()||administration!=PcJson.number(bp,"administrationVersion")
            ||!("ACTIVE".equals(binding.getState())||consumed)||binding.getExpiresAt()==null||!binding.getExpiresAt().isAfter(store.clock.instant())
            ||!Set.of("ENROLL","BOUND").contains(PcJson.text(bp,"purpose")))throw PcException.deny("设备绑定凭证已撤销、过期或身份改变");
        var account=users.selectUserById(principal.ownerId());
        if(account==null||!"0".equals(account.getStatus())||!"0".equals(account.getDelFlag()))throw PcException.deny("绑定账号已停用");
    }
    /** 仅有效长期绑定可续接；前一凭证最多保留十分钟，重试复用同一继任凭证。 */
    private String rotateBound(PcRecord runner,PcRecord binding){
        Map<String,Object> data=store.data(binding);
        if(data.get("successorId")!=null){
            PcRecord successor=store.require(PcTable.RUNNER_CREDENTIAL,PcJson.number(data,"successorId"),true);
            if(!"ACTIVE".equals(successor.getState())||!"DEVICE".equals(successor.getKind())||!"BOUND".equals(store.data(successor).get("purpose"))
                ||!Objects.equals(successor.getRunnerId(),runner.getId())||!Objects.equals(successor.getOwnerId(),runner.getOwnerId())
                ||PcJson.number(store.data(successor),"administrationVersion")!=PcJson.number(data,"administrationVersion")
                ||!successor.getExpiresAt().isAfter(store.clock.instant()))throw PcException.deny("继任设备凭证已失效，请重新确认绑定");
            return crypto.open("device-rotation:"+binding.getId(),PcJson.text(data,"successorCipher"));
        }
        if(binding.getExpiresAt().isAfter(store.clock.instant().plusSeconds(86400*7L)))return "";
        String token=crypto.token("pcd_");PcRecord next=credential(runner,"DEVICE",token,86400*30);
        Map<String,Object> nextData=store.data(next);nextData.put("purpose","BOUND");store.save(PcTable.RUNNER_CREDENTIAL,next,nextData);
        data.put("successorId",next.getId());data.put("successorCipher",crypto.seal("device-rotation:"+binding.getId(),token));
        java.time.Instant grace=store.clock.instant().plusSeconds(600);if(binding.getExpiresAt().isAfter(grace))binding.setExpiresAt(grace);
        store.save(PcTable.RUNNER_CREDENTIAL,binding,data);
        evidence.audit(runner.getDirectoryContextId(),runner.getOwnerId(),null,null,"RUNNER_DEVICE_ROTATE","ACCEPTED",Map.of("runnerId",runner.getId(),"credentialId",next.getId()));
        return token;
    }
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
        if("REVOKED".equals(r.getState())||version!=PcJson.number(store.data(c),"administrationVersion")||!Objects.equals(c.getOwnerId(),r.getOwnerId()))throw new PcException(401,"PC_RUNNER_REVOKED","设备已撤销或所属用户已改变");
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
