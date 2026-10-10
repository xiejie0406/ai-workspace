package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.ApplicationRecord;
import com.ruoyi.paiflow.mapper.ApplicationMapper;
import com.ruoyi.paiflow.mapper.AgentMapper;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 中心私有应用登记、不可变版本选择及正式运行；不把登记当作外部渠道发布。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ApplicationService {
    private final ApplicationMapper applications;
    private final AgentMapper agents;
    private final WorkflowMapper versions;
    private final WorkflowService workflows;
    private final RunService runs;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private final DefaultPersonalSpaceService defaults;
    public ApplicationService(ApplicationMapper applications,AgentMapper agents,WorkflowMapper versions,WorkflowService workflows,
            RunService runs,ResourceAccessService access,IdentityMappingService identities,DefaultPersonalSpaceService defaults) {
        this.applications=applications;this.agents=agents;this.versions=versions;this.workflows=workflows;
        this.runs=runs;this.access=access;this.identities=identities;this.defaults=defaults;
    }
    public JSONObject detail(String id) { return view(require(id,"query",false)); }
    public JSONObject page(long spaceId,int page,int size) {
        long owner=access.requirePermission(permission("query"));access.requireSpace(spaceId,permission("query"),3,false);
        if(page<1 || size<1 || size>100 || (long)(page-1)*size>Integer.MAX_VALUE)throw new ServiceException("应用分页无效",400);
        var rows=applications.list(spaceId,owner,(long)(page-1)*size,size).stream().map(ApplicationService::view).toList();
        long total=applications.count(spaceId,owner);current(owner,spaceId,"query");
        return JSONObject.of("records",rows,"total",total,"current",page,"size",size,"pages",(total+size-1)/size);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject create(long spaceId,JSONObject body) { return createInside(spaceId,body); }
    private JSONObject createInside(long spaceId,JSONObject body) {
        checkMetadata(body);long owner=access.requirePermission(permission("create"));access.requireSpace(spaceId,permission("create"),3,true);
        var row=new ApplicationRecord();row.setId(UUID.randomUUID().toString());row.setSpaceId(spaceId);row.setOwnerUserId(owner);
        metadata(row,body);row.setState("UNBOUND");row.setRevision(0L);row.setDeleted(0);
        row.setCreateBy(identities.current().getUsername());row.setUpdateBy(row.getCreateBy());
        current(owner,spaceId,"create");
        try { if(applications.insert(row)!=1)throw new ServiceException("应用登记保存失败",500); }
        catch(org.springframework.dao.DuplicateKeyException error){throw new ServiceException("应用名称已存在",409);}
        return view(applications.find(row.getId()));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject update(String id,Long revision,JSONObject body) {
        checkMetadata(body);var row=require(id,"edit",true);revision(row,revision);metadata(row,body);return save(row);
    }
    /** 锁序space→workflow→agent→application；不能把调试快照或客户端发布标志当正式版本。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject bind(String id,Long revision,long agentId,long versionId) {
        var before=require(id,"edit",false);access.requireSpace(before.getSpaceId(),permission("edit"),3,true);
        var version=versions.version(versionId);
        if(version==null || version.getDebugMode()!=null || !Objects.equals(version.getSpaceId(),before.getSpaceId()))
            throw new ServiceException("应用版本不存在或不可访问",404);
        var workflow=workflows.require(version.getWorkflowId(),before.getSpaceId(),"paiflow:workflow:query",3,true);
        access.requireSpace(before.getSpaceId(),"paiflow:agent:query",3,false);var agent=agents.lock(agentId);
        if(agent==null || agent.getVersion()!=3 || !Objects.equals(agent.getSpaceId(),before.getSpaceId())
                || !Objects.equals(agent.getWorkflowId(),workflow.getId()))throw new ServiceException("应用智能体与固定版本不一致",409);
        var snapshot=workflows.runtimeSnapshot(versionId);
        var row=require(id,"edit",true);revision(row,revision);
        row.setAgentId(agentId);row.setWorkflowId(snapshot.workflowId());row.setWorkflowVersionId(versionId);
        row.setDslHash(snapshot.dslHash());row.setEngineProfile(snapshot.engineProfile());row.setState("READY");
        return save(row);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject disable(String id,Long revision) {
        var row=require(id,"edit",true);revision(row,revision);row.setState("DISABLED");return save(row);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void delete(String id,Long revision) {
        var row=require(id,"remove",true);revision(row,revision);current(row.getOwnerUserId(),row.getSpaceId(),"remove");
        if(applications.delete(id,row.getRevision(),identities.current().getUsername())!=1)throw new ServiceException("应用已变化，删除已回滚",409);
        // 只阻止新调用；已派发Run、不可变版本和历史关联保留，不宣告外部停止。
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public RunService.View invoke(String id,Long revision,JSONObject input,String key,int timeout) {
        var row=require(id,"run",true);
        if(!"READY".equals(row.getState()))throw new ServiceException("应用未绑定可调用版本或已停用",409);
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,128}"))throw new ServiceException("请提供8至128位应用运行幂等标识",400);
        if(revision==null || revision<0)throw new ServiceException("应用调用需要先前读取的revision",409);
        if(input==null || timeout<1 || timeout>3600)throw new ServiceException("应用输入或截止时间无效",400);
        RunJsonBoundary.check(input,RunProtocolLimits.INPUT_BYTES);
        var request=JSONObject.of("applicationId",id,"applicationRevision",revision,"input",input,"timeoutSeconds",timeout);
        String hash=RunJsonBoundary.hashFull(request);var old=applications.request(id,row.getOwnerUserId(),key);
        if(old!=null) {
            if(!Objects.equals(old.get("requestHash"),hash))throw new ServiceException("应用幂等标识已用于不同请求",409);
            var result=runs.detail((String)old.get("runId"));
            if(!(old.get("workflowVersionId") instanceof Number version) || result.workflowVersionId()!=version.longValue())
                throw new ServiceException("应用运行关联损坏",409);
            current(row.getOwnerUserId(),row.getSpaceId(),"run");return result;
        }
        revision(row,revision);requireBinding(row);
        // 与普通调用共享中心正式ENV2准入，模型费用/数据写入/资源授权不由应用登记放行。
        String runKey=RunJsonBoundary.hashFull(JSONObject.of("applicationId",id,"requestKey",key));
        var run=runs.create(row.getWorkflowVersionId(),row.getEngineProfile(),input,runKey,timeout);
        if(applications.link(id,row.getOwnerUserId(),run.runId(),row.getWorkflowVersionId(),row.getRevision(),key,hash)!=1)
            throw new ServiceException("应用运行关联保存失败，运行已回滚",500);
        current(row.getOwnerUserId(),row.getSpaceId(),"run");return run;
    }
    private void requireBinding(ApplicationRecord row) {
        var agent=agents.find(row.getAgentId());
        if(agent==null || agent.getVersion()!=3 || !Objects.equals(agent.getSpaceId(),row.getSpaceId())
                || !Objects.equals(agent.getWorkflowId(),row.getWorkflowId()))throw new ServiceException("应用智能体绑定已失效",409);
        access.requireSpace(row.getSpaceId(),"paiflow:agent:query",3,false);
        var snapshot=workflows.runtimeSnapshot(row.getWorkflowVersionId());
        if(snapshot.workflowId()!=row.getWorkflowId() || snapshot.spaceId()!=row.getSpaceId()
                || !Objects.equals(snapshot.dslHash(),row.getDslHash()) || !Objects.equals(snapshot.engineProfile(),row.getEngineProfile()))
            throw new ServiceException("应用固定版本绑定已失效",409);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject legacyCreate(String header,JSONObject body) {
        checkMetadata(body);var selected=defaults.select(header,null,permission("create"));
        return createInside(defaults.materialize(selected,permission("create")),body);
    }
    public List<JSONObject> legacyList(String header) {
        var selected=defaults.select(header,null,permission("query"));Long space=defaults.existing(selected,permission("query"));
        if(space==null)return List.of();long owner=access.requirePermission(permission("query"));
        long count=applications.count(space,owner);if(count>1000)throw new ServiceException("应用列表超过一千项，请使用分页接口",413);
        var result=applications.list(space,owner,0,1000).stream().map(ApplicationService::view).toList();current(owner,space,"query");return result;
    }
    private ApplicationRecord require(String id,String operation,boolean lock) {
        long actor=access.requirePermission(permission(operation));var row=applications.find(id);own(row,actor);
        access.requireSpace(row.getSpaceId(),permission(operation),3,lock);
        if(lock) { row=applications.lock(id);own(row,actor); }
        current(actor,row.getSpaceId(),operation);return row;
    }
    private static void own(ApplicationRecord row,long actor) {
        if(row==null || !Objects.equals(row.getOwnerUserId(),actor))throw new ServiceException("应用不存在或不可访问",404);
    }
    private void current(long actor,long space,String operation) {
        if(access.requirePermission(permission(operation))!=actor)throw new ServiceException("应用当前主体已变化",403);
        access.requireSpace(space,permission(operation),3,false);
    }
    private JSONObject save(ApplicationRecord row) {
        current(row.getOwnerUserId(),row.getSpaceId(),"edit");row.setUpdateBy(identities.current().getUsername());
        try { if(applications.update(row)!=1)throw new ServiceException("应用已被修改，请刷新后重试",409); }
        catch(org.springframework.dao.DuplicateKeyException error){throw new ServiceException("应用名称已存在",409);}
        return view(applications.find(row.getId()));
    }
    private static String permission(String operation) { return "paiflow:application:"+operation; }
    private static void revision(ApplicationRecord row,Long expected) {
        if(expected==null || !Objects.equals(expected,row.getRevision()))throw new ServiceException("应用版本已变化，请刷新后重试",409);
    }
    private static void checkMetadata(JSONObject body) {
        if(body==null)throw new ServiceException("应用登记请求不能为空",400);
        if(!Set.of("name","description","appName","appDescribe").containsAll(body.keySet()))
            throw new ServiceException("应用登记只接受名称和描述，不接受身份、凭据或发布事实",400);
        alias(body,"name","appName");alias(body,"description","appDescribe");
        RunJsonBoundary.check(body,4096);
    }
    private static void alias(JSONObject body,String a,String b) {
        if(body.containsKey(a) && body.containsKey(b) && !Objects.equals(body.get(a),body.get(b)))throw new ServiceException("应用名称或描述字段不一致",400);
    }
    private static void metadata(ApplicationRecord row,JSONObject body) {
        row.setName(text(body.containsKey("name")?body.get("name"):body.get("appName"),100,true));
        row.setDescription(text(body.containsKey("description")?body.get("description"):body.get("appDescribe"),500,false));
    }
    private static String text(Object raw,int max,boolean required) {
        if(raw!=null && !(raw instanceof String))throw new ServiceException("应用名称或描述必须为文字",400);
        String value=(String)raw;if(value!=null)value=value.trim();
        if(required && (value==null || value.isEmpty()) || value!=null && (value.length()>max || value.chars().anyMatch(c->c<32 && c!='\n' && c!='\t')))
            throw new ServiceException("应用名称或描述无效",400);return value;
    }
    private static JSONObject view(ApplicationRecord row) {
        if(row==null)throw new ServiceException("应用登记读取失败",500);
        return JSONObject.of("id",row.getId(),"appId",row.getId(),"spaceId",row.getSpaceId(),"ownerUserId",row.getOwnerUserId(),
            "name",row.getName(),"appName",row.getName(),"description",row.getDescription(),"appDescribe",row.getDescription(),
            "state",row.getState(),"revision",row.getRevision(),"botId",row.getAgentId(),"workflowId",row.getWorkflowId(),
            "workflowVersionId",row.getWorkflowVersionId(),"dslHash",row.getDslHash(),"engineProfile",row.getEngineProfile(),
            "applicationBindingStatus",row.getWorkflowVersionId()==null?"unconfigured":"bound",
            "publicationStatus","unpublished","credentialStatus","unconfigured","appKey",null,"appSecret",null,
            "createTime",row.getCreateTime(),"updateTime",row.getUpdateTime());
    }
}
