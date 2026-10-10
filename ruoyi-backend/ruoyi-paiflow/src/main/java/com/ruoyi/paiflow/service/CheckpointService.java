package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.CheckpointRecord;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.CheckpointMapper;
import com.ruoyi.paiflow.mapper.RunMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 用户回复由中心授权，原event_type/content和metadata交由原QA算法处理。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class CheckpointService {
    private final CheckpointMapper checkpoints;private final RunMapper runs;private final RunLeaseService leases;
    private final ResourceAccessService access;private final IdentityMappingService identities;private final SysPermissionService permissions;private final Clock clock;
    @Autowired public CheckpointService(CheckpointMapper checkpoints,RunMapper runs,RunLeaseService leases,ResourceAccessService access,IdentityMappingService identities,SysPermissionService permissions) {
        this(checkpoints,runs,leases,access,identities,permissions,Clock.systemUTC());
    }
    CheckpointService(CheckpointMapper checkpoints,RunMapper runs,RunLeaseService leases,ResourceAccessService access,IdentityMappingService identities,SysPermissionService permissions,Clock clock) {
        this.checkpoints=checkpoints;this.runs=runs;this.leases=leases;this.access=access;this.identities=identities;this.permissions=permissions;this.clock=clock;
    }
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private static long integer(Object value) {
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))throw new ServiceException("检查点序号须为整数",400);
        return ((Number)value).longValue();
    }
    private static String text(JSONObject body,String field) {
        int limit=Set.of("nodeId","iterationId").contains(field)?255:256;
        if(!(body.get(field) instanceof String value) || value.isEmpty() || value.length()>limit)throw new ServiceException("检查点"+field+"无效",400);return value;
    }
    private static String key(JSONObject body) {
        String value=text(body,"idempotencyKey");if(!value.matches("[A-Za-z0-9_-]{8,128}"))throw new ServiceException("检查点幂等标识无效",400);return value;
    }
    private void save(CheckpointRecord value) {
        if(checkpoints.update(value)!=1)throw new ServiceException("检查点版本已变化",409);value.setRevision(value.getRevision()+1);
    }
    private CheckpointRecord scoped(RunRecord run,String id) {
        var initial=checkpoints.find(id);requireScope(run,initial);
        var value=checkpoints.lock(id);requireScope(run,value);return value;
    }
    private static void requireScope(RunRecord run,CheckpointRecord value) {
        if(value==null || !run.getId().equals(value.getRunId()) || !java.util.Objects.equals(run.getLeaseId(),value.getLeaseId()) || !run.getAttempt().equals(value.getAttempt()))throw new ServiceException("检查点不存在或不属于当前租约",404);
    }
    private JSONObject view(CheckpointRecord value) {
        return JSONObject.of("checkpointId",value.getId(),"revision",value.getRevision(),"state",value.getState(),
            "deadline",value.getDeadline().toInstant(ZoneOffset.UTC).toString(),"stopped",Set.of("CONSUMED","EXPIRED","CANCELLED").contains(value.getState()));
    }
    private void closeIfEnded(RunRecord run,CheckpointRecord value) {
        if(!Set.of("WAITING","RESUMED").contains(value.getState()))return;
        if(!Set.of("RUNNING","CANCEL_REQUESTED").contains(run.getState()) || "CANCEL_REQUESTED".equals(run.getState())) {
            value.setState("CANCELLED");save(value);
        } else if(!value.getDeadline().isAfter(now()) || !run.getDeadline().isAfter(now()) || run.getLeaseExpiresAt()==null || !run.getLeaseExpiresAt().isAfter(now())) {
            value.setState("EXPIRED");save(value);
        }
    }
    private static JSONObject definition(RunRecord run,String nodeId) {
        for(Object item:JSON.parseObject(run.getAssignmentJson()).getJSONObject("dsl").getJSONArray("nodes")) {
            if(item instanceof JSONObject node && nodeId.equals(node.getString("id")) && nodeId.startsWith("question-answer::")) {
                if(node.get("data") instanceof JSONObject data && data.get("nodeParam") instanceof JSONObject params)return params;
            }
        }
        throw new ServiceException("固定版本的问答节点或参数未接通",400);
    }
    private static void question(JSONObject value,JSONObject params) {
        RunJsonBoundary.fields(value,Set.of("type","content"),Set.of("option"));
        if(!(value.get("content") instanceof String) || !java.util.Objects.equals(value.get("type"),params.get("answerType")) || !Set.of("option","direct").contains(value.getString("type")))throw new ServiceException("问答题面与固定类型不符",400);
        if("direct".equals(value.getString("type"))) {
            if(value.containsKey("option"))throw new ServiceException("直接问答不能注入选项",400);return;
        }
        if(params.containsKey("optionAnswer") && !(params.get("optionAnswer") instanceof com.alibaba.fastjson2.JSONArray))throw new ServiceException("固定问答选项须为数组",400);
        if(value.containsKey("option") && !(value.get("option") instanceof com.alibaba.fastjson2.JSONArray))throw new ServiceException("题面选项须为数组",400);
        var configured=params.getJSONArray("optionAnswer");var options=value.getJSONArray("option");
        // 原InterruptData在无选项时省略option，不凭空生成A/default。
        if(configured==null || configured.isEmpty()) { if(options!=null && !options.isEmpty())throw new ServiceException("题面不能新增未配置选项",400);return; }
        if(options==null || options.size()!=configured.size())throw new ServiceException("题面选项数量与固定版本不符",400);
        for(int index=0;index<configured.size();index++) {
            if(!(options.get(index) instanceof JSONObject option) || !(configured.get(index) instanceof JSONObject original))throw new ServiceException("题面选项无效",400);
            RunJsonBoundary.fields(option,Set.of("id","text","content_type"),Set.of());
            if(!(option.get("text") instanceof String) || !(option.get("content_type") instanceof String) || !java.util.Objects.equals(option.get("id"),original.get("name")) || !java.util.Objects.equals(option.get("content_type"),original.get("content_type")))throw new ServiceException("题面选项顺序、名称或内容类型与固定版本不符",400);
        }
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject create(String runId,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","nodeId","ordinal","question","needReply","timeoutSeconds","idempotencyKey"),Set.of("iterationId"));
        RunJsonBoundary.check(body,RunProtocolLimits.RESULT_BYTES);String leaseId=text(body,"leaseId"),nodeId=text(body,"nodeId"),requestKey=key(body);
        int attempt=RunLeaseService.attempt(body);long ordinal=integer(body.get("ordinal")),timeout=integer(body.get("timeoutSeconds"));
        if(ordinal<1 || ordinal>5 || timeout<60 || timeout>300 || timeout%60!=0 || !(body.get("needReply") instanceof Boolean) || !(body.get("question") instanceof JSONObject))throw new ServiceException("检查点题面、轮次或等待时间无效",400);
        String iterationId=body.containsKey("iterationId")?text(body,"iterationId"):null;
        if(iterationId!=null)throw new ServiceException("嵌套迭代问答的中心归属尚未接通",503);
        var run=leases.requireActiveLease(runId,worker,leaseId,attempt);
        RunLeaseService.requireExecutionBegun(run);
        if("CANCEL_REQUESTED".equals(run.getState()))throw new ServiceException("运行已请求取消，不能新增检查点",409);
        var params=definition(run,nodeId);question(body.getJSONObject("question"),params);
        if(!(params.get("needReply") instanceof Boolean) || !params.get("needReply").equals(body.get("needReply")) || integer(params.get("timeout"))*60!=timeout)throw new ServiceException("问答等待策略与固定版本不符",400);
        int maxOrdinal=1;
        if("direct".equals(params.getString("answerType"))) {
            if(!(params.get("directAnswer") instanceof JSONObject direct) || !(direct.get("handleResponse") instanceof Boolean))throw new ServiceException("直接问答处理策略未接通",400);
            if(direct.getBooleanValue("handleResponse")) {
                long retries=integer(direct.get("maxRetryCounts"));if(retries<1 || retries>5)throw new ServiceException("问答重问上限无效",400);maxOrdinal=(int)retries;
            }
        }
        if(ordinal>maxOrdinal)throw new ServiceException("重问轮次超出固定版本策略",400);
        String hash=RunJsonBoundary.hash(body);var existing=checkpoints.byRequest(runId,attempt,requestKey);
        if(existing!=null) {
            if(!hash.equals(existing.getRequestHash()))throw new ServiceException("检查点幂等标识用于不同题面",409);
            var confirmed=scoped(run,existing.getId());closeIfEnded(run,confirmed);return view(confirmed);
        }
        if(checkpoints.byPosition(runId,attempt,nodeId,iterationId,(int)ordinal)!=null)throw new ServiceException("同一问答轮次已有检查点",409);
        if(ordinal>1) {
            var previous=checkpoints.byPosition(runId,attempt,nodeId,iterationId,(int)ordinal-1);
            if(previous==null || !"CONSUMED".equals(previous.getState()))throw new ServiceException("前一问答轮次尚未消费",409);
            var originalReply=JSON.parseObject(JSON.parseObject(previous.getResumeDataJson()).getString("message"));
            if(!"resume".equals(originalReply.getString("event_type")))throw new ServiceException("前一问答已忽略或终止，不能重问",409);
        }
        var value=new CheckpointRecord();value.setId(UUID.randomUUID().toString());value.setRunId(runId);value.setLeaseId(leaseId);value.setAttempt(attempt);
        value.setNodeId(nodeId);value.setIterationId(iterationId);value.setOrdinal((int)ordinal);value.setNeedReply(body.getBooleanValue("needReply")?1:0);
        value.setState("WAITING");value.setRequestKey(requestKey);value.setRequestHash(hash);value.setQuestionJson(RunJsonBoundary.canonical(body.getJSONObject("question")));value.setRevision(0L);
        var until=now().plusSeconds(timeout);
        // 正式deadline列为DATETIME(6)；创建ACK与Mapper回读必须是同一时刻/字符串。
        value.setDeadline((until.isBefore(run.getDeadline())?until:run.getDeadline()).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        if(checkpoints.insert(value)!=1)throw new ServiceException("检查点保存失败",500);return view(value);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject poll(String runId,String checkpointId,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","afterRevision"),Set.of());RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        long after=integer(body.get("afterRevision"));if(after<0)throw new ServiceException("检查点游标无效",400);
        var run=leases.lockWorkerRun(runId,worker,text(body,"leaseId"),RunLeaseService.attempt(body));var value=scoped(run,checkpointId);
        if(after>value.getRevision())throw new ServiceException("检查点游标超前",409);closeIfEnded(run,value);return view(value);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject consume(String runId,String checkpointId,WorkerIdentityService.Principal worker,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("leaseId","attempt","expectedRevision","idempotencyKey"),Set.of());RunJsonBoundary.check(body,RunProtocolLimits.CONTROL_BYTES);
        long expected=integer(body.get("expectedRevision"));String requestKey=key(body);if(expected<0)throw new ServiceException("检查点版本无效",400);
        var run=leases.lockWorkerRun(runId,worker,text(body,"leaseId"),RunLeaseService.attempt(body));var value=scoped(run,checkpointId);String hash=RunJsonBoundary.hash(body);
        if("CONSUMED".equals(value.getState())) {
            if(!requestKey.equals(value.getConsumeKey()) || !hash.equals(value.getConsumeHash()))throw new ServiceException("检查点已消费且回执不同",409);
        } else {
            closeIfEnded(run,value);
            if(Set.of("EXPIRED","CANCELLED").contains(value.getState()))return view(value).fluentPut("consumed",false);
            if(!"RESUMED".equals(value.getState()) || expected!=value.getRevision())throw new ServiceException("检查点未回复或版本已变化",409);
            value.setState("CONSUMED");value.setConsumeKey(requestKey);value.setConsumeHash(hash);save(value);
        }
        return view(value).fluentPut("consumed",true).fluentPut("resumeData",JSON.parseObject(value.getResumeDataJson()));
    }
    private RunRecord userRun(String runId,String permission) {
        long actor=access.requirePermission(permission);
        var initial=runs.find(runId);if(initial==null)throw new ServiceException("运行不存在",404);
        access.requireSpace(initial.getSpaceId(),permission,3,true);var run=runs.lock(runId);
        if(run!=null)RunJsonCodec.require(run);
        var current=identities.current();var live=permissions.getMenuPermission(current.getUser());
        if(live==null || (!live.contains("*:*:*") && !live.contains(permission)))throw new ServiceException("当前问答功能权限已撤销",403);
        if(run==null || run.getOwnerUserId()!=actor || current.getUserId()!=actor)throw new ServiceException("只有运行发起者可处理此问答",403);return run;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject detail(String runId,String checkpointId) {
        var run=userRun(runId,"paiflow:run:query");var value=scoped(run,checkpointId);closeIfEnded(run,value);
        return view(value).fluentPut("nodeId",value.getNodeId()).fluentPut("ordinal",value.getOrdinal()).fluentPut("needReply",value.getNeedReply()==1)
            .fluentPut("question",JSON.parseObject(value.getQuestionJson()));
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject reply(String runId,String checkpointId,JSONObject body) {
        RunJsonBoundary.fields(body,Set.of("expectedRevision","idempotencyKey","event_type","content"),Set.of());RunJsonBoundary.check(body,RunProtocolLimits.INPUT_BYTES);
        long expected=integer(body.get("expectedRevision"));String requestKey=key(body),eventType=text(body,"event_type");
        if(expected<0 || !Set.of("resume","ignore","abort").contains(eventType) || !(body.get("content") instanceof String))throw new ServiceException("问答回复类型或内容无效",400);
        var run=userRun(runId,"paiflow:run:resume");var value=scoped(run,checkpointId);String hash=RunJsonBoundary.hash(body);
        if(value.getReplyKey()!=null) {
            if(!requestKey.equals(value.getReplyKey()) || !hash.equals(value.getReplyHash()))throw new ServiceException("检查点已有不同回复",409);
            return view(value);
        }
        if(!"RUNNING".equals(run.getState()) || !run.getDeadline().isAfter(now()) || run.getLeaseExpiresAt()==null || !run.getLeaseExpiresAt().isAfter(now()) || !value.getDeadline().isAfter(now()))throw new ServiceException("问答等待已结束",410);
        if(!"WAITING".equals(value.getState()) || expected!=value.getRevision())throw new ServiceException("问答状态或版本已变化",409);
        if(value.getNeedReply()==1 && "ignore".equals(eventType))throw new ServiceException("必答问答不能忽略",400);
        // 原EventRegistry首轮retries=0，后续依次+1；timestamp由中心实际回复时刻生成。
        var message=JSONObject.of("event_type",eventType,"content",body.getString("content"));
        var resume=JSONObject.of("message",message.toJSONString(),"metadata",JSONObject.of("retries",value.getOrdinal()-1,"timestamp",clock.instant().getEpochSecond()));
        value.setResumeDataJson(RunJsonBoundary.canonical(resume));value.setReplyKey(requestKey);value.setReplyHash(hash);value.setState("RESUMED");save(value);return view(value);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public int reconcileEnded() {
        int count=0;
        for(var candidate:checkpoints.ended(now(),32)) {
            var run=runs.lock(candidate.getRunId());if(run==null)continue;RunJsonCodec.require(run);
            var value=checkpoints.lock(candidate.getId());long before=value.getRevision();closeIfEnded(run,value);if(value.getRevision()!=before)count++;
        }
        return count;
    }
}
