package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.CheckpointRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.service.RunJsonBoundary;
import com.ruoyi.paiflow.service.RunJsonCodec;
import com.ruoyi.paiflow.service.RunProtocolLimits;
import com.ruoyi.paiflow.service.RunLeaseService;
import com.ruoyi.paiflow.service.PureIterationPolicy;
import com.ruoyi.paiflow.service.DataNodeCompiler;
import com.ruoyi.paiflow.service.WorkflowModelCompiler;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/** 原页流格式的纯投影；调用者须先通过既有运行权限，输入须来自中心持久记录。
 * 不创建运行、恢复许可或公开路由，不保存第二套状态。资源帧只投影已固定的模型/数据/工具绑定。
 */
public final class LegacyWorkflowStreamCodec {
    private static final Set<String> NODE_EVENTS=Set.of("node.started","node.completed","node.failed","text.delta");
    private static final Set<String> SUPPORTED_NODES=Set.of("node-start","node-end","if-else","text-joiner","message","question-answer","node-variable",
        "spark-llm","extractor-parameter","decision-making","database","plugin");
    private static final Set<String> ITERATION_NODES=Set.of("iteration","iteration-node-start","iteration-node-end");
    private static final Set<String> PURE_ITERATION_NODES=Set.of("node-start","node-end","if-else","text-joiner","message",
        "iteration","iteration-node-start","iteration-node-end");
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private LegacyWorkflowStreamCodec() {}

    /** 不含合法原帧的辅助事件暂不投影，防止 reasoning.delta 重复推送。 */
    public static JSONObject project(RunRecord run,JSONObject event,CheckpointRecord checkpoint) {
        RunJsonCodec.require(run);
        supportedAssignment(run);
        RunJsonBoundary.check(event,RunProtocolLimits.EVENT_BYTES);
        if(event==null || !"paiflow.events.v1".equals(event.getString("schemaVersion"))
                || !Objects.equals(run.getId(),event.getString("runId"))
                || !Objects.equals(run.getAttempt(),event.getInteger("attempt"))
                || !Objects.equals(run.getLeaseId()==null?"center":run.getLeaseId(),event.getString("leaseId"))
                || !(event.get("payload") instanceof JSONObject))throw invalid();
        long seq=integer(event.get("seq"));
        if(seq<1 || run.getLastEventSeq()==null || seq>run.getLastEventSeq())throw invalid();
        String type=event.getString("eventType");
        if(type!=null && type.startsWith("run.")) {
            if(event.containsKey("nodeId") || event.containsKey("iterationId"))throw invalid();
            return terminal(run,event,type,seq);
        }
        String nodeId=event.getString("nodeId");
        if(event.containsKey("nodeId") || event.containsKey("iterationId")) {
            for(String field:Set.of("nodeId","iterationId"))
                if(event.containsKey(field) && (!(event.get(field) instanceof String value) || value.isEmpty()))throw invalid();
            var assignment=JSON.parseObject(run.getAssignmentJson());
            try {
                PureIterationPolicy.requireEventScope(assignment.getJSONObject("dsl"),assignment.getJSONObject("input"),nodeId,event.getString("iterationId"));
            } catch(ServiceException ex) { throw invalid(); }
        }
        if(!NODE_EVENTS.contains(type==null?"":type) && !"checkpoint".equals(type))return null;
        fixedNode(run,nodeId);
        boolean javaEngine="java".equals(engine(run));
        if(javaEngine && Set.of("node.started","node.completed","node.failed").contains(type)
                && !event.getJSONObject("payload").containsKey("legacyFrame")) {
            // 原Java Map生命周期事实不是LLMGenerate，不能制造第二份原页节点帧。
            if(!Objects.equals(nodeId,event.getJSONObject("payload").get("nodeId")))throw invalid();
            return null;
        }
        // 存量坏行也必须是实际JSON对象，禁止getter把字符串自动解析成可信原帧。
        if(!(event.getJSONObject("payload").get("legacyFrame") instanceof JSONObject rawFrame))throw invalid();
        JSONObject frame=JSON.parseObject(JSON.toJSONString(rawFrame,JSONWriter.Feature.WriteMapNullValue));
        JSONObject step=frame.getJSONObject("workflow_step");
        JSONObject node=step==null?null:step.getJSONObject("node");
        Object sourceId=frame.get("id");
        if(javaEngine) {
            // 原Java算法生成独立sid；它不是授权来源。运行作用域取已校验的持久event。
            if(!RunLeaseService.matchesLegacyFrameId(run,sourceId))throw invalid();
            frame.put("id",run.getId());
        } else if(!Objects.equals(run.getId(),sourceId))throw invalid();
        if(node==null || !Objects.equals(nodeId,node.getString("id"))
                || !(frame.get("code") instanceof Integer) || !(frame.get("message") instanceof String))throw invalid();
        JSONArray choices=frame.getJSONArray("choices");
        if(choices==null || choices.size()!=1 || !(choices.get(0) instanceof JSONObject choice)
                || !(choice.get("delta") instanceof JSONObject))throw invalid();
        if(!javaEngine && !"node.failed".equals(type) && frame.getIntValue("code")!=0)throw invalid();
        // 仅恢复原 DTO 声明的 nullable 字段；不猜填历史版本已丢失的任意输出。
        if(!node.containsKey("finish_reason"))node.put("finish_reason",null);
        if("checkpoint".equals(type))checkpoint(run,event,checkpoint,frame,node,choice);
        else {
            if("interrupt".equals(node.getString("finish_reason")) || frame.get("event_data")!=null)throw invalid();
            boolean ended=javaEngine?"stop".equals(node.getString("finish_reason")):"node.completed".equals(type)||"node.failed".equals(type);
            if(ended?!"stop".equals(node.getString("finish_reason")):node.get("finish_reason")!=null)throw invalid();
            if(javaEngine) {
                if(!"text.delta".equals(type) || !ended && frame.getIntValue("code")!=0)throw invalid();
            } else if("node.completed".equals(type) && frame.getIntValue("code")!=0
                    || "node.failed".equals(type) && frame.getIntValue("code")==0)throw invalid();
            // 原 Worker 的节点 stop 不能冒充中心 run 终态。
            choice.put("finish_reason",null);
        }
        return frame;
    }

