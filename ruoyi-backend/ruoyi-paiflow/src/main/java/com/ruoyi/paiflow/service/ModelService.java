package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.ModelRecord;
import com.ruoyi.paiflow.domain.ModelVersionRecord;
import com.ruoyi.paiflow.mapper.ModelMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 模型草稿/版本唯一中心；原ModelService的字段和纯组装算法，不搬任意env或旧库副作用。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelService {
    public record Page(List<JSONObject> records,long total,int size,int current,long pages) {}
    /** immutable字符串固定完整候选；端口不能通过修改JSONObject改写被批准的内容。 */
    public record Candidate(long spaceId,long ownerUserId,Long modelId,long expectedRevision,int type,String configurationJson,String candidateHash,String endpoint,String provider,String credentialRef) {}
    public record Authorization(long spaceId,long ownerUserId,Long modelId,String candidateHash,String endpoint,String provider,String credentialRef,String credentialVersion,String authorizationId,Instant validUntil) {}
    public record ProbeReceipt(String candidateHash,String endpoint,String provider,String credentialVersion,String authorizationId,String responseSha256,Instant observedAt,String state,String evidenceJson) {}
    /** 只解析已批准同域引用；current必须只核对中心状态，不发网络、不读取任意环境/文件。 */
    public interface CredentialReferencePort { Authorization authorize(Candidate candidate); boolean current(Authorization authorization); }
    /** 真正供应商调用及脱敏证据；本模块没有默认实现。 */
    public interface ProviderValidationPort { ProbeReceipt hello(Candidate candidate,Authorization authorization,JSONObject body); }
    public interface LocalDeploymentPort {
        ProbeReceipt deploy(Candidate candidate,Authorization authorization);
        default JSONArray list(long space,long user){throw fail("受控本地模型目录尚未接通",503);}
        default ProbeReceipt status(Candidate candidate,Authorization authorization,String serviceId){throw fail("受控本地模型状态尚未接通",503);}
    }
    public JSONArray localModelList(Long space){long selected=space==null?requireDefault("paiflow:model:query"):space;access.requireSpace(selected,"paiflow:model:query",3,false);var port=deployers==null?null:deployers.getIfAvailable();if(port==null)throw fail("受控本地模型目录尚未接通",503);return port.list(selected,identities.current().getUserId());}
    /** 按原部署状态轮询协议回读；网络在事务外，状态保存重新核对空间/主体/revision。 */
    public JSONObject refreshDeployment(long id,Long space,Long expected){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw fail("本地部署状态回读不能持有业务事务",500);
        var row=require(id,space,"paiflow:model:edit",false,true);revision(row,expected);if(row.getType()!=2)throw fail("该模型不是本地部署模型",400);
        String service=ManagedModelValidation.deploymentId(row.getReceiptJson());if(service==null)throw fail("模型缺少实际部署标识",409);
        var config=config(row);var c=new Candidate(row.getSpaceId(),row.getOwnerUserId(),row.getId(),row.getRevision(),2,row.getConfigurationJson(),candidateHash(row.getSpaceId(),row.getOwnerUserId(),row.getId(),row.getRevision(),2,config),config.getString("endpoint"),config.getString("provider"),config.getString("credentialRef"));
        var ports=requirePorts(true);var a=ports.reference().authorize(c);checkAuthorization(c,a);var receipt=ports.deployer().status(c,a,service);
        var proxy=selves==null?null:selves.getIfAvailable();if(proxy==null)throw fail("模型状态事务入口未装配",503);return proxy.commitDeploymentStatus(c,a,receipt);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject commitDeploymentStatus(Candidate c,Authorization a,ProbeReceipt r){
        checkAuthorization(c,a);if(r==null || !Objects.equals(c.candidateHash(),r.candidateHash()) || !Objects.equals(a.authorizationId(),r.authorizationId()) || !Objects.equals(a.credentialVersion(),r.credentialVersion()) || !Objects.equals(c.endpoint(),r.endpoint()) || !Objects.equals(c.provider(),r.provider()) || r.observedAt()==null || r.observedAt().isBefore(Instant.now().minusSeconds(300)) || r.responseSha256()==null || !r.responseSha256().matches("[0-9a-f]{64}"))throw fail("本地部署状态回执绑定无效",409);
        var evidence=JSON.parseObject(r.evidenceJson());if(evidence==null || !(evidence.get("deploymentId") instanceof String) || !Set.of("PENDING","READY","FAILED").contains(r.state()))throw fail("本地部署状态回执无效",409);
        var row=require(c.modelId(),c.spaceId(),"paiflow:model:edit",true,true);revision(row,c.expectedRevision());
        var ref=references==null?null:references.getIfAvailable();if(ref==null || !ref.current(a))throw fail("部署状态保存前用途已变化",409);checkAuthorization(c,a);
        if("READY".equals(r.state()) && (!(evidence.get("inferenceEndpoint") instanceof String) || number(evidence.get("helloHttpStatus"),"helloHttpStatus")!=200 || !(evidence.get("usage") instanceof JSONObject)))throw fail("本地模型尚未取得实际推理Hello回执",422);
        row.setState("READY".equals(r.state())?"VALIDATED":"FAILED".equals(r.state())?"DEPLOYED_FAILED":"DEPLOYED_PENDING");row.setEnabled("READY".equals(r.state())?1:0);row.setCandidateHash(c.candidateHash());row.setCredentialVersion(a.credentialVersion());row.setReceiptJson(JSONObject.of("authorization",a,"receipt",r).toJSONString(JSONWriter.Feature.WriteMapNullValue));row.setUpdateBy(identities.current().getUsername());
        if(mapper.update(row)!=1)throw fail("模型状态版本已变化",409);row=mapper.find(row.getId());snapshot(row);return view(mapper.find(row.getId()));
    }
    private final ModelMapper mapper;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private ObjectProvider<CredentialReferencePort> references;
    private ObjectProvider<ProviderValidationPort> validators;
    private ObjectProvider<LocalDeploymentPort> deployers;
    private ObjectProvider<ModelService> selves;
    private DefaultPersonalSpaceService defaultSpaces;
    private final Map<Long,JSONObject> categories=new LinkedHashMap<>();
    private final JSONObject categorySource;
    private static final Set<String> DIMENSIONS=Set.of("modelCategory","modelScenario","languageSupport","contextLengthTag");
    private static final Set<String> INPUT=Set.of("id","spaceId","revision","modelName","name","domain","endpoint","credentialRef","apiKey","apiKeyMasked","provider","description","icon","color","tag","config","modelCategoryReq","capabilities","type","modelPath","acceleratorCount","replicaCount");
    public ModelService(ModelMapper mapper,ResourceAccessService access,IdentityMappingService identities) {
        this.mapper=mapper;this.access=access;this.identities=identities;
        try(var input=getClass().getResourceAsStream("/paiflow/model-category-source.json")) {
            if(input==null)throw new IllegalStateException();categorySource=JSON.parseObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            for(Object value:categorySource.getJSONArray("rows")) {var row=(JSONObject)value;if(row.getIntValue("isDelete")==0)categories.put(row.getLong("id"),row);}
        }catch(Exception error){throw new ServiceException("原模型类别资料缺失",503);}
    }
    @Autowired public void usePorts(ObjectProvider<CredentialReferencePort> references,ObjectProvider<ProviderValidationPort> validators,ObjectProvider<LocalDeploymentPort> deployers,ObjectProvider<ModelService> selves) {this.references=references;this.validators=validators;this.deployers=deployers;this.selves=selves;}
    @Autowired public void useDefaultSpaces(DefaultPersonalSpaceService defaults) {this.defaultSpaces=defaults;}
    public JSONObject detail(long id,Long spaceId) {return view(require(id,spaceId,"paiflow:model:query",false,false));}
    public Page list(long space,int page,int size,String search,Integer type) {
        access.requireSpace(space,"paiflow:model:query",3,false);if(page<1 || size<1 || size>200)throw fail("分页参数无效",400);
        if(type!=null && type!=1 && type!=2)throw fail("模型类型无效",400);search=text(search,200,false,"搜索内容");
        long total=mapper.count(space,search,type);return new Page(mapper.list(space,search,type,(long)(page-1)*size,size).stream().map(this::view).toList(),total,size,page,(total+size-1)/size);
    }
    public Page legacyList(long space,JSONObject body) {
        access.requireSpace(space,"paiflow:model:query",3,false);var query=legacyQuery(body);
        return list(space,query.page(),query.size(),query.name(),query.filter());
    }
    private record LegacyQuery(int page,int size,String name,Integer filter) {}
    private static LegacyQuery legacyQuery(JSONObject body) {
        if(body==null)throw fail("模型请求不能为空",400);int type=intValue(body.get("type"),0,"type"),filter=intValue(body.get("filter"),0,"filter");
        if(type<0 || type>2 || filter<0 || filter>2)throw fail("原模型筛选参数无效",400);
        if(type!=2)throw fail("官方模型目录尚未接通；不能返回空目录假成功",503);
        int page=intValue(body.get("page"),1,"page"),size=intValue(body.get("pageSize"),10,"pageSize");if(page<1 || size<1 || size>200)throw fail("分页参数无效",400);
        return new LegacyQuery(page,size,text(body.getString("name"),200,false,"搜索内容"),filter==0?null:filter);
    }
    private DefaultPersonalSpaceService defaults() {if(defaultSpaces==null)throw fail("默认个人域映射入口尚未装配",503);return defaultSpaces;}
    private Long defaultExisting(String permission) {var scope=defaults();return scope.existing(scope.defaultSelection(permission),permission);}
    private long requireDefault(String permission) {var value=defaultExisting(permission);if(value==null)throw fail("当前默认个人域没有该模型",404);return value;}
    private static JSONObject defaultWire(JSONObject value) {var result=copy(value);result.put("spaceId",null);return result;}
    public Page legacyDefaultList(JSONObject body) {
        Long space=defaultExisting("paiflow:model:query");var query=legacyQuery(body);
        if(space==null)return new Page(List.of(),0,query.size(),query.page(),0);
        var page=list(space,query.page(),query.size(),query.name(),query.filter());return new Page(page.records().stream().map(ModelService::defaultWire).toList(),page.total(),page.size(),page.current(),page.pages());
    }
    public JSONObject legacyDefaultDetail(long id,int source) {if(source!=0)legacyDefaultUnavailable("官方/微调模型详情");return defaultWire(detail(id,requireDefault("paiflow:model:query")));}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject legacyDefaultEnabled(long id,Long expected,boolean enabled) {return defaultWire(switchEnabled(id,requireDefault("paiflow:model:edit"),expected,enabled));}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void legacyDefaultDelete(long id,Long expected) {delete(id,requireDefault("paiflow:model:remove"),expected);}
    public List<JSONObject> legacyDefaultCategories() {defaultExisting("paiflow:model:query");return tree(null);}
    public void legacyDefaultUnavailable(String capability) {defaultExisting("paiflow:model:query");throw fail(capability+"尚未接通，当前不会生成可用模型或空成功",503);}
    /** 只有所需端口及纯候选检查通过后才建立内置私域；真实探测始终在事务之外。 */
    public JSONObject validateLegacyDefault(Long expected,JSONObject body,boolean local) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw fail("模型探测不得在数据库事务中执行",500);
        if(body==null)throw fail("模型请求不能为空",400);var request=copy(body);request.remove("spaceId");Long id=request.get("id")==null?null:positive(request.get("id"),"id");
        String permission=id==null?"paiflow:model:create":"paiflow:model:edit";var scope=defaults();var selected=scope.select(null,body.get("spaceId"),permission);Long space=scope.existing(selected,permission);
        if(space==null) {
            if(id!=null)throw fail("当前默认个人域没有该模型",404);if(expected!=null)throw fail("新建模型不能携带已有版本条件",400);
            // 0仅用于纯形状检查，不进入授权/回执/记录。正式候选必须绑定materialize返回的实际正ID。
            var preview=candidate(0,selected.ownerUserId(),null,-1,local?2:1,request,null);
            if(hasCustomCategories(JSON.parseObject(preview.configurationJson())))throw fail("自定义类别实体尚未迁接；不能建立正式绑定",503);
            requirePorts(local);var proxy=selves==null?null:selves.getIfAvailable();if(proxy==null || proxy==this)throw fail("模型最终事务入口尚未装配",503);
            space=scope.materializeIndependent(selected,permission);
        }
        return defaultWire(validateLegacy(space,expected,request,local));
    }
    private record Ports(CredentialReferencePort reference,ProviderValidationPort validator,LocalDeploymentPort deployer) {}
    private Ports requirePorts(boolean local) {
        var reference=references==null?null:references.getIfAvailable();var validator=validators==null?null:validators.getIfAvailable();var deployer=deployers==null?null:deployers.getIfAvailable();
        if(reference==null || (local?deployer==null:validator==null))throw fail(local?"受控本地模型部署端口尚未接通":"作用域凭据引用和真实Provider Hello校验端口尚未接通",503);
        return new Ports(reference,validator,deployer);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject createDraft(long space,JSONObject body) {
        if(body==null || body.get("id")!=null)throw fail("新建草稿不能携带已有ID",400);scope(body,space);
        access.requireSpace(space,"paiflow:model:create",3,true);access.requireSpace(space,"paiflow:model:create",3,false);var actor=identities.current();
        int type=intValue(body.get("type"),1,"type");var candidate=candidate(space,actor.getUserId(),null,-1,type,body,null);
        return view(persist(candidate,null,null,false));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject saveDraft(long id,Long space,Long expected,JSONObject body) {
        var row=require(id,space,"paiflow:model:edit",true,true);revision(row,expected);scope(body,row.getSpaceId());id(body,id);
        var merged=merge(row,body);if(merged.get("type")!=null && number(merged.get("type"),"type")!=row.getType())throw fail("已有模型类型不能转换",409);
        var candidate=candidate(row.getSpaceId(),row.getOwnerUserId(),id,row.getRevision(),row.getType(),merged,row);
        return view(persist(candidate,null,null,false));
    }
    /** 非事务准备与供应商调用；事务许可只在commitValidated中重新核对并CAS。 */
    public JSONObject validateLegacy(long space,Long expected,JSONObject body,boolean local) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw fail("模型探测不得在数据库事务中执行",500);
        if(body==null)throw fail("模型请求不能为空",400);Long id=body.get("id")==null?null:positive(body.get("id"),"id");
        var row=id==null?null:require(id,space,"paiflow:model:edit",false,true);
        access.requireSpace(space,id==null?"paiflow:model:create":"paiflow:model:edit",3,false);scope(body,space);
        if(row!=null)revision(row,expected);else if(expected!=null)throw fail("新建模型不能携带已有版本条件",400);
        var ports=requirePorts(local);var reference=ports.reference();var validator=ports.validator();var deployer=ports.deployer();
        var actor=identities.current();
        if(row!=null && row.getType()!=(local?2:1))throw fail("已有模型类型不能转换",409);
        var request=row==null?copy(body):merge(row,body);
        // 原apiKeyMasked=false表示没有更换；RSA重上架包裹值不被解析或存储。
        if(row!=null && Boolean.FALSE.equals(request.getOrDefault("apiKeyMasked",false))) {request.remove("apiKey");request.put("credentialRef",config(row).get("credentialRef"));}
        var candidate=candidate(space,actor.getUserId(),id,row==null?-1:row.getRevision(),local?2:1,request,row);
        if(hasCustomCategories(JSON.parseObject(candidate.configurationJson())))throw fail("自定义类别实体尚未迁接；可保全草稿，但不能伪造正式类别绑定",503);
        var authorization=reference.authorize(candidate);checkAuthorization(candidate,authorization);
        var receipt=local?deployer.deploy(candidate,authorization):validator.hello(candidate,authorization,hello(candidate));
        checkReceipt(candidate,authorization,receipt);
        var proxy=selves==null?null:selves.getIfAvailable();if(proxy==null || proxy==this)throw fail("模型最终事务入口尚未装配",503);
        return proxy.commitValidated(candidate,authorization,receipt);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject commitValidated(Candidate candidate,Authorization authorization,ProbeReceipt receipt) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw fail("模型保存须使用中心事务",500);
        checkAuthorization(candidate,authorization);checkReceipt(candidate,authorization,receipt);
        String permission=candidate.modelId()==null?"paiflow:model:create":"paiflow:model:edit";
        access.requireSpace(candidate.spaceId(),permission,3,true);access.requireSpace(candidate.spaceId(),permission,3,false);
        if(!Objects.equals(identities.current().getUserId(),candidate.ownerUserId()))throw fail("模型校验主体已经变化",403);
        if(candidate.modelId()!=null)revision(require(candidate.modelId(),candidate.spaceId(),permission,true,true),candidate.expectedRevision());
        var reference=references==null?null:references.getIfAvailable();if(reference==null || !reference.current(authorization))throw fail("凭据引用版本或批准已变化，旧探测不能保存",409);
        if(!candidate.candidateHash().equals(candidateHash(candidate.spaceId(),candidate.ownerUserId(),candidate.modelId(),candidate.expectedRevision(),candidate.type(),JSON.parseObject(candidate.configurationJson()))))throw fail("候选模型完整性不一致",409);
        // space/model及引用核对可能等待：所有等待完成后再次核时效，必须在正式写入之前。
        checkAuthorization(candidate,authorization);checkReceipt(candidate,authorization,receipt);
        return view(persist(candidate,authorization,receipt,true));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject switchEnabled(long id,Long space,Long expected,boolean enabled) {
        var row=require(id,space,"paiflow:model:edit",true,true);revision(row,expected);
        if(enabled) {
            if(!"VALIDATED".equals(row.getState()) || row.getReceiptJson()==null)throw fail("未取得真实校验回执的模型不能启用",409);
            var record=JSON.parseObject(row.getReceiptJson());var authorization=record.getObject("authorization",Authorization.class);
            if(authorization==null || authorization.validUntil()==null || !authorization.validUntil().isAfter(Instant.now()) || authorization.spaceId()!=row.getSpaceId() || authorization.ownerUserId()!=row.getOwnerUserId() || !Objects.equals(authorization.modelId(),row.getId()) && authorization.modelId()!=null || !Objects.equals(authorization.candidateHash(),row.getCandidateHash()) || !Objects.equals(authorization.credentialVersion(),row.getCredentialVersion()))throw fail("模型校验批准已过期或绑定失效，请重新校验",409);
            var reference=references==null?null:references.getIfAvailable();if(reference==null || !reference.current(authorization))throw fail("受控凭据版本核对端口缺失或引用已变化",503);
            if(!authorization.validUntil().isAfter(Instant.now()))throw fail("本地引用核对期间模型批准已过期，请重新校验",409);
        }
        row.setEnabled(enabled?1:0);row.setUpdateBy(identities.current().getUsername());if(mapper.update(row)!=1)throw fail("模型已经变化",409);snapshot(mapper.find(id));return view(mapper.find(id));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void delete(long id,Long space,Long expected) {var row=require(id,space,"paiflow:model:remove",true,true);revision(row,expected);if(mapper.delete(id,row.getSpaceId(),row.getRevision(),identities.current().getUsername())!=1)throw fail("模型已经变化",409);}
    public Page versions(long id,Long space,int page,int size) {var row=require(id,space,"paiflow:model:query",false,false);if(page<1 || size<1 || size>200)throw fail("分页参数无效",400);long total=mapper.countVersions(id);return new Page(mapper.versions(row.getId(),(long)(page-1)*size,size).stream().map(this::versionView).toList(),total,size,page,(total+size-1)/size);}
    public JSONObject versionDetail(long versionId,Long space) {var row=mapper.version(versionId);if(row==null || space!=null && !space.equals(row.getSpaceId()))throw fail("模型版本不存在或不可访问",404);access.requireSpace(row.getSpaceId(),"paiflow:model:query",3,false);return versionView(row);}
    public List<JSONObject> categoryTree(long space) {access.requireSpace(space,"paiflow:model:query",3,false);return tree(null);}
    public JSONObject categoryDiagnostics(long space) {access.requireSpace(space,"paiflow:model:query",3,false);return copy(categorySource);}
    public void unavailable(long space,String capability) {access.requireSpace(space,"paiflow:model:query",3,false);throw fail(capability+"尚未接通，当前不会生成可用模型或空成功",503);}
    public JSONObject legacyDetail(long id,Long space,int llmSource) {if(llmSource==0)return detail(id,space);if(space==null)throw fail("请选择模型所属空间",400);unavailable(space,llmSource==1?"官方模型详情":"微调模型详情");throw new IllegalStateException();}
    private ModelRecord require(long id,Long space,String permission,boolean lock,boolean owner) {
        var row=mapper.find(id);if(row==null || space!=null && !space.equals(row.getSpaceId()))throw fail("模型不存在或不可访问",404);
        access.requireSpace(row.getSpaceId(),permission,3,lock);if(lock){row=mapper.lock(id);if(row==null)throw fail("模型不存在或不可访问",404);access.requireSpace(row.getSpaceId(),permission,3,false);}
        if(owner && !row.getOwnerUserId().equals(identities.current().getUserId()))throw fail("只能修改自己创建的模型",403);return row;
    }
    private ModelRecord persist(Candidate candidate,Authorization authorization,ProbeReceipt receipt,boolean validated) {
        var row=candidate.modelId()==null?new ModelRecord():mapper.lock(candidate.modelId());
        if(row==null)throw fail("模型不存在",404);var configuration=JSON.parseObject(candidate.configurationJson());
        if(candidate.modelId()==null){row.setSpaceId(candidate.spaceId());row.setOwnerUserId(candidate.ownerUserId());row.setType(candidate.type());row.setRevision(0L);row.setDeleted(0);row.setCreateBy(identities.current().getUsername());}
        else revision(row,candidate.expectedRevision());
        row.setName(configuration.getString("modelName"));row.setConfigurationJson(candidate.configurationJson());row.setCandidateHash(candidate.candidateHash());
        row.setState(validated?(candidate.type()==1?"VALIDATED":"DEPLOYED_PENDING"):"DRAFT");row.setEnabled(validated && candidate.type()==1?1:0);
        row.setCredentialVersion(authorization==null?null:authorization.credentialVersion());row.setReceiptJson(authorization==null?null:JSONObject.of("authorization",authorization,"receipt",receipt).toJSONString(JSONWriter.Feature.WriteMapNullValue));row.setUpdateBy(identities.current().getUsername());
        if(mapper.countName(row.getSpaceId(),row.getName(),row.getId())!=0)throw fail("当前空间已存在同名模型",409);
        if(candidate.modelId()==null){if(mapper.insert(row)!=1 || row.getId()==null)throw fail("模型保存失败",500);}else if(mapper.update(row)!=1)throw fail("模型已经变化，旧校验未保存",409);
        row=mapper.find(row.getId());snapshot(row);return mapper.find(row.getId());
    }
    private void snapshot(ModelRecord row) {
        var version=new ModelVersionRecord();version.setModelId(row.getId());version.setSpaceId(row.getSpaceId());version.setOwnerUserId(row.getOwnerUserId());version.setRevision(row.getRevision());version.setType(row.getType());version.setEnabled(row.getEnabled());version.setState(row.getState());version.setConfigurationJson(row.getConfigurationJson());version.setCandidateHash(row.getCandidateHash());version.setCredentialVersion(row.getCredentialVersion());version.setReceiptJson(row.getReceiptJson());version.setCreateBy(identities.current().getUsername());
        if(mapper.insertVersion(version)!=1 || version.getId()==null || mapper.selectVersion(row.getId(),row.getRevision(),version.getId())!=1)throw fail("模型版本保存失败，草稿已回滚",409);
    }
    private Candidate candidate(long space,long owner,Long id,long revision,int type,JSONObject request,ModelRecord old) {
        if(type!=1 && type!=2)throw fail("模型类型只能为1或2",400);var body=copy(request);
        for(String key:body.keySet())if(!INPUT.contains(key))throw fail("请求包含未允许的模型字段："+key,400);
        String name=body.getString("modelName");if(name==null)name=body.getString("name");if(body.get("name")!=null && body.get("modelName")!=null && !Objects.equals(body.get("name"),body.get("modelName")))throw fail("模型名称别名不一致",400);
        var result=new JSONObject();result.put("modelName",text(name,200,true,"模型名称"));result.put("domain",text(body.getString("domain"),255,true,"模型标识"));result.put("type",type);
        String provider=type==2?"local-deployment":text(body.getString("provider"),100,false,"provider");if(provider==null)provider="openai-compatible";result.put("provider",provider);
        String endpoint=type==1?endpoint(body.getString("endpoint")):"local-model:"+text(body.getString("modelPath"),1024,true,"本地模型目录引用");result.put("endpoint",endpoint);
        if(type==2) {result.put("modelPath",body.getString("modelPath"));for(String key:List.of("acceleratorCount","replicaCount")){int amount=intValue(body.get(key),1,key);if(amount<1 || amount>1024)throw fail(key+"无效",400);result.put(key,amount);}}
        Object ref=body.get("credentialRef");if(body.get("apiKey")!=null){String alias=reference(body.getString("apiKey"));if(ref!=null && !alias.equals(reference(ref.toString())))throw fail("凭据引用别名不一致",400);ref=alias;}
        String credential=ref==null?null:reference(ref.toString());result.put("credentialRef",credential);
        if(body.get("apiKeyMasked")!=null && !(body.get("apiKeyMasked") instanceof Boolean))throw fail("apiKeyMasked必须是布尔值",400);
        for(String key:List.of("description","icon","color"))result.put(key,text(body.getString(key),key.equals("description")?2000:key.equals("icon")?2560:64,false,key));
        var tags=array(body.get("tag"),"tag");for(Object value:tags)if(!(value instanceof String tag) || tag.length()>200)throw fail("tag必须为短字符串数组",400);result.put("tag",tags);
        var parameters=array(body.get("config"),"config");var keys=new HashSet<String>();for(Object value:parameters){if(!(value instanceof JSONObject parameter))throw fail("config必须为对象数组",400);String key=parameter.getString("key");if(key==null || !key.matches("[A-Za-z0-9_-]+") || !keys.add(key))throw fail("config参数key非法或重复",400);String fieldType=parameter.getString("fieldType");if(fieldType==null || !Set.of("int","float","boolean").contains(fieldType))throw fail("config参数fieldType无效",400);}
        result.put("config",parameters);if(!(body.get("modelCategoryReq")==null || body.get("modelCategoryReq") instanceof JSONObject))throw fail("modelCategoryReq必须为对象",400);var category=body.get("modelCategoryReq")==null?new JSONObject():(JSONObject)body.get("modelCategoryReq");validateCategories(category);result.put("modelCategoryReq",copy(category));
        result.put("capabilities",array(body.get("capabilities"),"capabilities"));WorkflowJsonBoundary.check(result,"model/config",1024*1024);
        String json=RunJsonBoundary.canonical(result);return new Candidate(space,owner,id,revision,type,json,candidateHash(space,owner,id,revision,type,result),endpoint,provider,credential);
    }
    private static String candidateHash(long space,long owner,Long id,long revision,int type,JSONObject config) {return RunJsonBoundary.hash(JSONObject.of("spaceId",space,"ownerUserId",owner,"modelId",id,"expectedRevision",revision,"type",type,"configuration",config));}
    private static JSONObject hello(Candidate candidate) {return JSONObject.of("model",JSON.parseObject(candidate.configurationJson()).get("domain"),"messages",List.of(JSONObject.of("role","user","content","Hello!")),"stream",false);}
    private static void checkAuthorization(Candidate c,Authorization a) {if(a==null || a.spaceId()!=c.spaceId() || a.ownerUserId()!=c.ownerUserId() || !Objects.equals(a.modelId(),c.modelId()) || !Objects.equals(a.candidateHash(),c.candidateHash()) || !Objects.equals(a.endpoint(),c.endpoint()) || !Objects.equals(a.provider(),c.provider()) || !Objects.equals(a.credentialRef(),c.credentialRef()) || a.credentialVersion()==null || a.credentialVersion().isBlank() || a.authorizationId()==null || a.authorizationId().isBlank() || a.validUntil()==null || !a.validUntil().isAfter(Instant.now()))throw fail("模型授权未绑定本次候选或已经过期",409);}
    private static void checkReceipt(Candidate c,Authorization a,ProbeReceipt r) {
        if(r==null || !Objects.equals(r.candidateHash(),c.candidateHash()) || !Objects.equals(r.endpoint(),c.endpoint()) || !Objects.equals(r.provider(),c.provider()) || !Objects.equals(r.credentialVersion(),a.credentialVersion()) || !Objects.equals(r.authorizationId(),a.authorizationId()) || r.responseSha256()==null || !r.responseSha256().matches("[0-9a-f]{64}") || r.observedAt()==null || r.observedAt().isAfter(Instant.now().plusSeconds(5)) || r.observedAt().isBefore(Instant.now().minusSeconds(300)))throw fail("模型真实回执与候选版本不一致或已经过期",409);
        JSONObject evidence;try{evidence=JSON.parseObject(r.evidenceJson());}catch(RuntimeException error){throw fail("模型回执缺少真实脱敏证据",409);}if(evidence==null)throw fail("模型回执缺少真实脱敏证据",409);WorkflowJsonBoundary.check(evidence,"model/receipt",65536);
        if(c.type()==1) {boolean wire="xinghuo".equals(evidence.getString("protocol"))?number(evidence.get("providerCode"),"providerCode")==0 && number(evidence.get("providerStatus"),"providerStatus")==2:number(evidence.get("httpStatus"),"httpStatus")==200 && number(evidence.get("choicesCount"),"choicesCount")>=1;if(!"READY".equals(r.state()) || !wire || !(evidence.get("usage") instanceof JSONObject))throw fail("Provider Hello没有确认兼容成功",422);}
        else if(!"PENDING".equals(r.state()) || !(evidence.get("deploymentId") instanceof String deployment) || deployment.isBlank())throw fail("本地模型缺少实际部署回执",422);
    }
    private JSONObject view(ModelRecord row) {
        var c=config(row);var result=new JSONObject();result.put("id",row.getId());result.put("modelId",row.getId());result.put("llmId",row.getId());result.put("spaceId",row.getSpaceId());result.put("ownerUserId",row.getOwnerUserId());result.put("uid",IdentityMappingService.legacyUid(row.getOwnerUserId()));result.put("revision",row.getRevision());result.put("latestVersionId",row.getLatestVersionId());
        result.put("name",row.getName());result.put("domain",c.get("domain"));result.put("serviceId",c.get("domain"));result.put("url",row.getType()==1?c.get("endpoint"):null);result.put("apiKey",c.get("credentialRef"));result.put("credentialRef",c.get("credentialRef"));result.put("config",c.getJSONArray("config").toJSONString());result.put("tag",c.get("tag"));result.put("desc",c.get("description"));result.put("icon",c.get("icon"));result.put("color",c.get("color"));result.put("provider",c.get("provider"));result.put("type",row.getType());result.put("llmSource",0);result.put("source",1);result.put("modelType",1);result.put("state",row.getState());result.put("status","VALIDATED".equals(row.getState())?1:"DEPLOYED_PENDING".equals(row.getState())?2:0);result.put("enabled",row.getEnabled()==1);result.put("validationStatus","VALIDATED".equals(row.getState())?"validated":"DEPLOYED_PENDING".equals(row.getState())?"deployment_pending":"not_validated");result.put("candidateHash",row.getCandidateHash());result.put("credentialVersion",row.getCredentialVersion());result.put("categoryTree",tree(c.getJSONObject("modelCategoryReq")));result.put("categoryBindingStatus",hasCustomCategories(c)?"custom_draft_not_materialized":"source_reachable");result.put("modelCategoryReq",c.get("modelCategoryReq"));result.put("capabilities",c.get("capabilities"));result.put("modelPath",c.get("modelPath"));result.put("acceleratorCount",c.get("acceleratorCount"));result.put("replicaCount",c.get("replicaCount"));result.put("isThink",false);result.put("multiMode",false);result.put("shelfStatus",null);result.put("appId",null);result.put("createTime",row.getCreateTime());result.put("updateTime",row.getUpdateTime());
        // 原ModelInfo中尚未接通的官方/发布字段保留null形状，不能补造实例或供应商事实。
        for(String key:List.of("serverId","patchId","licChannel","info","pretrainedModel","address","userName","shelfOffTime"))result.put(key,null);return result;
    }
    private JSONObject versionView(ModelVersionRecord row) {var result=JSONObject.of("id",row.getId(),"modelVersionId",row.getId(),"modelId",row.getModelId(),"spaceId",row.getSpaceId(),"ownerUserId",row.getOwnerUserId(),"revision",row.getRevision(),"state",row.getState(),"enabled",row.getEnabled()==1,"candidateHash",row.getCandidateHash(),"configuration",JSON.parseObject(row.getConfigurationJson()),"credentialVersion",row.getCredentialVersion(),"createTime",row.getCreateTime());return result;}
    private void validateCategories(JSONObject c) {
        if(c==null)throw fail("modelCategoryReq无效",400);for(String key:c.keySet())if(!Set.of("categorySystemIds","categoryCustom","sceneSystemIds","sceneCustom","languageSystemId","contextLengthSystemId").contains(key))throw fail("modelCategoryReq包含管理或未知字段",400);
        for(String key:List.of("categorySystemIds","sceneSystemIds")){var ids=array(c.get(key),key);String dimension=key.equals("categorySystemIds")?"modelCategory":"modelScenario";for(Object value:ids)category(positive(value,key),dimension);}
        if(c.get("languageSystemId")!=null)category(positive(c.get("languageSystemId"),"languageSystemId"),"languageSupport");if(c.get("contextLengthSystemId")!=null)category(positive(c.get("contextLengthSystemId"),"contextLengthSystemId"),"contextLengthTag");
        for(String key:List.of("categoryCustom","sceneCustom"))if(c.get(key)!=null){if(!(c.get(key) instanceof JSONObject custom) || !custom.keySet().equals(Set.of("pid","customName")))throw fail(key+"必须含pid/customName",400);category(positive(custom.get("pid"),"pid"),key.equals("categoryCustom")?"modelCategory":"modelScenario");text(custom.getString("customName"),200,true,"自定义类别");}
    }
    private JSONObject category(long id,String key) {var row=categories.get(id);if(row==null || !key.equals(row.getString("key")) || row.getLongValue("pid")==0)throw fail("类别不属于指定维度或已失效",400);var visited=new HashSet<Long>();var current=row;while(current.getLongValue("pid")!=0){if(!visited.add(current.getLong("id")))throw fail("原类别层级存在循环",503);current=categories.get(current.getLong("pid"));if(current==null)throw fail("原类别父节点缺失，不能伪造类别绑定",503);}if(!key.equals(current.getString("key")))throw fail("原类别层级归属不一致",503);return row;}
    private List<JSONObject> tree(JSONObject selection) {
        Set<Long> chosen=null;if(selection!=null){chosen=new HashSet<>();for(String key:List.of("categorySystemIds","sceneSystemIds"))for(Object id:array(selection.get(key),key))chosen.add(positive(id,key));for(String key:List.of("languageSystemId","contextLengthSystemId"))if(selection.get(key)!=null)chosen.add(positive(selection.get(key),key));}
        var roots=new ArrayList<JSONObject>();for(var row:categories.values())if(row.getLongValue("pid")==0 && DIMENSIONS.contains(row.getString("key"))){var value=treeNode(row,chosen,new HashSet<>());if(selection==null || !value.getJSONArray("children").isEmpty())roots.add(value);}roots.sort(categoryOrder());return roots;
    }
    private JSONObject treeNode(JSONObject row,Set<Long> chosen,Set<Long> ancestors) {long id=row.getLongValue("id");if(!ancestors.add(id))throw fail("原类别层级循环",503);var children=new ArrayList<JSONObject>();for(var child:categories.values())if(child.getLongValue("pid")==id){var node=treeNode(child,chosen,new HashSet<>(ancestors));if(chosen==null || chosen.contains(child.getLong("id")) || !node.getJSONArray("children").isEmpty())children.add(node);}children.sort(categoryOrder());return JSONObject.of("id",id,"key",row.get("key"),"name",row.get("name"),"sortOrder",row.get("sortOrder"),"source","SYSTEM","children",children);}
    private static Comparator<JSONObject> categoryOrder(){return Comparator.comparingInt((JSONObject value)->value.getIntValue("sortOrder")).reversed().thenComparing(value->value.getLong("id"),Comparator.reverseOrder());}
    private static boolean hasCustomCategories(JSONObject c){var categories=c.getJSONObject("modelCategoryReq");return categories!=null && (categories.get("categoryCustom")!=null || categories.get("sceneCustom")!=null);}
    public static String reference(String value){String text=text(value,132,true,"凭据引用");if(!text.startsWith("env:"))text="env:"+text;if(!text.matches("env:[A-Z][A-Z0-9_]{2,127}"))throw fail("只能提交受控env别名，不能提交秘密或RSA密文",400);return text;}
    public static String endpoint(String value) {try{var uri=URI.create(text(value,2048,true,"endpoint"));if("wss".equals(uri.getScheme()))return ManagedModelRuntime.sparkEndpoint(value).toString();if(uri.getScheme()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)throw new IllegalArgumentException();String path=Objects.toString(uri.getRawPath(),"").replaceAll("/+$","");if(path.contains("..") || path.contains("//") || path.contains("%"))throw new IllegalArgumentException();if(!path.endsWith("/chat/completions"))path+=path.matches(".*/v\\d+")?"/chat/completions":"/v1/chat/completions";return new URI(uri.getScheme(),null,uri.getHost(),uri.getPort(),path,null,null).toASCIIString();}catch(Exception error){throw fail("模型endpoint无效",400);}}
    public static Long optionalRevision(Object value){return value==null?null:nonnegative(value,"revision");}
    public static long positive(Object value,String field){long result=number(value,field);if(result<1)throw fail(field+"须为正整数",400);return result;}
    private static long nonnegative(Object value,String field){long result=number(value,field);if(result<0)throw fail(field+"不能为负数",400);return result;}
    private static long number(Object value,String field){try{if(value==null || !(value instanceof String || value instanceof Number))throw new IllegalArgumentException();String text=value.toString();if(!text.matches("-?[0-9]{1,19}"))throw new IllegalArgumentException();return Long.parseLong(text);}catch(RuntimeException error){throw fail(field+"必须为整数",400);}}
    private static int intValue(Object value,int fallback,String field){if(value==null)return fallback;long parsed=number(value,field);if(parsed<Integer.MIN_VALUE || parsed>Integer.MAX_VALUE)throw fail(field+"超出范围",400);return(int)parsed;}
    private static void revision(ModelRecord row,Long expected){if(expected==null || !row.getRevision().equals(expected))throw fail("缺少或不匹配的模型revision，请刷新后重试",409);}
    private static void scope(JSONObject body,long space){if(body==null)throw fail("模型请求不能为空",400);if(body.get("spaceId")!=null && positive(body.get("spaceId"),"spaceId")!=space)throw fail("模型空间不一致",400);}
    private static void id(JSONObject body,long id){if(body!=null && body.get("id")!=null && positive(body.get("id"),"id")!=id)throw fail("模型ID不一致",400);}
    private static JSONArray array(Object value,String field){if(value==null)return new JSONArray();if(value instanceof JSONArray list)return JSON.parseArray(list.toJSONString());if(value instanceof List<?> list)return JSON.parseArray(JSON.toJSONString(list));throw fail(field+"必须为真实数组",400);}
    private static JSONObject config(ModelRecord row){return JSON.parseObject(row.getConfigurationJson());}
    private static JSONObject merge(ModelRecord row,JSONObject body){var result=config(row);result.putAll(copy(body));if(body.containsKey("apiKey") && body.get("apiKey")!=null && !body.containsKey("credentialRef"))result.remove("credentialRef");if(body.containsKey("name") && !body.containsKey("modelName"))result.remove("modelName");return result;}
    private static JSONObject copy(JSONObject value){if(value==null)throw fail("模型请求不能为空",400);return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    private static String text(String value,int max,boolean required,String field){if(required && (value==null || value.isBlank()))throw fail(field+"不能为空",400);if(value!=null && (value.length()>max || value.chars().anyMatch(ch->ch<32)))throw fail(field+"长度或字符无效",400);return value==null?null:value.trim();}
    private static ServiceException fail(String message,int code){return new ServiceException(message,code);}
}
