package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.AgentRecord;
import com.ruoyi.paiflow.domain.WorkflowRecord;
import com.ruoyi.paiflow.mapper.AgentMapper;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原指令型/工作流型元数据共用中心事务；不创建供应商App或伪造发布/资源授权。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class AgentService implements WorkflowService.AgentBinding {
    private final AgentMapper mapper;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private final WorkflowService workflows;
    private DefaultPersonalSpaceService defaults;
    @org.springframework.beans.factory.annotation.Autowired
    public void useDefaultSpaces(DefaultPersonalSpaceService defaults) { this.defaults=defaults; }
    private static final Set<String> CONTROLLED=Set.of("id","botId","spaceId","ownerUserId","uid","revision","workflowRevision","version","workflowId","maasId","flowId","name","botName","botDesc","description","avatar","engineProfile","data");
    private static final Set<String> FORGED=Set.of("publicationStatus","publishStatus","publishResult","publishChannels","releaseType","botStatus","botwebStatus","isPublic","canPublish","deleted","createBy","updateBy","createTime","updateTime","appId","appKey","appSecret");
    public AgentService(AgentMapper mapper,ResourceAccessService access,IdentityMappingService identities,WorkflowService workflows) {
        this.mapper=mapper;this.access=access;this.identities=identities;this.workflows=workflows;
    }
    public record Page(List<JSONObject> pageData,long totalCount,int pageSize,int page,long totalPages) {}
    public void checkCatalogAccess(long spaceId) { access.requireSpace(spaceId,"paiflow:agent:query",3,false); }
    public JSONObject detail(long id,Long requestedSpace) { return view(require(id,requestedSpace,"paiflow:agent:query",false)); }
    public Page list(long spaceId,JSONObject query) {
        access.requireSpace(spaceId,"paiflow:agent:query",3,false);
        return listing(spaceId,query);
    }
    private Page listing(Long spaceId,JSONObject query) {
        if(query==null)throw new ServiceException("智能体查询不能为空",400);
        int page=query.getIntValue("pageIndex",1),size=query.getIntValue("pageSize",15);page(page,size);
        Integer version=query.getInteger("version");if(version!=null && version!=1 && version!=3)throw new ServiceException("智能体类型只能为1或3",400);
        String sort=query.getString("sort");if(sort!=null && !Set.of("createTime","updateTime").contains(sort))throw new ServiceException("排序字段无效",400);
        var statuses=query.getJSONArray("botStatus");if(statuses!=null && !statuses.isEmpty()) {
            boolean draft=false;for(Object status:statuses) { long value=number(status,"botStatus",false);if(value==-9 || value==0)draft=true;else if(value<1 || value>4)throw new ServiceException("发布状态参数无效",400); }
            // 本模块尚无正式发布记录，不能把未知渠道伪造为已发布；查询已发布条件真实无结果。
            if(!draft)return new Page(List.of(),0,size,page,0);
        }
        String search=text(query.getString("searchValue"),200,false,"搜索内容");if(spaceId==null)return new Page(List.of(),0,size,page,0);long total=mapper.count(spaceId,search,version);
        var values=mapper.list(spaceId,search,version,sort,(long)(page-1)*size,size).stream().map(this::view).toList();
        return new Page(values,total,size,page,(total+size-1)/size);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject create(long spaceId,int version,JSONObject body) {
        body=creationRequest(version,body);scope(body,spaceId);long owner=creatorGuard();return createInside(spaceId,version,body,owner);
    }
    private JSONObject creationRequest(int version,JSONObject body) {
        if(version!=1 && version!=3)throw new ServiceException("智能体类型只能为1或3",400);
        if(body!=null)body=JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        validate(body);if(body.get("botId")!=null || body.get("id")!=null)throw new ServiceException("新建请求不能携带已有智能体ID",400);return body;
    }
    private JSONObject createInside(long spaceId,int version,JSONObject body,long owner) {
        var domain=access.requireSpace(spaceId,"paiflow:agent:create",3,true);access.requireSpace(spaceId,"paiflow:agent:create",3,false);var actor=identities.current();sameCreator(owner);
        // 原NULL空间按同uid全部未删Bot计数；具名空间按uid+space。原比较>100，count100仍可创建101。
        long count=domain.isDefaultPersonal()?mapper.countAllOwner(owner):mapper.countOwner(spaceId,owner);
        if(count>100)throw new ServiceException("当前创建的智能体已达到上限",409);
        var row=new AgentRecord();row.setSpaceId(spaceId);row.setOwnerUserId(actor.getUserId());row.setVersion(version);row.setRevision(0L);row.setDeleted(0);row.setCreateBy(actor.getUsername());row.setUpdateBy(actor.getUsername());
        apply(row,body);name(row);insert(row);
        if(version==3) {
            var created=workflows.createForAgent(spaceId,row.getId(),workflowRequest(row,body,null));row.setWorkflowId(created.getLong("id"));
            if(mapper.update(row)!=1)throw new ServiceException("智能体工作流绑定已变化，创建已回滚",409);
        }
        return view(mapper.find(row.getId()));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject update(long id,Long requestedSpace,Long expectedRevision,Long workflowRevision,JSONObject body) {
        if(body==null)throw new ServiceException("智能体请求不能为空",400);
        if(body.get("id")!=null && number(body.get("id"),"id",true)!=id || body.get("botId")!=null && number(body.get("botId"),"botId",true)!=id)throw new ServiceException("请求智能体ID不一致",400);
        if(body.containsKey("data"))throw new ServiceException("画布必须使用已绑定工作流的草稿接口保存",400);
        var row=require(id,requestedSpace,"paiflow:agent:edit",true);revision(row,expectedRevision);scope(body,row.getSpaceId());
        if(body.get("version")!=null && number(body.get("version"),"version",true)!=row.getVersion())throw new ServiceException("已有智能体类型不能转换",409);
        WorkflowRecord workflow=bound(row,"paiflow:workflow:edit",true);
        var request=JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        for(String key:List.of("workflowId","maasId"))if(request.get(key)!=null) {
            if(workflow==null || number(request.get(key),key,true)!=workflow.getId())throw new ServiceException("智能体工作流绑定不一致",409);request.remove(key);
        }
        if(request.get("flowId")!=null) { if(workflow==null || !workflow.getFlowId().equals(request.getString("flowId")))throw new ServiceException("智能体flowId绑定不一致",409);request.remove("flowId"); }
        validate(request);apply(row,request);name(row);
        if(workflow!=null)workflows.saveForAgent(workflow.getId(),row.getSpaceId(),row.getId(),workflowRevision,workflowRequest(row,body,workflow));
        else if(workflowRevision!=null)throw new ServiceException("当前智能体没有工作流绑定",409);
        row.setUpdateBy(identities.current().getUsername());if(mapper.update(row)!=1)throw new ServiceException("智能体已被修改，请刷新后重试",409);
        return view(mapper.find(id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void delete(long id,Long requestedSpace,Long expectedRevision,Long workflowRevision) {
        var row=require(id,requestedSpace,"paiflow:agent:remove",true);revision(row,expectedRevision);
        var workflow=bound(row,"paiflow:workflow:remove",true);
        if(workflow!=null)workflows.deleteForAgent(workflow.getId(),row.getSpaceId(),workflowRevision);
        else if(workflowRevision!=null)throw new ServiceException("当前智能体没有工作流绑定",409);
        if(mapper.delete(id,row.getSpaceId(),row.getRevision(),identities.current().getUsername())!=1)throw new ServiceException("智能体状态已变化，删除已回滚",409);
        // Workflow不可变版本/既有运行及平台账号不连带删除；新调用已删资源会拒绝。
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject copy(long id,long spaceId) {
        long owner=creatorGuard();return copyInside(id,spaceId,owner);
    }
    private JSONObject copyInside(long id,long spaceId,long owner) {
        var source=require(id,spaceId,"paiflow:agent:query",true);access.requireSpace(spaceId,"paiflow:agent:copy",3,true);access.requireSpace(spaceId,"paiflow:agent:create",3,true);
        var workflow=bound(source,"paiflow:workflow:query",true);var actor=identities.current();sameCreator(owner);
        // 原copyBot直接复制，不调用validateBotCreation；不把创建计数规则扩成复制限制。
        WorkflowJsonBoundary.check(json(source.getConfigJson()),"agent/config",1024*1024);
        var row=new AgentRecord();row.setSpaceId(spaceId);row.setOwnerUserId(actor.getUserId());row.setVersion(source.getVersion());row.setBotType(source.getBotType());row.setDescription(source.getDescription());row.setAvatar(source.getAvatar());row.setConfigJson(source.getConfigJson());row.setRevision(0L);row.setDeleted(0);row.setCreateBy(actor.getUsername());row.setUpdateBy(actor.getUsername());
        String base=source.getName();if(base.length()>24)base=base.substring(0,24);String candidate=base+"_复制";int suffix=2;
        while(mapper.countName(spaceId,candidate,null)>0 || workflow!=null && workflows.nameExistsForAgent(spaceId,candidate))candidate=base+"_复制"+suffix++;
        row.setName(candidate);name(row);insert(row);
        if(workflow!=null) {
            var cloned=workflows.copyForAgent(workflow.getId(),spaceId,row.getId(),row.getName());row.setWorkflowId(cloned.getLong("id"));
            if(mapper.update(row)!=1)throw new ServiceException("复制工作流绑定失败，智能体与画布已回滚",409);
        }
        return view(mapper.find(row.getId()));
    }
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject cloneBoundWorkflow(long workflowId,long spaceId) {
        // Workflow.legacyClone的委托前只读；第一把写锁必须是此创建guard，不能在空间锁后获取它。
        long owner=creatorGuard();
        var workflow=workflows.require(workflowId,spaceId,"paiflow:workflow:query",3,true);var source=mapper.byWorkflow(workflowId);
        if(source==null)throw new ServiceException("原工作流克隆没有已核对的智能体绑定，不能伪造完整复制",503);
        if(!source.getSpaceId().equals(workflow.getSpaceId()))throw new ServiceException("工作流绑定空间不一致",409);
        var cloned=copyInside(source.getId(),spaceId,owner);return workflows.detail(cloned.getLongValue("workflowId"),spaceId);
    }
    @Override public void workflowSaved(WorkflowRecord workflow) {
        var existing=mapper.byWorkflow(workflow.getId());if(existing==null)return;
        access.requireSpace(workflow.getSpaceId(),"paiflow:agent:edit",3,true);var row=mapper.lock(existing.getId());consistent(row,workflow);
        row.setName(text(workflow.getName(),32,true,"智能体名称"));row.setDescription(text(workflow.getDescription(),200,false,"智能体描述"));
        var config=json(row.getConfigJson());var advanced=json(workflow.getAdvancedConfigJson());var prologue=advanced.getJSONObject("prologue");
        if(prologue!=null) { if(prologue.containsKey("prologueText"))config.put("prologue",prologue.get("prologueText"));if(prologue.containsKey("inputExample"))config.put("inputExample",prologue.get("inputExample")); }
        if(advanced.containsKey("backgroundPic"))config.put("appBackground",advanced.get("backgroundPic"));
        var metadata=json(workflow.getMetadataJson());if(metadata.containsKey("avatarIcon"))row.setAvatar(text(metadata.getString("avatarIcon"),2560,false,"头像"));
        WorkflowJsonBoundary.check(config,"agent/config",1024*1024);row.setConfigJson(config.toJSONString());row.setUpdateBy(identities.current().getUsername());name(row);
        if(mapper.update(row)!=1)throw new ServiceException("智能体元数据同步失败，画布保存已回滚",409);
    }
    @Override public void workflowDeleting(WorkflowRecord workflow) {
        var existing=mapper.byWorkflow(workflow.getId());if(existing==null)return;
        access.requireSpace(workflow.getSpaceId(),"paiflow:agent:remove",3,true);var row=mapper.lock(existing.getId());consistent(row,workflow);
        if(mapper.delete(row.getId(),row.getSpaceId(),row.getRevision(),identities.current().getUsername())!=1)throw new ServiceException("智能体删除失败，工作流删除已回滚",409);
    }
    @Override public JSONObject bindingView(long workflowId) {
        var row=mapper.byWorkflow(workflowId);if(row==null)return null;
        return JSONObject.of("botId",row.getId(),"agentRevision",row.getRevision(),"agentBindingStatus","bound","ext",JSONObject.of("botId",row.getId()).toJSONString());
    }
    public void unavailable(long spaceId,String permission,String capability) { access.requireSpace(spaceId,permission,3,false);throw new ServiceException(capability+"尚未接通，当前不会生成正式App、凭据或发布成功",503); }
    public void unavailableForAgent(long id,Long spaceId,String capability) { require(id,spaceId,"paiflow:agent:edit",false);throw new ServiceException(capability+"尚未接通，智能体仍为未发布草稿",503); }
    /** 原Bot路由的头部域由中心解析；不能把空值作为已知ID的全空间通配条件。 */
    public Page legacyList(String header,JSONObject query) {
        var selected=select(header,query==null?null:query.get("spaceId"),"paiflow:agent:query");Long space=defaults.existing(selected,"paiflow:agent:query");var page=listing(space,query);
        return new Page(page.pageData().stream().map(value->legacyWire(value,"paiflow:agent:query")).toList(),page.totalCount(),page.pageSize(),page.page(),page.totalPages());
    }
    public JSONObject legacyDetail(long id,String header) { return legacyWire(detail(id,existing(header,null,"paiflow:agent:query")),"paiflow:agent:query"); }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject legacyCreate(String header,int version,JSONObject body) {
        var request=creationRequest(version,body);var selected=select(header,request.get("spaceId"),"paiflow:agent:create");request.remove("spaceId");serverOwner(request);long owner=creatorGuard();
        long space=defaults.materialize(selected,"paiflow:agent:create");return legacyWire(createInside(space,version,request,owner),"paiflow:agent:create");
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject legacyUpdate(long id,String header,Long revision,Long workflowRevision,JSONObject body) {
        Long space=existing(header,body==null?null:body.get("spaceId"),"paiflow:agent:edit");var request=copyBody(body);request.remove("spaceId");return legacyWire(update(id,space,revision,workflowRevision,request),"paiflow:agent:edit");
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void legacyDelete(long id,String header,Long revision,Long workflowRevision) { delete(id,existing(header,null,"paiflow:agent:remove"),revision,workflowRevision); }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject legacyCopy(long id,String header) { Long space=existing(header,null,"paiflow:agent:query");long owner=creatorGuard();return legacyWire(copyInside(id,space,owner),"paiflow:agent:query"); }
    public void legacyCatalogAccess(String header) { var selected=select(header,null,"paiflow:agent:query");defaults.existing(selected,"paiflow:agent:query"); }
    public void legacyUnavailable(String header,String permission,String capability) {
        var selected=select(header,null,permission);defaults.existing(selected,permission);throw new ServiceException(capability+"尚未接通，当前不会生成正式App、凭据或发布成功",503);
    }
    public void legacyUnavailableForAgent(long id,String header,String capability) { unavailableForAgent(id,existing(header,null,"paiflow:agent:edit"),capability); }
    private DefaultPersonalSpaceService.Selection select(String header,Object echo,String permission) {
        if(defaults==null)throw new ServiceException("默认个人域适配尚未装配",503);return defaults.select(header,echo,permission);
    }
    private Long existing(String header,Object echo,String permission) {
        var selected=select(header,echo,permission);Long id=defaults.existing(selected,permission);if(id==null)throw new ServiceException("智能体不存在或不可访问",404);return id;
    }
    private JSONObject legacyWire(JSONObject value,String permission) {
        var result=copyBody(value);var domain=access.requireSpace(value.getLongValue("spaceId"),permission,3,false);result.put("spaceId",DefaultPersonalSpaceService.legacySpaceId(domain));return result;
    }
    private static JSONObject copyBody(JSONObject body) { if(body==null)throw new ServiceException("智能体请求不能为空",400);return JSON.parseObject(body.toJSONString(JSONWriter.Feature.WriteMapNullValue)); }
    private long creatorGuard() {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new ServiceException("智能体插入须在中心事务内串行核对",500);
        long owner=access.requirePermission("paiflow:agent:create");mapper.lockOwnerGuard(owner);long current=access.requirePermission("paiflow:agent:create");
        if(owner!=current)throw new ServiceException("创建主体已经变化",403);return owner;
    }
    private void sameCreator(long owner) { if(!Objects.equals(identities.current().getUserId(),owner))throw new ServiceException("创建主体已经变化",403); }
    private AgentRecord require(long id,Long spaceId,String permission,boolean lock) {
        var row=mapper.find(id);if(row==null || spaceId!=null && !spaceId.equals(row.getSpaceId()))throw new ServiceException("智能体不存在或不可访问",404);
        access.requireSpace(row.getSpaceId(),permission,3,lock);
        // 统一空间→Workflow→Agent行锁顺序，与画布直接保存/恢复/删除保持一致。
        if(lock) { if(row.getWorkflowId()!=null)workflows.require(row.getWorkflowId(),row.getSpaceId(),workflowPermission(permission),3,true);row=mapper.lock(id);if(row==null)throw new ServiceException("智能体不存在或不可访问",404);access.requireSpace(row.getSpaceId(),permission,3,false); }
        return row;
    }
    private static String workflowPermission(String agentPermission) { return agentPermission.endsWith(":remove")?"paiflow:workflow:remove":agentPermission.endsWith(":edit")?"paiflow:workflow:edit":"paiflow:workflow:query"; }
    private WorkflowRecord bound(AgentRecord row,String permission,boolean lock) {
        if(row.getVersion()==1) { if(row.getWorkflowId()!=null)throw new ServiceException("指令型智能体绑定异常",409);return null; }
        if(row.getWorkflowId()==null)throw new ServiceException("工作流型智能体缺少已核对的画布绑定",409);
        var workflow=workflows.require(row.getWorkflowId(),row.getSpaceId(),permission,3,lock);consistent(row,workflow);return workflow;
    }
    private static void consistent(AgentRecord agent,WorkflowRecord workflow) { if(agent==null || !Objects.equals(agent.getWorkflowId(),workflow.getId()) || !Objects.equals(agent.getSpaceId(),workflow.getSpaceId()))throw new ServiceException("智能体与工作流归属不一致",409); }
    private JSONObject view(AgentRecord row) {
        if(row==null)throw new ServiceException("智能体不存在",404);var result=json(row.getConfigJson());
        result.put("id",row.getId());result.put("botId",row.getId());result.put("spaceId",row.getSpaceId());result.put("ownerUserId",row.getOwnerUserId());result.put("uid",IdentityMappingService.legacyUid(row.getOwnerUserId()));result.put("revision",row.getRevision());
        result.put("name",row.getName());result.put("botName",row.getName());result.put("description",row.getDescription());result.put("botDesc",row.getDescription());result.put("avatar",row.getAvatar());result.put("botType",row.getBotType());result.put("version",row.getVersion());result.put("createTime",row.getCreateTime());result.put("updateTime",row.getUpdateTime());
        result.put("botStatus",-9);result.put("publishStatus",0);result.put("publicationStatus","draft_unpublished");result.put("releaseType",List.of());result.put("blockReason","");result.put("hotNum","0");result.put("marketBotId",0);result.put("isFavorite",0);result.put("af",null);
        result.put("resourceBindingStatus","unconfigured");result.put("applicationBindingStatus","unconfigured");
        result.putIfAbsent("datasetList",List.of());result.putIfAbsent("maasDatasetList",List.of());result.putIfAbsent("promptStructList",List.of());result.putIfAbsent("inputExample",List.of());result.put("inputExampleList",result.get("inputExample"));
        result.put("supportUploadList",List.of(result.getIntValue("supportUpload",0)));result.put("workflowId",row.getWorkflowId());result.put("maasId",row.getWorkflowId());
        var workflow=bound(row,"paiflow:workflow:query",false);result.put("flowId",workflow==null?null:workflow.getFlowId());result.put("workflowRevision",workflow==null?null:workflow.getRevision());result.put("multiInput",workflow!=null && multiInput(workflow));
        boolean editable=true;try { access.requireSpace(row.getSpaceId(),"paiflow:agent:edit",3,false); }catch(ServiceException error) { if(error.getCode()!=null && error.getCode()==403)editable=false;else throw error; }result.put("editable",editable);return result;
    }
    private JSONObject workflowRequest(AgentRecord row,JSONObject request,WorkflowRecord workflow) {
        var body=new JSONObject();body.put("name",row.getName());body.put("description",row.getDescription());body.put("avatarIcon",row.getAvatar());
        var advanced=workflow==null?new JSONObject():json(workflow.getAdvancedConfigJson());var config=json(row.getConfigJson());
        var prologue=advanced.getJSONObject("prologue");if(prologue==null)prologue=new JSONObject();prologue.put("enabled",true);prologue.put("prologueText",config.get("prologue"));prologue.put("inputExample",config.getOrDefault("inputExample",List.of()));advanced.put("prologue",prologue);advanced.put("backgroundPic",config.get("appBackground"));body.put("advancedConfig",advanced);
        if(workflow==null) { if(request.containsKey("data"))body.put("data",request.get("data"));if(request.containsKey("engineProfile"))body.put("engineProfile",request.get("engineProfile")); }
        return body;
    }
    private void apply(AgentRecord row,JSONObject body) {
        if(body.containsKey("name") || row.getName()==null)row.setName(text(body.getString("name"),32,true,"智能体名称"));
        if(body.containsKey("botDesc") || body.containsKey("description") || row.getDescription()==null)row.setDescription(text(body.containsKey("botDesc")?body.getString("botDesc"):body.getString("description"),200,false,"智能体描述"));
        if(body.containsKey("avatar"))row.setAvatar(text(body.getString("avatar"),2560,false,"头像"));
        if(body.containsKey("botType")) { long type=number(body.get("botType"),"botType",false);if(type<0 || type>Integer.MAX_VALUE)throw new ServiceException("智能体分类无效",400);row.setBotType((int)type); }else if(row.getBotType()==null)row.setBotType(0);
        var config=json(row.getConfigJson());body.forEach((key,value)->{if(!CONTROLLED.contains(key))config.put(key,value);});row.setConfigJson(config.toJSONString(JSONWriter.Feature.WriteMapNullValue));
    }
    private void name(AgentRecord row) { if(mapper.countName(row.getSpaceId(),row.getName(),row.getId())!=0)throw new ServiceException("当前空间已存在同名智能体",409); }
    private void insert(AgentRecord row) { if(mapper.insert(row)!=1 || row.getId()==null)throw new ServiceException("智能体保存失败",500); }
    private static void validate(JSONObject body) {
        if(body==null)throw new ServiceException("智能体请求不能为空",400);WorkflowJsonBoundary.check(body,"request",1024*1024);
        for(String key:FORGED)if(body.containsKey(key))throw new ServiceException("请求不能提供中心管理字段："+key,400);
        for(String key:List.of("workflowId","maasId","flowId","maasBotId","virtualAgentId"))if(body.get(key)!=null)throw new ServiceException("原资源绑定尚未核对："+key,503);
        for(String key:List.of("datasetList","maasDatasetList"))if(body.containsKey(key)) { var array=body.getJSONArray(key);if(array==null && body.get(key)!=null)throw new ServiceException(key+"必须为数组",400);if(array!=null && !array.isEmpty())throw new ServiceException("知识资源归属/版本端口尚未接通："+key,503); }
        Object model=body.get("modelId");if(model!=null && !"null".equals(model.toString()) && number(model,"modelId",false)!=0)throw new ServiceException("模型资源归属/版本端口尚未接通：modelId",503);
        for(String key:List.of("inputExample","inputExampleEn","promptStructList"))if(body.get(key)!=null && !(body.get(key) instanceof JSONArray || body.get(key) instanceof List<?>))throw new ServiceException(key+"必须为数组",400);
        for(String key:List.of("inputExample","inputExampleEn"))if(body.getJSONArray(key)!=null)for(Object item:body.getJSONArray(key))if(!(item instanceof String))throw new ServiceException(key+"必须为字符串数组",400);
        if(body.getJSONArray("promptStructList")!=null)for(Object item:body.getJSONArray("promptStructList")) {
            if(!(item instanceof JSONObject part) || !(part.get("promptKey") instanceof String) || !(part.get("promptValue") instanceof String))throw new ServiceException("promptStructList必须包含promptKey/promptValue字符串",400);
        }
    }
    private static boolean multiInput(WorkflowRecord workflow) {
        var nodes=json(workflow.getCanvasJson()).getJSONArray("nodes");if(nodes==null)return false;
        for(Object value:nodes)if(value instanceof JSONObject node && node.getString("id")!=null && node.getString("id").startsWith("node-start::")) {
            var data=node.getJSONObject("data");var outputs=data==null?null:data.getJSONArray("outputs");if(outputs!=null)for(Object output:outputs)if(output instanceof JSONObject field && !"AGENT_USER_INPUT".equals(field.getString("name")))return true;
        }return false;
    }
    private static void revision(AgentRecord row,Long expected) { if(expected==null || !expected.equals(row.getRevision()))throw new ServiceException("缺少或不匹配的智能体revision，请刷新后重试",409); }
    private static void scope(JSONObject body,long spaceId) { if(body.get("spaceId")!=null && number(body.get("spaceId"),"spaceId",true)!=spaceId)throw new ServiceException("智能体空间标识不一致",400);serverOwner(body); }
    private static void serverOwner(JSONObject body) { if(body.get("uid")!=null || body.get("ownerUserId")!=null)throw new ServiceException("智能体所有权由平台认证主体决定",400); }
    public static long number(Object value,String field,boolean positive) { try { if(value==null)throw new IllegalArgumentException();String text=value.toString();if(!text.matches("-?[0-9]{1,19}"))throw new IllegalArgumentException();long result=Long.parseLong(text);if(positive && result<1)throw new IllegalArgumentException();return result; }catch(RuntimeException error) { throw new ServiceException(field+"必须为有效整数",400); } }
    public static Long optionalRevision(Object value,String field) { if(value==null)return null;long result=number(value,field,false);if(result<0)throw new ServiceException(field+"不能为负数",400);return result; }
    private static JSONObject json(String value) { return value==null?new JSONObject():JSON.parseObject(value); }
    private static String text(String value,int max,boolean required,String field) { if(required && (value==null || value.isBlank()))throw new ServiceException(field+"不能为空",400);if(value!=null && value.length()>max)throw new ServiceException(field+"超过允许长度",400);return value==null?null:value.trim(); }
    private static void page(int current,int size) { if(current<1 || size<1 || size>200)throw new ServiceException("分页参数无效，每页最多200条",400); }
}