    private static void fixedNode(RunRecord run,String nodeId) {
        JSONObject assignment=JSON.parseObject(run.getAssignmentJson());
        JSONArray nodes=assignment==null||assignment.getJSONObject("dsl")==null?null:assignment.getJSONObject("dsl").getJSONArray("nodes");
        if(nodeId==null || nodes==null || nodes.stream().noneMatch(raw->raw instanceof JSONObject n && nodeId.equals(n.getString("id"))))throw invalid();
    }
    private static void supportedAssignment(RunRecord run) {
        JSONObject assignment=JSON.parseObject(run.getAssignmentJson());
        try {
            requireSupportedExecution(assignment==null?null:assignment.getJSONObject("dsl"),assignment==null?null:assignment.getJSONObject("resourceSnapshots"),
                assignment==null?null:assignment.getJSONObject("input"),engine(run));
        } catch(ServiceException ex) {
            if(Integer.valueOf(400).equals(ex.getCode()) || Integer.valueOf(413).equals(ex.getCode()))throw invalid();
            throw ex;
        }
    }
    /** 调试入队前复用同一投影能力门；不能先运行有副作用节点再发现流不可消费。 */
    public static void requireSupportedExecution(JSONObject execution,JSONObject resources) {
        requireTopLevel(execution,resources,"python");
    }
    private static void requireTopLevel(JSONObject execution,JSONObject resources,String engine) {
        JSONArray nodes=execution==null?null:execution.getJSONArray("nodes");
        if(nodes==null || nodes.isEmpty())throw invalid();
        for(Object raw:nodes) {
            if(!(raw instanceof JSONObject node) || !(node.get("id") instanceof String id))throw invalid();
            if(!SUPPORTED_NODES.contains(id.split("::",2)[0]))throw new ServiceException("该固定运行包含尚未核对的原页流节点",503);
        }
        requireResourceBindings(execution,resources,engine);
    }
    private static void requireResourceBindings(JSONObject execution,JSONObject resources,String engine) {
        JSONObject fixed=resources==null?new JSONObject():resources;Set<String> used=new java.util.HashSet<>();
        boolean dataNode=false;
        for(Object raw:execution.getJSONArray("nodes")) {
            var node=(JSONObject)raw;String type=NodeCatalog.typeOf(node.getString("id"));
            if("database".equals(type)){dataNode=true;continue;}
            if(!WorkflowModelCompiler.isModelNode(node))continue;
            JSONObject data=node.getJSONObject("data"),params=data==null?null:data.getJSONObject("nodeParam");
            if(params==null || !(params.get("modelResourceId") instanceof String id) || !(fixed.get(id) instanceof JSONObject descriptor))
                throw new ServiceException("原页模型流缺少中心固定资源",503);
            WorkflowModelCompiler.validateDescriptor(id,descriptor);
            if(!Objects.equals(params.get("source"),descriptor.get("provider")) || !Objects.equals(params.get("domain"),descriptor.get("modelName")))throw invalid();
            used.add(id);
        }
        if(dataNode) {
            JSONObject copy=JSON.parseObject(execution.toJSONString(JSONWriter.Feature.WriteMapNullValue));
            used.addAll(DataNodeCompiler.reconstruct(copy,fixed));
            if(!RunJsonBoundary.canonicalFull(copy).equals(RunJsonBoundary.canonicalFull(execution)))throw invalid();
        }
        JSONObject toolCopy=JSON.parseObject(execution.toJSONString(JSONWriter.Feature.WriteMapNullValue));
        used.addAll(WorkflowModelCompiler.reconstructToolBindings(toolCopy,fixed,engine));
        if(!RunJsonBoundary.canonicalFull(toolCopy).equals(RunJsonBoundary.canonicalFull(execution)))throw invalid();
        if(!used.equals(fixed.keySet()))throw new ServiceException("原页运行流含未使用或缺失的固定资源",503);
    }
    /** 只有取得固定输入后才允许原单层纯迭代；旧两参入口仍保持保守。 */
    public static void requireSupportedExecution(JSONObject execution,JSONObject resources,JSONObject input) {
        requireSupportedExecution(execution,resources,input,"python");
    }
    public static void requireSupportedExecution(JSONObject execution,JSONObject resources,JSONObject input,String engine) {
        if(!Set.of("java","python").contains(engine==null?"":engine))throw invalid();
        JSONArray nodes=execution==null?null:execution.getJSONArray("nodes");
        if(nodes==null || nodes.isEmpty())throw invalid();
        boolean iteration=false;
        for(Object raw:nodes) {
            if(!(raw instanceof JSONObject node) || !(node.get("id") instanceof String id))throw invalid();
            if(ITERATION_NODES.contains(id.split("::",2)[0]))iteration=true;
        }
        if(!iteration) { requireTopLevel(execution,resources,engine);return; }
        for(Object raw:nodes) {
            String id=((JSONObject)raw).getString("id");
            if(!PURE_ITERATION_NODES.contains(id.split("::",2)[0]))throw new ServiceException("原页单层迭代流的混合节点尚未接通",503);
        }
        if(resources!=null && !resources.isEmpty())throw new ServiceException("带资源的原页迭代流尚未核对",503);
        PureIterationPolicy.validate(execution,input);
    }
    private static String engine(RunRecord run) {
        // 旧单元/历史入口未传引擎时沿原Python投影；真实Run由固定中心字段选择。
        String engine=run.getEngineProfile();
        if(engine==null)engine="python";
        if(!Set.of("java","python").contains(engine))throw invalid();
        return engine;
    }
    private static void checkpoint(RunRecord run,JSONObject event,CheckpointRecord value,JSONObject frame,JSONObject node,JSONObject choice) {
        JSONObject payload=event.getJSONObject("payload"),data=frame.getJSONObject("event_data");
        if(value==null || !Objects.equals(value.getRunId(),run.getId()) || !Objects.equals(value.getLeaseId(),run.getLeaseId())
                || !Objects.equals(value.getAttempt(),run.getAttempt()) || !Objects.equals(value.getNodeId(),event.getString("nodeId"))
                || value.getIterationId()!=null || !value.getNodeId().startsWith("question-answer::")
                || !Objects.equals(value.getId(),payload.getString("checkpointId")) || data==null
                || !Objects.equals(value.getId(),data.getString("event_id")) || !"interrupt".equals(data.getString("event_type"))
                || !"interrupt".equals(node.getString("finish_reason")) || !"interrupt".equals(choice.getString("finish_reason"))
                || value.getNeedReply()==null || (value.getNeedReply()!=0 && value.getNeedReply()!=1)
                || !(data.get("need_reply") instanceof Boolean) || !Objects.equals(value.getNeedReply()==1,data.get("need_reply"))
                || !Objects.equals(JSON.parseObject(value.getQuestionJson()),data.getJSONObject("value")))throw invalid();
        // 旧事件的 revision 可以低于已回复/已消费记录；禁止伪造未来 revision。
        long revision=integer(payload.get("revision"));
        if(revision<0 || value.getRevision()==null || revision>value.getRevision())throw invalid();
        data.put("value",JSON.parseObject(value.getQuestionJson()));
    }
    private static JSONObject terminal(RunRecord run,JSONObject event,String type,long seq) {
        String expected="COMPLETED".equals(run.getState())?"run.completed":"CANCELLED".equals(run.getState())?"run.cancelled":"run.failed";
        JSONObject result=run.getResultJson()==null?null:JSON.parseObject(run.getResultJson());
        if(!TERMINAL.contains(run.getState()==null?"":run.getState()) || !expected.equals(type) || seq!=run.getLastEventSeq()
                || result==null || !Objects.equals(run.getState(),result.getString("state"))
                || !Objects.equals(run.getState(),event.getJSONObject("payload").getString("state")))throw invalid();
        Instant at;
        try { at=Instant.parse(event.getString("timestamp")); } catch(RuntimeException ex) { throw invalid(); }
        boolean success="COMPLETED".equals(run.getState());
        // 500/499 是中心终态的流状态码；不声称它们是原节点错误码。
        int code=success?0:"CANCELLED".equals(run.getState())?499:500;
        JSONObject error=result.getJSONObject("error");
        String message=success?"Success":"CANCELLED".equals(run.getState())?"运行已取消":"中心确认运行失败";
        if(error!=null && error.get("message") instanceof String safe && !safe.isBlank())message=safe;
        JSONObject node=JSONObject.of("id","flow_obj","finish_reason","stop","inputs",new JSONObject(),"outputs",new JSONObject());
        JSONObject frame=JSONObject.of("code",code,"message",message,"id",run.getId(),"created",at.getEpochSecond(),
            "workflow_step",JSONObject.of("node",node,"seq",seq,"progress",1),
            "choices",JSONArray.of(JSONObject.of("index",0,"finish_reason","stop","delta",JSONObject.of("role","assistant","content","","reasoning_content",""))));
        JSONObject output=result.getJSONObject("output");
        if(output!=null && output.get("usage") instanceof JSONObject usage)frame.put("usage",usage);
        if(run.getExecutionStartedAt()!=null) {
            long millis=Duration.between(run.getExecutionStartedAt().toInstant(ZoneOffset.UTC),at).toMillis();
            if(millis<0)throw invalid();
            frame.put("executedTime",millis/1000.0);
        }
        return frame;
    }
    private static long integer(Object value) {
        if(!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte))throw invalid();
        return ((Number)value).longValue();
    }
    private static ServiceException invalid(){return new ServiceException("原页运行事件与中心固定记录不一致",409);}
}
