package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.domain.ModelOperationRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.ModelOperationMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 中心保存固定调用、一次派发和原始流事实；凭据提取/SDK派发/停止均在事务外。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ModelOperationService implements ProducerCallbacks {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private final ModelOperationMapper operations;
    private final RunMapper runs;
    private final SpaceMapper spaces;
    private final RunLeaseService leases;
    private final ModelRuntimeCatalog catalog;
    private final Clock clock;
    private CompletedOutputSources completedSources;
    @Autowired public void useCompletedSources(CompletedOutputSources value){completedSources=Objects.requireNonNull(value);}
    private ObjectProvider<RunHistoryService> histories;
    @Autowired public void useHistory(ObjectProvider<RunHistoryService> value){histories=value;}
    private RunHistoryService history(){return histories==null?null:histories.getIfAvailable();}
    private ObjectProvider<InvocationPolicy> policies;
    private ObjectProvider<CredentialMaterialPort> credentials;
    private ObjectProvider<ProducerBridge> bridges;
    private ObjectProvider<ModelOperationService> selves;
    private org.mybatis.spring.SqlSessionTemplate sessions;
    private com.ruoyi.paiflow.mapper.CheckpointMapper checkpoints;
    @Autowired(required=false) public void useCheckpoints(com.ruoyi.paiflow.mapper.CheckpointMapper mapper){checkpoints=mapper;}
    @Autowired public void useCurrentSession(org.mybatis.spring.SqlSessionTemplate template){sessions=template;}
    @Autowired public ModelOperationService(ModelOperationMapper operations,RunMapper runs,SpaceMapper spaces,RunLeaseService leases,ModelRuntimeCatalog catalog){this(operations,runs,spaces,leases,catalog,Clock.systemUTC());}
    ModelOperationService(ModelOperationMapper operations,RunMapper runs,SpaceMapper spaces,RunLeaseService leases,ModelRuntimeCatalog catalog,Clock clock){this.operations=operations;this.runs=runs;this.spaces=spaces;this.leases=leases;this.catalog=catalog;this.clock=clock;}
    @Autowired public void usePorts(ObjectProvider<InvocationPolicy> policy,ObjectProvider<CredentialMaterialPort> credential,ObjectProvider<ProducerBridge> bridge,ObjectProvider<ModelOperationService> self){policies=policy;credentials=credential;bridges=bridge;selves=self;}
    private InvocationPolicy policy(){var p=policies==null?null:policies.getIfAvailable();if(p==null)throw unavailable();return p;}
    private CredentialMaterialPort credential(){var p=credentials==null?null:credentials.getIfAvailable();if(p==null)throw unavailable();return p;}
    private ProducerBridge bridge(){var p=bridges==null?null:bridges.getIfAvailable();if(p==null)throw unavailable();return p;}
    private ModelOperationService self(){var p=selves==null?null:selves.getIfAvailable();if(p==null)throw unavailable();return p;}
    private static com.ruoyi.common.exception.ServiceException unavailable(){return error("模型运行许可、凭据或受控Producer尚未装配",503);}
    private Instant now(){return clock.instant();}
    private static <T>T local(java.util.function.Supplier<T> read){try{return read.get();}catch(com.ruoyi.common.exception.ServiceException ex){int status=ex.getCode()==null?503:ex.getCode();throw error("模型本地许可或配置核对未通过",status>=400 && status<500?status:503);}catch(RuntimeException ex){throw unavailable();}}
    private static LocalDateTime date(Instant instant){return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);}
    private static Instant instant(LocalDateTime value){return value.toInstant(ZoneOffset.UTC);}
    private static void outsideTransaction(){if(TransactionSynchronizationManager.isActualTransactionActive())throw error("模型私有派发不能在数据库事务内执行",500);}
    private void ports(ModelRuntimeCatalog.FixedModel fixed){policy();self();if(!local(()->credential().available(fixed)) || !local(()->bridge().available(fixed)))throw unavailable();}
    private static JSONObject task(RunRecord run){return ModelMessageSources.sourceObject(run.getAssignmentJson());}
    private static JSONObject node(JSONObject dsl,String nodeId){
        for(Object raw:dsl.getJSONArray("nodes"))if(raw instanceof JSONObject n && Objects.equals(nodeId,n.getString("id")))return n;
        throw error("模型节点不属于固定运行版本",409);
    }
    private static JSONObject descriptor(JSONObject resources,String resource){Object value=resources==null?null:resources.get(resource);if(!(value instanceof JSONObject object))throw error("模型资源没有固定快照",409);return object;}
    private ModelRuntimeCatalog.FixedModel fixed(RunRecord run,String resource){return catalog.resolvePinned(run.getSpaceId(),run.getOwnerUserId(),resource,descriptor(task(run).getJSONObject("resourceSnapshots"),resource));}
    private static String fingerprint(JSONObject value){return sha(canonical(value,REQUEST_BYTES));}
    private static JSONObject runFacts(RunCandidate c){var facts=JSONObject.of("runId",c.runId(),"ownerUserId",c.ownerUserId(),"spaceId",c.spaceId(),"workflowVersionId",c.workflowVersionId(),"dslHash",c.dslHash(),"inputJson",c.inputJson(),"resourcesJson",c.resourcesJson(),"executionJson",c.executionJson(),"deadline",c.deadline().toString());if(!"python".equals(c.engineProfile()))facts.put("engineProfile",c.engineProfile());return facts;}
    private static RunCandidate candidate(String id,long owner,long space,long version,String hash,JSONObject input,JSONObject resources,JSONObject dsl,Instant deadline){
        return candidate(id,owner,space,version,hash,input,resources,dsl,deadline,"python");
    }
    private static RunCandidate candidate(String id,long owner,long space,long version,String hash,JSONObject input,JSONObject resources,JSONObject dsl,Instant deadline,String engine){
        if(!Set.of("python","java").contains(engine))throw error("模型运行引擎无效",409);
        var base=new RunCandidate(id,owner,space,version,hash,canonical(input,REQUEST_BYTES),canonical(resources,REQUEST_BYTES),ModelOperationJson.canonicalWorkflow(dsl,REQUEST_BYTES),deadline,engine,null);
        return new RunCandidate(id,owner,space,version,hash,base.inputJson(),base.resourcesJson(),base.executionJson(),deadline,engine,fingerprint(runFacts(base)));
    }
    private static RunCandidate runCandidate(RunRecord run){var t=task(run);return candidate(run.getId(),run.getOwnerUserId(),run.getSpaceId(),run.getWorkflowVersionId(),t.getString("dslHash"),t.getJSONObject("input"),t.getJSONObject("resourceSnapshots"),t.getJSONObject("dsl"),instant(run.getDeadline()),run.getEngineProfile());}
    private static JSONObject permitJson(RunPermit permit){return JSONObject.of("fingerprint",permit.fingerprint(),"approvalRef",permit.approvalRef(),"expiresAt",permit.expiresAt().toString());}
    private RunPermit runPermit(RunRecord run){
        var value=JSON.parseObject(run.getModelPermitJson());if(value==null)throw unavailable();
        try{return new RunPermit(value.getString("fingerprint"),text(value.get("approvalRef"),512,"approvalRef"),Instant.parse(value.getString("expiresAt")));}catch(RuntimeException ex){throw error("中心模型运行许可无效",409);}
    }
    private void currentRun(RunRecord run){var c=runCandidate(run);var p=runPermit(run);if(!Objects.equals(c.fingerprint(),p.fingerprint()) || !p.expiresAt().isAfter(now()) || !local(()->policy().currentRun(c,p)) || !p.expiresAt().isAfter(now()) || !c.deadline().isAfter(now()))throw error("模型运行许可已过期或撤销",403);}
    /** 调用方已有空间事务；这里只读批准事实，不进行Provider探测或提取明文。 */
    public JSONObject admit(String runId,long owner,long space,long version,String hash,JSONObject input,JSONObject resources,JSONObject dsl,Instant deadline){
        return admit(runId,owner,space,version,hash,input,resources,dsl,deadline,"python");
    }
    public JSONObject admit(String runId,long owner,long space,long version,String hash,JSONObject input,JSONObject resources,JSONObject dsl,Instant deadline,String engine){
        if(!Set.of("python","java").contains(engine))throw error("模型运行引擎无效",409);
        if("java".equals(engine))JavaToolGraphPolicy.validate(dsl);
        if(resources==null || resources.isEmpty())throw unavailable();
        Set<String> used=new HashSet<>();
        for(Object raw:dsl.getJSONArray("nodes")){
            var n=(JSONObject)raw;String id=n.getString("id"),type=id.split("::",2)[0];
            // 数据和工具由RunService分别调用对应中心准入；模型许可不授予这些节点的副作用。
            if(Set.of("node-start","node-end","if-else","text-joiner","message","database","plugin","node-variable").contains(type))continue;
            if("question-answer".equals(type) && !WorkflowModelCompiler.isModelNode(n)){StaticQuestionAnswerOptions.effective(n.getJSONObject("data").getJSONObject("nodeParam"));continue;}
            if(!WorkflowModelCompiler.isModelNode(n) || n.getJSONObject("data")==null)throw unavailable();
            var param=n.getJSONObject("data").getJSONObject("nodeParam");ModelOperationOptions.forNode(n,engine);
            String resource=text(param.get("modelResourceId"),128,"modelResourceId");
            var f=catalog.resolvePinned(space,owner,resource,descriptor(resources,resource));if("java".equals(engine)&&(!"openai".equals(f.provider())||!f.capabilities().contains("chat")))throw error("Java固定模型未批准原OpenAI chat能力",503);if("python".equals(engine)&&"function_call".equals(ModelNodeSemantics.mode(n)) && !f.capabilities().contains("function_call"))throw error("固定模型未批准FunctionCall能力",503);ports(f);used.add(resource);
        }
        var modelKeys=resources.keySet().stream().filter(key->key.startsWith("model:")).collect(java.util.stream.Collectors.toSet());
        if(used.isEmpty() || !used.equals(modelKeys))throw error("模型快照与固定节点引用不一致",409);
        var c=candidate(runId,owner,space,version,hash,input,resources,dsl,deadline,engine);var p=local(()->policy().approveRun(c));
        if(p==null || !Objects.equals(c.fingerprint(),p.fingerprint()) || p.expiresAt()==null || !p.expiresAt().isAfter(now()) || p.expiresAt().isAfter(deadline))throw error("未取得准确模型运行许可",403);
        text(p.approvalRef(),255,"approvalRef");if(!local(()->policy().currentRun(c,p)))throw error("模型运行许可已撤销",403);
        for(String id:used)catalog.resolvePinned(space,owner,id,descriptor(resources,id));
        if(!p.expiresAt().isAfter(now()) || !deadline.isAfter(now()))throw error("模型运行许可已过期",403);return permitJson(p);
    }
    /** 固定锁顺序空间→运行→操作；当前权限由现有租约服务和目录重新核对。 */
    private RunRecord workerRun(String id,WorkerIdentityService.Principal worker,String lease,int attempt,boolean active){
        if(sessions!=null)sessions.clearCache();
        var before=runs.find(id);if(before==null)throw error("运行不存在",404);
        if(spaces.lock(before.getSpaceId())==null)throw error("运行空间不存在",403);
        var run=active?leases.requireActiveLease(id,worker,lease,attempt):leases.lockWorkerRun(id,worker,lease,attempt);
        if(!Objects.equals(before.getSpaceId(),run.getSpaceId()))throw error("运行空间已变化",409);
        RunLeaseService.requireExecutionBegun(run);return run;
    }
    private void callable(RunRecord run){if(!"RUNNING".equals(run.getState()))throw error("运行不能继续模型调用",410);currentRun(run);}
    private void save(ModelOperationRecord row){if(operations.update(row)!=1)throw error("模型操作版本已变化",409);row.setRevision(row.getRevision()+1);}
    private static JSONObject binding(ModelOperationRecord row){return JSONObject.of("runId",row.getRunId(),"leaseId",row.getLeaseId(),"attempt",row.getAttempt(),"nodeId",row.getNodeId(),"iterationId",row.getIterationId(),"workflowVersionId",row.getWorkflowVersionId().toString(),"dslHash",row.getDslHash(),"operationId",row.getId(),"resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider",row.getProvider());}
    private static JSONObject startAck(ModelOperationRecord row){var result=binding(row);result.put("state","RUNNING");return result;}
    private ModelOperationRecord owned(String operation,RunRecord run){var row=operations.lock(operation);if(row==null || !Objects.equals(row.getRunId(),run.getId()) || !Objects.equals(row.getLeaseId(),run.getLeaseId()) || !Objects.equals(row.getAttempt(),run.getAttempt()) || !Objects.equals(row.getOwnerUserId(),run.getOwnerUserId()) || !Objects.equals(row.getWorkflowVersionId(),run.getWorkflowVersionId()) || !Objects.equals(row.getDslHash(),task(run).getString("dslHash")))throw error("模型操作不属于当前运行租约",409);return row;}
    private static JSONObject callFacts(CallCandidate c){var facts=JSONObject.of("runId",c.runId(),"leaseId",c.leaseId(),"attempt",c.attempt(),"ownerUserId",c.ownerUserId(),"spaceId",c.spaceId(),"workflowVersionId",c.workflowVersionId(),"dslHash",c.dslHash(),"nodeId",c.nodeId(),"iterationId",c.iterationId(),"resourceId",c.resourceId(),"resourceVersion",c.resourceVersion(),"provider",c.provider(),"parametersJson",c.parametersJson(),"parametersHash",c.parametersHash(),"inputJson",c.inputJson(),"nodeJson",c.nodeJson(),"eventsJson",c.eventsJson(),"runApprovalRef",c.runApprovalRef(),"deadline",c.deadline().toString());if(!"[]".equals(c.checkpointSourcesJson()))facts.put("checkpointSourcesJson",c.checkpointSourcesJson());if(c.verifiedInputsJson()!=null)facts.put("verifiedInputsJson",c.verifiedInputsJson());if(c.javaHistoryJson()!=null)facts.put("javaHistoryJson",c.javaHistoryJson());if(c.pythonHistoryJson()!=null)facts.put("pythonHistoryJson",c.pythonHistoryJson());if(!"python".equals(c.engineProfile()))facts.put("engineProfile",c.engineProfile());return facts;}
    private static CallCandidate callCandidate(JSONObject facts){
        String engine=facts.getString("engineProfile");if(engine==null)engine="python";if(!Set.of("python","java").contains(engine))throw error("模型私有候选引擎无效",403);
        var c=new CallCandidate(facts.getString("runId"),facts.getString("leaseId"),facts.getIntValue("attempt"),facts.getLongValue("ownerUserId"),facts.getLongValue("spaceId"),facts.getLongValue("workflowVersionId"),facts.getString("dslHash"),facts.getString("nodeId"),facts.getString("iterationId"),facts.getString("resourceId"),facts.getString("resourceVersion"),facts.getString("provider"),facts.getString("parametersJson"),facts.getString("parametersHash"),facts.getString("inputJson"),facts.getString("nodeJson"),facts.getString("eventsJson"),facts.getString("runApprovalRef"),Instant.parse(facts.getString("deadline")),facts.getString("checkpointSourcesJson")==null?"[]":facts.getString("checkpointSourcesJson"),facts.getString("verifiedInputsJson"),facts.getString("javaHistoryJson"),facts.getString("pythonHistoryJson"),engine,fingerprint(facts));return c;
    }
    private void currentCall(ModelOperationRecord row){
        var json=JSON.parseObject(row.getApprovalJson());var c=callCandidate(json.getJSONObject("candidate"));var p=new CallPermit(json.getString("fingerprint"),json.getString("approvalRef"),Instant.parse(json.getString("expiresAt")));
        var run=runs.find(row.getRunId());if(run==null||!Objects.equals(run.getEngineProfile(),c.engineProfile()))throw error("模型私有候选引擎偏离固定运行",403);
        if(!Objects.equals(c.fingerprint(),p.fingerprint()) || !Objects.equals(row.getApprovalRef(),p.approvalRef()) || !Objects.equals(row.getParametersJson(),c.parametersJson()) || !Objects.equals(row.getRequestHash(),json.getString("requestHash")) || !p.expiresAt().isAfter(now()) || !instant(row.getDeadline()).isAfter(now()) || !local(()->policy().currentCall(c,p)) || !p.expiresAt().isAfter(now()) || !instant(row.getDeadline()).isAfter(now()))throw error("模型逐次调用许可已过期或撤销",403);
    }
    private static String providerUid(ModelOperationRecord row){var facts=JSON.parseObject(row.getApprovalJson()).getJSONObject("candidate");return ModelNodeSemantics.providerUid(ModelMessageSources.sourceObject(facts.getString("nodeJson")));}
    private JSONArray qaSources(RunRecord run,JSONObject node,JSONObject parameters){
        if(checkpoints==null)throw error("动态问答中心检查点读取端口缺失",503);String checkpointId=parameters.getString("checkpointId");var current=checkpoints.find(checkpointId);
        if(current==null || !Objects.equals(current.getRunId(),run.getId()) || !Objects.equals(current.getLeaseId(),run.getLeaseId()) || !Objects.equals(current.getAttempt(),run.getAttempt()) || !Objects.equals(current.getNodeId(),node.getString("id")) || current.getIterationId()!=null || !"CONSUMED".equals(current.getState()))throw error("模型调用没有当前已消费问答回复",403);
        int max=node.getJSONObject("data").getJSONObject("nodeParam").getJSONObject("directAnswer").getIntValue("maxRetryCounts");if(current.getOrdinal()==null || current.getOrdinal()<1 || current.getOrdinal()>max)throw error("动态问答轮次超出固定节点",403);
        var records=operations.forRunResource(run.getId(),parameters.getString("resourceId"));if(records.size()>1000)throw error("动态问答原调用来源超出预算",503);
        for(var prior:records)if(Objects.equals(node.getString("id"),prior.getNodeId()) && Objects.equals(run.getAttempt(),prior.getAttempt()) && Objects.equals(checkpointId,JSON.parseObject(prior.getParametersJson()).getString("checkpointId")))throw error("已消费问答回复已有模型操作，不重复签发费用",409);
        var sources=new JSONArray();
        for(int ordinal=1;ordinal<=current.getOrdinal();ordinal++){
            var cp=checkpoints.byPosition(run.getId(),run.getAttempt(),node.getString("id"),null,ordinal);
            if(cp==null || !Objects.equals(cp.getRunId(),run.getId()) || !Objects.equals(cp.getLeaseId(),run.getLeaseId()) || !Objects.equals(cp.getAttempt(),run.getAttempt()) || !Objects.equals(cp.getNodeId(),node.getString("id")) || cp.getIterationId()!=null || !"CONSUMED".equals(cp.getState()))throw error("动态问答缺少连续真实消费记录",403);
            var resume=JSON.parseObject(cp.getResumeDataJson());var message=resume==null?null:JSON.parseObject(resume.getString("message"));var question=JSON.parseObject(cp.getQuestionJson());
            if(message==null || !"resume".equals(message.getString("event_type")) || !(message.get("content") instanceof String) || question==null || !"direct".equals(question.get("type")) || !(question.get("content") instanceof String))throw error("动态问答没有原合法resume回复",403);
            var source=JSONObject.of("checkpointId",cp.getId(),"ordinal",ordinal,"question",question.get("content"),"reply",message.get("content"));
            if(ordinal<current.getOrdinal()){
                ModelOperationRecord completed=null;for(var row:records)if(Objects.equals(node.getString("id"),row.getNodeId()) && Objects.equals(run.getLeaseId(),row.getLeaseId()) && Objects.equals(run.getAttempt(),row.getAttempt()) && Objects.equals(cp.getId(),JSON.parseObject(row.getParametersJson()).getString("checkpointId"))){if(completed!=null)throw error("同一问答回复存在多个模型操作",409);completed=row;}
                if(completed==null || !"COMPLETED".equals(completed.getState()) || !"stop".equals(completed.getFinishReason()) || completed.getFinishSeq()==null || completed.getLastSeq()==null || completed.getFinishSeq()>completed.getLastSeq())throw error("问答重问缺少上轮已确认模型终态",403);
                source.put("priorModelOutput",modelText(completed));
            }
            sources.add(source);
        }
        if(!Objects.equals(current.getId(),sources.getJSONObject(sources.size()-1).get("checkpointId")))throw error("动态问答回复位置不一致",403);return sources;
    }
    private String modelText(ModelOperationRecord row){var frames=operations.frames(row.getId(),0,MAX_FRAMES);if(frames.size()!=row.getLastSeq())throw error("问答先前模型原帧不完整",403);var result=new StringBuilder();long expected=0;
        for(var frame:frames){if(frame.getSeq()!=++expected)throw error("问答先前模型帧序号不连续",403);var raw=JSON.parseObject(frame.getFrameJson());String part=null;
            if("openai".equals(row.getProvider())){var choices=raw.getJSONArray("choices");if(choices!=null && !choices.isEmpty()){var delta=choices.getJSONObject(0).getJSONObject("delta");if(delta!=null && delta.get("content") instanceof String s)part=s;}}
            else{var payload=raw.getJSONObject("payload");var choices=payload==null?null:payload.getJSONObject("choices");var text=choices==null?null:choices.getJSONArray("text");if(text!=null && !text.isEmpty() && text.getJSONObject(0).get("content") instanceof String s)part=s;}
            if(part!=null)result.append(part);if(result.length()>REQUEST_BYTES)throw error("问答先前模型文本超出消息预算",413);
        }return result.toString();
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public ModelOperationRecord prepare(String id,WorkerIdentityService.Principal worker,JSONObject request){
        RunJsonBoundary.fields(request,Set.of("leaseId","attempt","nodeId","iterationId","operation","parameters","idempotencyKey"),Set.of());canonical(request,REQUEST_BYTES);
        String lease=text(request.get("leaseId"),64,"leaseId"),nodeId=text(request.get("nodeId"),255,"nodeId"),key=text(request.get("idempotencyKey"),4096,"idempotencyKey");
        if(key.getBytes(StandardCharsets.UTF_8).length>16384)throw error("模型幂等标识超出字节范围",400);
        if(request.get("iterationId")!=null || !"model.invoke".equals(request.get("operation")) || !(request.get("parameters") instanceof JSONObject params))throw error("当前模型操作或迭代范围不支持",503);
        var run=workerRun(id,worker,lease,RunLeaseService.attempt(request),true);callable(run);
        String engine=run.getEngineProfile();if(!Set.of("python","java").contains(engine))throw unavailable();
        var n=node(task(run).getJSONObject("dsl"),nodeId);if(!WorkflowModelCompiler.isModelNode(n) || !nodeId.startsWith("spark-llm::") && !DslCodec.V4.equals(task(run).getString("executionCodec")))throw unavailable();if("java".equals(engine)&&!DslCodec.V2.equals(task(run).getOrDefault("executionCodec",DslCodec.V2)))throw unavailable();ModelOperationOptions.validateRequest(params,n,engine);
        String resource=text(params.get("resourceId"),128,"resourceId");var f=fixed(run,resource);ports(f);
        if(!Objects.equals(resource,n.getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId")) || !Objects.equals(params.get("resourceVersion"),String.valueOf(f.modelVersionId())) || !Objects.equals(params.get("provider"),f.provider()) || !Objects.equals(params.get("modelName"),f.domain()))throw error("模型调用未绑定固定版本",409);
        String requestHash=fingerprint(request),keyHash=sha(key);var old=operations.byRequest(id,run.getAttempt(),keyHash);
        if(old!=null){old=owned(old.getId(),run);if(!Objects.equals(key,old.getRequestKey()) || !Objects.equals(requestHash,old.getRequestHash()))throw error("模型幂等标识已用于不同请求",409);currentCall(old);workerRun(id,worker,lease,run.getAttempt(),true);return old;}
        var rows=runs.eventsThrough(id,0,run.getLastEventSeq(),1001);if(rows.size()>1000)throw error("模型消息来源事件超出当前批准范围",503);
        var events=new JSONArray();for(var row:rows){var event=ModelMessageSources.sourceObject(row.eventJson());if(!Objects.equals(id,event.get("runId"))||event.getLongValue("seq")!=row.seq())throw error("模型来源事件偏离当前中心行事实",403);if(!Objects.equals(run.getAttempt(),event.getInteger("attempt")))continue;events.add(event);}
        if(completedSources==null)throw error("模型完成输出来源校核未装配",503);
        var verifiedInputs=completedSources.values(run,task(run).getJSONObject("dsl"),n,events);
        JSONObject javaHistory=null,pythonHistory=null;
        if("java".equals(engine)){
            var history=history();
            if(history!=null&&history.executionContext(run)!=null){javaHistory=history.javaSnapshot(run,n,verifiedInputs);if(!ToolSchema.same(javaHistory.get("messages"),params.get("messages")))throw error("Java模型消息偏离中心冻结历史",403);}
            else if(JavaModelSemantics.historyLimit(n)!=0)throw error("Java模型运行未绑定中心历史上下文",503);
        }else if(PythonModelHistorySemantics.legacy(n)){
            PythonModelHistorySemantics.requireLegacy(n);var history=history();
            if(history==null || history.executionContext(run)==null)throw error("Python模型运行未绑定中心历史上下文",503);
            pythonHistory=history.pythonSnapshot(run,n);
        }
        String parameters=canonical(params,REQUEST_BYTES);var t=task(run);
        var base=new CallCandidate(id,lease,run.getAttempt(),run.getOwnerUserId(),run.getSpaceId(),run.getWorkflowVersionId(),t.getString("dslHash"),nodeId,null,resource,String.valueOf(f.modelVersionId()),f.provider(),parameters,sha(parameters),canonical(t.getJSONObject("input"),REQUEST_BYTES),ModelOperationJson.canonicalNode(n,REQUEST_BYTES),canonical(events,REQUEST_BYTES),runPermit(run).approvalRef(),instant(run.getDeadline()),null);
        var facts=callFacts(base);if(!"python".equals(engine))facts.put("engineProfile",engine);if(javaHistory!=null)facts.put("javaHistoryJson",RunJsonBoundary.canonicalFull(javaHistory));if(pythonHistory!=null)facts.put("pythonHistoryJson",RunJsonBoundary.canonicalFull(pythonHistory));facts.put("verifiedInputsJson",ModelMessageSources.verifiedInputsJson(verifiedInputs));if("question-answer".equals(ModelNodeSemantics.type(n)))facts.put("checkpointSourcesJson",canonical(qaSources(run,n,params),REQUEST_BYTES));var c=callCandidate(facts);var p=local(()->policy().approveCall(c));
        if(p==null || !Objects.equals(c.fingerprint(),p.fingerprint()) || p.expiresAt()==null || !p.expiresAt().isAfter(now()) || p.expiresAt().isAfter(c.deadline()) || p.expiresAt().isAfter(runPermit(run).expiresAt()))throw error("未取得准确模型逐次调用许可",403);
        text(p.approvalRef(),255,"approvalRef");if(!local(()->policy().currentCall(c,p)))throw error("模型逐次调用许可已撤销",403);callable(run);fixed(run,resource);workerRun(id,worker,lease,run.getAttempt(),true);
        var approval=JSONObject.of("candidate",facts,"fingerprint",p.fingerprint(),"approvalRef",p.approvalRef(),"expiresAt",p.expiresAt().toString(),"requestHash",requestHash);
        var row=new ModelOperationRecord();row.setId(UUID.randomUUID().toString());row.setRunId(id);row.setLeaseId(lease);row.setWorkerId(worker.id());row.setAttempt(run.getAttempt());row.setNodeId(nodeId);row.setIterationId(null);row.setWorkflowVersionId(run.getWorkflowVersionId());row.setSpaceId(run.getSpaceId());row.setOwnerUserId(run.getOwnerUserId());row.setDslHash(c.dslHash());row.setResourceId(resource);row.setResourceVersion(c.resourceVersion());row.setProvider(f.provider());row.setRequestKey(key);row.setRequestKeyHash(keyHash);row.setRequestHash(requestHash);row.setParametersJson(parameters);row.setApprovalJson(canonical(approval,REQUEST_BYTES));row.setApprovalRef(p.approvalRef());row.setApprovalExpiresAt(date(p.expiresAt()));row.setDeadline(run.getDeadline());row.setState("PREPARED");row.setLastSeq(0L);row.setResponseBytes(0L);row.setRevision(0L);row.setDispatchStarted(0);row.setGrantAcquired(0);row.setStopConfirmed(0);
        if(!p.expiresAt().isAfter(now()))throw error("模型逐次调用许可已过期",403);if(operations.insert(row)!=1)throw error("模型操作保存失败",500);return row;
    }
    public JSONObject start(String id,WorkerIdentityService.Principal worker,JSONObject request){
        outsideTransaction();var row=self().prepare(id,worker,request);
        if(row.getDispatchStarted()==1 || TERMINAL.contains(row.getState()))return startAck(row);
        var run=runs.find(id);var f=fixed(run,row.getResourceId());ports(f);PrivateMaterial material=local(()->credential().resolve(f));
        if(material==null || !Objects.equals(f.credentialRef(),material.credentialRef()) || !Objects.equals(f.credentialVersion(),material.credentialVersion()) || !Objects.equals(f.privateBaseUrl(),material.baseUrl()) || material.apiKey()==null || material.apiKey().isBlank() || material.apiKey().chars().anyMatch(Character::isISOControl))throw error("模型准确版本凭据不可用",503);
        String token=UUID.randomUUID()+"."+UUID.randomUUID();String grantId=UUID.randomUUID().toString();
        var claimed=self().claimDispatch(row.getId(),worker,token,grantId);if(claimed==null)return startAck(operations.find(row.getId()));
        Instant deadline=instant(claimed.getApprovalExpiresAt());if(runPermit(run).expiresAt().isBefore(deadline))deadline=runPermit(run).expiresAt();if(instant(claimed.getDeadline()).isBefore(deadline))deadline=instant(claimed.getDeadline());
        var grant=new PrivateGrant(claimed.getId(),grantId,token,binding(claimed),claimed.getParametersJson(),sha(claimed.getParametersJson()),canonical(JSON.parseObject(claimed.getParametersJson()).getJSONObject("options"),REQUEST_BYTES),deadline,material,providerUid(claimed),run.getEngineProfile());
        try{var ack=bridge().submit(grant,self());if(ack==null || !ack.accepted() || !Objects.equals(claimed.getId(),ack.operationId()) || !Objects.equals(grantId,ack.grantId()))self().recordFailure(grant,new FailureFacts("transport","DISPATCH_ACK_UNKNOWN",true,false,false));}
        catch(RuntimeException ex){self().recordFailure(grant,new FailureFacts("transport","DISPATCH_OUTCOME_UNKNOWN",true,false,false));}
        return startAck(claimed);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public ModelOperationRecord claimDispatch(String operation,WorkerIdentityService.Principal worker,String token,String grantId){
        var before=operations.find(operation);if(before==null)throw error("模型操作不存在",404);var run=workerRun(before.getRunId(),worker,before.getLeaseId(),before.getAttempt(),true);callable(run);var row=owned(operation,run);fixed(run,row.getResourceId());currentCall(row);
        if(row.getDispatchStarted()==1 || TERMINAL.contains(row.getState()))return null;
        ports(fixed(run,row.getResourceId()));currentRun(run);fixed(run,row.getResourceId());workerRun(run.getId(),worker,row.getLeaseId(),row.getAttempt(),true);
        if(!runPermit(run).expiresAt().isAfter(now()) || !instant(row.getApprovalExpiresAt()).isAfter(now()) || !instant(row.getDeadline()).isAfter(now()))throw error("模型派发前许可已过期",403);
        row.setGrantId(grantId);row.setGrantTokenHash(sha(token));row.setGrantAcquired(1);row.setDispatchStarted(1);row.setState("RUNNING");save(row);return row;
    }
    private ModelOperationRecord privateRow(PrivateGrant grant,boolean active){
        if(grant==null)throw error("私有模型许可无效",403);var before=operations.find(grant.operationId());if(before==null)throw error("模型操作不存在",404);
        ModelOperationRecord row;RunRecord lockedRun;
        var found=runs.find(before.getRunId());if(found==null)throw error("模型运行不存在",404);
        if(active){var worker=new WorkerIdentityService.Principal(before.getWorkerId(),found.getEngineProfile());lockedRun=workerRun(before.getRunId(),worker,before.getLeaseId(),before.getAttempt(),true);callable(lockedRun);row=owned(before.getId(),lockedRun);fixed(lockedRun,row.getResourceId());currentCall(row);}else{if(spaces.lock(found.getSpaceId())==null || (lockedRun=runs.lock(found.getId()))==null)throw error("模型运行不存在",404);row=operations.lock(before.getId());}
        if(!Objects.equals(grant.engineProfile(),lockedRun.getEngineProfile()))throw error("私有模型许可引擎偏离固定运行",403);
        if(row==null || !Objects.equals(grant.optionsJson(),canonical(JSON.parseObject(row.getParametersJson()).getJSONObject("options"),REQUEST_BYTES)))throw error("私有模型选项偏离准确操作",403);
        if(row==null || row.getGrantAcquired()!=1 || !Objects.equals(grant.grantId(),row.getGrantId()) || row.getGrantTokenHash()==null || !MessageDigest.isEqual(sha(grant.token()).getBytes(StandardCharsets.US_ASCII),row.getGrantTokenHash().getBytes(StandardCharsets.US_ASCII)) || !Objects.equals(canonical(grant.binding(),REQUEST_BYTES),canonical(binding(row),REQUEST_BYTES)) || !Objects.equals(grant.parametersJson(),row.getParametersJson()) || !Objects.equals(grant.parametersHash(),sha(row.getParametersJson())) || !Objects.equals(grant.providerUid(),providerUid(row)))throw error("私有模型许可不属于准确操作",403);
        if(active){var run=runs.find(row.getRunId());ports(fixed(run,row.getResourceId()));currentRun(run);fixed(run,row.getResourceId());workerRun(row.getRunId(),new WorkerIdentityService.Principal(row.getWorkerId(),lockedRun.getEngineProfile()),row.getLeaseId(),row.getAttempt(),true);if(!runPermit(run).expiresAt().isAfter(now()) || !instant(row.getApprovalExpiresAt()).isAfter(now()) || !instant(row.getDeadline()).isAfter(now()))throw error("模型私有许可已过期",403);}
        return row;
    }
    private static boolean privateContent(Object value,PrivateMaterial material){
        if(value instanceof String s){for(String secret:new String[]{material.apiKey(),material.baseUrl(),material.credentialRef(),material.appId(),material.apiSecret()})if(secret!=null && !secret.isEmpty() && s.contains(secret))return true;return false;}
        if(value instanceof Map<?,?> map){for(var e:map.entrySet())if(privateContent(e.getKey(),material) || privateContent(e.getValue(),material))return true;}
        if(value instanceof List<?> list)for(Object item:list)if(privateContent(item,material))return true;return false;
    }
    private static void usage(Object value){
        if(!(value instanceof Map<?,?> map))throw error("模型usage必须为真实对象",400);
        for(String key:Set.of("prompt_tokens","completion_tokens","total_tokens"))integer(map.get(key),0,Long.MAX_VALUE,"usage."+key);
    }
    private static JSONObject frameAck(ModelOperationRecord row,long seq){var result=binding(row);result.put("state",row.getState());result.put("accepted",true);result.put("seq",seq);result.put("lastSeq",row.getLastSeq());result.put("finishSeq",row.getFinishSeq());result.put("done",TERMINAL.contains(row.getState()) && seq==row.getLastSeq());result.put("failureKind",row.getFailureKind());return result;}
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject assertActive(PrivateGrant grant){var row=privateRow(grant,true);if(!"RUNNING".equals(row.getState()))throw error("模型操作不能继续",410);var result=binding(row);result.put("active",true);return result;}
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject commitFrame(PrivateGrant grant,long sequence,JSONObject raw){
        var row=privateRow(grant,true);if(!"RUNNING".equals(row.getState()))throw error("模型操作不能再追加帧",410);
        if(sequence<1 || sequence>MAX_FRAMES)throw error("模型帧序号超出范围",400);if(privateContent(raw,grant.privateMaterial()))throw error("模型原始帧含私有运行材料",403);String bytes=canonical(raw,FRAME_BYTES),hash=sha(bytes);int size=bytes.getBytes(StandardCharsets.UTF_8).length;
        if(sequence<=row.getLastSeq()){var prior=operations.frame(row.getId(),sequence);if(prior==null || !Objects.equals(hash,prior.getFrameHash()) || !Objects.equals(bytes,prior.getFrameJson()))throw error("重复模型帧内容不同",409);return frameAck(row,sequence);}
        if(sequence!=row.getLastSeq()+1)throw error("模型帧序号不连续",409);
        var facts=ModelProviderWire.facts(row.getProvider(),raw,grant.engineProfile(),JSON.parseObject(row.getParametersJson()).getJSONObject("options"));String finish=facts.finish();
        if(row.getFinishSeq()!=null && (!"openai".equals(row.getProvider()) || !(raw.get("choices") instanceof List<?> choices) || !choices.isEmpty()))throw error("结束帧后只允许实际usage尾帧",409);
        if(facts.usage()!=null)usage(facts.usage());
        if(row.getResponseBytes()+size>STREAM_BYTES)throw error("模型流累计字节超出范围",413);
        if(operations.insertFrame(row.getId(),sequence,bytes,hash,size)!=1)throw error("模型原始帧保存失败",500);
        row.setLastSeq(sequence);row.setResponseBytes(row.getResponseBytes()+size);if(finish!=null){row.setFinishSeq(sequence);row.setFinishReason(finish);}if(facts.usage()!=null)row.setUsageJson(canonical(facts.usage(),FRAME_BYTES));save(row);return frameAck(row,sequence);
    }
    /** SDK终态先独立提交；历史回填失败不能把准确的供应商终态撤销成未知。 */
    @Override
    public JSONObject finishStream(PrivateGrant grant,long last,long finish,String reason){
        outsideTransaction();var ack=self().finishStreamFacts(grant,last,finish,reason);
        if("java".equals(grant.engineProfile())){
            var row=operations.find(grant.operationId());var run=runs.find(row.getRunId());var history=history();
            if(history!=null&&history.executionContext(run)!=null){
                if(JSON.parseObject(row.getApprovalJson()).getJSONObject("candidate").get("javaHistoryJson")==null)throw error("Java模型历史完成缺少已批准冻结快照",403);
                history.completeJava(run.getId(),row.getNodeId(),row.getId());
            }
        }
        return ack;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject finishStreamFacts(PrivateGrant grant,long last,long finish,String reason){
        var row=privateRow(grant,true);if(!Objects.equals(row.getLastSeq(),last) || !Objects.equals(row.getFinishSeq(),finish) || !Objects.equals(row.getFinishReason(),reason) || finish<1 || last<finish)throw error("SDK结束回执未引用真实原始帧",409);
        if(!"RUNNING".equals(row.getState()) && !Set.of("COMPLETED","FAILED").contains(row.getState()))throw error("模型操作已停止",410);
        if("RUNNING".equals(row.getState())){row.setState("stop".equals(reason)?"COMPLETED":"FAILED");row.setFailureKind("stop".equals(reason)?null:"provider");row.setStopConfirmed(1);row.setStopProofId("stream-finished:"+row.getId()+":"+last+":"+finish);save(row);}
        return frameAck(row,last);
    }
    private static void stopEvidence(ModelOperationRecord row,StopEvidence evidence){if(evidence==null || !evidence.stopped() || !Objects.equals(row.getId(),evidence.operationId()) || !Objects.equals(row.getGrantId(),evidence.grantId()))throw error("没有准确供应商停止证据",409);text(evidence.proofId(),255,"stopProofId");}
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject confirmStop(PrivateGrant grant,StopEvidence evidence){var row=privateRow(grant,false);stopEvidence(row,evidence);if(!Set.of("COMPLETED","FAILED").contains(row.getState())){row.setState("CANCELLED");row.setFailureKind("cancelled");}row.setStopConfirmed(1);row.setStopProofId(evidence.proofId());save(row);return cancelAck(row);}
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void recordFailure(PrivateGrant grant,FailureFacts facts){
        var row=privateRow(grant,false);if(facts==null || !Set.of("transport","provider","lease","cancel","cancelled","authorization","protocol","budget","deadline").contains(facts.kind()))throw error("模型失败事实类型无效",400);
        if(Set.of("COMPLETED","FAILED","CANCELLED").contains(row.getState()))return;
        boolean proof=row.getStopConfirmed()==1 && row.getStopProofId()!=null;
        // 布尔自报或本地close不是停止证明；已派发但停止未知必须保留不确定。
        row.setState(proof?"FAILED":"EFFECT_UNKNOWN");row.setFailureKind("cancel".equals(facts.kind())?"cancelled":facts.kind());save(row);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject resource(String id,WorkerIdentityService.Principal worker,String resource,JSONObject body){
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt"),Set.of());var run=workerRun(id,worker,text(body.get("leaseId"),128,"leaseId"),RunLeaseService.attempt(body),true);callable(run);return fixed(run,resource).publicDescriptor();
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject frames(String id,WorkerIdentityService.Principal worker,String operation,JSONObject body){
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","afterCursor","limit"),Set.of());long after=integer(body.get("afterCursor"),0,MAX_FRAMES,"afterCursor");int limit=(int)integer(body.get("limit"),1,64,"limit");
        var run=workerRun(id,worker,text(body.get("leaseId"),128,"leaseId"),RunLeaseService.attempt(body),true);currentRun(run);var row=owned(operation,run);fixed(run,row.getResourceId());currentCall(row);if(after>row.getLastSeq())throw error("模型游标超过实际流",409);
        if("java".equals(run.getEngineProfile())&&Set.of("COMPLETED","FAILED").contains(row.getState())
                &&Integer.valueOf(1).equals(row.getStopConfirmed())&&row.getStopProofId()!=null){
            var history=history();var candidate=JSON.parseObject(row.getApprovalJson()).getJSONObject("candidate");
            if(history!=null&&history.executionContext(run)!=null&&candidate.containsKey("javaHistoryJson")){
                // 回调已提交终态但尚未回填时，读帧完成回执先同步修复同次半轮，不重派Provider。
                if(history.isJavaRoundOpen(run,row.getNodeId()))history.completeJava(run.getId(),row.getNodeId(),row.getId());
                history.requireCompletedJava(run,row.getNodeId(),row.getId());
            }
        }
        var frames=new JSONArray();long cursor=after;int bytes=0;
        for(var item:operations.frames(operation,after,limit)){if(item.getSeq()!=cursor+1 || !Objects.equals(item.getFrameHash(),sha(item.getFrameJson())))throw error("模型流原始事实校验失败",500);if(bytes+item.getFrameBytes()>1500*1024)break;frames.add(JSON.parseObject(item.getFrameJson()));bytes+=item.getFrameBytes();cursor=item.getSeq();}
        currentRun(run);fixed(run,row.getResourceId());workerRun(id,worker,row.getLeaseId(),row.getAttempt(),true);if(!runPermit(run).expiresAt().isAfter(now()) || !instant(row.getApprovalExpiresAt()).isAfter(now()) || !instant(row.getDeadline()).isAfter(now()))throw error("模型读取许可已过期",403);
        boolean done=TERMINAL.contains(row.getState()) && cursor==row.getLastSeq();var result=binding(row);result.put("state",done?row.getState():"RUNNING");result.put("operationState",row.getState());result.put("frames",frames);result.put("nextCursor",cursor);result.put("done",done);result.put("lastSeq",row.getLastSeq());result.put("finishSeq",row.getFinishSeq());result.put("failureKind",row.getFailureKind());canonical(result,REQUEST_BYTES);return result;
    }
    private static JSONObject cancelAck(ModelOperationRecord row){var value=binding(row);value.put("state",row.getState());value.put("stopped",row.getStopConfirmed()==1 && row.getStopProofId()!=null && TERMINAL.contains(row.getState()));value.put("proofId",row.getStopProofId());return value;}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public ModelOperationRecord stopRequest(String id,WorkerIdentityService.Principal worker,String operation,JSONObject body){
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt"),Set.of());var run=workerRun(id,worker,text(body.get("leaseId"),128,"leaseId"),RunLeaseService.attempt(body),false);var row=owned(operation,run);
        if(row.getDispatchStarted()==0 && !TERMINAL.contains(row.getState())){row.setState("CANCELLED");row.setStopConfirmed(1);row.setFailureKind("cancelled");row.setStopProofId("not-dispatched:"+row.getId());save(row);}return row;
    }
    public JSONObject cancel(String id,WorkerIdentityService.Principal worker,String operation,JSONObject body){
        outsideTransaction();var row=self().stopRequest(id,worker,operation,body);if(row.getStopConfirmed()==1)return cancelAck(row);
        StopEvidence evidence=null;try{evidence=bridge().stop(row.getId(),row.getGrantId(),"worker_cancel");}catch(RuntimeException ignored){}
        return self().finishCancel(row.getId(),worker,evidence);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject finishCancel(String operation,WorkerIdentityService.Principal worker,StopEvidence evidence){
        var before=operations.find(operation);if(before==null)throw error("模型操作不存在",404);var found=runs.find(before.getRunId());if(found==null || spaces.lock(found.getSpaceId())==null)throw error("模型运行不存在",404);var run=runs.lock(found.getId());
        if(run==null || worker==null || !Objects.equals(worker.id(),before.getWorkerId()) || !Objects.equals(run.getEngineProfile(),worker.engineProfile()))throw error("模型停止回执身份无效",403);var row=owned(operation,run);
        if(row.getStopConfirmed()==1)return cancelAck(row);
        if(evidence!=null && evidence.stopped()){stopEvidence(row,evidence);row.setState("CANCELLED");row.setStopConfirmed(1);row.setStopProofId(evidence.proofId());row.setFailureKind("cancelled");}
        else{row.setState("EFFECT_UNKNOWN");row.setFailureKind("cancelled");}save(row);return cancelAck(row);
    }
}
