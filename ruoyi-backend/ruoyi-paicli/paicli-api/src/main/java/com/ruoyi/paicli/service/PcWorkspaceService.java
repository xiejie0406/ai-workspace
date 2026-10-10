package com.ruoyi.paicli.service;

import com.ruoyi.paicli.domain.*;
import com.ruoyi.system.service.ISysUserService;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

public class PcWorkspaceService {
    private final PcStore store;private final PcAccess access;private final PcEvidence evidence;private final PcIdempotency idem;private final ISysUserService users;private final PcConnectionService connections;
    public PcWorkspaceService(PcStore store,PcAccess access,PcEvidence evidence,PcIdempotency idem,ISysUserService users){this.store=store;this.access=access;this.evidence=evidence;this.idem=idem;this.users=users;this.connections=null;}
    public PcWorkspaceService(PcStore s,PcAccess a,PcEvidence e,PcIdempotency i,ISysUserService u,PcConnectionService c){store=s;access=a;evidence=e;idem=i;users=u;connections=c;}
    private PcException retired(){return new PcException(410,"PC_WORKSPACE_REMOVED","工作区和成员机制已退出，请使用个人上下文");}
    public List<PcRecord> list(long user){throw retired();}
    public Map<String,Object> save(long user,String key,Map<String,Object> input){throw retired();}
    public List<Map<String,Object>> members(long id,long user){throw retired();}
    public Map<String,Object> member(long id,long user,String key,long version,Map<String,Object> input,boolean remove){throw retired();}
    public Map<String,Object> policy(long id,long user){throw retired();}
    public Map<String,Object> policy(long user,String key,long id,long version,Map<String,Object> input){throw retired();}
    public static Map<String,Object> defaultPolicy(){return Map.ofEntries(Map.entry("commandSandbox","required"),Map.entry("autoFactExtractionEnabled",true),Map.entry("autoCompactionEnabled",true),Map.entry("sessionMemoryEnabled",false),Map.entry("maxActiveSessions",2),Map.entry("maxQueuedTasks",100),Map.entry("maxReadTools",4),Map.entry("maxWriteTasks",1),Map.entry("commandTimeoutSeconds",60),Map.entry("approvalTimeoutSeconds",900),Map.entry("taskTokenBudget",100000),Map.entry("dailyTokenBudget",1000000),Map.entry("retentionDays",90),Map.entry("hardDeniedPaths",List.of(".ssh",".aws",".codex","Windows","Program Files")),Map.entry("allowedCapabilities",List.of("files","command","memory","rag","web","mcp","browser","skills","snapshot","images","wechat")));}
}
