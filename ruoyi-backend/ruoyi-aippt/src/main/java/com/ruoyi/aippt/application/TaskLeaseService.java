package com.ruoyi.aippt.application;
import com.alibaba.fastjson2.*;
import com.ruoyi.aippt.configuration.*;
import com.ruoyi.aippt.domain.*;
import com.ruoyi.aippt.infrastructure.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
@Service @AipptEnabled public class TaskLeaseService  {
    private final AipptRepository db;
    private final AipptMapper mapper;
    private final TransactionTemplate tx;
    private final AipptService service;
    private final AipptProperties props;
    private RedisDeliveryService delivery;
    private static final Set<String> TASK_TYPES=Set.of("parse_source","generate_outline","generate_slide","ai_edit","relayout","quality","export");
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setDelivery(RedisDeliveryService value) { delivery=value; }
    public TaskLeaseService(AipptRepository d,AipptMapper m,@Qualifier("aipptTransactions")TransactionTemplate t,AipptService s,AipptProperties p)  {
        db=d;
        mapper=m;
        tx=t;
        service=s;
        props=p;
    }
    public JSONObject claim(JSONObject request)  {
        AipptRules.require(request.keySet().stream().allMatch(Set.of("workerId","catalogVersion","types","waitMillis")::contains),"未知Worker领取字段");
        AipptRules.text(request.get("workerId"),1,128,"workerId");
        AipptRules.text(request.get("catalogVersion"),1,128,"catalogVersion");
        if(!props.getCatalogVersion().equals(request.getString("catalogVersion")))throw new AipptException(409,"CATALOG_MISMATCH","Worker与中心设计目录版本不同");
        Set<String> types=TASK_TYPES;
        if(request.containsKey("types")) {
            JSONArray requested=AipptRules.array(request,"types");
            types=new HashSet<>();
            for(Object type:requested) {
                AipptRules.require(type instanceof String&&TASK_TYPES.contains(type),"未知Worker任务类型");
                types.add((String)type);
            }
        }
        long wait=request.containsKey("waitMillis")?AipptRules.integer(request.get("waitMillis"),0,10000,"waitMillis"):0;
        if(types.isEmpty())return null;
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(wait);
        do {
            long observed=delivery==null?0:delivery.signalVersion();
            JSONObject result=claimOnce(request,types);
            if(result!=null||wait==0)return result;
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)return null;
            try {
                long milliseconds=Math.min(1000,Math.max(1,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
                if(delivery!=null)delivery.awaitSignal(observed,milliseconds);
                else java.util.concurrent.TimeUnit.MILLISECONDS.sleep(milliseconds);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while(System.nanoTime()<deadline);
        return null;
    }
    private JSONObject claimOnce(JSONObject request,Set<String> types) {
        return tx.execute(s->  {
            mapper.lockDispatch();
            recover();
            List<JSONObject> running=db.running();
            if(running.size()>=props.getWorker().getGlobalConcurrency())return null;
            for(JSONObject candidate:db.claimable(types))  {
                if(!types.contains(candidate.getString("type")))continue;
                JSONObject p=service.workerProject(candidate.getString("project_id"));
                JSONObject task=db.lock("task",candidate.getString("id"));
                if(!"queued".equals(task.getString("status"))||task.getBooleanValue("cancel_requested"))continue;
                long same=running.stream().filter(t->p.getString("id").equals(t.getString("project_id"))).count();
                if(same>=props.getWorker().getProjectConcurrency())continue;
                if(Instant.parse(task.getString("deadline")).isBefore(Instant.now()))  {
                    finishFailure(task,"任务超过总时限");
                    continue;
                }
                task.put("attempt",task.getIntValue("attempt")+1);
                task.put("fencing_token",task.getLongValue("fencing_token")+1);
                task.put("lease_token",UUID.randomUUID().toString());
                task.put("lease_until",Instant.now().plusSeconds(props.getWorker().getLeaseSeconds()).toString());
                task.put("worker_id",request.getString("workerId"));
                task.put("attempt_started_at",Instant.now().toString());
                task.put("status","running");
                db.save("task",task,task.getLongValue("revision"));
                service.event(p.getString("id"),p.getLongValue("owner_user_id"),"started",JSONObject.of("task_id",task.getString("id"),"slide_id",task.getString("slide_id")));
                return JSONObject.of("taskId",task.getString("id"),"attempt",task.getIntValue("attempt"),"leaseToken",task.getString("lease_token"),"fencingToken",task.getLongValue("fencing_token"),"type",task.getString("type"),"payload",task.getJSONObject("payload"),"deadline",task.getString("deadline"),"catalogVersion",task.getString("catalog_version"));
            }
            return null;
        }
        );
    }
    private void recover()  {
        for(JSONObject stale:db.expired(Instant.now().toString()))  {
            JSONObject p=db.get("project",stale.getString("project_id"));
            if(p==null)continue;
            db.lock("project",p.getString("id"));
            JSONObject task=db.lock("task",stale.getString("id"));
            if("queued".equals(task.getString("status"))) {
                if(Instant.parse(task.getString("deadline")).isAfter(Instant.now()))continue;
                task.put("fencing_token",task.getLongValue("fencing_token")+1);
                finishFailure(task,"任务超过总时限");
                continue;
            }
            if(!"running".equals(task.getString("status")))continue;
            Instant now=Instant.now();
            // 初筛后可能已收到续租；行锁内再次读当前租约，避免回收已续租的在途任务。
            if(Instant.parse(task.getString("lease_until")).isAfter(now)&&Instant.parse(task.getString("deadline")).isAfter(now))continue;
            task.put("fencing_token",task.getLongValue("fencing_token")+1);
            if(task.getBooleanValue("cancel_requested")||"deleted".equals(p.getString("status")))  {
                task.put("status","cancelled");
                service.failedOutput(task,"任务取消，租约到期");
            }
            else if(task.getIntValue("attempt")>=props.getWorker().getMaxAttempts()||Instant.parse(task.getString("deadline")).isBefore(Instant.now()))  {
                task.put("status","failed");
                service.failedOutput(task,"Worker租约超时，超过恢复次数");
            }
            else task.put("status","queued");
            db.save("task",task,task.getLongValue("revision"));
            if("generate_slide".equals(task.getString("type")))service.settle(p);
            service.event(p.getString("id"),p.getLongValue("owner_user_id"),task.getString("status"),JSONObject.of("task_id",task.getString("id")));
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=10000) public void recoverExpired()  {
        tx.execute(status->  {
            mapper.lockDispatch();
            recover();
            return null;
        }
        );
    }
    public static void validateLease(JSONObject task,JSONObject request,Instant now)  {
        if(!"running".equals(task.getString("status"))||!Objects.equals(task.getString("lease_token"),request.getString("leaseToken"))||task.getLongValue("fencing_token")!=request.getLongValue("fencingToken")||task.getIntValue("attempt")!=request.getIntValue("attempt")||task.getString("lease_until")==null||(Instant.parse(task.getString("lease_until")).isBefore(now)||(task.getString("deadline")!=null&&Instant.parse(task.getString("deadline")).isBefore(now))))throw new AipptException(409,"LEASE_LOST","租约已失效，禁止旧结果回写");
    }
    public JSONObject withLease(String id,JSONObject request,java.util.function.Function<JSONObject,JSONObject> action)  {
        return tx.execute(s->  {
            JSONObject current=db.get("task",id);
            if(current==null)throw AipptException.missing();
            service.workerProject(current.getString("project_id"));
            JSONObject task=db.lock("task",id);
            validateLease(task,request,Instant.now());
            return action.apply(task);
        }
        );
    }
    public JSONObject heartbeat(String id,JSONObject request)  {
        return withLease(id,request,t->  {
            t.put("lease_until",Instant.now().plusSeconds(props.getWorker().getLeaseSeconds()).toString());
            db.save("task",t,t.getLongValue("revision"));
            return JSONObject.of("cancelRequested",t.getBooleanValue("cancel_requested"),"leaseUntil",t.getString("lease_until"),"leaseValid",true);
        }
        );
    }
    public JSONObject progress(String id,JSONObject request)  {
        return withLease(id,request,t->  {
            JSONObject progress=request.getJSONObject("progress");
            AipptRules.require(progress!=null&&progress.toJSONString().length()<=32000,"进度消息无效");
            AipptRules.require(progress.keySet().stream().allMatch(Set.of("percent","message","stage")::contains),"未知进度字段");
            AipptRules.require(progress.get("percent") instanceof Number && progress.getDoubleValue("percent")==progress.getIntValue("percent")&&progress.getIntValue("percent")>=0&&progress.getIntValue("percent")<=100,"进度百分比无效");
            AipptRules.text(progress.getString("message"),0,1000,"进度消息");
            if(progress.containsKey("stage")) AipptRules.text(progress.getString("stage"),0,100,"进度阶段");
            t.put("progress",progress);
            db.save("task",t,t.getLongValue("revision"));
            service.event(t.getString("project_id"),t.getLongValue("owner_user_id"),"progress",JSONObject.of("task_id",id,"progress",progress));
            return JSONObject.of("accepted",true);
        }
        );
    }
    public JSONObject complete(String id,JSONObject request)  {
        String digest=AipptRules.hash(JSON.toJSONString(JSONObject.of("output",request.get("output"),"assets",request.get("assets")),JSONWriter.Feature.MapSortField,JSONWriter.Feature.WriteMapNullValue));
        return tx.execute(status ->  {
            JSONObject current=db.get("task",id);
            if(current==null) throw AipptException.missing();
            JSONObject p=service.workerProject(current.getString("project_id"));
            JSONObject t=db.lock("task",id);
            if("succeeded".equals(t.getString("status"))&&Objects.equals(t.getString("lease_token"),request.getString("leaseToken"))&&t.getIntValue("attempt")==request.getIntValue("attempt")&&t.getLongValue("fencing_token")==request.getLongValue("fencingToken")&&digest.equals(t.getString("completion_digest"))) return JSONObject.of("accepted",true,"status","succeeded");
            validateLease(t,request,Instant.now());
            JSONObject output=request.getJSONObject("output");
            AipptRules.require(output!=null&&output.toJSONString().length()<=4*1024*1024,"任务结果无效或超限");
            JSONObject execution=executionSummary(output.getJSONObject("execution"));
            service.completeOutput(t,output,request.getJSONArray("assets"));
            service.reconcileAssets(t.getString("project_id"));
            t.put("status","succeeded");
            t.put("completion_digest",digest);
            if(execution!=null)t.put("execution",execution);
            db.save("task",t,t.getLongValue("revision"));
            if("generate_slide".equals(t.getString("type")))service.settle(p);
            JSONObject audit=JSONObject.of("task_id",id,"type",t.getString("type"),"attempt",t.getIntValue("attempt"),"catalog_version",t.getString("catalog_version"),"state","succeeded");
            if(t.getString("attempt_started_at")!=null)audit.put("center_elapsed_ms",Duration.between(Instant.parse(t.getString("attempt_started_at")),Instant.now()).toMillis());
            if(execution!=null)  {
                audit.put("worker_execution",execution);
            }
            service.audit(p,"task_completed",audit);
            service.event(p.getString("id"),p.getLongValue("owner_user_id"),"succeeded",JSONObject.of("task_id",id,"output",t.get("output")));
            return JSONObject.of("accepted",true,"status","succeeded");
        }
        );
    }
    public static JSONObject executionSummary(JSONObject execution)  {
        if(execution==null)return null;
        Set<String> strings=Set.of("model","prompt_version","parameters_version","usage_status","usage_missing_reason"),numbers=Set.of("elapsed_ms","input_tokens","output_tokens","total_tokens","model_calls","tool_rounds");
        JSONObject safe=new JSONObject();
        for(String key:execution.keySet())  {
            Object value=execution.get(key);
            AipptRules.require(strings.contains(key)||numbers.contains(key),"未知执行摘要字段");
            if(value==null)continue;
            if(strings.contains(key))  {
                AipptRules.require(value instanceof String,"摘要标识须为文字");
                AipptRules.text((String)value,1,200,"摘要标识");
                if("usage_status".equals(key))AipptRules.require(Set.of("available","partial","unavailable","not_applicable").contains(value),"用量摘要状态无效");
            }
            else  {
                AipptRules.require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue())&&((Number)value).doubleValue()==((Number)value).longValue()&&((Number)value).longValue()>=0&&((Number)value).longValue()<=100_000_000,"执行摘要数值无效");
            }
            safe.put(key,value);
        }
        return safe;
    }
    public JSONObject fail(String id,JSONObject request)  {
        return withLease(id,request,t->  {
            JSONObject verification=verificationFailure(t,request);
            if(verification!=null)t.put("verification_failure",verification);
            JSONObject execution=executionSummary(request.getJSONObject("execution"));
            if(execution!=null)t.put("execution",execution);
            String error=request.getString("message");
            if(error==null)error="算法任务失败";
            AipptRules.text(error,1,1000,"失败原因");
            boolean retry=request.getBooleanValue("retryable")&&!t.getBooleanValue("cancel_requested")&&t.getIntValue("attempt")<props.getWorker().getMaxAttempts()&&Instant.parse(t.getString("deadline")).isAfter(Instant.now());
            if(retry)  {
                t.put("status","queued");
                t.put("error",error);
                t.put("fencing_token",t.getLongValue("fencing_token")+1);
                db.save("task",t,t.getLongValue("revision"));
            }
            else finishFailure(t,error);
            JSONObject audit=JSONObject.of("task_id",id,"type",t.getString("type"),"attempt",t.getIntValue("attempt"),"catalog_version",t.getString("catalog_version"),"state",t.getString("status"));
            if(execution!=null)audit.put("worker_execution",execution);
            if(t.getString("attempt_started_at")!=null)audit.put("center_elapsed_ms",Duration.between(Instant.parse(t.getString("attempt_started_at")),Instant.now()).toMillis());
            service.audit(service.workerProject(t.getString("project_id")),"task_attempt_failed",audit);
            return JSONObject.of("status",t.getString("status"));
        }
        );
    }
    private static JSONObject verificationFailure(JSONObject task,JSONObject request) {
        if(!request.containsKey("verification_failure"))return null;
        AipptRules.require("export".equals(task.getString("type"))
                &&"PPTX_VERIFY_FAILED".equals(request.get("code"))
                &&Boolean.FALSE.equals(request.get("retryable")),"回读报告仅允许用于不可重试的PPTX回读失败");
        AipptRules.require(request.get("verification_failure") instanceof JSONObject,"回读失败报告须为对象");
        JSONObject report=(JSONObject)request.get("verification_failure");
        AipptRules.require(report.keySet().equals(Set.of("passed","issues","slide_count","expected_slide_count","diagnostics_truncated","total_issues")),"回读失败报告字段不完整或含未知字段");
        AipptRules.require(Boolean.FALSE.equals(report.get("passed")),"失败回读报告必须明确未通过");
        AipptRules.require(report.get("issues") instanceof JSONArray,"回读问题须为数组");
        JSONArray issues=(JSONArray)report.get("issues");
        AipptRules.require(!issues.isEmpty()&&issues.size()<=2000,"回读问题须为1至2000项");
        JSONObject snapshot=task.getJSONObject("payload").getJSONObject("snapshot");
        int expected=snapshot.getJSONArray("slides").size();
        AipptRules.require(expected>=1&&expected<=40,"导出任务快照页数须为1至40页");
        strictReportInteger(report.get("slide_count"),0,1000,"回读页数");
        AipptRules.require(strictReportInteger(report.get("expected_slide_count"),1,40,"预期页数")==expected,"回读预期页数与任务快照不同");
        AipptRules.require(report.get("diagnostics_truncated") instanceof Boolean,"报告截断标识须为布尔值");
        long total=strictReportInteger(report.get("total_issues"),1,1_000_000,"原始问题数量");
        AipptRules.require(total>=issues.size()&&(Boolean.TRUE.equals(report.get("diagnostics_truncated"))||total==issues.size()),"报告问题数量或截断标识不符");
        Set<String> checks=Set.of("bounds","canvas_size","content_integrity","full_page_picture","native_chart","native_image","native_table","open","page_count","speaker_notes","text_frame");
        for(Object raw:issues) {
            AipptRules.require(raw instanceof JSONObject,"回读问题须为对象");
            JSONObject issue=(JSONObject)raw;
            AipptRules.require(issue.keySet().equals(Set.of("check","slide_index","shape","message")),"回读问题字段不完整或含未知字段");
            AipptRules.require(issue.get("check") instanceof String&&checks.contains(issue.get("check")),"未知回读检查类型");
            if(issue.get("slide_index")!=null)strictReportInteger(issue.get("slide_index"),1,expected,"回读页面索引");
            if(issue.get("shape")!=null)reportText(issue.get("shape"),0,200,"回读形状名称");
            reportText(issue.get("message"),1,1000,"回读问题说明");
        }
        return AipptRules.copy(report);
    }
    private static long strictReportInteger(Object raw,long min,long max,String field) {
        AipptRules.require(raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long||raw instanceof java.math.BigInteger,field+"须为整数");
        java.math.BigInteger value=new java.math.BigInteger(raw.toString());
        AipptRules.require(value.compareTo(java.math.BigInteger.valueOf(min))>=0&&value.compareTo(java.math.BigInteger.valueOf(max))<=0,field+"超出范围");
        return value.longValue();
    }
    private static void reportText(Object raw,int min,int max,String field) {
        AipptRules.require(raw instanceof String,field+"须为文字");
        String value=(String)raw;
        int length=value.codePointCount(0,value.length());
        AipptRules.require(length>=min&&length<=max,field+"长度超出范围");
    }
    private void finishFailure(JSONObject task,String error)  {
        task.put("status",task.getBooleanValue("cancel_requested")?"cancelled":"failed");
        task.put("error",error);
        service.failedOutput(task,error);
        db.save("task",task,task.getLongValue("revision"));
        JSONObject p=db.get("project",task.getString("project_id"));
        if("generate_slide".equals(task.getString("type")))service.settle(p);
        service.event(p.getString("id"),p.getLongValue("owner_user_id"),"failed",JSONObject.of("task_id",task.getString("id"),"message",error));
    }
}
