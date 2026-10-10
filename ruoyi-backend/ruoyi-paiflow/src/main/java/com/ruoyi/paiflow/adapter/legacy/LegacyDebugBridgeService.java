package com.ruoyi.paiflow.adapter.legacy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.CheckpointMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.service.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 原调试协议仅绑定现有版本、Run与Checkpoint；不保存第二套运行状态。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class LegacyDebugBridgeService {
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private final WorkflowService workflows;
    private final DefaultPersonalSpaceService defaults;
    private final ResourceAccessService access;
    private final RunService runs;
    private final RunMapper mapper;
    private final CheckpointService checkpoints;
    private final CheckpointMapper checkpointMapper;
    private org.springframework.beans.factory.ObjectProvider<RunHistoryService> histories;
    @org.springframework.beans.factory.annotation.Autowired public void useHistories(org.springframework.beans.factory.ObjectProvider<RunHistoryService> provider){histories=provider;}

    public LegacyDebugBridgeService(WorkflowService workflows,DefaultPersonalSpaceService defaults,ResourceAccessService access,
            RunService runs,RunMapper mapper,CheckpointService checkpoints,CheckpointMapper checkpointMapper) {
        this.workflows=workflows;this.defaults=defaults;this.access=access;this.runs=runs;this.mapper=mapper;
        this.checkpoints=checkpoints;this.checkpointMapper=checkpointMapper;
    }
    public record Attachment(String runId,long workflowVersionId,long spaceId,JSONObject receipt,long after) {
        public Attachment { receipt=copy(receipt); }
        @Override public JSONObject receipt(){return copy(receipt);}
    }
    public record Frame(String id,JSONObject data) {}
    public record Page(List<Frame> frames,long scannedThrough,long watermark,String state,boolean waiting,boolean terminal) {}
    public record Reply(Attachment attachment,JSONObject checkpoint) {}
    public record DisplayBinding(String runId,long workflowId,long workflowVersionId,long spaceId,long ownerUserId,long eventWatermark) {}

    private long space(String header,String permission) {
        var selection=defaults.select(header,null,permission);
        Long id=defaults.existing(selection,permission);
        if(id==null)throw new ServiceException("默认个人调试流程不存在或不可访问",404);
        return id;
    }
    private WorkflowService.DebugSnapshot snapshot(JSONObject body,long spaceId) {
        if(!(body.get("debugReceipt") instanceof Map<?,?>))throw bad("请提供本次构建回执");
        JSONObject receipt=body.getJSONObject("debugReceipt");
        var debug=workflows.requireDebugReceipt(receipt,spaceId);
        if(!Objects.equals(debug.snapshot().flowId(),body.getString("flow_id")))throw new ServiceException("调试回执不属于本次流程",409);
        boolean comparison="comparison".equals(debug.mode());
        if(comparison) {
            if(!Objects.equals(debug.comparisonId(),body.get("version")) || !Boolean.TRUE.equals(body.get("promptDebugger")))
                throw new ServiceException("比较调试来源与本次构建不一致",409);
        } else if(body.containsKey("version") || (body.containsKey("promptDebugger") && !Boolean.FALSE.equals(body.get("promptDebugger"))))
            throw new ServiceException("普通调试不能引用比较或历史标签",409);
        return debug;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Attachment chat(JSONObject body,String header,String lastEventId) {
        RunJsonBoundary.fields(body,Set.of("flow_id","inputs","chatId","regen","debugReceipt","requestKey"),Set.of("version","promptDebugger"));
        RunJsonBoundary.check(body,RunProtocolLimits.INPUT_BYTES);
        long actor=access.requirePermission("paiflow:run:create");
        long spaceId=space(header,"paiflow:run:create");var debug=snapshot(body,spaceId);
        if(!(body.get("inputs") instanceof Map<?,?>) || !(body.get("regen") instanceof Boolean))throw bad("原调试输入和重新生成标识无效");
        // MVC的Map/List是同一JSON输入；深转换真实数组并保全显式null，禁止浅getter留下List。
        JSONObject input=JSON.parseObject(JSON.toJSONString(body.get("inputs"),JSONWriter.Feature.WriteMapNullValue));
        LegacyWorkflowStreamCodec.requireSupportedExecution(debug.snapshot().execution(),debug.snapshot().resourceSnapshots(),input,debug.snapshot().engineProfile());
        text(body,"chatId",128);String key=key(body,"requestKey");
        if(access.requirePermission("paiflow:run:create")!=actor)throw new ServiceException("调试创建主体已变化",403);
        var run=runs.createDebug(debug.snapshot().workflowVersionId(),debug.snapshot().engineProfile(),input,key,900);
        var history=histories==null?null:histories.getIfAvailable();
        if(history!=null)history.bindDebug(run.runId(),debug.snapshot().flowId(),body.getString("chatId"));
        long after=cursor(lastEventId,run.runId(),run.lastEventSeq());
        return new Attachment(run.runId(),run.workflowVersionId(),spaceId,body.getJSONObject("debugReceipt"),after);
    }
    private RunRecord boundRun(String runId,long spaceId,WorkflowService.DebugSnapshot debug,String permission,boolean lock) {
        long actor=access.requirePermission(permission);
        var initial=mapper.find(runId);
        if(initial==null || !Objects.equals(initial.getSpaceId(),spaceId))throw new ServiceException("调试运行不存在或不可访问",404);
        access.requireSpace(spaceId,permission,3,lock);
        var run=lock?mapper.lock(runId):mapper.find(runId);
        if(run==null || !Objects.equals(run.getSpaceId(),spaceId) || !Objects.equals(run.getWorkflowId(),debug.snapshot().workflowId())
                || !Objects.equals(run.getWorkflowVersionId(),debug.snapshot().workflowVersionId()) || !Objects.equals(run.getOwnerUserId(),actor))
            throw new ServiceException("调试运行不属于本次回执与发起者",404);
        RunJsonCodec.require(run);
        requireFixedRun(debug,run);
        if(access.requirePermission(permission)!=actor)throw new ServiceException("调试读取主体已变化",403);
        return run;
    }
    /** 显示段可在QA等待或运行中保存；关联校验与流读取共用真实版本/Run入口。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public DisplayBinding requireDisplayRun(long workflowId,String runId,String header) {
        long actor=access.requirePermission("paiflow:workflow:edit");
        if(access.requirePermission("paiflow:run:query")!=actor)throw new ServiceException("显示记录主体已变化",403);
        long spaceId=space(header,"paiflow:workflow:edit");var initial=mapper.find(runId);
        if(initial==null || !Objects.equals(initial.getSpaceId(),spaceId) || !Objects.equals(initial.getWorkflowId(),workflowId)
                || !Objects.equals(initial.getOwnerUserId(),actor) || initial.getWorkflowVersionId()==null)
            throw new ServiceException("显示记录运行不存在或不属于本人工作流",404);
        var debug=workflows.requireDisplayDebugVersion(initial.getWorkflowVersionId(),spaceId);
        var run=boundRun(runId,spaceId,debug,"paiflow:run:query",true);
        if(!Objects.equals(run.getWorkflowId(),workflowId))throw new ServiceException("显示记录运行不属于当前工作流",404);
        access.requireSpace(spaceId,"paiflow:workflow:edit",3,false);
        if(access.requirePermission("paiflow:workflow:edit")!=actor)throw new ServiceException("显示记录主体已变化",403);
        return new DisplayBinding(run.getId(),run.getWorkflowId(),run.getWorkflowVersionId(),spaceId,actor,run.getLastEventSeq());
    }
    /** 固定Run输入供迭代归属检查；列关联相同不能替代执行副本与回执快照的一致性。 */
    private static void requireFixedRun(WorkflowService.DebugSnapshot debug,RunRecord run) {
        JSONObject assignment;
        try { assignment=JSON.parseObject(run.getAssignmentJson()); }
        catch(RuntimeException ex) { throw new ServiceException("调试运行固定执行副本无效",409); }
        var fixed=debug.snapshot();
        if(DslCodec.V4.equals(fixed.executionCodec()) && !DslCodec.V4.equals(assignment==null?null:assignment.get("executionCodec"))
                || assignment!=null && assignment.containsKey("executionCodec") && !Objects.equals(fixed.executionCodec(),assignment.get("executionCodec")))
            throw new ServiceException("调试运行执行格式与固定回执不一致",409);
        if(assignment==null || !Objects.equals(Long.toString(fixed.workflowVersionId()),assignment.get("workflowVersionId"))
                || !Objects.equals(fixed.dslHash(),assignment.get("dslHash"))
                || !Objects.equals(fixed.canonicalDslBase64(),assignment.get("dslCanonicalBase64"))
                || !Objects.equals(fixed.engineProfile(),assignment.get("engineProfile"))
                || !(assignment.get("dsl") instanceof JSONObject execution) || !Objects.equals(fixed.execution(),execution)
                || !(assignment.get("resourceSnapshots") instanceof JSONObject resources) || !Objects.equals(fixed.resourceSnapshots(),resources)
                || !(assignment.get("input") instanceof JSONObject input))
            throw new ServiceException("调试运行执行副本与本次回执不一致",409);
        LegacyWorkflowStreamCodec.requireSupportedExecution(execution,resources,input,fixed.engineProfile());
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Reply resume(JSONObject body,String header,String lastEventId) {
        RunJsonBoundary.fields(body,Set.of("flow_id","eventId","eventType","runId","debugReceipt","expectedRevision","replyKey"),Set.of("content","version","promptDebugger"));
        RunJsonBoundary.check(body,RunProtocolLimits.INPUT_BYTES);
        long spaceId=space(header,"paiflow:run:resume");var debug=snapshot(body,spaceId);
        String runId=text(body,"runId",128),checkpointId=text(body,"eventId",128),eventType=text(body,"eventType",16);
        var run=boundRun(runId,spaceId,debug,"paiflow:run:resume",true);
        long after=cursor(lastEventId,runId,run.getLastEventSeq());
        Object content=body.get("content");
        // 原stop的abort没有content；仅这一种明确原请求窄补空串。
        if(!body.containsKey("content") && "abort".equals(eventType))content="";
        if(!(content instanceof String))throw bad("问答回复内容须为字符串");
        var response=checkpoints.reply(runId,checkpointId,JSONObject.of("expectedRevision",decimal(body.get("expectedRevision")),
                "idempotencyKey",key(body,"replyKey"),"event_type",eventType,"content",content));
        return new Reply(new Attachment(runId,run.getWorkflowVersionId(),spaceId,body.getJSONObject("debugReceipt"),after),response);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public RunService.View cancel(JSONObject body,String header) {
        RunJsonBoundary.fields(body,Set.of("flow_id","runId","debugReceipt"),Set.of("version","promptDebugger"));
        RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        long spaceId=space(header,"paiflow:run:cancel");var debug=snapshot(body,spaceId);
        String id=text(body,"runId",128);boundRun(id,spaceId,debug,"paiflow:run:cancel",true);
        return runs.cancel(id);
    }
    /** 仅在短事务内固定水位和读取数据；发送、等待和网络关闭在事务外。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public Page page(Attachment attachment,long after) {
        var receipt=attachment.receipt();var debug=workflows.requireDebugReceipt(receipt,attachment.spaceId());
        var run=boundRun(attachment.runId(),attachment.spaceId(),debug,"paiflow:run:query",true);
        if(run.getWorkflowVersionId()!=attachment.workflowVersionId())throw new ServiceException("流版本与回执不一致",409);
        long watermark=run.getLastEventSeq();if(after<0 || after>watermark)throw new ServiceException("流游标超出真实事件水位",409);
        var rows=mapper.eventsThrough(run.getId(),after,watermark,32);
        if(after<watermark && rows.isEmpty())throw new ServiceException("部分流事件不可续读",410);
        var frames=new ArrayList<Frame>();long scanned=after;boolean waiting=false;
        for(var row:rows) {
            if(row.seq()!=scanned+1 || row.seq()>watermark)throw new ServiceException("部分流事件不可续读",410);
            JSONObject event=JSON.parseObject(row.eventJson());
            if(event==null || decimalNumber(event.get("seq"))!=row.seq())throw new ServiceException("事件记录与数据库序号不一致",409);
            if(!(event.get("payload") instanceof Map<?,?>))throw new ServiceException("中心事件内容无效",409);
            var payload=event.getJSONObject("payload");
            var checkpoint="checkpoint".equals(event.getString("eventType"))
                    ?checkpointMapper.find(payload.getString("checkpointId")):null;
            JSONObject frame=LegacyWorkflowStreamCodec.project(run,event,checkpoint);
            if(frame!=null) {
                if(checkpoint!=null) {
                    var data=frame.getJSONObject("event_data");
                    data.put("checkpoint_revision",Long.toString(decimalNumber(payload.get("revision"))));
                    data.put("checkpoint_state",checkpoint.getState());
                    var now=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
                    waiting="WAITING".equals(checkpoint.getState()) && "RUNNING".equals(run.getState())
                            && checkpoint.getDeadline().isAfter(now) && run.getDeadline().isAfter(now)
                            && run.getLeaseExpiresAt()!=null && run.getLeaseExpiresAt().isAfter(now);
                    data.put("checkpoint_waiting",waiting);
                }
                frames.add(new Frame(run.getId()+":"+row.seq(),frame));
            }
            scanned=row.seq();
            if(waiting)break;
        }
        boolean terminal=TERMINAL.contains(run.getState()) && scanned==watermark;
        return new Page(List.copyOf(frames),scanned,watermark,run.getState(),waiting,terminal);
    }
    public static long cursor(String value,String runId,long watermark) {
        if(value==null || value.isEmpty())return 0;
        String prefix=runId+":";
        if(!value.startsWith(prefix))throw bad("流游标不属于本次运行");
        long seq=decimal(value.substring(prefix.length()));
        if(seq>watermark)throw new ServiceException("流游标超出真实事件水位",409);
        return seq;
    }
    private static String key(JSONObject body,String field) {
        String value=text(body,field,128);if(!value.matches("[A-Za-z0-9_-]{8,128}"))throw bad("调试幂等标识无效");return value;
    }
    private static String text(JSONObject body,String field,int max) {
        if(!(body.get(field) instanceof String value) || value.isEmpty() || value.length()>max)throw bad("调试字段"+field+"无效");return value;
    }
    private static long decimal(Object value) {
        if(!(value instanceof String text) || !text.matches("0|[1-9][0-9]{0,18}"))throw bad("调试版本或游标无效");
        try{return Long.parseLong(text);}catch(NumberFormatException ex){throw bad("调试版本或游标无效");}
    }
    private static long decimalNumber(Object value) {
        if(!(value instanceof Number))throw new ServiceException("中心事件版本或序号无效",409);
        try{long result=new java.math.BigDecimal(value.toString()).longValueExact();if(result<0)throw new ArithmeticException();return result;}
        catch(ArithmeticException | NumberFormatException ex){throw new ServiceException("中心事件版本或序号无效",409);}
    }
    private static JSONObject copy(JSONObject value){return JSON.parseObject(value.toJSONString(JSONWriter.Feature.WriteMapNullValue));}
    private static ServiceException bad(String message){return new ServiceException(message,400);}
}
