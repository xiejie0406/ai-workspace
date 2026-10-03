package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.paiflow.domain.RunRecord;
import com.ruoyi.paiflow.mapper.RunMapper;
import com.ruoyi.paiflow.mapper.SpaceMapper;
import com.ruoyi.system.service.ISysUserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 中心行锁+revision CAS保存租约、连续事件与终态；重复回执不重复执行业务。 */
@Service
@ConditionalOnProperty(prefix="paiflow",name="enabled",havingValue="true")
public class RunLeaseService {
    private static final Set<String> ACTIVE=Set.of("RUNNING","CANCEL_REQUESTED");
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","CANCELLED","EFFECT_UNKNOWN");
    private static final Set<String> EVENTS=Set.of("node.started","node.completed","node.failed","text.delta","reasoning.delta","usage","artifact","checkpoint");
    private final RunMapper runs;
    private final SpaceMapper spaces;
    private final ISysUserService users;
    private final SysPermissionService permissions;
    private final Clock clock;
    private org.mybatis.spring.SqlSessionTemplate sessions;
    @Autowired public void useCurrentSession(org.mybatis.spring.SqlSessionTemplate template){sessions=template;}
    @Autowired public RunLeaseService(RunMapper runs,SpaceMapper spaces,ISysUserService users,SysPermissionService permissions) {
        this(runs,spaces,users,permissions,Clock.systemUTC());
    }
    RunLeaseService(RunMapper runs,SpaceMapper spaces,ISysUserService users,SysPermissionService permissions,Clock clock) {
        this.runs=runs;this.spaces=spaces;this.users=users;this.permissions=permissions;this.clock=clock;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private RunRecord lock(String id) {
        if(id==null || !id.matches("[0-9a-fA-F-]{36}")) throw new ServiceException("运行标识无效",400);
        var run=runs.lock(id);if(run==null) throw new ServiceException("运行不存在",404);RunJsonCodec.require(run);return run;
    }
    private void ownerStillAuthorized(RunRecord run) {
        if(sessions!=null)sessions.clearCache();
        var user=users.selectUserById(run.getOwnerUserId());var space=spaces.find(run.getSpaceId());
        var member=spaces.member(run.getSpaceId(),run.getOwnerUserId());
        if(user==null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag()) || space==null ||
           space.getDeleted()==null || space.getDeleted()!=0 || member==null || !"active".equals(member.getStatus()) ||
           member.getRole()==null || member.getRole()<1 || member.getRole()>3) throw new ServiceException("运行发起者或空间授权已失效",403);
        // 默认私域映射不因脏成员导入变成协作空间；运行主体必须仍是该域真实owner。
        if(space.isDefaultPersonal() && (!Objects.equals(space.getOwnerUserId(),run.getOwnerUserId()) || space.getTeamId()!=null || member.getRole()!=1))throw new ServiceException("默认个人域运行授权已失效",403);
        var current=permissions.getMenuPermission(user);
        if(current==null || (!current.contains("*:*:*") && !current.contains("paiflow:run:create"))) throw new ServiceException("运行权限已撤销",403);
    }
    private void sameLease(RunRecord run,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        if(worker==null || !Objects.equals(worker.id(),run.getWorkerId()) || !Objects.equals(worker.engineProfile(),run.getEngineProfile()) ||
           !Objects.equals(leaseId,run.getLeaseId()) || attempt<1 || !Objects.equals(attempt,run.getAttempt())) throw new ServiceException("运行租约不属于当前Worker",409);
        ownerStillAuthorized(run);
    }
    private void active(RunRecord run) {
        if(!ACTIVE.contains(run.getState()) || run.getLeaseExpiresAt()==null || !run.getLeaseExpiresAt().isAfter(now()) || !run.getDeadline().isAfter(now())) throw new ServiceException("运行租约已失效",410);
    }
    /** 调用者须在同一事务中按运行→检查点顺序锁定；终态仅供停止/重复回执核对。 */
    public RunRecord lockWorkerRun(String id,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        var run=lock(id);sameLease(run,worker,leaseId,attempt);return run;
    }
    public RunRecord requireActiveLease(String id,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        var run=lockWorkerRun(id,worker,leaseId,attempt);active(run);return run;
    }
    /** 已登记只说明中心签发过许可，不证明引擎已运行或外部副作用已完成。 */
    public static void requireExecutionBegun(RunRecord run) {
        if(run.getExecutionStartedAt()==null)throw new ServiceException("当前运行尚未取得一次性执行许可",409);
    }
    private void save(RunRecord run) {
        if(runs.update(run)!=1) throw new ServiceException("运行版本已变化",409);
        run.setRevision(run.getRevision()+1);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject claim(WorkerIdentityService.Principal worker) {
        reconcileExpired(worker.engineProfile());
        for(var candidate:runs.queued(worker.engineProfile(),32)) {
            var run=lock(candidate.getId());if(!"QUEUED".equals(run.getState())) continue;
            if(!run.getDeadline().isAfter(now())) continue;
            try { ownerStillAuthorized(run); }
            catch(ServiceException denied) {
                finishAtCenter(run,"FAILED","AUTHORIZATION_REVOKED");continue;
            }
            run.setWorkerId(worker.id());run.setLeaseId(UUID.randomUUID().toString());run.setAttempt(run.getAttempt()+1);
            run.setState("RUNNING");run.setLeaseExpiresAt(min(now().plusSeconds(60),run.getDeadline()));save(run);
            return assignment(run);
        }
        return null;
    }
    /** 由若依任务入口或领取流程调用；无Worker领取时也可由既有Quartz调度核对过期运行。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public int reconcileExpiredRuns() { return reconcileExpired("java")+reconcileExpired("python"); }
    private int reconcileExpired(String engine) {
        int count=0;
        for(var candidate:runs.expired(engine,now(),32)) {
            var run=lock(candidate.getId());if(TERMINAL.contains(run.getState()))continue;
            if(run.getDeadline().isAfter(now()) && ("QUEUED".equals(run.getState()) || run.getLeaseExpiresAt()!=null && run.getLeaseExpiresAt().isAfter(now())))continue;
            String mode=JSON.parseObject(run.getAssignmentJson()).getJSONObject("effectPolicy")==null?null:
                JSON.parseObject(run.getAssignmentJson()).getJSONObject("effectPolicy").getString("mode");
            boolean uncertain=run.getAttempt()>0 && "approved_write".equals(mode);
            finishAtCenter(run,uncertain?"EFFECT_UNKNOWN":"FAILED",uncertain?"LEASE_LOST_EFFECT_UNKNOWN":"LEASE_OR_DEADLINE_EXPIRED");
            count++;
        }
        return count;
    }
    private void finishAtCenter(RunRecord run,String state,String reason) {
        var result=JSONObject.of("state",state,"error",JSONObject.of("code",reason,"message","中心核对运行不可继续"));
        var event=terminalEvent(run,state,clock.instant(),result);
        if(runs.insertEvent(run.getId(),run.getLastEventSeq()+1,RunJsonCodec.canonical(run,event))!=1)throw new ServiceException("中心终态事件保存失败",500);
        run.setState(state);run.setResultJson(RunJsonCodec.canonical(run,result));run.setResultHash(RunJsonCodec.hash(run,result));
        run.setLastEventSeq(run.getLastEventSeq()+1);save(run);
    }
    static JSONObject terminalEvent(RunRecord run,String state,Instant timestamp,JSONObject payload) {
        return JSONObject.of("schemaVersion","paiflow.events.v1","runId",run.getId(),"leaseId",run.getLeaseId()==null?"center":run.getLeaseId(),
            "attempt",run.getAttempt(),"seq",run.getLastEventSeq()+1,"eventType",state.equals("COMPLETED")?"run.completed":state.equals("CANCELLED")?"run.cancelled":"run.failed",
            "timestamp",timestamp.toString(),"payload",payload);
    }
    private static LocalDateTime min(LocalDateTime left,LocalDateTime right) { return left.isBefore(right)?left:right; }
    private JSONObject assignment(RunRecord run) {
        var task=JSON.parseObject(run.getAssignmentJson());
        task.put("leaseId",run.getLeaseId());task.put("attempt",run.getAttempt());task.put("lastEventSeq",run.getLastEventSeq());
        return task;
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject verify(String id,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        var run=lock(id);sameLease(run,worker,leaseId,attempt);active(run);
        var task=assignment(run);
        return new JSONObject().fluentPut("valid",true).fluentPut("cancelRequested","CANCEL_REQUESTED".equals(run.getState()))
            .fluentPut("dslHash",task.getString("dslHash")).fluentPut("workflowVersionId",task.getString("workflowVersionId"))
            .fluentPut("lastEventSeq",run.getLastEventSeq()).fluentPut("assignment",task);
    }
    /** 每个正式运行只能签发一次；许可ACK丢失也不重发，防止Worker重启后重复进入引擎。 */
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject beginExecution(String id,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        var run=requireActiveLease(id,worker,leaseId,attempt);
        if("CANCEL_REQUESTED".equals(run.getState()))throw new ServiceException("运行已请求取消，不能开始执行",410);
        if(run.getExecutionStartedAt()!=null)throw new ServiceException("运行执行许可已经签发，禁止再次执行",409);
        var task=assignment(run);String version=task.getString("workflowVersionId"),hash=task.getString("dslHash");
        if(run.getWorkflowVersionId()==null || !run.getWorkflowVersionId().toString().equals(version) || hash==null || !hash.matches("[0-9a-f]{64}"))
            throw new ServiceException("固定运行版本或执行哈希无效",500);
        run.setExecutionStartedAt(now());save(run);
        return JSONObject.of("begun",true,"runId",run.getId(),"leaseId",run.getLeaseId(),"attempt",run.getAttempt(),"workflowVersionId",version,"dslHash",hash);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject heartbeat(String id,WorkerIdentityService.Principal worker,String leaseId,int attempt) {
        var run=lock(id);sameLease(run,worker,leaseId,attempt);active(run);
        run.setLeaseExpiresAt(min(now().plusSeconds(60),run.getDeadline()));save(run);
        return new JSONObject().fluentPut("valid",true).fluentPut("cancelRequested","CANCEL_REQUESTED".equals(run.getState()))
            .fluentPut("leaseExpiresAt",run.getLeaseExpiresAt().toInstant(ZoneOffset.UTC).toString());
    }
    public static int attempt(JSONObject body) {
        long number=integer(body.get("attempt"));if(number<1 || number>Integer.MAX_VALUE) throw new ServiceException("租约次数无效",400);return (int)number;
    }
    private static long integer(Object value) {
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new ServiceException("运行序号必须为整数",400);
        return ((Number)value).longValue();
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject append(String id,WorkerIdentityService.Principal worker,JSONObject event) {
        RunJsonBoundary.fields(event,Set.of("schemaVersion","runId","leaseId","attempt","seq","eventType","timestamp","payload"),Set.of("nodeId","iterationId"));
        RunJsonBoundary.check(event,RunProtocolLimits.EVENT_BYTES);
        if(!"paiflow.events.v1".equals(event.getString("schemaVersion")) || !id.equals(event.getString("runId")) ||
           !EVENTS.contains(event.getString("eventType")) || !(event.get("payload") instanceof JSONObject)) throw new ServiceException("Worker事件协议或事件类型无效",400);
        for(String field:Set.of("nodeId","iterationId")) {
            if(event.containsKey(field) && (!(event.get(field) instanceof String value) || value.isEmpty())) throw new ServiceException("事件"+field+"须为非空字符串",400);
        }
        try { Instant.parse(event.getString("timestamp")); } catch(Exception ex) { throw new ServiceException("事件时间须包含时区",400); }
        var run=lock(id);sameLease(run,worker,event.getString("leaseId"),attempt(event));active(run);requireExecutionBegun(run);
        String node=event.getString("nodeId");
        if(node!=null && JSON.parseObject(run.getAssignmentJson()).getJSONObject("dsl").getJSONArray("nodes").stream()
            .noneMatch(n->n instanceof JSONObject object && node.equals(object.getString("id")))) throw new ServiceException("事件节点不属于固定版本",400);
        long seq=integer(event.get("seq"));if(seq<1) throw new ServiceException("事件序号无效",400);
        String encoded=RunJsonCodec.canonical(run,event);
        if(seq<=run.getLastEventSeq()) {
            if(!encoded.equals(runs.event(id,seq))) throw new ServiceException("重复序号的事件内容不一致",409);
        } else {
            if(seq!=run.getLastEventSeq()+1) throw new ServiceException("事件序号不连续",409);
            if(runs.insertEvent(id,seq,encoded)!=1) throw new ServiceException("事件保存失败",500);
            run.setLastEventSeq(seq);save(run);
        }
        return new JSONObject().fluentPut("accepted",true).fluentPut("seq",seq);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public JSONObject result(String id,WorkerIdentityService.Principal worker,JSONObject result) {
        RunJsonBoundary.fields(result,Set.of("leaseId","attempt","state"),Set.of("output","error"));RunJsonBoundary.check(result,RunProtocolLimits.RESULT_BYTES);
        String state=result.getString("state");if(!TERMINAL.contains(state)) throw new ServiceException("运行结果状态无效",400);
        if(result.containsKey("error") && !(result.get("error") instanceof JSONObject)) throw new ServiceException("运行错误须为对象",400);
        var run=lock(id);sameLease(run,worker,result.getString("leaseId"),attempt(result));String hash=RunJsonCodec.hash(run,result);
        if(TERMINAL.contains(run.getState())) {
            if(!hash.equals(run.getResultHash())) throw new ServiceException("运行已终止且结果不同",409);
        } else {
            active(run);if("COMPLETED".equals(state))requireExecutionBegun(run);
            if("COMPLETED".equals(state) && "CANCEL_REQUESTED".equals(run.getState()))throw new ServiceException("取消请求已保存，须核对真实停止结果，不能再标完成",409);
            run.setState(state);run.setResultJson(RunJsonCodec.canonical(run,result));run.setResultHash(hash);
            var event=terminalEvent(run,state,clock.instant(),JSONObject.of("state",state));
            if(runs.insertEvent(id,run.getLastEventSeq()+1,RunJsonCodec.canonical(run,event))!=1) throw new ServiceException("运行终态事件保存失败",500);
            run.setLastEventSeq(run.getLastEventSeq()+1);save(run);
        }
        return new JSONObject().fluentPut("accepted",true).fluentPut("state",run.getState());
    }
}
