package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.*;
import com.ruoyi.paiflow.domain.*;
import com.ruoyi.paiflow.mapper.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import static com.ruoyi.paiflow.service.ModelOperationJson.error;

/** 输入源校核供工具和数据共用；根输入/字面量不读取工具或模型账本，不建立第二状态。 */
@Service @ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class CompletedOutputSources {
    private final ToolMapper tools;private final ModelOperationMapper models;private final CheckpointMapper checkpoints;
    public CompletedOutputSources(ToolMapper tools,ModelOperationMapper models,CheckpointMapper checkpoints){this.tools=tools;this.models=models;this.checkpoints=checkpoints;}

    private DataOperationService dataSources;
    private RunVariableService variableSources;
    private org.springframework.beans.factory.ObjectProvider<RunHistoryService> histories;
    @org.springframework.beans.factory.annotation.Autowired
    public void useHistory(org.springframework.beans.factory.ObjectProvider<RunHistoryService> provider){histories=provider;}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void useDataSources(org.springframework.beans.factory.ObjectProvider<DataOperationService> provider){dataSources=provider.getIfAvailable();}
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    void useVariableSources(org.springframework.beans.factory.ObjectProvider<RunVariableService> provider){variableSources=provider.getIfAvailable();}

    /** acceptedEvents由调用方读中心当前事件；verify保持兼容，解析结果不产生虚假完成事件。 */
    public void verify(RunRecord run,JSONObject execution,JSONObject target,JSONArray acceptedEvents) { values(run,execution,target,acceptedEvents); }
    public LinkedHashMap<String,Object> values(RunRecord run,JSONObject execution,JSONObject target,JSONArray acceptedEvents) {
        if(run==null||run.getId()==null||run.getLeaseId()==null||run.getAttempt()==null||run.getAttempt()<1||run.getLastEventSeq()==null||run.getLastEventSeq()<0||run.getLastEventSeq()==Long.MAX_VALUE||run.getAssignmentJson()==null||execution==null||target==null||target.getJSONObject("data")==null||acceptedEvents==null)throw error("输入源没有当前中心运行事实",403);
        for(Object value:acceptedEvents){if(!(value instanceof JSONObject event)||!Objects.equals(run.getId(),event.get("runId"))||!Objects.equals(run.getAttempt(),event.getInteger("attempt"))||!Objects.equals(run.getLeaseId(),event.get("leaseId"))||event.getLongValue("seq")<1||event.getLongValue("seq")>run.getLastEventSeq())throw error("输入源事件不属于当前中心水位",403);}
        if("java".equals(run.getEngineProfile()))return new JavaSources(run,execution,acceptedEvents).values(target,run.getLastEventSeq()+1,0);
        var context=new SourceContext(run,execution,acceptedEvents);
        long upper=run.getLastEventSeq()+1;
        context.ready(target,upper);
        return context.inputs(target,upper,0);
    }
    private enum Route { ACTIVE, INACTIVE, UNPROVEN }
    /** Java原变量池规则只消费当前中心账本；完成帧须同时匹配固定输入与真实整Map输出。 */
    private final class JavaSources {
        final RunRecord run;final JSONObject graph,input;final JSONArray events;
        final Map<String,Map<String,Object>> pool=new HashMap<>();final Map<String,Long> completedAt=new HashMap<>();final Set<String> resolving=new HashSet<>();
        JavaSources(RunRecord run,JSONObject graph,JSONArray events){this.run=run;this.graph=graph;this.events=events;var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());if(!"java".equals(assignment.get("engineProfile"))||!Objects.equals(run.getId(),assignment.get("runId"))||!ToolSchema.same(graph,assignment.get("dsl"))||!DslCodec.V2.equals(assignment.getOrDefault("executionCodec",DslCodec.V2)))throw error("Java来源与固定运行绑定不一致",409);JavaToolGraphPolicy.validate(graph);input=assignment.getJSONObject("input");if(input==null)throw error("Java固定运行输入缺失",403);}
        LinkedHashMap<String,Object> values(JSONObject node,long upper,int depth){if(depth>128)throw error("Java输入来源链超过预算",413);var fixed=DataWorkerOperationService.find(graph,node.getString("id"));if(fixed==null||!ToolSchema.same(fixed,node))throw error("Java消费者不是固定节点",403);ready(node,upper,depth);if("node-start".equals(ModelNodeSemantics.type(node)))return new LinkedHashMap<>(JavaToolSemantics.startPool(input));
            var fields=node.getJSONObject("data").getJSONArray("inputs");if(fields!=null)for(Object raw:fields){var schema=((JSONObject)raw).getJSONObject("schema");var binding=schema==null?null:schema.getJSONObject("value");if(binding!=null&&"ref".equals(binding.get("type")))resolve(binding.getJSONObject("content").getString("nodeId"),upper,depth+1);}
            return new LinkedHashMap<>(JavaToolSemantics.inputs(node,pool));
        }
        void ready(JSONObject node,long upper,int depth){for(Object raw:graph.getJSONArray("edges")){var edge=(JSONObject)raw;if(node.getString("id").equals(edge.get("targetNodeId")))resolve(edge.getString("sourceNodeId"),upper,depth+1);}}
        Map<String,Object> resolve(String id,long upper,int depth){if(depth>128)throw error("Java来源链超过预算",413);if(pool.containsKey(id)){if(completedAt.get(id)>=upper)throw error("Java来源完成晚于消费者",403);return pool.get(id);}if(!resolving.add(id))throw error("Java固定来源存在循环",403);
            try {var node=DataWorkerOperationService.find(graph,id);if(node==null)throw error("Java来源不属于固定图",403);JSONObject accepted=null;for(Object raw:events){var event=(JSONObject)raw;if(!id.equals(event.get("nodeId"))||!"node.completed".equals(event.get("eventType")))continue;if(event.get("iterationId")!=null||event.getLongValue("seq")>=upper||accepted!=null)throw error("Java来源完成事件不唯一或晚于消费者",403);accepted=event;}if(accepted==null)throw error("Java活跃前驱尚无完成事实",403);long seq=accepted.getLongValue("seq");var verifiedInputs=values(node,seq,depth+1);String type=ModelNodeSemantics.type(node);JSONObject expected;
                if("node-start".equals(type))expected=new JSONObject();else if("plugin".equals(type)){if(tools==null)throw error("Java工具来源账本未装配",503);expected=ToolCompletedSources.completed(run,node,tools);}else if("node-end".equals(type))expected=new JSONObject(verifiedInputs);else if("spark-llm".equals(type)){var projection=javaModel(run,node,verifiedInputs);expected=JavaModelSemantics.outputs(node,projection);JavaToolSemantics.verifyModelEvent(run,node,accepted,verifiedInputs,projection);}else throw error("Java来源算法尚未接通",503);
                if(!"spark-llm".equals(type))JavaToolSemantics.verifyEvent(run,node,accepted,verifiedInputs,expected);
                var output="node-start".equals(type)?JavaToolSemantics.startPool(input):JavaToolSemantics.outputPool(expected);pool.put(id,output);completedAt.put(id,seq);return output;
            }finally{resolving.remove(id);}
        }
    }
    /** 本次读取局部推导；不持久化、不改写acceptedEvents，也不以缺事件单独推定未执行。 */
    private final class SourceContext {
        final RunRecord run;final JSONObject execution,input;final JSONArray events;
        final Map<String,JSONObject> outputs=new HashMap<>();final Map<String,Long> completionSeq=new HashMap<>();
        final Set<String> resolving=new HashSet<>(),routing=new HashSet<>(),readying=new HashSet<>(),readyDone=new HashSet<>();final Map<String,Route> routes=new HashMap<>();final Map<String,Long> routeProofSeq=new HashMap<>();
        final boolean hasBranches;
        SourceContext(RunRecord run,JSONObject execution,JSONArray events){this.run=run;this.execution=execution;this.events=events;input=ModelMessageSources.sourceObject(run.getAssignmentJson()).getJSONObject("input");boolean found=false;var nodes=execution.getJSONArray("nodes");if(nodes!=null)for(Object raw:nodes)if(raw instanceof JSONObject node&&isBranch(node))found=true;hasBranches=found;}
        LinkedHashMap<String,Object> inputs(JSONObject node,long upper,int depth){
            if(depth>128)throw error("输入来源链超过固定图预算",413);
            var fields=node.getJSONObject("data").getJSONArray("inputs");if(fields!=null)for(Object raw:fields){var field=(JSONObject)raw;var schema=field.getJSONObject("schema");var binding=schema==null?null:schema.getJSONObject("value");if(binding==null){if(!ModelNodeSemantics.isModelNode(node)||input==null||!input.containsKey(field.getString("name")))throw error("固定节点输入来源缺失",403);continue;}if("ref".equals(binding.get("type"))){String source=binding.getJSONObject("content").getString("nodeId");if(source==null)throw error("固定输入引用节点缺失",403);if(!source.startsWith("node-start::"))resolve(source,upper,depth+1);}}
            return WorkflowInputSources.values(execution,node,input,events,outputs);
        }
        JSONObject resolve(String source,long upper,int depth){
            if(depth>128)throw error("输入来源链超过固定图预算",413);
            if(outputs.containsKey(source)){if(completionSeq.get(source)>=upper)throw error("来源完成事实晚于消费者",403);return outputs.get(source);}
            if(!resolving.add(source))throw error("固定输入来源链存在循环",403);
            try {
                var node=DataWorkerOperationService.find(execution,source);if(node==null)throw error("输入来源节点不属于固定图",403);
                var event=maybeCompletion(source,events,upper);
                long routeUpper=event==null?upper:event.getLongValue("seq");Route active=hasBranches?route(source,routeUpper):Route.ACTIVE;
                if(event==null){if(active!=Route.INACTIVE)throw error("输入源尚无完成事实且未证明支路停用",403);var defaults=new JSONObject();for(Object raw:CompletedModelOutputs.outputs(node)){var field=(JSONObject)raw;defaults.put(field.getString("name"),CompletedModelOutputs.defaultValue(field));}outputs.put(source,defaults);completionSeq.put(source,routeProofSeq.get(source+"@"+routeUpper));return defaults;}
                if(active!=Route.ACTIVE)throw error("未选或未证明活跃支路出现完成帧",403);
                long seq=event.getLongValue("seq");ready(node,seq);var values=inputs(node,seq,depth+1);JSONObject expected;String type=ModelNodeSemantics.type(node);
                if("plugin".equals(type)){if(tools==null)throw error("工具输入来源账本未装配",503);expected=ToolCompletedSources.completed(run,node,tools);}
                else if(ModelNodeSemantics.isModelNode(node))expected="question-answer".equals(type)?qa(run,execution,node,events,values):model(run,node,values);
                else if("question-answer".equals(type))expected=staticQa(run,execution,node,events,values);
                else if("node-variable".equals(type)){if(variableSources==null)throw error("会话变量来源快照未装配",503);expected=variableSources.completed(run,node,values);}
                else if("database".equals(type)){if(dataSources==null)throw error("数据来源完成事实未装配",503);expected=dataOutput(node,dataSources.completed(run,node,null));}
                else expected=CompletedPureOutputs.project(node,values);
                if(!ToolSchema.same(eventOutput(event),expected))throw error("上游完成输出偏离真实中心来源或原节点算法",409);
                var pool=ModelMessageSources.sourceObject(expected.toJSONString(JSONWriter.Feature.WriteMapNullValue));pool.put("errorCode",0);pool.put("errorMessage","");outputs.put(source,pool);completionSeq.put(source,seq);return pool;
            } finally {resolving.remove(source);}
        }
        /** 原引擎等待每条未停用路径的直接前驱；任一路活跃不代表可以越过其他前驱。 */
        void ready(JSONObject node,long upper){String id=node.getString("id"),cache=id+"@"+upper;if(readyDone.contains(cache))return;if(readying.size()>128)throw error("固定前驱等待链超过预算",413);if(!readying.add(cache))throw error("固定前驱等待链存在循环",403);
            try{if(hasBranches&&route(id,upper)!=Route.ACTIVE)throw error("当前消费者没有已证明活跃的固定分支路径",403);var edges=execution.getJSONArray("edges");if(edges!=null)for(Object raw:edges){var edge=(JSONObject)raw;String from=edge.getString(edge.containsKey("sourceNodeId")?"sourceNodeId":"source"),to=edge.getString(edge.containsKey("targetNodeId")?"targetNodeId":"target");if(!id.equals(to))continue;Route prior=hasBranches?route(from,upper):Route.ACTIVE;if(prior==Route.INACTIVE)continue;if(prior==Route.UNPROVEN)throw error("直接前驱的路径尚未证明停用或完成",403);var parent=DataWorkerOperationService.find(execution,from);if(parent==null)throw error("固定前驱节点不存在",403);
                if(isBranch(parent)){resolve(from,upper,0);String handle=branchHandle(parent,inputs(parent,completionSeq.get(from),0),outputs.get(from));if(!selectedTarget(from,handle,id))continue;}
                else {if(edgeHandle(edge)!=null&&edgeHandle(edge).contains("fail_one_of"))continue;if(!from.startsWith("node-start::"))resolve(from,upper,0);}
            }readyDone.add(cache);}finally{readying.remove(cache);}
        }
        Route route(String id,long upper){
            if(id==null)throw error("固定路由节点缺失",403);if(id.startsWith("node-start::")){routeProofSeq.put(id+"@"+upper,0L);return Route.ACTIVE;}
            String cache=id+"@"+upper;if(routes.containsKey(cache))return routes.get(cache);
            if(routing.size()>128)throw error("固定分支路径超过预算",413);if(!routing.add(cache))throw error("固定分支路径存在循环",403);
            try{boolean any=false,unknown=false;long proof=0;var edges=execution.getJSONArray("edges");if(edges!=null)for(Object raw:edges){var edge=(JSONObject)raw;String from=edge.getString(edge.containsKey("sourceNodeId")?"sourceNodeId":"source"),to=edge.getString(edge.containsKey("targetNodeId")?"targetNodeId":"target");if(!id.equals(to))continue;any=true;Route prior=route(from,upper);proof=Math.max(proof,routeProofSeq.getOrDefault(from+"@"+upper,0L));if(prior==Route.UNPROVEN){unknown=true;continue;}if(prior==Route.INACTIVE)continue;var source=DataWorkerOperationService.find(execution,from);if(source==null)throw error("固定分支边来源不存在",403);if(isBranch(source)){if(completionBefore(from,events,upper)==null){unknown=true;continue;}resolve(from,upper,0);proof=Math.max(proof,completionSeq.get(from));String handle=branchHandle(source,inputs(source,completionSeq.get(from),0),outputs.get(from));if(!selectedTarget(from,handle,id))continue;}else if(edge.getString("sourceHandle")!=null&&edge.getString("sourceHandle").contains("fail_one_of"))continue;routes.put(cache,Route.ACTIVE);routeProofSeq.put(cache,proof);return Route.ACTIVE;}
                Route result=!any||unknown?Route.UNPROVEN:Route.INACTIVE;routes.put(cache,result);routeProofSeq.put(cache,proof);return result;
            }finally{routing.remove(cache);}
        }
        boolean selectedTarget(String source,String handle,String target){boolean configured=false,selected=false;for(Object raw:execution.getJSONArray("edges")){var edge=(JSONObject)raw;String from=edge.getString(edge.containsKey("sourceNodeId")?"sourceNodeId":"source"),to=edge.getString(edge.containsKey("targetNodeId")?"targetNodeId":"target");if(source.equals(from)&&Objects.equals(handle,edgeHandle(edge))){configured=true;if(target.equals(to))selected=true;}}if(!configured)throw error("原控制分支没有对应固定后继",403);return selected;}
    }
    private static String edgeHandle(JSONObject edge){String handle=edge.getString("sourceHandle");return handle!=null&&handle.startsWith("intent_chain|")?handle.split("\\|",-1)[1]:handle;}
    private static boolean isBranch(JSONObject node){String type=ModelNodeSemantics.type(node);return "if-else".equals(type)||"decision-making".equals(type)||"question-answer".equals(type)&&"option".equals(node.getJSONObject("data").getJSONObject("nodeParam").get("answerType"));}
    private static String branchHandle(JSONObject node,LinkedHashMap<String,Object> values,JSONObject output){String type=ModelNodeSemantics.type(node);var p=node.getJSONObject("data").getJSONObject("nodeParam");
        if("if-else".equals(type))return CompletedPureOutputs.branchHandle(node,values);
        if("decision-making".equals(type)){var fields=CompletedModelOutputs.outputs(node);String selected=output.getString(fields.getJSONObject(0).getString("name"));String fallback=null,match=null;boolean fc="function_call".equals(ModelNodeSemantics.mode(node));for(Object raw:p.getJSONArray("intentChains")){var chain=(JSONObject)raw;if(chain.getIntValue("intentType")==1)fallback=chain.getString("id");if((fc||chain.getIntValue("intentType")!=1)&&Objects.equals(chain.get("name"),selected))match=chain.getString("id");}return !fc&&"DEFAULT".equals(selected)?fallback:match==null?fallback:match;}
        String selected=output.getString("id");for(Object raw:p.getJSONArray("optionAnswer")){var option=(JSONObject)raw;if(Objects.equals(option.get("name"),selected))return option.getString("id");}throw error("原选项问答没有准确固定分支",403);
    }
    /** 路由只看该来源完成之前的控制事实；晚到控制不能阻断另一条已经证明活跃的路径。 */
    private static JSONObject completionBefore(String source,JSONArray events,long upper){JSONObject found=null;for(Object raw:events){var event=(JSONObject)raw;if(!source.equals(event.get("nodeId"))||event.get("iterationId")!=null||!"node.completed".equals(event.get("eventType"))||event.getLongValue("seq")>=upper)continue;if(found!=null)throw error("当前控制节点完成事件不唯一",403);eventOutput(event);found=event;}return found;}
    private static JSONObject maybeCompletion(String source,JSONArray events,long upper){JSONObject found=null;for(Object value:events){var event=(JSONObject)value;if(!source.equals(event.get("nodeId"))||event.get("iterationId")!=null||!"node.completed".equals(event.get("eventType")))continue;if(event.getLongValue("seq")>=upper)throw error("来源完成事件晚于消费者",403);if(found!=null)throw error("当前节点完成事件不唯一",403);eventOutput(event);found=event;}return found;}
    private static JSONObject completion(String source,JSONArray events,long upper){JSONObject found=null;for(Object value:events){var event=(JSONObject)value;if(!source.equals(event.get("nodeId"))||event.get("iterationId")!=null||!"node.completed".equals(event.get("eventType")))continue;if(event.getLongValue("seq")>=upper)throw error("来源完成事件晚于消费者",403);if(found!=null)throw error("当前节点完成事件不唯一",403);eventOutput(event);found=event;}if(found==null)throw error("输入源没有当前已接受完成输出",403);return found;}
    private static JSONObject eventOutput(JSONObject event){var payload=event.getJSONObject("payload");var frame=payload==null?null:payload.getJSONObject("legacyFrame");var step=frame==null?null:frame.getJSONObject("workflow_step");var node=step==null?null:step.getJSONObject("node");if(node==null||!Objects.equals(event.get("nodeId"),node.get("id"))||!(node.get("outputs") instanceof JSONObject outputs))throw error("来源完成帧形状无效",403);return outputs;}
    private static JSONObject completed(String source,JSONArray events){return eventOutput(completion(source,events,Long.MAX_VALUE));}
    private static JSONObject dataOutput(JSONObject node,JSONObject receipt){var raw=receipt.getJSONObject("responseJson");if(raw==null)throw error("数据完成事实没有原回执",403);int mode=node.getJSONObject("data").getJSONObject("nodeParam").getIntValue("mode");var result=JSONObject.of("isSuccess",true,"message",raw.getOrDefault("message",""));if(mode==4)return result;var data=raw.getJSONObject("data");var actual=data==null?new JSONArray():data.getJSONArray("exec_success");if(actual==null)actual=new JSONArray();Object output=actual;if(mode==0||mode==1||mode==2){JSONObject schema=new JSONObject();for(Object value:CompletedModelOutputs.outputs(node)){var field=(JSONObject)value;if("outputList".equals(field.get("name")))schema=field.getJSONObject("schema");}var items=schema.getJSONObject("items");var required=items==null?null:items.getJSONArray("required");if(mode==0&&(required==null||required.isEmpty()))output=actual;else{var filtered=new JSONArray();if(required!=null&&!required.isEmpty())for(Object value:actual){if(!(value instanceof JSONObject row))throw error("原数据outputList行结构无效",403);var fields=new JSONObject();for(Object key:required){String name=(String)key;var property=items.getJSONObject("properties").getJSONObject(name);fields.put(name,row.containsKey(name)?row.get(name):CompletedModelOutputs.zero(property.getString("type")));}filtered.add(fields);}output=filtered;}}result.put("outputList",output);return result;}
    private JSONObject staticQa(RunRecord run,JSONObject execution,JSONObject node,JSONArray events,LinkedHashMap<String,Object> values){
        if(checkpoints==null)throw error("静态问答消费来源未装配",503);var cp=checkpoints.byPosition(run.getId(),run.getAttempt(),node.getString("id"),null,1);if(cp==null||!Objects.equals(run.getLeaseId(),cp.getLeaseId())||!Objects.equals(run.getId(),cp.getRunId())||!Objects.equals(run.getAttempt(),cp.getAttempt())||!Objects.equals(node.get("id"),cp.getNodeId())||cp.getIterationId()!=null||!"CONSUMED".equals(cp.getState()))throw error("静态问答没有当前真实消费",403);
        var p=node.getJSONObject("data").getJSONObject("nodeParam");String question=ModelMessageSources.renderQuestion(p.getString("question"),values);var asked=ModelMessageSources.sourceObject(cp.getQuestionJson());if(!Objects.equals(p.get("answerType"),asked.get("type"))||!Objects.equals(question,asked.get("content")))throw error("静态问答题面偏离固定输入",403);var envelope=ModelMessageSources.sourceObject(cp.getResumeDataJson());var reply=ModelMessageSources.sourceObject(envelope.getString("message"));if(!(reply.get("content") instanceof String content))throw error("静态问答回复无效",403);String action=reply.getString("event_type");var result=JSONObject.of("query",question,"content",content);
        if("ignore".equals(action)){if(p.getBooleanValue("needReply"))throw error("原问答不允许ignore",403);defaults(node,result,false);if("option".equals(p.get("answerType")))result.put("id","default");return result;}
        if(!"resume".equals(action))throw error("问答成功输出不能来自abort",403);
        if("option".equals(p.get("answerType"))){JSONObject selected=null;var options=p.getJSONArray("optionAnswer");var publicOptions=new JSONArray();for(Object raw:options){var option=(JSONObject)raw;var effective=ModelMessageSources.sourceObject(option.toJSONString(JSONWriter.Feature.WriteMapNullValue));effective.put("content",ModelMessageSources.renderQuestion(effective.getString("content"),values));publicOptions.add(JSONObject.of("id",effective.get("name"),"content_type",effective.getOrDefault("content_type","text"),"text",effective.get("content")));if(selected==null&&(content.equals(effective.get("name"))||effective.getIntValue("type")==1))selected=effective;}if(!ToolSchema.same(asked.get("option"),publicOptions))throw error("问答固定选项题面不一致",403);if(selected==null)throw error("原问答没有匹配或默认选项",403);result.putAll(selected);result.put("id",selected.get("name"));}
        var ordered=new JSONObject();for(Object raw:CompletedModelOutputs.outputs(node)){var field=(JSONObject)raw;String name=field.getString("name");ordered.put(name,result.containsKey(name)?result.get(name):CompletedModelOutputs.defaultValue(field));}return ordered;
    }
    private List<ModelOperationRecord> rows(RunRecord run,JSONObject node){if(models==null)throw error("模型原帧来源账本未装配",503);String resource=node.getJSONObject("data").getJSONObject("nodeParam").getString("modelResourceId");if(resource==null)throw error("模型上游没有固定资源",403);var all=models.forRunResource(run.getId(),resource);if(all.size()>1000)throw error("模型来源操作超过预算",503);var values=new ArrayList<ModelOperationRecord>();var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());var descriptor=assignment.getJSONObject("resourceSnapshots").getJSONObject(resource);for(var row:all)if(node.getString("id").equals(row.getNodeId())&&Objects.equals(run.getLeaseId(),row.getLeaseId())&&Objects.equals(run.getAttempt(),row.getAttempt())&&row.getIterationId()==null){
            if(!Objects.equals(run.getId(),row.getRunId())||!Objects.equals(run.getWorkerId(),row.getWorkerId())||!Objects.equals(run.getWorkflowVersionId(),row.getWorkflowVersionId())||!Objects.equals(run.getSpaceId(),row.getSpaceId())||!Objects.equals(run.getOwnerUserId(),row.getOwnerUserId())||!Objects.equals(assignment.get("dslHash"),row.getDslHash())||descriptor==null||!Objects.equals(descriptor.get("version"),row.getResourceVersion())||!Objects.equals(descriptor.get("provider"),row.getProvider())||!Integer.valueOf(1).equals(row.getGrantAcquired())||!Integer.valueOf(1).equals(row.getDispatchStarted()))throw error("模型来源操作固定范围不一致",403);
            var approval=ModelMessageSources.sourceObject(row.getApprovalJson());var candidate=approval.getJSONObject("candidate");if(candidate==null||!ToolSchema.same(node,ModelMessageSources.sourceObject(candidate.getString("nodeJson"))))throw error("模型来源操作不是当前固定节点",403);values.add(row);}
        return values;
    }
    /** Java 原 SDK 的成功节点输出须对应真实调用终结；length 的中心 FAILED/provider 保留。 */
    private JavaModelSemantics.Projection javaModel(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs){
        var history=histories==null?null:histories.getIfAvailable();
        JSONObject proof=null;ModelOperationRecord completed=null;
        if(history!=null&&history.executionContext(run)!=null){
            var found=rows(run,node);if(found.size()!=1)throw error("Java模型节点没有唯一当前调用事实",403);completed=found.getFirst();
            var candidate=ModelMessageSources.sourceObject(completed.getApprovalJson()).getJSONObject("candidate");
            boolean legacy=Objects.equals(JavaModelSemantics.historyLimit(node),0)
                    &&!history.hasJavaRound(run,node.getString("id"))&&!candidate.containsKey("javaHistoryJson")
                    &&("COMPLETED".equals(completed.getState())||"FAILED".equals(completed.getState())&&"provider".equals(completed.getFailureKind()));
            // 旧零历史已完成调用没有033轮次；仍逐项校核完整固定调用与全部原帧，不吞快照错误。
            if(!legacy)proof=history.javaSnapshot(run,node,verifiedInputs);
        }
        var projection=javaModel(run,node,verifiedInputs,null,proof);
        if(proof!=null)history.requireCompletedJava(run,node.getString("id"),completed.getId());
        return projection;
    }
    /** 历史回填沿用完整固定调用校核；historyProof必须来自中心已核快照。 */
    JavaModelSemantics.Projection javaModelHistory(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs,String operationId,JSONObject historyProof) {
        if(historyProof==null)throw error("Java历史完成缺少固定快照",403);
        return javaModel(run,node,verifiedInputs,operationId,historyProof);
    }
    private JavaModelSemantics.Projection javaModel(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs,String operationId,JSONObject historyProof){
        var found=rows(run,node);if(found.size()!=1)throw error("Java模型节点没有唯一当前调用事实",403);
        var row=found.getFirst();var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        if(operationId!=null && !Objects.equals(operationId,row.getId()))throw error("Java历史回填不是固定节点的唯一模型操作",403);
        var descriptor=assignment.getJSONObject("resourceSnapshots").getJSONObject(row.getResourceId());
        var candidate=ModelMessageSources.sourceObject(row.getApprovalJson()).getJSONObject("candidate");
        if(!"java".equals(run.getEngineProfile())||!"java".equals(candidate.getOrDefault("engineProfile","python"))||candidate.get("pythonHistoryJson")!=null
                ||!"openai".equals(row.getProvider())||descriptor==null||!"model".equals(descriptor.get("kind"))
                ||!Boolean.TRUE.equals(descriptor.get("enabled"))||!ToolSchema.same(descriptor.get("capabilities"),List.of("chat")))throw error("Java模型来源没有准确引擎与固定资源",403);
        var binding=JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"ownerUserId",run.getOwnerUserId(),"spaceId",run.getSpaceId(),"workflowVersionId",run.getWorkflowVersionId(),"dslHash",row.getDslHash(),"nodeId",node.get("id"),"iterationId",null,"resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider",row.getProvider());
        for(String key:binding.keySet())if(!candidate.containsKey(key)||!ToolSchema.same(binding.get(key),candidate.get(key)))throw error("Java模型批准来源scope不一致",403);
        if(!Objects.equals(row.getParametersJson(),candidate.get("parametersJson"))||!Objects.equals(DataRuntimeCatalog.sha(row.getParametersJson()),candidate.get("parametersHash"))
                ||!ToolSchema.same(assignment.get("input"),ModelMessageSources.sourceObject(candidate.getString("inputJson")))
                ||candidate.get("verifiedInputsJson")==null||!ToolSchema.same(verifiedInputs,ModelMessageSources.sourceObject(candidate.getString("verifiedInputsJson"))))throw error("Java模型批准不是当前真实输入来源",403);
        var parameters=ModelMessageSources.sourceObject(row.getParametersJson());
        var histories=historyProof==null?new JSONArray():historyProof.getJSONArray("history");
        if(historyProof==null&&candidate.get("javaHistoryJson")!=null)throw error("Java模型完成历史没有实际中心上下文",403);
        if(historyProof!=null && (!Objects.equals(candidate.get("javaHistoryJson"),RunJsonBoundary.canonicalFull(historyProof))
                || !ToolSchema.same(historyProof.get("messages"),JavaModelSemantics.messages(node,verifiedInputs,histories))))
            throw error("Java模型批准历史偏离中心固定快照",403);
        var expected=JSONObject.of("resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider","openai","modelName",descriptor.get("modelName"),"mode","chat","messages",JavaModelSemantics.messages(node,verifiedInputs,histories),"options",JavaModelSemantics.options(node));
        Integer history=JavaModelSemantics.historyLimit(node);if(history==null||historyProof==null&&history!=0)throw error("Java模型历史完成来源尚未接通",503);
        if(!ToolSchema.same(parameters,expected))throw error("Java模型请求偏离原固定算法",403);
        return javaProjection(row,parameters.getJSONObject("options"));
    }
    /** Java完成事件与历史回填共用同一原帧/账本终结判定，避免另设较低标准。 */
    JavaModelSemantics.Projection javaProjection(ModelOperationRecord row,JSONObject options) {
        if(!"openai".equals(row.getProvider()))throw error("Java模型完成来源不是固定OpenAI算法",403);
        var raw=frames(row,true,"java",options);var projection=JavaModelSemantics.projection(raw);
        boolean stopped="stop".equals(projection.finishReason());
        if(stopped?(!"COMPLETED".equals(row.getState())||row.getFailureKind()!=null):(!"FAILED".equals(row.getState())||!"provider".equals(row.getFailureKind())))throw error("Java模型终态与真实结束原因不一致",403);
        if(!Objects.equals("stream-finished:"+row.getId()+":"+row.getLastSeq()+":"+row.getFinishSeq(),row.getStopProofId()))throw error("Java模型没有准确流终结证明",403);
        JSONObject lastUsage=null;for(Object frame:raw){var usage=((JSONObject)frame).getJSONObject("usage");if(usage!=null)lastUsage=usage;}
        if(lastUsage==null?row.getUsageJson()!=null:row.getUsageJson()==null||!ToolSchema.same(lastUsage,ModelMessageSources.sourceObject(row.getUsageJson())))throw error("Java模型账本用量与原帧不一致",403);
        return projection;
    }
    private JSONArray frames(ModelOperationRecord row,boolean allowDecisionFailure){return frames(row,allowDecisionFailure,"python",null);}
    private JSONArray frames(ModelOperationRecord row,boolean allowDecisionFailure,String engine,JSONObject options){if(!"COMPLETED".equals(row.getState())&&!(allowDecisionFailure&&"FAILED".equals(row.getState())&&"provider".equals(row.getFailureKind())))throw error("模型来源没有真实终态",403);Long last=row.getLastSeq(),finish=row.getFinishSeq();if(last==null||finish==null||last<1||last>ModelOperationJson.MAX_FRAMES||finish<1||finish>last||!Integer.valueOf(1).equals(row.getStopConfirmed())||row.getStopProofId()==null)throw error("模型来源没有准确结束帧",403);var stored=models.frames(row.getId(),0,ModelOperationJson.MAX_FRAMES);if(stored.size()!=last)throw error("模型来源帧不完整",403);var result=new JSONArray();long seq=0,bytes=0;for(var frame:stored){if(!Objects.equals(row.getId(),frame.getOperationId())||frame.getSeq()!=++seq||!Objects.equals(DataRuntimeCatalog.sha(frame.getFrameJson()),frame.getFrameHash()))throw error("模型来源原帧哈希或顺序不一致",403);int size=frame.getFrameJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(frame.getFrameBytes()==null||size!=frame.getFrameBytes())throw error("模型来源原帧长度不一致",403);bytes+=size;var raw=ModelMessageSources.sourceObject(frame.getFrameJson());var facts=ModelProviderWire.facts(row.getProvider(),raw,engine,options);if(seq==finish&&!Objects.equals(row.getFinishReason(),facts.finish()))throw error("模型来源结束原因不一致",403);if(seq<finish&&facts.finish()!=null)throw error("模型来源含较早结束帧",403);if(seq>finish&&(!"openai".equals(row.getProvider())||!(raw.get("choices") instanceof JSONArray choices)||!choices.isEmpty()))throw error("模型来源结束后含非usage帧",403);result.add(raw);}if(bytes>ModelOperationJson.STREAM_BYTES||!Objects.equals(bytes,row.getResponseBytes()))throw error("模型来源累计字节不一致",403);return result;}
    private record PythonHistoryFacts(ModelOperationRecord operation,JSONArray rawFrames,JSONObject question,JSONObject answer) {}
    /** 原旧历史追加在完成回调及required输出写回之前；不伪造已接受的节点完成事件。 */
    JSONObject pythonHistory(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs) {
        var facts=pythonHistoryFacts(run,node,verifiedInputs);
        return JSONObject.of("question",facts.question(),"answer",facts.answer(),"operationId",facts.operation().getId());
    }
    private PythonHistoryFacts pythonHistoryFacts(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs) {
        PythonModelHistorySemantics.requireLegacy(node);
        if(!"python".equals(run.getEngineProfile()))throw error("Python历史不属于实际运行引擎",403);
        var assignment=ModelMessageSources.sourceObject(run.getAssignmentJson());
        if(!"python".equals(assignment.get("engineProfile")) || !ToolSchema.same(node,DataWorkerOperationService.find(assignment.getJSONObject("dsl"),node.getString("id"))))
            throw error("Python历史节点不是实际固定版本",403);
        var history=histories==null?null:histories.getIfAvailable();
        if(history==null)throw error("Python历史快照来源未装配",503);
        var proof=history.pythonSnapshot(run,node);var found=rows(run,node);
        if(found.size()!=1)throw error("Python历史没有唯一实际模型调用",403);
        var row=found.getFirst();var approval=ModelMessageSources.sourceObject(row.getApprovalJson());var candidate=approval.getJSONObject("candidate");
        if(!Objects.equals(ModelOperationJson.sha(ModelOperationJson.canonical(candidate,ModelOperationJson.REQUEST_BYTES)),approval.get("fingerprint"))
                || !Objects.equals(row.getApprovalRef(),approval.get("approvalRef")) || !Objects.equals(row.getRequestHash(),approval.get("requestHash")))
            throw error("Python历史模型批准指纹或操作关联不一致",403);
        var binding=JSONObject.of("runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"ownerUserId",run.getOwnerUserId(),"spaceId",run.getSpaceId(),"workflowVersionId",run.getWorkflowVersionId(),"dslHash",assignment.get("dslHash"),"nodeId",node.get("id"),"iterationId",null,"resourceId",row.getResourceId(),"resourceVersion",row.getResourceVersion(),"provider",row.getProvider());
        for(String key:binding.keySet())if(!candidate.containsKey(key) || !ToolSchema.same(binding.get(key),candidate.get(key)))throw error("Python历史模型批准scope不一致",403);
        if(!"python".equals(candidate.getOrDefault("engineProfile","python")) || candidate.get("javaHistoryJson")!=null
                || !Objects.equals(row.getParametersJson(),candidate.get("parametersJson")) || !Objects.equals(ModelOperationJson.sha(row.getParametersJson()),candidate.get("parametersHash"))
                || !ToolSchema.same(assignment.get("input"),ModelMessageSources.sourceObject(candidate.getString("inputJson")))
                || candidate.get("verifiedInputsJson")==null || !ToolSchema.same(verifiedInputs,ModelMessageSources.sourceObject(candidate.getString("verifiedInputsJson")))
                || !Objects.equals(RunJsonBoundary.canonicalFull(proof),candidate.get("pythonHistoryJson"))
                || candidate.containsKey("checkpointSourcesJson") && !"[]".equals(candidate.get("checkpointSourcesJson")))
            throw error("Python历史模型批准不是准确输入及冻结历史来源",403);
        var parameters=ModelMessageSources.sourceObject(row.getParametersJson());ModelOperationOptions.validateRequest(parameters,node);
        var descriptor=assignment.getJSONObject("resourceSnapshots").getJSONObject(row.getResourceId());
        var messages=ModelMessageSources.expected(node,verifiedInputs,new JSONArray());
        if(!Objects.equals(parameters.get("resourceId"),row.getResourceId()) || !Objects.equals(parameters.get("resourceVersion"),row.getResourceVersion())
                || !Objects.equals(parameters.get("provider"),row.getProvider()) || !Objects.equals(parameters.get("modelName"),descriptor.get("modelName"))
                || !ToolSchema.same(parameters.get("messages"),messages))throw error("Python历史请求偏离原固定算法",403);
        var raw=frames(row,false);var content=CompletedModelOutputs.content(node,raw);
        if(content.failed() || row.getFailureKind()!=null || !"stop".equals(row.getFinishReason())
                || !Objects.equals("stream-finished:"+row.getId()+":"+row.getLastSeq()+":"+row.getFinishSeq(),row.getStopProofId()))
            throw error("Python失败模型不能写成功历史",403);
        // 原async_execute的格式化也须成功；这里不执行其后变量池required/type校验。
        CompletedModelOutputs.project(node,raw);
        JSONObject lastUsage=null;
        for(Object frame:raw){var usage=ModelProviderWire.facts(row.getProvider(),(JSONObject)frame,"python",parameters.getJSONObject("options")).usage();if(usage!=null)lastUsage=usage;}
        if(lastUsage==null?row.getUsageJson()!=null:row.getUsageJson()==null || !ToolSchema.same(lastUsage,ModelMessageSources.sourceObject(row.getUsageJson())))
            throw error("Python历史模型用量偏离真实原帧",403);
        var currentUser=messages.getJSONObject(messages.size()-1);
        return new PythonHistoryFacts(row,raw,JSONObject.of("role","user","content",currentUser.get("content")),JSONObject.of("role","assistant","content",content.text()));
    }
    private JSONObject model(RunRecord run,JSONObject node,Map<String,Object> verifiedInputs){
        if(PythonModelHistorySemantics.legacy(node))return CompletedModelOutputs.project(node,pythonHistoryFacts(run,node,verifiedInputs).rawFrames());
        var found=rows(run,node);if(found.size()!=1)throw error("模型节点没有唯一当前调用事实",403);boolean decision="decision-making".equals(ModelNodeSemantics.type(node));return CompletedModelOutputs.project(node,frames(found.getFirst(),decision));
    }

    private JSONObject qa(RunRecord run,JSONObject execution,JSONObject node,JSONArray events,LinkedHashMap<String,Object> values){if(checkpoints==null)throw error("动态问答消费来源未装配",503);var p=node.getJSONObject("data").getJSONObject("nodeParam");int max=p.getJSONObject("directAnswer").getIntValue("maxRetryCounts");var actual=rows(run,node);var consumed=new HashSet<String>();String question=ModelMessageSources.renderQuestion(p.getString("question"),values);String initialQuestion=question;JSONObject result=null;
        for(int ordinal=1;ordinal<=max;ordinal++){var cp=checkpoints.byPosition(run.getId(),run.getAttempt(),node.getString("id"),null,ordinal);if(cp==null)break;if(!Objects.equals(run.getId(),cp.getRunId())||!Objects.equals(run.getLeaseId(),cp.getLeaseId())||!Objects.equals(run.getAttempt(),cp.getAttempt())||!Objects.equals(node.getString("id"),cp.getNodeId())||cp.getIterationId()!=null||!"CONSUMED".equals(cp.getState()))throw error("动态问答没有连续真实消费记录",403);var asked=ModelMessageSources.sourceObject(cp.getQuestionJson());var envelope=ModelMessageSources.sourceObject(cp.getResumeDataJson());var reply=ModelMessageSources.sourceObject(envelope.getString("message"));if(!"direct".equals(asked.get("type"))||!Objects.equals(question,asked.get("content"))||!(reply.get("content") instanceof String content))throw error("动态问答题面/回复来源不一致",403);
            var matching=actual.stream().filter(row->Objects.equals(cp.getId(),ModelMessageSources.sourceObject(row.getParametersJson()).get("checkpointId"))).toList();String action=reply.getString("event_type");
            if("ignore".equals(action)){if(p.getBooleanValue("needReply")||!matching.isEmpty())throw error("原问答ignore与模型事实不一致",403);result=JSONObject.of("query",initialQuestion,"content",content);defaults(node,result,false);}
            else if("resume".equals(action)){if(matching.size()!=1)throw error("问答每次消费须对应唯一模型调用",403);var row=matching.getFirst();consumed.add(row.getId());var raw=CompletedModelOutputs.content(node,frames(row,false));if(raw.failed())throw error("问答来源模型失败",403);var slot=slot(raw.text());if(slot.getJSONObject("incomplete").isEmpty()){result=JSONObject.of("query",initialQuestion,"content",content);result.putAll(slot.getJSONObject("completed"));defaults(node,result,true);var ordered=new JSONObject();for(Object value:CompletedModelOutputs.outputs(node)){var field=(JSONObject)value;String name=field.getString("name");ordered.put(name,result.containsKey(name)?result.get(name):CompletedModelOutputs.defaultValue(field));}result=CompletedModelOutputs.requiredTypes(node,ordered);}else{question=slot.getString("content");if(result!=null)throw error("问答完成后又出现补槽事实",403);}}
            else throw error("问答成功输出不能来自abort或未知回复",403);
            if(result!=null){if(checkpoints.byPosition(run.getId(),run.getAttempt(),node.getString("id"),null,ordinal+1)!=null)throw error("问答成功后仍有额外消费记录",403);break;}
        }
        if(result==null||actual.size()!=consumed.size())throw error("问答没有最终完整填槽及准确调用集合",403);return result;
    }
    private static void defaults(JSONObject node,JSONObject result,boolean absentOnly){for(Object value:CompletedModelOutputs.outputs(node)){var field=(JSONObject)value;String name=field.getString("name");if(Set.of("query","content").contains(name))continue;Object defaultValue=field.getJSONObject("schema").getOrDefault("default","");if(absentOnly)result.putIfAbsent(name,defaultValue);else result.put(name,defaultValue);}}
    private static JSONObject slot(String text){var match=java.util.regex.Pattern.compile("```json\\s*\\n?(.*?)\\n?```",java.util.regex.Pattern.DOTALL).matcher(text);Object parsed=CompletedModelOutputs.parse(match.find()?match.group(1).strip():text.strip(),true);if(!(parsed instanceof JSONObject result))throw error("问答原填槽输出不是对象",403);for(String key:List.of("role","content","completed","incomplete"))if(!result.containsKey(key))result.put(key,"role".equals(key)?"assistant":"content".equals(key)?"":new JSONObject());if(!(result.get("role") instanceof String)||!(result.get("content") instanceof String)||!(result.get("completed") instanceof JSONObject)||!(result.get("incomplete") instanceof JSONObject))throw error("问答原填槽输出结构无效",403);return result;}
}
