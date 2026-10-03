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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;

/** 原页流格式的纯投影；调用者须先通过既有运行权限，输入须来自中心持久记录。
 * 不创建运行、恢复许可或公开路由，不保存第二套状态。当前覆盖顶层纯节点/静态问答。
 */
public final class LegacyWorkflowStreamCodec {
    private static final Set<String> NODE_EVENTS=Set.of("node.started","node.completed","node.failed","text.delta");
    private static final Set<String> SUPPORTED_NODES=Set.of("node-start","node-end","if-else","text-joiner","message","question-answer");
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
        if(type!=null && type.startsWith("run."))return terminal(run,event,type,seq);
        if(!NODE_EVENTS.contains(type==null?"":type) && !"checkpoint".equals(type))return null;
        if(event.get("iterationId")!=null)throw new ServiceException("原页的迭代事件投影尚未核对",503);
        String nodeId=event.getString("nodeId");
        fixedNode(run,nodeId);
        JSONObject frame=event.getJSONObject("payload").getJSONObject("legacyFrame");
        if(frame==null)throw invalid();
        frame=JSON.parseObject(JSON.toJSONString(frame,JSONWriter.Feature.WriteMapNullValue));
        JSONObject step=frame.getJSONObject("workflow_step");
        JSONObject node=step==null?null:step.getJSONObject("node");
        if(!Objects.equals(run.getId(),frame.getString("id")) || node==null || !Objects.equals(nodeId,node.getString("id"))
                || !(frame.get("code") instanceof Integer) || !(frame.get("message") instanceof String))throw invalid();
        JSONArray choices=frame.getJSONArray("choices");
        if(choices==null || choices.size()!=1 || !(choices.get(0) instanceof JSONObject choice)
                || !(choice.get("delta") instanceof JSONObject))throw invalid();
        if(!"node.failed".equals(type) && frame.getIntValue("code")!=0)throw invalid();
        // 仅恢复原 DTO 声明的 nullable 字段；不猜填历史版本已丢失的任意输出。
        if(!node.containsKey("finish_reason"))node.put("finish_reason",null);
        if("checkpoint".equals(type))checkpoint(run,event,checkpoint,frame,node,choice);
        else {
            if("interrupt".equals(node.getString("finish_reason")) || frame.get("event_data")!=null)throw invalid();
            boolean ended="node.completed".equals(type)||"node.failed".equals(type);
            if(ended?!"stop".equals(node.getString("finish_reason")):node.get("finish_reason")!=null)throw invalid();
            if("node.completed".equals(type) && frame.getIntValue("code")!=0
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
        JSONArray nodes=assignment==null||assignment.getJSONObject("dsl")==null?null:assignment.getJSONObject("dsl").getJSONArray("nodes");
        if(nodes==null || nodes.isEmpty())throw invalid();
        for(Object raw:nodes) {
            if(!(raw instanceof JSONObject node) || !(node.get("id") instanceof String id))throw invalid();
            if(!SUPPORTED_NODES.contains(id.split("::",2)[0]))throw new ServiceException("该固定运行包含尚未核对的原页流节点",503);
        }
        if(assignment.get("resourceSnapshots") instanceof JSONObject resources && !resources.isEmpty())
            throw new ServiceException("带资源的原页运行流尚未核对",503);
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
