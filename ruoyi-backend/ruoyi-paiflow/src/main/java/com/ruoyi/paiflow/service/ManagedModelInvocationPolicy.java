package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import java.time.*;
import java.util.*;
import com.ruoyi.paiflow.mapper.ModelOperationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import static com.ruoyi.paiflow.service.ModelOperationJson.*;
import static com.ruoyi.paiflow.service.ModelOperationPorts.*;

/** 显式主体/空间/用途批准 + 固定源消息 + 同一run锁内持久预算。Hello回执不授予费用许可。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class ManagedModelInvocationPolicy implements InvocationPolicy {
    private final ManagedModelRuntime runtime;private final ModelRuntimeCatalog catalog;private final ModelOperationMapper operations;
    public ManagedModelInvocationPolicy(ManagedModelRuntime runtime,ModelRuntimeCatalog catalog,ModelOperationMapper operations){this.runtime=runtime;this.catalog=catalog;this.operations=operations;}
    private org.springframework.beans.factory.ObjectProvider<RunHistoryService> histories;
    private com.ruoyi.paiflow.mapper.RunMapper runs;
    @org.springframework.beans.factory.annotation.Autowired
    public void useHistory(org.springframework.beans.factory.ObjectProvider<RunHistoryService> value,com.ruoyi.paiflow.mapper.RunMapper runs){histories=value;this.runs=runs;}
    private JSONArray javaHistory(CallCandidate c,JSONObject node,Map<String,Object> inputs){
        var history=histories==null?null:histories.getIfAvailable();
        if(history!=null&&runs!=null){
            var run=runs.find(c.runId());
            if(run==null||!Objects.equals(run.getLeaseId(),c.leaseId())||!Objects.equals(run.getAttempt(),c.attempt())||!Objects.equals(run.getOwnerUserId(),c.ownerUserId())||!Objects.equals(run.getSpaceId(),c.spaceId())||!Objects.equals(run.getWorkflowVersionId(),c.workflowVersionId())||!"java".equals(run.getEngineProfile()))throw error("Java模型历史候选偏离当前中心运行",403);
            var task=ModelMessageSources.sourceObject(run.getAssignmentJson());if(!Objects.equals(task.get("dslHash"),c.dslHash())||!ToolSchema.same(task.get("input"),ModelMessageSources.sourceObject(c.inputJson())))throw error("Java模型历史候选固定输入/图不一致",403);
            if(history.executionContext(run)!=null){
                var proof=history.javaSnapshot(run,node,inputs);
                if(c.javaHistoryJson()==null||!Objects.equals(RunJsonBoundary.canonicalFull(proof),c.javaHistoryJson()))throw error("Java模型历史候选不是中心已核冻结快照",403);
                return proof.getJSONArray("history");
            }
        }
        if(c.javaHistoryJson()!=null)throw error("Java模型候选历史没有真实中心上下文",403);
        if(JavaModelSemantics.historyLimit(node)!=0)throw error("Java模型历史读取未装配或未绑定",503);
        return new JSONArray();
    }
    private void pythonHistory(CallCandidate c,JSONObject node){
        if(!PythonModelHistorySemantics.legacy(node)){
            if(c.pythonHistoryJson()!=null)throw error("Python模型候选历史不属于固定节点",403);
            return;
        }
        PythonModelHistorySemantics.requireLegacy(node);
        var history=histories==null?null:histories.getIfAvailable();
        if(history==null || runs==null)throw error("Python模型历史来源端口未装配",503);
        var run=runs.find(c.runId());
        if(run==null || !"python".equals(run.getEngineProfile()) || !Objects.equals(run.getLeaseId(),c.leaseId())
                || !Objects.equals(run.getAttempt(),c.attempt()) || !Objects.equals(run.getOwnerUserId(),c.ownerUserId())
                || !Objects.equals(run.getSpaceId(),c.spaceId()) || !Objects.equals(run.getWorkflowVersionId(),c.workflowVersionId()))
            throw error("Python模型历史候选偏离实际中心运行",403);
        var task=ModelMessageSources.sourceObject(run.getAssignmentJson());
        if(!Objects.equals(task.get("dslHash"),c.dslHash()) || !ToolSchema.same(task.get("input"),ModelMessageSources.sourceObject(c.inputJson()))
                || !ToolSchema.same(node,DataWorkerOperationService.find(task.getJSONObject("dsl"),c.nodeId()))
                || !Objects.equals(c.resourceId(),node.getJSONObject("data").getJSONObject("nodeParam").get("modelResourceId")))
            throw error("Python模型历史候选固定图/输入/资源不一致",403);
        var proof=history.pythonSnapshot(run,node);
        if(c.pythonHistoryJson()==null || !Objects.equals(RunJsonBoundary.canonicalFull(proof),c.pythonHistoryJson()))
            throw error("Python模型历史候选不是中心已核冻结窗口",403);
    }
    private String runApproval(RunCandidate c){
        engine(c.engineProfile());var resources=JSON.parseObject(c.resourcesJson());var hashes=new TreeMap<String,String>();var profiles=new HashMap<String,ModelRuntimeProperties.Profile>();
        for(var e:resources.entrySet()){if(!e.getKey().startsWith("model:"))continue;var fixed=catalog.resolvePinned(c.spaceId(),c.ownerUserId(),e.getKey(),(JSONObject)e.getValue());var p=runtime.profile(fixed);require(p,c.ownerUserId(),c.spaceId());hashes.put(e.getKey(),approved(p));profiles.put(e.getKey(),p);}
        if(hashes.isEmpty())throw error("模型许可缺少固定模型资源",409);
        if("java".equals(c.engineProfile()))javaRunBudget(c,profiles);
        return "model-run:"+sha(c.fingerprint()+canonical(hashes,REQUEST_BYTES)+("java".equals(c.engineProfile())?":engine:java":""));
    }
    private static void require(ModelRuntimeProperties.Profile p,long user,long space){ManagedModelRuntime.scope(p,user,space);if(!p.isInvocationAllowed())throw error("模型调用费用用途未批准",403);}
    private static String approved(ModelRuntimeProperties.Profile p){var facts=JSONObject.of("sourceProvider",p.getSourceProvider(),"provider",p.getRuntimeProvider(),"domain",p.getDomain(),"endpoint",p.getEndpoint(),"inferenceEndpoint",p.getInferenceEndpoint(),"inferenceAddresses",p.getAllowedInferenceAddresses(),"deploymentEndpoint",p.getDeploymentEndpoint(),"deploymentAddresses",p.getAllowedDeploymentAddresses(),"reference",p.getCredentialRef(),"alias",p.getSecretAlias(),"secretProvider",p.getSecretProvider(),"capability",p.getCapability(),"authMode",p.getAuthMode(),"users",p.getAllowedUserIds(),"spaces",p.getAllowedSpaceIds(),"calls",p.getMaxCalls(),"tokens",p.getMaxTokens(),"bytes",p.getMaxRequestBytes(),"seconds",p.getTimeoutSeconds(),"functionCall",p.isFunctionCall());if(p.getProviderDefaultMaxTokens()!=null)facts.put("providerDefaultMaxTokens",p.getProviderDefaultMaxTokens());return sha(canonical(facts,REQUEST_BYTES));}
    private static void engine(String engine){if(!("python".equals(engine)||"java".equals(engine)))throw error("中心模型候选执行引擎无效",409);}
    private static void javaRunBudget(RunCandidate c,Map<String,ModelRuntimeProperties.Profile> profiles){
        var execution=JSON.parseObject(c.executionJson());if(!(execution.get("nodes") instanceof JSONArray nodes))throw error("Java模型运行缺少固定节点",409);
        int count=0;
        for(Object raw:nodes){if(!(raw instanceof JSONObject node))throw error("Java模型固定节点无效",409);if(!ModelNodeSemantics.isModelNode(node))continue;
            var params=node.getJSONObject("data").getJSONObject("nodeParam");String resource=params.getString("modelResourceId");var profile=profiles.get(resource);if(profile==null)throw error("Java模型运行资源绑定缺失",409);
            var options=ModelOperationOptions.forNode(node,"java");javaOutputBudget(options,profile);count++;
        }
        if(count==0)throw error("Java模型运行缺少真实模型节点",409);
        // 上游输出尚未产生；精确消息 UTF-8 字节与持久预留在逐次调用时检查，不猜测输入长度。
    }
    private static long javaOutputBudget(JSONObject options,ModelRuntimeProperties.Profile profile){long tokens=reservedTokens(options,"java",profile.getProviderDefaultMaxTokens());if(tokens>profile.getMaxTokens())throw error("Java模型输出预算超过批准范围",429);return tokens;}
    private static long addBudget(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException overflow){throw error("模型预算预留溢出",429);}}
    @Override public RunPermit approveRun(RunCandidate c){return new RunPermit(c.fingerprint(),runApproval(c),c.deadline());}
    @Override public boolean currentRun(RunCandidate c,RunPermit permit){try{return permit!=null && permit.expiresAt().isAfter(Instant.now()) && Objects.equals(c.fingerprint(),permit.fingerprint()) && Objects.equals(runApproval(c),permit.approvalRef());}catch(RuntimeException denied){return false;}}
    private ModelRuntimeProperties.Profile callProfile(CallCandidate c){
        // 固定模型ID/版本来源被目录核对；描述仅为明确版本的公开形状。
        var parameters=JSON.parseObject(c.parametersJson());var profileDescriptor=JSONObject.of("kind","model","provider",c.provider(),"version",c.resourceVersion(),"modelName",parameters.getString("modelName"),"enabled",true);
        ModelRuntimeCatalog.FixedModel fixed=null;
        for(var capabilities:List.of(List.of("chat"),List.of("chat","function_call"))){profileDescriptor.put("capabilities",new JSONArray(capabilities));try{fixed=catalog.resolvePinned(c.spaceId(),c.ownerUserId(),c.resourceId(),profileDescriptor);break;}catch(com.ruoyi.common.exception.ServiceException denied){if(!Objects.equals(denied.getCode(),409))throw denied;}}
        if(fixed==null)throw error("逐次调用模型固定资源不一致",409);var p=runtime.profile(fixed);require(p,c.ownerUserId(),c.spaceId());
        if(!Objects.equals(c.parametersHash(),sha(c.parametersJson())) || c.parametersJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>p.getMaxRequestBytes())throw error("逐次模型参数超出批准范围",403);
        engine(c.engineProfile());var node=ModelMessageSources.sourceObject(c.nodeJson());ModelOperationOptions.validateRequest(parameters,node,c.engineProfile());
        // 旧持久候选保留原指纹读取；本轮prepare始终提供中心全链核对后的私有输入快照。
        JSONArray expected;
        if("java".equals(c.engineProfile())){
            if(c.pythonHistoryJson()!=null)throw error("Java模型候选不能包含Python历史事实",403);
            if(!Objects.equals(node.get("id"),c.nodeId()) || !Objects.equals(node.getJSONObject("data").getJSONObject("nodeParam").get("modelResourceId"),c.resourceId()))throw error("Java模型固定节点/资源不一致",409);
            if(!"[]".equals(c.checkpointSourcesJson()))throw error("Java模型检查点消息尚未接通",503);
            var inputs=javaValues(node,c.verifiedInputsJson());expected=JavaModelSemantics.messages(node,inputs,javaHistory(c,node,inputs));
        }else {if(c.javaHistoryJson()!=null)throw error("Python模型候选不能包含Java历史事实",403);pythonHistory(c,node);expected=c.verifiedInputsJson()==null?ModelMessageSources.expected(node,ModelMessageSources.sourceObject(c.inputJson()),ModelMessageSources.sourceArray(c.eventsJson()),JSON.parseArray(c.checkpointSourcesJson())):ModelMessageSources.expected(node,ModelMessageSources.verifiedValues(node,c.verifiedInputsJson()),JSON.parseArray(c.checkpointSourcesJson()));}
        if(!canonical(expected,REQUEST_BYTES).equals(canonical(parameters.get("messages"),REQUEST_BYTES)))throw error("模型消息与中心输入/固定节点/完成事件不一致",403);
        if("java".equals(c.engineProfile())){javaOutputBudget(parameters.getJSONObject("options"),p);return p;}
        long tokens=integer(parameters.getJSONObject("options").get("maxTokens"),1,p.getMaxTokens(),"maxTokens");
        // OpenAI原SDK额外max_tokens同样纳入预算；绝不按忽略的普通字段推算费用上限。
        var extra=parameters.getJSONObject("options").getJSONObject("extraParams");
        for(String key:List.of("max_tokens","max_completion_tokens"))if(extra.containsKey(key))tokens=Math.max(tokens,integer(extra.get(key),1,p.getMaxTokens(),key));
        if(parameters.containsKey("timeoutSeconds") && parameters.getDoubleValue("timeoutSeconds")>p.getTimeoutSeconds())throw error("调用超时超过用途预算",403);
        return p;
    }
    @Override public CallPermit approveCall(CallCandidate c){
        var p=callProfile(c);var records=operations.forRunResource(c.runId(),c.resourceId());if(records.size()>=p.getMaxCalls())throw error("模型运行调用次数预算耗尽",429);
        long reserved=0;for(var row:records){if("java".equals(c.engineProfile())){var approval=JSON.parseObject(row.getApprovalJson());if(approval==null || approval.getJSONObject("candidate")==null || !"java".equals(approval.getJSONObject("candidate").get("engineProfile")))throw error("Java模型账本缺少中心私有引擎事实",403);}reserved=addBudget(reserved,reservation(JSON.parseObject(row.getParametersJson()),c.engineProfile(),p.getProviderDefaultMaxTokens()));}
        long tokens=reservation(JSON.parseObject(c.parametersJson()),c.engineProfile(),p.getProviderDefaultMaxTokens());if(reserved>p.getMaxTokens() || tokens>p.getMaxTokens()-reserved)throw error("模型运行token预留预算耗尽",429);
        Instant end=callDeadline(JSON.parseObject(c.parametersJson()),p.getTimeoutSeconds(),c.deadline(),Instant.now());return new CallPermit(c.fingerprint(),callApproval(c,p),end);
    }
    static long reservedTokens(JSONObject options){long n=integer(options.get("maxTokens"),1,Long.MAX_VALUE,"maxTokens");var extra=options.getJSONObject("extraParams");for(String key:List.of("max_tokens","max_completion_tokens"))if(extra.containsKey(key))n=Math.max(n,integer(extra.get(key),1,Long.MAX_VALUE,key));return n;}
    static long reservation(JSONObject parameters){return Math.addExact(reservedTokens(parameters.getJSONObject("options")),canonical(parameters,REQUEST_BYTES).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
    /** Java 的 n 和省略 max_tokens 的核证上限只用于预算；不改八项原 wire options。 */
    static long reservedTokens(JSONObject options,String engine,Integer providerDefaultMaxTokens){
        engine(engine);if("python".equals(engine))return reservedTokens(options);
        Object raw=options.get("maxTokens");long limit;
        if(raw==null){if(providerDefaultMaxTokens==null)throw error("Java模型Provider默认输出上限未核证配置",503);limit=integer(providerDefaultMaxTokens,1,Integer.MAX_VALUE,"providerDefaultMaxTokens");}
        else limit=integer(raw,1,Integer.MAX_VALUE,"maxTokens");
        long choices=options.get("n")==null?1:integer(options.get("n"),1,Integer.MAX_VALUE,"n");
        try{return Math.multiplyExact(limit,choices);}catch(ArithmeticException overflow){throw error("Java模型多choice预算溢出",429);}
    }
    static long reservation(JSONObject parameters,String engine,Integer providerDefaultMaxTokens){return addBudget(reservedTokens(parameters.getJSONObject("options"),engine,providerDefaultMaxTokens),canonical(parameters,REQUEST_BYTES).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);}
    private static LinkedHashMap<String,Object> javaValues(JSONObject node,String snapshot){
        if(snapshot==null)throw error("Java模型调用缺少中心可信输入快照",403);var actual=ModelMessageSources.sourceObject(snapshot);if(actual==null)throw error("Java模型可信输入快照必须为对象",403);var result=new LinkedHashMap<String,Object>();
        var inputs=node.getJSONObject("data").getJSONArray("inputs");if(inputs==null)throw error("Java模型固定输入声明缺失",409);
        for(Object raw:inputs){if(!(raw instanceof JSONObject field) || !(field.get("name") instanceof String name))throw error("Java模型固定输入无效",409);var schema=field.getJSONObject("schema");var value=schema==null?null:schema.getJSONObject("value");if(value==null)continue;
            if(result.containsKey(name) || !actual.containsKey(name))throw error("Java模型可信输入字段不完整或重复",403);
            Object item=actual.get(name);
            if(!"ref".equals(value.get("type"))){Object fixed=JavaToolSemantics.plain(value.get("content"));if(!canonical(fixed,REQUEST_BYTES).equals(canonical(item,REQUEST_BYTES)))throw error("Java模型literal与固定原输入不一致",403);item=fixed;}
            result.put(name,item);
        }
        if(!result.keySet().equals(actual.keySet()))throw error("Java模型可信输入含未声明字段",403);return result;
    }
    static Instant callDeadline(JSONObject parameters,int configuredSeconds,Instant runDeadline,Instant now){
        double seconds=parameters.containsKey("timeoutSeconds")?parameters.getDoubleValue("timeoutSeconds"):configuredSeconds;
        if(!Double.isFinite(seconds) || seconds<=0 || seconds>configuredSeconds)throw error("模型调用期限超出用途预算",403);
        Instant end=now.plusNanos((long)(seconds*1_000_000_000L));return runDeadline.isBefore(end)?runDeadline:end;
    }
    private static String callApproval(CallCandidate c,ModelRuntimeProperties.Profile p){return "model-call:"+sha(c.fingerprint()+approved(p)+c.runApprovalRef()+("java".equals(c.engineProfile())?":engine:java":""));}
    @Override public boolean currentCall(CallCandidate c,CallPermit permit){try{var p=callProfile(c);return permit!=null && permit.expiresAt().isAfter(Instant.now()) && Objects.equals(c.fingerprint(),permit.fingerprint()) && Objects.equals(permit.approvalRef(),callApproval(c,p));}catch(RuntimeException denied){return false;}}
}
