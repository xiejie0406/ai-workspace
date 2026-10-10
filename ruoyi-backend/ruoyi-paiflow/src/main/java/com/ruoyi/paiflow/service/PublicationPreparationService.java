package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.ApplicationRecord;
import com.ruoyi.paiflow.domain.PublicationPreparationRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.domain.WorkflowVersionRecord;
import com.ruoyi.paiflow.mapper.*;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 发布前的固定版本及实际调试证明；渠道适配必须另做预检、派发和真实回读。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class PublicationPreparationService {
    public static final Set<String> CHANNELS=Set.of("MARKET","API","MCP","WECHAT","FEISHU","IFLYTEK_AIUI","IFLYTEK_OPEN");
    private final PublicationPreparationMapper preparations;
    private final ApplicationMapper applications;
    private final WorkflowMapper versions;
    private final AgentMapper agents;
    private final RunMapper runs;
    private final WorkflowService workflows;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    public PublicationPreparationService(PublicationPreparationMapper preparations,ApplicationMapper applications,
            WorkflowMapper versions,AgentMapper agents,RunMapper runs,WorkflowService workflows,
            ResourceAccessService access,IdentityMappingService identities) {
        this.preparations=preparations;this.applications=applications;this.versions=versions;this.agents=agents;
        this.runs=runs;this.workflows=workflows;this.access=access;this.identities=identities;
    }
    public JSONObject detail(String id) { return view(require(id,"query",false)); }
    public JSONObject page(long spaceId,int page,int size) {
        long actor=access.requirePermission(permission("query"));access.requireSpace(spaceId,permission("query"),3,false);
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE)throw new ServiceException("发布准备分页无效",400);
        var rows=preparations.list(spaceId,actor,(long)(page-1)*size,size).stream().map(PublicationPreparationService::view).toList();
        long total=preparations.count(spaceId,actor);current(actor,spaceId,"query");return JSONObject.of("records",rows,"total",total);
    }
    /** 空间→工作流→智能体→应用→运行；不锁相反次序，也不持锁进行外部渠道调用。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject prepare(String applicationId,Long applicationRevision,String channel,String debugRunId,String key) {
        if(!CHANNELS.contains(channel==null?"":channel))throw new ServiceException("请选择实际支持的发布渠道",400);
        if(applicationRevision==null || applicationRevision<0)throw new ServiceException("发布准备需要应用revision",409);
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,128}") || debugRunId==null || !debugRunId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ServiceException("发布准备幂等标识或调试运行ID无效",400);
        long actor=access.requirePermission(permission("create"));var before=applications.find(applicationId);own(before,actor);
        access.requireSpace(before.getSpaceId(),permission("create"),3,true);
        access.requireSpace(before.getSpaceId(),"paiflow:application:query",3,false);
        if(!"READY".equals(before.getState()) || before.getWorkflowVersionId()==null)throw new ServiceException("应用未绑定版本或已停用",409);
        workflows.require(before.getWorkflowId(),before.getSpaceId(),"paiflow:workflow:query",3,true);
        access.requireSpace(before.getSpaceId(),"paiflow:agent:query",3,false);var agent=agents.lock(before.getAgentId());
        if(agent==null || agent.getVersion()!=3 || !Objects.equals(agent.getSpaceId(),before.getSpaceId()) || !Objects.equals(agent.getWorkflowId(),before.getWorkflowId()))
            throw new ServiceException("发布准备的智能体绑定已失效",409);
        var app=applications.lock(applicationId);own(app,actor);
        if(!"READY".equals(app.getState()) || !Objects.equals(before.getWorkflowId(),app.getWorkflowId()) || !Objects.equals(before.getAgentId(),app.getAgentId()))
            throw new ServiceException("应用绑定已变化，请刷新后重试",409);
        access.requireSpace(app.getSpaceId(),"paiflow:run:query",3,false);
        var request=JSONObject.of("applicationId",applicationId,"applicationRevision",applicationRevision,"channel",channel,"debugRunId",debugRunId);
        String hash=RunJsonBoundary.hashFull(request);var old=preparations.request(applicationId,actor,key);
        if(old!=null) {
            if(!Objects.equals(old.getRequestHash(),hash))throw new ServiceException("发布准备幂等标识已用于不同请求",409);
            current(actor,app.getSpaceId(),"create");return view(old);
        }
        if(!Objects.equals(app.getRevision(),applicationRevision))throw new ServiceException("应用已变化，请刷新后重试",409);
        var fixed=versions.version(app.getWorkflowVersionId());
        if(fixed==null || fixed.getDebugMode()!=null || !Objects.equals(fixed.getWorkflowId(),app.getWorkflowId()) || !Objects.equals(fixed.getSpaceId(),app.getSpaceId()))
            throw new ServiceException("发布只能选择应用实际绑定的普通版本",409);
        var fixedSnapshot=workflows.runtimeSnapshot(fixed.getId());
        if(!Objects.equals(fixedSnapshot.dslHash(),app.getDslHash()) || !Objects.equals(fixedSnapshot.engineProfile(),app.getEngineProfile()))
            throw new ServiceException("应用固定版本校验失败",409);
        var debug=runs.lock(debugRunId);
        if(debug==null || !Objects.equals(debug.getOwnerUserId(),actor) || !Objects.equals(debug.getSpaceId(),app.getSpaceId()))
            throw new ServiceException("调试运行不存在或不可访问",404);
        RunJsonCodec.require(debug);
        var source=versions.version(debug.getWorkflowVersionId());
        if(source==null || source.getDebugMode()==null || !Objects.equals(source.getDebugOwnerUserId(),actor)
                || !Objects.equals(source.getDebugAgentId(),app.getAgentId()) || !Objects.equals(source.getWorkflowId(),app.getWorkflowId()))
            throw new ServiceException("需要当前智能体的真实调试运行，普通调用不能代替",409);
        var debugSnapshot=workflows.runtimeSnapshot(source.getId());
        requireEquivalent(fixed,source,fixedSnapshot,debugSnapshot);
        requireCompleted(debug,debugSnapshot);
        // 证明只保存固定ID/hash/结果摘要；不把用户输入、调试内容或凭据写入发布列表。
        String proof=RunJsonBoundary.hashFull(JSONObject.of("debugRunId",debug.getId(),"debugWorkflowVersionId",source.getId(),
            "workflowVersionId",fixed.getId(),"dslHash",fixed.getDslHash(),"executionCodec",fixed.getExecutionCodec(),
            "fixedSnapshotRevision",fixed.getSnapshotRevision(),"debugSnapshotRevision",source.getSnapshotRevision(),
            "resultHash",debug.getResultHash(),"terminalSeq",debug.getLastEventSeq()));
        var row=new PublicationPreparationRecord();row.setId(UUID.randomUUID().toString());row.setApplicationId(app.getId());
        row.setApplicationRevision(app.getRevision());row.setSpaceId(app.getSpaceId());row.setOwnerUserId(actor);
        row.setAgentId(app.getAgentId());row.setWorkflowId(app.getWorkflowId());row.setWorkflowVersionId(fixed.getId());
        row.setDslHash(fixed.getDslHash());row.setEngineProfile(fixed.getEngineProfile());row.setChannel(channel);row.setState("PENDING");
        row.setDebugRunId(debug.getId());row.setDebugWorkflowVersionId(source.getId());row.setProofHash(proof);
        row.setFixedSnapshotRevision(fixed.getSnapshotRevision());row.setDebugSnapshotRevision(source.getSnapshotRevision());
        row.setRequestKey(key);row.setRequestHash(hash);row.setRevision(0L);row.setCreateBy(identities.current().getUsername());
        current(actor,app.getSpaceId(),"create");
        for(String permission:Set.of("paiflow:run:query","paiflow:application:query","paiflow:agent:query","paiflow:workflow:query"))
            access.requireSpace(app.getSpaceId(),permission,3,false);
        if(preparations.insert(row)!=1)throw new ServiceException("发布准备保存失败",500);return view(preparations.find(row.getId()));
    }
    private static void requireEquivalent(WorkflowVersionRecord fixed,WorkflowVersionRecord source,
            WorkflowService.RuntimeSnapshot a,WorkflowService.RuntimeSnapshot b) {
        if(!Objects.equals(a.executionCodec(),b.executionCodec()) || !Objects.equals(a.engineProfile(),b.engineProfile())
                || !Objects.equals(a.dslHash(),b.dslHash()) || !Objects.equals(a.canonicalDslBase64(),b.canonicalDslBase64())
                || !Objects.equals(a.execution(),b.execution()) || !Objects.equals(a.resourceSnapshots(),b.resourceSnapshots())
                || !Objects.equals(a.canvas(),b.canvas()) || !Objects.equals(a.advancedConfig(),b.advancedConfig())
                || !Objects.equals(a.flowConfig(),b.flowConfig()) || !json(fixed.getMetadataJson()).equals(json(source.getMetadataJson())))
            throw new ServiceException("调试快照与待发布固定版本不同，请对该版本重新调试",409);
    }
    private void requireCompleted(RunRecord run,WorkflowService.RuntimeSnapshot snapshot) {
        if(!"COMPLETED".equals(run.getState()) || run.getExecutionStartedAt()==null || run.getAttempt()==null || run.getAttempt()<1
                || run.getResultJson()==null || run.getResultHash()==null || run.getLastEventSeq()==null || run.getLastEventSeq()<1)
            throw new ServiceException("调试运行尚未正式完成，不能准备发布",409);
        try {
            var task=JSON.parseObject(run.getAssignmentJson());var result=JSON.parseObject(run.getResultJson());
            var terminal=JSON.parseObject(runs.event(run.getId(),run.getLastEventSeq()));
            if(!Objects.equals(task.getString("runId"),run.getId()) || !Objects.equals(task.getString("workflowVersionId"),Long.toString(snapshot.workflowVersionId()))
                    || !Objects.equals(task.getString("dslHash"),snapshot.dslHash()) || !Objects.equals(task.getString("engineProfile"),snapshot.engineProfile())
                    || !Objects.equals(task.getString("dslCanonicalBase64"),snapshot.canonicalDslBase64()) || !Objects.equals(task.getJSONObject("dsl"),snapshot.execution())
                    || !Objects.equals(task.getJSONObject("resourceSnapshots"),snapshot.resourceSnapshots())
                    || !Objects.equals(task.getString("executionCodec"),DslCodec.V4.equals(snapshot.executionCodec())?DslCodec.V4:null)
                    || !"COMPLETED".equals(result.getString("state")) || !Objects.equals(result.getString("leaseId"),run.getLeaseId())
                    || !Objects.equals(result.getInteger("attempt"),run.getAttempt()) || !Objects.equals(RunJsonCodec.hash(run,result),run.getResultHash())
                    || terminal==null || !"run.completed".equals(terminal.getString("eventType")) || !Objects.equals(terminal.getString("runId"),run.getId())
                    || !Objects.equals(terminal.getLong("seq"),run.getLastEventSeq()) || !Objects.equals(terminal.getInteger("attempt"),run.getAttempt())
                    || !Objects.equals(terminal.getString("leaseId"),run.getLeaseId()) || !"COMPLETED".equals(terminal.getJSONObject("payload").getString("state")))
                throw new IllegalArgumentException();
        } catch(RuntimeException error) { throw new ServiceException("调试运行的固定快照或终态证据不完整",409); }
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject withdraw(String id,Long revision) {
        var row=require(id,"edit",true);
        if(revision==null || !Objects.equals(row.getRevision(),revision))throw new ServiceException("发布准备已变化，请刷新",409);
        if(!"PENDING".equals(row.getState()))throw new ServiceException("发布准备已撤回",409);
        current(row.getOwnerUserId(),row.getSpaceId(),"edit");
        if(preparations.withdraw(id,revision)!=1)throw new ServiceException("发布准备已变化，请刷新",409);
        return view(preparations.find(id)); // 只撤回尚未派发准备；没有宣告外部渠道下线。
    }
    private PublicationPreparationRecord require(String id,String operation,boolean lock) {
        long actor=access.requirePermission(permission(operation));var row=preparations.find(id);
        if(row==null || !Objects.equals(row.getOwnerUserId(),actor))throw new ServiceException("发布准备不存在或不可访问",404);
        access.requireSpace(row.getSpaceId(),permission(operation),3,lock);
        if(lock){row=preparations.lock(id);if(row==null || !Objects.equals(row.getOwnerUserId(),actor))throw new ServiceException("发布准备不存在或不可访问",404);}
        current(actor,row.getSpaceId(),operation);return row;
    }
    private void current(long actor,long space,String operation){access.requireSpace(space,permission(operation),3,false);if(access.requirePermission(permission(operation))!=actor)throw new ServiceException("发布准备主体已变化",403);}
    private static String permission(String operation){return "paiflow:publication:"+operation;}
    private static void own(ApplicationRecord row,long actor){if(row==null || !Objects.equals(row.getOwnerUserId(),actor))throw new ServiceException("应用不存在或不可访问",404);}
    private static JSONObject json(String value){return value==null?new JSONObject():JSON.parseObject(value);}
    private static JSONObject view(PublicationPreparationRecord row){
        if(row==null)throw new ServiceException("发布准备读取失败",500);
        return JSONObject.of("id",row.getId(),"applicationId",row.getApplicationId(),"applicationRevision",row.getApplicationRevision(),
            "spaceId",row.getSpaceId(),"ownerUserId",row.getOwnerUserId(),"agentId",row.getAgentId(),"workflowId",row.getWorkflowId(),
            "workflowVersionId",row.getWorkflowVersionId(),"dslHash",row.getDslHash(),"engineProfile",row.getEngineProfile(),
            "channel",row.getChannel(),"state",row.getState(),"revision",row.getRevision(),"debugRunId",row.getDebugRunId(),
            "debugWorkflowVersionId",row.getDebugWorkflowVersionId(),"proofHash",row.getProofHash(),"createTime",row.getCreateTime(),
            "fixedSnapshotRevision",row.getFixedSnapshotRevision(),"debugSnapshotRevision",row.getDebugSnapshotRevision(),
            "phase","version_debug_proof_prepared","isPublished",false,"channelStatus","unconfigured",
            "resourceStatus","requires_current_channel_preflight","credentialStatus","unconfigured");
    }
}
