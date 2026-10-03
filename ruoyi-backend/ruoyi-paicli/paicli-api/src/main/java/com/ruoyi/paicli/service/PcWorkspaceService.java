package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

public class PcWorkspaceService {
    private final PcStore store;private final PcAccess access;private final PcEvidence evidence;private final PcIdempotency idem;private final ISysUserService users;private final PcConnectionService connections;
    public PcWorkspaceService(PcStore store,PcAccess access,PcEvidence evidence,PcIdempotency idem,ISysUserService users){this.store=store;this.access=access;this.evidence=evidence;this.idem=idem;this.users=users;this.connections=null;}
    public PcWorkspaceService(PcStore s,PcAccess a,PcEvidence e,PcIdempotency i,ISysUserService u,PcConnectionService c){store=s;access=a;evidence=e;idem=i;users=u;connections=c;}
    public List<PcRecord> list(long user){
        return store.mapper.visibleWorkspaces(user);
    }
    @Transactional("paicliTransactionManager")
    public Map<String,Object> save(long user,String key,Map<String,Object> input){return idem.run("user:"+user+":workspace",key,input,()->{
        if(input.containsKey("disabledSkillNames")||input.containsKey("skillStateVersion"))throw PcException.invalid("Skill名称状态只能通过已授权的Skill启停维护");if(PcSecretPolicy.contains(input))throw PcException.invalid("工作区配置只能引用托管密钥，不允许明文凭证");String name=PcJson.text(input,"name"),fingerprint=PcJson.text(input,"rootFingerprint");
        if(name.isBlank()||name.length()>128||!fingerprint.matches("[a-f0-9]{64}"))throw PcException.invalid("工作区名称或SHA-256根目录指纹非法");
        String root=PcJson.text(input,"rootPath");if(root.isBlank()||root.length()>4096||root.matches("(?i)^[A-Z]:[\\\\/]?$")||root.equals("/")||root.contains("\u0000"))throw PcException.invalid("须登记具体仓库目录，不能登记系统盘根目录");
        Map<String,Object> p=new LinkedHashMap<>(input);p.remove("id");p.remove("version");p.put("commandSandbox","required");p.put("policyVersion",1L);
        PcRecord row;
        if(input.get("id")!=null){long id=PcJson.number(input,"id");access.admin(id,user);row=store.require(PcTable.WORKSPACE,id,true);store.version(row,PcJson.number(input,"version"));
            Map<String,Object> preserved=new LinkedHashMap<>(store.data(row));for(String field:List.of("name","rootPath","rootFingerprint","searchConfig","embeddingConfig","defaultProviderId"))if(p.containsKey(field))preserved.put(field,p.get(field));p=preserved;if(!fingerprint.equals(PcJson.text(store.data(row),"rootFingerprint")))throw PcException.conflict("工作区根目录不可直接替换；请注册独立工作区并重新绑定Runner");store.save(PcTable.WORKSPACE,row,p);
        }else{row=store.insert(PcTable.WORKSPACE,null,user,null,null,null,"WORKSPACE","ACTIVE",null,p);
            store.insert(PcTable.WORKSPACE_MEMBER,row.getId(),user,null,null,null,"MEMBER","ACTIVE","workspace:"+row.getId()+":user:"+user,Map.of("role","ADMIN","userId",String.valueOf(user)));
            store.insert(PcTable.POLICY_VERSION,row.getId(),user,null,null,null,"POLICY","ACTIVE","workspace:"+row.getId(),defaultPolicy());}
        if(p.containsKey("searchConfig")||p.containsKey("embeddingConfig")){if(connections==null)throw new IllegalStateException("工作区用途配置校验器未装配");connections.validateWorkspaceConfig(p,row.getId());}
        if(p.get("defaultProviderId")!=null){PcRecord provider=store.require(PcTable.PROVIDER_CONFIG,PcJson.number(p,"defaultProviderId"),false);if(!Objects.equals(provider.getWorkspaceId(),row.getId())||provider.getState().equals("REVOKED_IMPORT"))throw PcException.deny("默认供应商必须属于当前工作区");}
        evidence.audit(row.getId(),user,null,null,"WORKSPACE_SAVE","ACCEPTED",Map.of("version",row.getVersion()));return store.view(row);
    });}
    public List<Map<String,Object>> members(long workspace,long user){access.admin(workspace,user);return store.mapper.list(PcTable.WORKSPACE_MEMBER,workspace,null,null,"ACTIVE").stream().map(r->{Map<String,Object> view=store.view(r);var account=users.selectUserById(r.getOwnerId());view.put("userName",account==null?null:account.getUserName());return view;}).toList();}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> member(long workspace,long user,String key,long version,Map<String,Object> input,boolean remove){return idem.run("user:"+user+":members:"+workspace,key,Map.of("version",version,"input",input,"remove",remove),()->{
        access.admin(workspace,user);PcRecord w=access.workspace(workspace,user,true);store.version(w,version);long target=PcJson.number(input,"userId");
        String role=PcJson.text(input,"role");if(!remove&&!Set.of("ADMIN","APPROVER","DEVELOPER").contains(role))throw PcException.invalid("成员角色非法");
        if(!remove){var account=users.selectUserById(target);if(account==null||!"0".equals(account.getStatus())||!"0".equals(account.getDelFlag()))throw PcException.invalid("目标若依用户不存在或已停用");}
        PcRecord m=store.mapper.findKey(PcTable.WORKSPACE_MEMBER,"workspace:"+workspace+":user:"+target,true);
        if(m!=null&&"ADMIN".equals(PcJson.text(store.data(m),"role"))&&(remove||!role.equals("ADMIN"))){long admins=store.mapper.list(PcTable.WORKSPACE_MEMBER,workspace,null,null,"ACTIVE").stream().filter(r->"ADMIN".equals(PcJson.text(store.data(r),"role"))).count();if(admins<=1)throw PcException.conflict("不能移除最后一个工作区管理员");}
        if(m==null){if(remove)throw PcException.invalid("成员不存在");m=store.insert(PcTable.WORKSPACE_MEMBER,workspace,target,null,null,null,"MEMBER","ACTIVE","workspace:"+workspace+":user:"+target,Map.of("role",role,"userId",String.valueOf(target)));}
        else{m.setState(remove?"REMOVED":"ACTIVE");store.save(PcTable.WORKSPACE_MEMBER,m,remove?store.data(m):Map.of("role",role,"userId",String.valueOf(target)));}
        store.save(PcTable.WORKSPACE,w,store.data(w));evidence.audit(workspace,user,null,null,"MEMBER_"+(remove?"REMOVE":"SAVE"),"ACCEPTED",Map.of("userId",target));return store.view(m);
    });}
    public Map<String,Object> policy(long workspace,long user){access.workspace(workspace,user,false);PcRecord row=store.mapper.findKey(PcTable.POLICY_VERSION,"workspace:"+workspace,false);return store.view(row);}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> policy(long user,String key,long workspace,long version,Map<String,Object> input){return idem.run("user:"+user+":policy:"+workspace,key,input,()->{
        access.admin(workspace,user);PcRecord w=access.workspace(workspace,user,true);PcRecord row=store.mapper.findKey(PcTable.POLICY_VERSION,"workspace:"+workspace,true);store.version(row,version);
        if(!"required".equals(PcJson.text(input,"commandSandbox")))throw PcException.deny("联机命令隔离为硬要求，不能由业务配置关闭");
        Map<String,Object> p=new LinkedHashMap<>(input);p.remove("workspaceId");p.remove("version");
        for(String n:List.of("maxActiveSessions","maxQueuedTasks","maxReadTools","maxWriteTasks","commandTimeoutSeconds","approvalTimeoutSeconds","taskTokenBudget","dailyTokenBudget","retentionDays")){long value=PcJson.number(p,n);if(value<1||value>1000000000||(n.equals("retentionDays")&&value>3650))throw PcException.invalid("策略上限非法："+n);}
        if(PcJson.number(p,"maxReadTools")>4||PcJson.number(p,"maxWriteTasks")!=1||PcJson.number(p,"commandTimeoutSeconds")>60||PcJson.number(p,"approvalTimeoutSeconds")>900||PcJson.number(p,"maxQueuedTasks")>100)throw PcException.invalid("策略超过已经批准的运行边界");
        for(String field:List.of("autoFactExtractionEnabled","autoCompactionEnabled","sessionMemoryEnabled")){if(p.containsKey(field)&&!(p.get(field) instanceof Boolean))throw PcException.invalid("策略实验/自动开关须为布尔："+field);p.putIfAbsent(field,defaultPolicy().get(field));}store.save(PcTable.POLICY_VERSION,row,p);Map<String,Object> wp=store.data(w);wp.put("policyVersion",row.getVersion());store.save(PcTable.WORKSPACE,w,wp);
        evidence.audit(workspace,user,null,null,"POLICY_CHANGE","ACCEPTED",Map.of("policyVersion",row.getVersion()));return store.view(row);
    });}
    public static Map<String,Object> defaultPolicy(){return Map.ofEntries(Map.entry("commandSandbox","required"),Map.entry("autoFactExtractionEnabled",true),Map.entry("autoCompactionEnabled",true),Map.entry("sessionMemoryEnabled",false),Map.entry("maxActiveSessions",2),Map.entry("maxQueuedTasks",100),Map.entry("maxReadTools",4),Map.entry("maxWriteTasks",1),Map.entry("commandTimeoutSeconds",60),Map.entry("approvalTimeoutSeconds",900),Map.entry("taskTokenBudget",100000),Map.entry("dailyTokenBudget",1000000),Map.entry("retentionDays",90),Map.entry("hardDeniedPaths",List.of(".ssh",".aws",".codex","Windows","Program Files")),Map.entry("allowedCapabilities",List.of("files","command","memory","rag","web","mcp","browser","skills","snapshot","images","wechat")));}
}
