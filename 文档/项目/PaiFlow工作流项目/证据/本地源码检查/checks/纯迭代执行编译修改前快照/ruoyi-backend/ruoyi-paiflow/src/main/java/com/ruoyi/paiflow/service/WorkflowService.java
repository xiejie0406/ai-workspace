package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.WorkflowRecord;
import com.ruoyi.paiflow.domain.WorkflowVersionRecord;
import com.ruoyi.paiflow.mapper.WorkflowMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 原画布完整保全与不可变版本的唯一业务实现；编译、资源与渠道事实不在此伪造。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class WorkflowService {
    private final WorkflowMapper mapper;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private final DslCodec codec = new DslCodec();
    private final DslValidationService validator = new DslValidationService();
    /** 延迟获取避免AgentService与既有WorkflowService互相构造；只有数据库关系能宣告绑定。 */
    public interface AgentBinding {
        JSONObject cloneBoundWorkflow(long workflowId,long targetSpace);
        void workflowSaved(WorkflowRecord workflow);
        void workflowDeleting(WorkflowRecord workflow);
        JSONObject bindingView(long workflowId);
    }
    private ObjectProvider<AgentBinding> agentBindings;
    @Autowired public void useAgentBindings(ObjectProvider<AgentBinding> provider) { agentBindings=provider; }
    private AgentBinding bindings() { return agentBindings==null?null:agentBindings.getIfAvailable(); }
    private ObjectProvider<ModelRuntimeCatalog> modelCatalogs;
    @Autowired public void useModelCatalogs(ObjectProvider<ModelRuntimeCatalog> provider) { modelCatalogs=provider; }
    private static final Set<String> CONTROLLED = Set.of("id","flowId","spaceId","uid","ownerUserId","revision","latestVersionId","createBy","updateBy","createTime","updateTime","deleted","isPublic","status","canPublish","data","advancedConfig","flowConfig","name","description","engineProfile","botId","agentRevision","agentBindingStatus");
    public WorkflowService(WorkflowMapper mapper,ResourceAccessService access,IdentityMappingService identities) { this.mapper=mapper;this.access=access;this.identities=identities; }
    public record RuntimeSnapshot(long workflowId,long workflowVersionId,long spaceId,long ownerUserId,String flowId,String engineProfile,String dslHash,String canonicalDslBase64,JSONObject execution,JSONObject canvas,JSONObject advancedConfig,JSONObject flowConfig,JSONObject resourceSnapshots) {
        public RuntimeSnapshot(long workflowId,long workflowVersionId,long spaceId,long ownerUserId,String flowId,String engineProfile,String dslHash,String canonicalDslBase64,JSONObject execution,JSONObject canvas,JSONObject advancedConfig,JSONObject flowConfig) {this(workflowId,workflowVersionId,spaceId,ownerUserId,flowId,engineProfile,dslHash,canonicalDslBase64,execution,canvas,advancedConfig,flowConfig,new JSONObject());}
    }
    public record Page(List<JSONObject> pageData,long totalCount,int page,int pageSize,long totalPages) {}
    public record VersionPage(List<JSONObject> records,long total,long current,long size,long pages) {}

    public JSONObject detail(long id,Long requestedSpace) { return legacy(require(id,requestedSpace,"paiflow:workflow:query",3,false)); }
    public WorkflowRecord require(long id,Long requestedSpace,String permission,int maximumRole,boolean lock) {
        WorkflowRecord row=mapper.find(id);
        if(row==null || row.getDeleted()!=0) throw new ServiceException("工作流不存在或不可访问",404);
        if(requestedSpace!=null && !requestedSpace.equals(row.getSpaceId())) throw new ServiceException("工作流不存在或不可访问",404);
        access.requireSpace(row.getSpaceId(),permission,maximumRole,lock);
        // 与空间创建/转让保持空间锁→资源锁的顺序，避免同空间并发写形成反向锁。
        if(lock) { row=mapper.lock(id);if(row==null || row.getDeleted()!=0)throw new ServiceException("工作流不存在或不可访问",404); }
        return row;
    }
    public WorkflowRecord requireFlow(String flowId,Long requestedSpace,String permission,int role,boolean lock) {
        if(flowId==null || flowId.isBlank()) throw new ServiceException("flowId不能为空",400);
        var row=mapper.findByFlowId(flowId);
        if(row==null) throw new ServiceException("工作流不存在或不可访问",404);
        return require(row.getId(),requestedSpace,permission,role,lock);
    }
    public Page list(long spaceId,int current,int size,String search,String flowId,int order,Integer status) {
        access.requireSpace(spaceId,"paiflow:workflow:query",3,false);page(current,size);
        if(status!=null && status!=-1 && status!=0) throw new ServiceException("发布状态查询依赖尚未接入的发布事实",503);
        long total=mapper.count(spaceId,search,flowId);
        var rows=mapper.list(spaceId,search,flowId,order,(long)(current-1)*size,size).stream().map(this::legacy).toList();
        return new Page(rows,total,current,size,(total+size-1)/size);
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject create(long spaceId,JSONObject body) {
        if(body==null)throw new ServiceException("工作流请求不能为空",400);
        WorkflowJsonBoundary.check(body,"request",16*1024*1024);
        checkBindingEcho(body,null);
        // 原agent_space_permission的创建/编辑均允许owner/admin/member；仍必须具备平台按钮权限。
        access.requireSpace(spaceId,"paiflow:workflow:create",3,true);
        var actor=identities.current();var row=new WorkflowRecord();
        row.setSpaceId(spaceId);row.setOwnerUserId(actor.getUserId());row.setFlowId(UUID.randomUUID().toString());row.setRevision(0L);row.setDeleted(0);
        row.setName(text(body.getString("name"),200,true,"工作流名称"));row.setDescription(text(body.getString("description"),2000,false,"描述"));
        checkName(row);row.setEngineProfile(engine(body.getString("engineProfile")));
        row.setCanvasJson(body.containsKey("data")?canvas(body.get("data")):initialCanvas());
        row.setAdvancedConfigJson(objectJson(body.get("advancedConfig")));row.setFlowConfigJson(objectJson(body.get("flowConfig")));
        row.setMetadataJson(metadata(new JSONObject(),body).toJSONString());row.setCreateBy(actor.getUsername());row.setUpdateBy(actor.getUsername());
        if(mapper.insert(row)!=1 || row.getId()==null) throw new ServiceException("工作流保存失败",500);
        return legacy(mapper.find(row.getId()));
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject save(long id,Long spaceId,Long expectedRevision,JSONObject body) {
        return saveInternal(id,spaceId,expectedRevision,body,true);
    }
    private JSONObject saveInternal(long id,Long spaceId,Long expectedRevision,JSONObject body,boolean synchronizeAgent) {
        if(body==null)throw new ServiceException("工作流请求不能为空",400);
        WorkflowJsonBoundary.check(body,"request",16*1024*1024);
        if(body.containsKey("id") && !Objects.equals(body.getLong("id"),id))throw new ServiceException("请求id与当前工作流不匹配",400);
        var row=require(id,spaceId,"paiflow:workflow:edit",3,true);revision(row,expectedRevision);
        checkBindingEcho(body,bindings()==null?null:bindings().bindingView(id));
        if(body.containsKey("flowId") && !Objects.equals(body.getString("flowId"),row.getFlowId())) throw new ServiceException("flowId与当前工作流不匹配",409);
        if(body.containsKey("name"))row.setName(text(body.getString("name"),200,true,"工作流名称"));
        if(body.containsKey("description"))row.setDescription(text(body.getString("description"),2000,false,"描述"));
        if(body.get("data")!=null)row.setCanvasJson(updateCanvas(row.getCanvasJson(),body.get("data")));
        if(body.get("advancedConfig")!=null)row.setAdvancedConfigJson(mergeAdvanced(row.getAdvancedConfigJson(),body.get("advancedConfig")));
        if(body.get("flowConfig")!=null)row.setFlowConfigJson(objectJson(body.get("flowConfig")));
        if(body.containsKey("engineProfile"))row.setEngineProfile(engine(body.getString("engineProfile")));
        row.setMetadataJson(metadata(json(row.getMetadataJson()),body).toJSONString());row.setUpdateBy(identities.current().getUsername());checkName(row);
        if(mapper.update(row)!=1) throw new ServiceException("工作流已被修改，请刷新后重试",409);
        if(synchronizeAgent && bindings()!=null)bindings().workflowSaved(mapper.find(id));
        return legacy(mapper.find(id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void delete(long id,Long spaceId,Long expectedRevision) {
        // 原logicDelete→checkWorkflowBelong在空间域只核对所属空间，不额外限制admin角色。
        var row=require(id,spaceId,"paiflow:workflow:remove",3,true);revision(row,expectedRevision);
        if(bindings()!=null)bindings().workflowDeleting(row);
        if(mapper.delete(id,row.getSpaceId(),row.getRevision(),identities.current().getUsername())!=1) throw new ServiceException("工作流状态已变化，请刷新",409);
        // 不删除不可变版本：已创建运行的固定快照/审计必须保留；新运行读取已删工作流会拒绝。
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject copy(long id,long targetSpace) {
        // 原checkWorkflowVisible按当前目标空间匹配私有来源；公开复制由目录/共享策略另行承载。
        var source=require(id,targetSpace,"paiflow:workflow:query",3,false);
        access.requireSpace(targetSpace,"paiflow:workflow:create",3,true);
        var body=json(source.getMetadataJson());body.put("data",json(source.getCanvasJson()));body.put("description",source.getDescription());
        body.put("advancedConfig",json(source.getAdvancedConfigJson()));body.put("flowConfig",json(source.getFlowConfigJson()));body.put("engineProfile",source.getEngineProfile());
        // 复制不宣称克隆了Agent/App，也不沿用身份、发布凭据或发布状态。
        clearBindingFields(body);body.remove("appId");
        String base=source.getName();String name=base.length()>170?base.substring(0,170):base;
        String candidate=name+"_复制";int suffix=2;
        while(mapper.countName(targetSpace,candidate,null)>0)candidate=name+"_复制"+suffix++;
        body.put("name",candidate);return create(targetSpace,body);
    }
    /** 原clone只有存在真实Agent绑定时才走完整复制；未绑定不能用裸画布假成功。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject legacyClone(long id,long targetSpace) {
        require(id,targetSpace,"paiflow:workflow:query",3,false);
        access.requireSpace(targetSpace,"paiflow:workflow:create",3,false);
        if(bindings()!=null)return bindings().cloneBoundWorkflow(id,targetSpace);
        throw new ServiceException("原工作流复制依赖Agent/App绑定迁移；当前仅提供中心独立草稿复制，原克隆能力尚不可用",503);
    }
    /** 以下入口仅由同包Agent事务使用；不增加HTTP写画布或绕过revision的入口。 */
    JSONObject createForAgent(long spaceId,long agentId,JSONObject body) {
        bindingTransaction();var request=JSON.parseObject(body.toJSONString());
        // 引用只由Agent表workflow_id建立；内部创建也不把ext当第二套绑定。
        clearBindingFields(request);request.remove("appId");
        return create(spaceId,request);
    }
    boolean nameExistsForAgent(long spaceId,String name) { bindingTransaction();return mapper.countName(spaceId,name,null)>0; }
    JSONObject copyForAgent(long workflowId,long spaceId,long agentId,String name) {
        bindingTransaction();var row=require(workflowId,spaceId,"paiflow:workflow:query",3,true);
        var body=json(row.getMetadataJson());body.remove("appId");clearBindingFields(body);
        body.put("name",name);body.put("description",row.getDescription());body.put("data",row.getCanvasJson());
        body.put("advancedConfig",json(row.getAdvancedConfigJson()));body.put("flowConfig",json(row.getFlowConfigJson()));body.put("engineProfile",row.getEngineProfile());
        return createForAgent(spaceId,agentId,body);
    }
    JSONObject saveForAgent(long workflowId,long spaceId,long agentId,Long expectedRevision,JSONObject body) {
        bindingTransaction();var request=JSON.parseObject(body.toJSONString());clearBindingFields(request);
        return saveInternal(workflowId,spaceId,expectedRevision,request,false);
    }
    void deleteForAgent(long workflowId,long spaceId,Long expectedRevision) {
        bindingTransaction();var row=require(workflowId,spaceId,"paiflow:workflow:remove",3,true);revision(row,expectedRevision);
        if(mapper.delete(workflowId,spaceId,row.getRevision(),identities.current().getUsername())!=1)throw new ServiceException("工作流状态已变化，请刷新",409);
    }
    private static void bindingTransaction() { if(!TransactionSynchronizationManager.isActualTransactionActive())throw new ServiceException("智能体与工作流绑定必须在中心事务内完成",500); }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject createVersion(long id,Long spaceId,Long expectedRevision,JSONObject body) {
        var row=require(id,spaceId,"paiflow:workflow:edit",3,true);revision(row,expectedRevision);
        checkBindingEcho(body,bindings()==null?null:bindings().bindingView(id));
        WorkflowJsonBoundary.check(body,"request",16*1024*1024);
        WorkflowJsonBoundary.check(json(row.getCanvasJson()),"data",16*1024*1024);
        WorkflowJsonBoundary.check(json(row.getMetadataJson()),"metadata",16*1024*1024);
        WorkflowJsonBoundary.check(json(row.getAdvancedConfigJson()),"advancedConfig",16*1024*1024);
        WorkflowJsonBoundary.check(json(row.getFlowConfigJson()),"flowConfig",16*1024*1024);
        if(body.get("publishResult")!=null || body.get("publishChannel")!=null) throw new ServiceException("渠道结果只能由真实发布回执写入，版本接口不接受客户端发布事实",409);
        var graph=json(row.getCanvasJson());var report=validator.validate(graph,row.getEngineProfile());
        if(!report.valid())throw new ServiceException("工作流结构预检未通过："+report.issues().get(0).message(),422);
        if(!"python".equals(row.getEngineProfile()) && WorkflowModelCompiler.hasResources(graph))throw new ServiceException("Java资源执行桥尚未接通",503);
        var compiled=WorkflowModelCompiler.compile(graph,(modelId,source,domain)->{var catalog=modelCatalogs==null?null:modelCatalogs.getIfAvailable();if(catalog==null)throw new ServiceException("固定模型运行目录尚未接通",503);return catalog.bindCurrent(row.getSpaceId(),modelId,source,domain);});
        var normalized=codec.normalize(compiled.execution());String name=text(body.getString("name"),200,false,"版本名称");
        if(name==null || name.isBlank())name=suggestVersionName(row.getId());
        var previous=mapper.versionByName(row.getId(),name);
        if(previous!=null)throw new ServiceException("版本名称已存在，不可覆盖历史快照",409);
        var version=new WorkflowVersionRecord();version.setWorkflowId(row.getId());version.setSpaceId(row.getSpaceId());version.setOwnerUserId(row.getOwnerUserId());version.setSnapshotRevision(row.getRevision());
        version.setFlowId(row.getFlowId());version.setVersionNum(UUID.randomUUID().toString());version.setName(name);version.setDescription(text(body.getString("description"),2000,false,"版本描述"));
        version.setCanvasJson(row.getCanvasJson());version.setExecutionJson(normalized.execution().toJSONString());version.setDslHash(normalized.executionHash());version.setCanonicalDslBase64(DslCodec.canonicalDslBase64(normalized.execution()));
        version.setResourceSnapshotsJson(compiled.resourceSnapshots().toJSONString());
        version.setMetadataJson(row.getMetadataJson());version.setAdvancedConfigJson(row.getAdvancedConfigJson());version.setFlowConfigJson(row.getFlowConfigJson());version.setEngineProfile(row.getEngineProfile());version.setCreateBy(identities.current().getUsername());
        if(mapper.insertVersion(version)!=1 || version.getId()==null)throw new ServiceException("版本保存失败",500);
        if(mapper.selectVersion(id,row.getSpaceId(),version.getId(),row.getRevision(),version.getCreateBy())!=1)throw new ServiceException("工作流已被修改，版本创建已回滚",409);
        return versionLegacy(mapper.version(version.getId()),mapper.find(id));
    }
    public VersionPage versions(long id,Long spaceId,int current,int size) {
        var row=require(id,spaceId,"paiflow:workflow:query",3,false);page(current,size);long total=mapper.countVersions(id);
        return new VersionPage(mapper.versions(id,(long)(current-1)*size,size).stream().map(v->versionLegacy(v,row)).toList(),total,current,size,(total+size-1)/size);
    }
    public JSONObject versionDetail(long id) { var version=versionRequired(id);var row=require(version.getWorkflowId(),version.getSpaceId(),"paiflow:workflow:query",3,false);return versionLegacy(version,row); }
    public String suggestVersionName(long id) { require(id,null,"paiflow:workflow:query",3,false);return "v"+(mapper.countVersions(id)+1)+".0"; }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public JSONObject restore(long id,long versionId,Long spaceId,Long expectedRevision) {
        var row=require(id,spaceId,"paiflow:workflow:edit",3,true);revision(row,expectedRevision);var version=versionRequired(versionId);
        if(!row.getId().equals(version.getWorkflowId()) || !row.getSpaceId().equals(version.getSpaceId())) throw new ServiceException("版本不属于当前工作流",404);
        checkSnapshotBoundary(version);
        row.setCanvasJson(version.getCanvasJson());row.setMetadataJson(version.getMetadataJson());row.setAdvancedConfigJson(version.getAdvancedConfigJson());row.setFlowConfigJson(version.getFlowConfigJson());row.setEngineProfile(version.getEngineProfile());row.setLatestVersionId(version.getId());row.setUpdateBy(identities.current().getUsername());
        if(mapper.update(row)!=1)throw new ServiceException("工作流已被修改，请刷新",409);
        if(bindings()!=null)bindings().workflowSaved(mapper.find(id));
        return legacy(mapper.find(id));
    }
    public RuntimeSnapshot runtimeSnapshot(long workflowVersionId) {
        var version=versionRequired(workflowVersionId);var row=require(version.getWorkflowId(),version.getSpaceId(),"paiflow:workflow:query",3,false);
        checkSnapshotBoundary(version);
        verifyStoredHash(version);
        if(version.getResourceSnapshotsJson()==null && WorkflowModelCompiler.hasResources(json(version.getCanvasJson())))throw new ServiceException("旧含资源版本尚无中心固定编译，请创建新版本",503);
        return new RuntimeSnapshot(row.getId(),version.getId(),row.getSpaceId(),row.getOwnerUserId(),row.getFlowId(),version.getEngineProfile(),version.getDslHash(),version.getCanonicalDslBase64(),json(version.getExecutionJson()),json(version.getCanvasJson()),json(version.getAdvancedConfigJson()),json(version.getFlowConfigJson()),json(version.getResourceSnapshotsJson()));
    }
    private WorkflowVersionRecord versionRequired(long id) { var v=mapper.version(id);if(v==null)throw new ServiceException("版本不存在或不可访问",404);return v; }
    private static void checkSnapshotBoundary(WorkflowVersionRecord version) {
        WorkflowJsonBoundary.check(json(version.getCanvasJson()),"data",16*1024*1024);
        WorkflowJsonBoundary.check(json(version.getExecutionJson()),"execution",16*1024*1024);
        WorkflowJsonBoundary.check(json(version.getMetadataJson()),"metadata",16*1024*1024);
        WorkflowJsonBoundary.check(json(version.getAdvancedConfigJson()),"advancedConfig",16*1024*1024);
        WorkflowJsonBoundary.check(json(version.getFlowConfigJson()),"flowConfig",16*1024*1024);
        if(version.getResourceSnapshotsJson()!=null)WorkflowJsonBoundary.check(json(version.getResourceSnapshotsJson()),"resourceSnapshots",1024*1024);
    }
    private void verifyStoredHash(WorkflowVersionRecord version) {
        try {
            byte[] bytes=Base64.getDecoder().decode(version.getCanonicalDslBase64());String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var expected=version.getResourceSnapshotsJson()==null?codec.normalize(json(version.getCanvasJson())).execution():WorkflowModelCompiler.reconstruct(json(version.getCanvasJson()),json(version.getResourceSnapshotsJson()));
            if(!Objects.equals(hash,version.getDslHash()) || !JSON.parseObject(new String(bytes,StandardCharsets.UTF_8)).equals(json(version.getExecutionJson())) || !Objects.equals(codec.normalize(expected).executionHash(),hash)) throw new ServiceException("版本快照完整性校验失败",409);
        }catch(IllegalArgumentException|NoSuchAlgorithmException ex){throw new ServiceException("版本快照完整性校验失败",409);}
    }
    public JSONObject legacy(WorkflowRecord row) {
        if(row==null)throw new ServiceException("工作流不存在",404);var value=json(row.getMetadataJson());
        // 历史metadata中的botId没有关系证据；不向原React交付伪造引用，保留其他扩展。
        clearBindingFields(value);
        if(value.get("ext") instanceof JSONObject ext)value.put("ext",ext.toJSONString());
        value.put("id",row.getId());value.put("flowId",row.getFlowId());value.put("spaceId",row.getSpaceId());value.put("uid",IdentityMappingService.legacyUid(row.getOwnerUserId()));value.put("revision",row.getRevision());
        value.put("name",row.getName());value.put("description",row.getDescription());value.put("data",row.getCanvasJson());value.put("advancedConfig",row.getAdvancedConfigJson());value.put("flowConfig",row.getFlowConfigJson());value.put("engineProfile",row.getEngineProfile());value.put("latestVersionId",row.getLatestVersionId());
        value.put("createTime",row.getCreateTime());value.put("updateTime",row.getUpdateTime());value.put("deleted",false);value.put("isPublic",false);value.put("canPublish",false);value.put("appUpdatable",false);
        // 资源/App绑定和发布记录未由本存储迁移层核验；不能标发布成功。
        value.put("publicationStatus","unverified");value.put("bindingStatus","unverified");
        if(bindings()!=null) { var binding=bindings().bindingView(row.getId());if(binding!=null) {
            var ext=extObject(value.get("ext"));if(ext==null)ext=new JSONObject();ext.put("botId",binding.get("botId"));value.putAll(binding);value.put("ext",ext.toJSONString());
        } }
        value.putIfAbsent("type",value.getInteger("flowType")==null?3:value.getInteger("flowType"));
        value.putIfAbsent("avatarColor","#FFEAD5");value.putIfAbsent("avatarIcon","icon/common/emojiitem_00_10@2x.png");return value;
    }
    private JSONObject versionLegacy(WorkflowVersionRecord v,WorkflowRecord row) {
        // 同名客户端metadata仍保全；交付的资源事实只取不可变版本独立字段。
        JSONObject resources=json(v.getResourceSnapshotsJson());
        var result=new JSONObject();result.put("id",v.getId());result.put("workflowVersionId",v.getId());result.put("workflowId",v.getWorkflowId());result.put("flowId",v.getFlowId());result.put("spaceId",v.getSpaceId());result.put("name",v.getName());result.put("workflowVersionName",v.getName());result.put("versionNum",v.getVersionNum());result.put("data",v.getCanvasJson());result.put("sysData",v.getExecutionJson());result.put("advancedConfig",v.getAdvancedConfigJson());result.put("flowConfig",v.getFlowConfigJson());result.put("description",v.getDescription());result.put("createdTime",v.getCreateTime());result.put("updatedTime",v.getCreateTime());result.put("isVersion",Objects.equals(row.getLatestVersionId(),v.getId())?1:2);result.put("revision",row.getRevision());result.put("snapshotRevision",v.getSnapshotRevision());result.put("dslHash",v.getDslHash());result.put("engineProfile",v.getEngineProfile());result.put("resourceSnapshots",resources);result.put("resourceCompilationStatus",v.getResourceSnapshotsJson()==null?"legacy_uncompiled":"fixed");result.put("verificationStatus","structure_checked_worker_and_operations_unverified");return result;
    }
    private void checkName(WorkflowRecord row) { if(mapper.countName(row.getSpaceId(),row.getName(),row.getId())!=0)throw new ServiceException("当前空间已存在同名工作流",409); }
    private static void revision(WorkflowRecord row,Long expected) { if(expected==null || !expected.equals(row.getRevision()))throw new ServiceException("缺少或不匹配的工作流revision，请刷新后重试",409); }
    private static void page(int current,int size) { if(current<1 || size<1 || size>200)throw new ServiceException("分页参数无效，每页最多200条",400); }
    private static String engine(String value) { String engine=value==null?"python":value;if(!Set.of("java","python").contains(engine))throw new ServiceException("请选择java或python引擎",400);return engine; }
    private static String text(String value,int max,boolean required,String name) { if(required && (value==null || value.isBlank()))throw new ServiceException(name+"不能为空",400);if(value!=null && value.length()>max)throw new ServiceException(name+"超出允许长度",400);return value==null?null:value.trim(); }
    private static JSONObject json(String value) { return value==null?new JSONObject():JSON.parseObject(value); }
    // Jackson将JSON对象的嵌套值绑定为普通Map；沿用原请求对象，字符串画布仍逐字保存。
    private static String objectJson(Object value) { if(value==null)return "{}";try { JSONObject result=value instanceof String s?JSON.parseObject(s):value instanceof java.util.Map<?,?>?JSON.parseObject(JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue)):null;if(result==null)throw new IllegalArgumentException();return result.toJSONString(JSONWriter.Feature.WriteMapNullValue); }catch(RuntimeException ex){throw new ServiceException("配置必须为JSON对象",400);} }
    private static String canvas(Object value) { try { String raw=value instanceof String s?s:value instanceof java.util.Map<?,?>?JSON.toJSONString(value,JSONWriter.Feature.WriteMapNullValue):null;if(raw==null || raw.getBytes(StandardCharsets.UTF_8).length>16*1024*1024)throw new IllegalArgumentException();JSONObject parsed=JSON.parseObject(raw);if(parsed==null || !(parsed.get("nodes") instanceof com.alibaba.fastjson2.JSONArray) || !(parsed.get("edges") instanceof com.alibaba.fastjson2.JSONArray))throw new IllegalArgumentException();return raw; }catch(RuntimeException ex){throw new ServiceException("画布必须包含nodes与edges数组，大小不超过16MiB",400);} }
    private static String updateCanvas(String existing,Object patch) {
        String incoming=canvas(patch);if(patch instanceof String)return incoming;
        var merged=json(existing);merged.putAll(JSON.parseObject(incoming));
        WorkflowJsonBoundary.check(merged,"canvas",16*1024*1024);return merged.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }
    private static String mergeAdvanced(String existing,Object patch) {
        var merged=json(existing);mergeObjects(merged,JSON.parseObject(objectJson(patch)));
        WorkflowJsonBoundary.check(merged,"advancedConfig",16*1024*1024);return merged.toJSONString(JSONWriter.Feature.WriteMapNullValue);
    }
    /** 原ObjectNode合并规则：对象递归，数组/标量/null替换；未传字段保留。 */
    private static void mergeObjects(JSONObject target,JSONObject patch) {
        patch.forEach((key,value)->{if(target.get(key) instanceof JSONObject old && value instanceof JSONObject child)mergeObjects(old,child);else target.put(key,value);});
    }
    private static JSONObject metadata(JSONObject old,JSONObject body) { JSONObject value=JSON.parseObject(old.toJSONString());body.forEach((key,item)->{if(!CONTROLLED.contains(key))value.put(key,item);});return value; }
    private static JSONObject extObject(Object value) {
        if(value==null)return null;
        try { if(value instanceof JSONObject object)return JSON.parseObject(object.toJSONString());if(value instanceof java.util.Map<?,?>)return JSON.parseObject(JSON.toJSONString(value));if(value instanceof String text)return JSON.parseObject(text); }catch(RuntimeException ignored) { /* 非对象扩展仍为未知metadata，不能生成Agent引用。 */ }
        return null;
    }
    private static void clearBindingFields(JSONObject body) {
        body.remove("botId");body.remove("agentRevision");body.remove("agentBindingStatus");
        var ext=extObject(body.get("ext"));if(ext!=null) { ext.remove("botId");if(ext.isEmpty())body.remove("ext");else body.put("ext",body.get("ext") instanceof String?ext.toJSONString():ext); }
    }
    private static void checkBindingEcho(JSONObject body,JSONObject binding) {
        Long actual=binding==null?null:binding.getLong("botId");var ext=extObject(body.get("ext"));
        if(body.get("botId")!=null && !Objects.equals(actual,body.getLong("botId")) || ext!=null && ext.get("botId")!=null && !Objects.equals(actual,ext.getLong("botId")))throw new ServiceException("智能体绑定只能由中心绑定事务建立，不能由画布metadata提供",409);
        if(body.get("agentRevision")!=null && (binding==null || !Objects.equals(binding.getLong("agentRevision"),body.getLong("agentRevision"))))throw new ServiceException("画布中的智能体revision与实际绑定不一致",409);
        if(body.get("agentBindingStatus")!=null && (binding==null || !"bound".equals(body.getString("agentBindingStatus"))))throw new ServiceException("画布中的智能体绑定状态无效",409);
    }
    private static String initialCanvas() { try(var input=WorkflowService.class.getResourceAsStream("/paiflow/workflow-initial-canvas.json")) { if(input==null)throw new IOException();return new String(input.readAllBytes(),StandardCharsets.UTF_8); }catch(IOException ex){throw new ServiceException("缺少原工作流初始画布资源",503);} }
}
