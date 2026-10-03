package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 原用户/项目提示词覆盖的中心事实；Builtin资源仍由受控原生包提供。 */
public class PcPromptService {
    private final PcStore store;private final PcAccess access;private final PcIdempotency idem;private final PcEvidence evidence;
    public PcPromptService(PcStore s,PcAccess a,PcIdempotency i,PcEvidence e){store=s;access=a;idem=i;evidence=e;}
    public List<Map<String,Object>> list(long user,Long workspace){if(workspace!=null)access.workspace(workspace,user,false);return store.mapper.visible(PcTable.PROMPT_TEMPLATE,user,workspace,false).stream().filter(r->!r.getState().equals("REVOKED_IMPORT")).map(r->view(store,r,false)).toList();}
    public Map<String,Object> get(long user,long id,boolean raw){return view(store,access.resource(PcTable.PROMPT_TEMPLATE,id,user,false,false),raw);}
    @Transactional("paicliTransactionManager")
    public Map<String,Object> save(long user,String key,long version,Map<String,Object> input){return idem.run("user:"+user+":prompt",key,Map.of("version",version,"input",input),()->{
        String scope=PcJson.text(input,"scope");if(!Set.of("PERSONAL","PROJECT").contains(scope))throw PcException.invalid("提示词scope仅支持PERSONAL/PROJECT");
        Long workspace=scope.equals("PROJECT")?PcJson.number(input,"workspaceId"):null;if(workspace!=null){access.admin(workspace,user);access.workspace(workspace,user,true);}
        String path=normalize(PcJson.text(input,"relativePath")),content=PcJson.text(input,"content");if(content.isBlank()||content.getBytes(StandardCharsets.UTF_8).length>65536)throw PcException.invalid("提示词全文应为1至65536 UTF-8字节");
        Map<String,Object> p=new LinkedHashMap<>();p.put("relativePath",path);p.put("scope",scope);p.put("content",content);p.put("sha256",PcJson.digest(content));p.put("enabled",!Boolean.FALSE.equals(input.get("enabled")));
        String identity="prompt:"+PcJson.digest(scope+":"+(workspace==null?user:workspace)+":"+path);PcRecord row;
        if(input.get("id")!=null){row=access.resource(PcTable.PROMPT_TEMPLATE,PcJson.number(input,"id"),user,false,true);if(!Objects.equals(row.getWorkspaceId(),workspace)||!identity.equals(row.getUniqueKey()))throw PcException.conflict("提示词scope或路径不能通过更新改变");store.version(row,version);row.setState(Boolean.TRUE.equals(p.get("enabled"))?"ACTIVE":"DISABLED");store.save(PcTable.PROMPT_TEMPLATE,row,p);}
        else{if(version!=0)throw PcException.conflict("新建提示词版本必须为0");if(store.mapper.findKey(PcTable.PROMPT_TEMPLATE,identity,true)!=null)throw PcException.conflict("同scope路径已有提示词，请更新现有版本");row=store.insert(PcTable.PROMPT_TEMPLATE,workspace,user,null,null,null,"PROMPT",Boolean.TRUE.equals(p.get("enabled"))?"ACTIVE":"DISABLED",identity,p);}
        evidence.audit(workspace,user,null,null,"PROMPT_SAVE","ACCEPTED",Map.of("promptId",row.getId(),"sha256",p.get("sha256"),"relativePath",path,"version",row.getVersion()));return view(store,row,false);
    });}
    public static String normalize(String value){String path=value.replace('\\','/');if(path.isBlank()||path.length()>256||path.startsWith("/")||path.indexOf(':')>=0||path.chars().anyMatch(c->c<32)||Arrays.stream(path.split("/",-1)).anyMatch(s->s.isBlank()||s.equals(".")||s.equals("..")))throw PcException.invalid("提示词须为不含空段或..的相对资源路径");return path;}
    private static Map<String,Object> view(PcStore store,PcRecord row,boolean raw){Map<String,Object> result=store.view(row);if(!raw)result.remove("content");return result;}
    public static List<Map<String,Object>> forTask(PcStore store,PcRecord task){List<PcRecord> rows=new ArrayList<>();rows.addAll(store.mapper.list(PcTable.PROMPT_TEMPLATE,null,task.getOwnerId(),null,"ACTIVE").stream().filter(r->r.getWorkspaceId()==null).toList());rows.addAll(store.mapper.list(PcTable.PROMPT_TEMPLATE,task.getWorkspaceId(),null,null,"ACTIVE"));return rows.stream().filter(r->Boolean.TRUE.equals(store.data(r).get("enabled"))).sorted(Comparator.comparingInt((PcRecord r)->r.getWorkspaceId()==null?0:1).thenComparing(r->PcJson.text(store.data(r),"relativePath"))).map(r->view(store,r,true)).toList();}
}
