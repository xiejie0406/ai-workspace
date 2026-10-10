package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class RunService {
    public record View(String runId,long workflowVersionId,String state,int attempt,long lastEventSeq,long revision,
        String deadline,Object result) {}
    private final WorkflowService workflows;
    private final RunMapper runs;
    private final ResourceAccessService access;
    private final IdentityMappingService identities;
    private org.springframework.beans.factory.ObjectProvider<ModelOperationService> modelOperations;
    private org.springframework.beans.factory.ObjectProvider<CheckpointService> checkpoints;
    private org.springframework.beans.factory.ObjectProvider<RunVariableService> runtimeVariables;
    private org.springframework.beans.factory.ObjectProvider<DataOperationService> dataOperations;
    private org.springframework.beans.factory.ObjectProvider<ToolOperationService> toolOperations;
    @org.springframework.beans.factory.annotation.Autowired public void useToolOperations(org.springframework.beans.factory.ObjectProvider<ToolOperationService> provider){toolOperations=provider;}
    @org.springframework.beans.factory.annotation.Autowired public void useDataOperations(org.springframework.beans.factory.ObjectProvider<DataOperationService> provider){dataOperations=provider;}
    @org.springframework.beans.factory.annotation.Autowired public void useRuntimeVariables(org.springframework.beans.factory.ObjectProvider<RunVariableService> provider){runtimeVariables=provider;}
    @org.springframework.beans.factory.annotation.Autowired public void useCheckpoints(org.springframework.beans.factory.ObjectProvider<CheckpointService> provider){checkpoints=provider;}
    @org.springframework.beans.factory.annotation.Autowired public void useModelOperations(org.springframework.beans.factory.ObjectProvider<ModelOperationService> provider){modelOperations=provider;}
    public RunService(WorkflowService workflows,RunMapper runs,ResourceAccessService access,IdentityMappingService identities) {
        this.workflows=workflows;this.runs=runs;this.access=access;this.identities=identities;
    }
    private RunRecord permitted(String id,String permission,boolean lock) {
        access.requirePermission(permission);
        var run=runs.find(id);if(run==null)throw new ServiceException("运行不存在或不可访问",404);
        access.requireSpace(run.getSpaceId(),permission,3,lock);
        if(lock)run=runs.lock(id);
        if(run==null)throw new ServiceException("运行不存在或不可访问",404);RunJsonCodec.require(run);return run;
    }
    private static View view(RunRecord run) {
        RunJsonCodec.require(run);
        return new View(run.getId(),run.getWorkflowVersionId(),run.getState(),run.getAttempt(),run.getLastEventSeq(),run.getRevision(),
            run.getDeadline().toInstant(ZoneOffset.UTC).toString(),run.getResultJson()==null?null:JSON.parseObject(run.getResultJson()));
    }
    public View detail(String id) { return view(permitted(id,"paiflow:run:query",false)); }
    public List<JSONObject> events(String id,long after,int limit) {
        permitted(id,"paiflow:run:query",false);
        if(after<0 || limit<1 || limit>1000)throw new ServiceException("事件游标或条数无效",400);
        return runs.events(id,after,limit).stream().map(JSON::parseObject).toList();
    }
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public View create(long version,String engine,JSONObject input,String key,int timeoutSeconds) {
        return createInternal(version,engine,input,key,timeoutSeconds,2);
    }
    /** 环境由已核验的调试入口决定，普通请求不能以input/Worker字段改选正式数据。 */
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public View createDebug(long version,String engine,JSONObject input,String key,int timeoutSeconds) {
        return createInternal(version,engine,input,key,timeoutSeconds,1);
    }
    private View createInternal(long version,String engine,JSONObject input,String key,int timeoutSeconds,int dataEnvironment) {
        long actor=access.requirePermission("paiflow:run:create");
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,128}"))throw new ServiceException("请提供8至128位运行幂等标识",400);
        if(timeoutSeconds<1 || timeoutSeconds>3600)throw new ServiceException("运行截止时间须在1至3600秒内",400);
        if(input==null)throw new ServiceException("运行输入须为对象",400);RunJsonBoundary.check(input,RunProtocolLimits.INPUT_BYTES);
        var request=JSONObject.of("workflowVersionId",version,"engineProfile",engine,"input",input,"timeoutSeconds",timeoutSeconds);
        String hash=RunJsonBoundary.hashFull(request);var existing=runs.byRequest(actor,key);
        if(existing!=null) {
            var oldTask=JSON.parseObject(existing.getAssignmentJson());
            if(dataEnvironment==1 && hasData(oldTask.getJSONObject("resourceSnapshots")))request.put("dataEnvironment",1);
            if(!RunJsonCodec.hash(existing,request).equals(existing.getInputHash()))throw new ServiceException("幂等标识已用于不同的运行请求",409);
            access.requireSpace(existing.getSpaceId(),"paiflow:run:create",3,false);
            return view(existing);
        }
        var snapshot=workflows.runtimeSnapshot(version);
        boolean needsData=hasData(snapshot.resourceSnapshots());
        if(needsData && dataEnvironment==1){request.put("dataEnvironment",1);hash=RunJsonBoundary.hashFull(request);}
        if(!snapshot.engineProfile().equals(engine))throw new ServiceException("执行器必须与不可变版本一致",409);
        // 已固定版本只核对中心保存的原字节；不能按当前编译规则重新解释历史执行副本。
        try {
            byte[] fixedBytes=java.util.Base64.getDecoder().decode(snapshot.canonicalDslBase64());
            String fixedHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixedBytes));
            if(!snapshot.dslHash().equals(fixedHash) || !snapshot.execution().equals(JSON.parseObject(new String(fixedBytes,java.nio.charset.StandardCharsets.UTF_8))))
                throw new ServiceException("工作流版本快照校验失败",409);
        } catch(RuntimeException | java.security.NoSuchAlgorithmException ex) {
            throw new ServiceException("工作流版本快照校验失败",409);
        }
        var validation=new DslValidationService().validate(snapshot.execution(),engine);
        if(!validation.valid())throw new ServiceException("工作流结构或引擎不兼容，不能排队",400);
        // 资源节点必须经真实中心资源快照解析；缺适配时拒绝，不能以空配置运行或宣称21节点已就绪。
        Set<String> pure=engine.equals("java")?Set.of("node-start","node-end"):Set.of("node-start","node-end","if-else","text-joiner","message","node-variable");
        Set<String> iterationTypes=Set.of("iteration","iteration-node-start","iteration-node-end");
        boolean pureIteration="python".equals(engine) && snapshot.execution().getJSONArray("nodes").stream()
            .anyMatch(raw->iterationTypes.contains(com.ruoyi.paiflow.domain.NodeCatalog.typeOf(((JSONObject)raw).getString("id"))));
        if(pureIteration)PureIterationPolicy.validate(snapshot.execution(),input);
        boolean v4=DslCodec.V4.equals(snapshot.executionCodec());
        if(v4) {
            if(!"python".equals(engine))throw new ServiceException("V4资源执行图只接Python原执行器",503);
            DynamicModelGraphPolicy.validate(snapshot.execution());
        }
        boolean qa=WorkflowModelCompiler.containsStaticQuestionAnswer(snapshot.execution());
        if(qa) {
            if(!"python".equals(engine) || !v4 && !DslCodec.V3.equals(snapshot.executionCodec()))throw new ServiceException("旧问答执行副本尚未编译为问答版本",503);
            if(!v4) {
                StaticQuestionAnswerPolicy.validate(snapshot.execution());
                if(snapshot.resourceSnapshots()==null || !snapshot.resourceSnapshots().isEmpty())throw new ServiceException("静态问答不能绑定资源",503);
            }
            if(checkpoints==null || checkpoints.getIfAvailable()==null)throw new ServiceException("中心问答检查点尚未装配",503);
        }
        boolean needsModel=false,needsVariableWrite=false,needsTool=false;
        for(Object raw:snapshot.execution().getJSONArray("nodes")) {
            var node=(JSONObject)raw;String type=node.getString("id").split("::",2)[0];
            needsTool|="plugin".equals(type);
            if("node-variable".equals(type)) {
                if(runtimeVariables==null || runtimeVariables.getIfAvailable()==null)throw new ServiceException("中心历史/变量存储尚未启用，请先完成021/022迁移及配置",503);
                var parameters=node.getJSONObject("data")==null?null:node.getJSONObject("data").getJSONObject("nodeParam");
                if(parameters==null || !Set.of("set","get").contains(parameters.getString("method")))throw new ServiceException("变量节点方法无效",422);
                needsVariableWrite|="set".equals(parameters.get("method"));
            }
            if(Set.of("decision-making","extractor-parameter").contains(type) && !v4)throw new ServiceException("动态模型节点须显式建立V4固定版本",503);
            if(qa && "question-answer".equals(type) && WorkflowModelCompiler.isModelNode(node))needsModel=true;
            if(!pure.contains(type) && !(pureIteration && iterationTypes.contains(type)) && !(qa && "question-answer".equals(type))){if("python".equals(engine) && Set.of("spark-llm","decision-making","extractor-parameter").contains(type) || "java".equals(engine) && DslCodec.V2.equals(snapshot.executionCodec()) && "spark-llm".equals(type))needsModel=true;else if("python".equals(engine) && "database".equals(type) && needsData) { /* 固定目录与实际用途由数据Service再次准入。 */ } else if("plugin".equals(type)&&("python".equals(engine)&&v4||"java".equals(engine)&&DslCodec.V2.equals(snapshot.executionCodec()))){ /* 工具域准入重核固定节点、用途及成员。 */ }else throw new ServiceException("节点"+node.getString("id")+"的中心资源/运行适配尚未接通",503);}
        }
        RunJsonBoundary.checkSchemaAware(snapshot.execution(),16*1024*1024);
        var now=LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var run=new RunRecord();run.setId(UUID.randomUUID().toString());
        run.setWorkflowId(snapshot.workflowId());run.setWorkflowVersionId(version);run.setSpaceId(snapshot.spaceId());run.setOwnerUserId(actor);
        run.setEngineProfile(engine);run.setState("QUEUED");run.setRunJsonCodec(RunJsonCodec.V2);run.setInputHash(hash);run.setIdempotencyKey(key);run.setDeadline(now.plusSeconds(timeoutSeconds));
        run.setAttempt(0);run.setLastEventSeq(0L);run.setRevision(0L);run.setCreateBy(identities.current().getUsername());
        var effects=JSONObject.of("mode","read_only","allowedOperations",new com.alibaba.fastjson2.JSONArray());var resources=snapshot.resourceSnapshots()==null?new JSONObject():snapshot.resourceSnapshots();
        if(needsModel){var service=modelOperations==null?null:modelOperations.getIfAvailable();if(service==null)throw new ServiceException("中心模型运行适配尚未装配",503);resources=snapshot.resourceSnapshots();
            var permit=service.admit(run.getId(),actor,snapshot.spaceId(),version,snapshot.dslHash(),input,resources,snapshot.execution(),run.getDeadline().toInstant(ZoneOffset.UTC),engine);
            run.setModelPermitJson(ModelOperationJson.canonical(permit,ModelOperationJson.REQUEST_BYTES));effects=JSONObject.of("mode","approved_write","allowedOperations",new com.alibaba.fastjson2.JSONArray(java.util.List.of("model.invoke")),"approvalRef",permit.getString("approvalRef"));}
        if(needsVariableWrite) {
            effects.put("mode","approved_write");effects.getJSONArray("allowedOperations").add("variables.set");
            effects.putIfAbsent("approvalRef","center-variable:"+run.getId());
        }
        if(needsData) {
            var service=dataOperations==null?null:dataOperations.getIfAvailable();
            if(service==null)throw new ServiceException("中心数据运行端口尚未启用，请先完成026迁移及配置",503);
            var allowed=service.admit(snapshot.spaceId(),actor,resources,snapshot.execution(),dataEnvironment);
            effects.getJSONArray("allowedOperations").addAll(allowed);
            if(allowed.contains("data.mutate")){effects.put("mode","approved_write");effects.putIfAbsent("approvalRef","center-data:"+run.getId());}
        }
        if(needsTool){var service=toolOperations==null?null:toolOperations.getIfAvailable();if(service==null)throw new ServiceException("工具中心尚未装配，请完成030及用途配置",503);var allowed=service.admit(snapshot.spaceId(),actor,resources,snapshot.execution(),engine);effects.getJSONArray("allowedOperations").addAll(allowed);if(allowed.contains("tools.invoke")){effects.put("mode","approved_write");effects.putIfAbsent("approvalRef","center-tool:"+run.getId());}}
        var task=JSONObject.of("schemaVersion","paiflow.worker.v1","runId",run.getId(),"workflowVersionId",Long.toString(version),
            "dslHash",snapshot.dslHash(),"dslCanonicalBase64",snapshot.canonicalDslBase64(),"engineProfile",engine,"input",input,
            "resourceSnapshots",resources,"deadline",run.getDeadline().toInstant(ZoneOffset.UTC).toString(),
            "effectPolicy",effects,"dsl",snapshot.execution());
        if(needsData)task.put("dataEnvironment",dataEnvironment);
        if(v4)task.put("executionCodec",DslCodec.V4);
        // 当前两客户端默认响应预算2MiB，预留规范字节/信封空间；过大明确拒绝，不派发必定失败的任务。
        RunJsonBoundary.checkSchemaAware(task,RunProtocolLimits.ASSIGNMENT_BYTES);
        run.setAssignmentJson(RunJsonCodec.canonical(run,task));
        // 审批或空间锁等待后重新读取原平台权限及成员关系，再保存当前主体的运行。
        access.requireSpace(snapshot.spaceId(),"paiflow:run:create",3,true);
        if(access.requirePermission("paiflow:run:create")!=actor)throw new ServiceException("运行创建主体已变化",403);
        var savingAt=java.time.Instant.now();
        if(!run.getDeadline().toInstant(ZoneOffset.UTC).isAfter(savingAt) ||
            (needsModel && !java.time.Instant.parse(JSON.parseObject(run.getModelPermitJson()).getString("expiresAt")).isAfter(savingAt)))
            throw new ServiceException("运行创建许可或截止时间已过期",403);
        try { if(runs.insert(run)!=1)throw new ServiceException("运行保存失败",500); }
        catch(DuplicateKeyException ex) { throw new ServiceException("重复运行请求，请按相同幂等标识查询",409); }
        return view(run);
    }
    private static boolean hasData(JSONObject resources){return resources!=null && resources.keySet().stream().anyMatch(key->key.startsWith("data:"));}
    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public View cancel(String id) {
        var run=permitted(id,"paiflow:run:cancel",true);long actor=identities.current().getUserId();
        if(actor!=run.getOwnerUserId())access.requireSpace(run.getSpaceId(),"paiflow:run:cancel",2,true);
        if(Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN").contains(run.getState()))return view(run);
        if("QUEUED".equals(run.getState())) {
            run.setState("CANCELLED");var result=JSONObject.of("state","CANCELLED","reason","cancelled_before_dispatch");
            run.setResultJson(RunJsonCodec.canonical(run,result));run.setResultHash(RunJsonCodec.hash(run,result));
            var event=RunLeaseService.terminalEvent(run,"CANCELLED",java.time.Instant.now(),result);
            if(runs.insertEvent(id,run.getLastEventSeq()+1,RunJsonCodec.canonical(run,event))!=1)throw new ServiceException("取消事件保存失败",500);
            run.setLastEventSeq(run.getLastEventSeq()+1);
        } else run.setState("CANCEL_REQUESTED");
        if(runs.update(run)!=1)throw new ServiceException("运行版本已变化",409);run.setRevision(run.getRevision()+1);return view(run);
    }
}
