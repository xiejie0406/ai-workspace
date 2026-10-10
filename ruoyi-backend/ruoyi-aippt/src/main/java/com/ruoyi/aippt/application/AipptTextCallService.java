package com.ruoyi.aippt.application;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.aippt.configuration.AipptEnabled;
import com.ruoyi.aippt.domain.AipptException;
import com.ruoyi.aippt.domain.AipptRules;
import com.ruoyi.aippt.infrastructure.persistence.AipptRepository;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 原任务body中的文本调用账本；网络等待与解析始终在数据库事务外。 */
@Service @AipptEnabled
public class AipptTextCallService {
    private static final Set<String> FIELDS=Set.of("attempt","leaseToken","fencingToken","callId","system","user","purpose");
    private final TaskLeaseService leases;
    private final AipptRepository db;
    private final TransactionTemplate tx;
    private final AipptTextRouteService routes;
    private final AipptTextHttpAdapter http;

    public AipptTextCallService(TaskLeaseService leases,AipptRepository db,@Qualifier("aipptTransactions")TransactionTemplate tx,
            AipptTextRouteService routes,AipptTextHttpAdapter http) {
        this.leases=leases;this.db=db;this.tx=tx;this.routes=routes;this.http=http;
    }

    public JSONObject text(String id,JSONObject request) {
        validateRequest(request);
        String callId=request.getString("callId");
        String digest=AipptRules.hash(JSON.toJSONString(JSONObject.of("system",request.get("system"),"user",request.get("user"),"purpose",request.get("purpose")),JSONWriter.Feature.MapSortField));
        JSONObject reservation=leases.withLease(id,request,task->{
            validateTask(task);
            JSONObject frozen=task.getJSONObject("text_route");
            if(frozen==null)throw new AipptException(409,"TEXT_ROUTE_MISSING","任务未冻结文本路由，请终止旧任务后重新创建");
            routes.assertCurrent(frozen);
            JSONObject calls=task.getJSONObject("text_calls");
            if(calls==null) { calls=new JSONObject();task.put("text_calls",calls); }
            JSONObject previous=calls.getJSONObject(callId);
            if(previous!=null) {
                if(!digest.equals(previous.getString("bodyDigest"))||previous.getIntValue("attempt")!=request.getIntValue("attempt")
                    ||previous.getLongValue("fencingToken")!=request.getLongValue("fencingToken"))throw conflict("同一文本调用标识的正文或任务尝试不同");
                if("completed".equals(previous.getString("status")))return JSONObject.of("replay",AipptRules.copy(previous.getJSONObject("response")));
                throw conflict("文本调用已在途或远端结果未知，禁止重送");
            }
            if(calls.values().stream().anyMatch(value->value instanceof JSONObject call&&!"completed".equals(call.getString("status"))))
                throw conflict("任务存在未核对文本调用，禁止后续模型请求");
            if(calls.size()>=frozen.getIntValue("maxCalls"))throw new AipptException(429,"TEXT_CALL_BUDGET_EXCEEDED","任务文本调用次数预算已耗尽");
            JSONObject entry=JSONObject.of("bodyDigest",digest,"attempt",request.getIntValue("attempt"),"fencingToken",request.getLongValue("fencingToken"),
                "status","inflight","routeVersion",frozen.getString("routeVersion"),"startedAt",Instant.now().toString());
            calls.put(callId,entry);
            db.save("task",task,task.getLongValue("revision"));
            return JSONObject.of("route",AipptRules.copy(frozen));
        });
        if(reservation.containsKey("replay"))return reservation.getJSONObject("replay");
        JSONObject route=reservation.getJSONObject("route");
        JSONObject received=null;
        try {
            String key=routes.resolve(route);
            JSONObject response=http.call(route,key,request.getString("system"),request.getString("user"),()->{
                leases.withLease(id,request,task->{validateTask(task);routes.assertCurrent(route);return new JSONObject();});
                return true;
            });
            received=response;
            return leases.withLease(id,request,task->{
                validateTask(task);routes.assertCurrent(route);
                JSONObject call=task.getJSONObject("text_calls").getJSONObject(callId);
                if(call==null||!"inflight".equals(call.getString("status"))||!digest.equals(call.getString("bodyDigest")))throw conflict("文本调用账本已变化");
                if(task.getJSONObject("text_calls").toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    +response.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4*1024*1024)
                    throw new AipptException(413,"TEXT_RESULT_BUDGET_EXCEEDED","任务文本结果账本超过字节预算，已收到用量保留并须核对");
                call.put("status","completed");call.put("completedAt",Instant.now().toString());call.put("response",AipptRules.copy(response));
                db.save("task",task,task.getLongValue("revision"));
                return response;
            });
        } catch(RuntimeException failure) {
            JSONObject usage=received==null?failure instanceof AipptTextHttpAdapter.RejectedResponse rejected?rejected.reportedUsage():new JSONObject():received.getJSONObject("usage");
            markUnknown(id,callId,digest,usage);
            if(failure instanceof AipptException safe)throw safe;
            throw new AipptException(502,"TEXT_RESULT_UNKNOWN","文本请求结果待核对，禁止自动重送");
        }
    }

    private void markUnknown(String id,String callId,String digest,JSONObject usage) {
        // 即使旧租约已失效也保留原调用未知事实，不以旧租约修改正式业务输出。
        try {
            tx.execute(status->{
                JSONObject current=db.get("task",id);if(current==null)return null;
                db.lock("project",current.getString("project_id"));
                JSONObject task=db.lock("task",id);if(task==null)return null;
                JSONObject calls=task.getJSONObject("text_calls"),call=calls==null?null:calls.getJSONObject(callId);
                if(call!=null&&"inflight".equals(call.getString("status"))&&digest.equals(call.getString("bodyDigest"))) {
                    call.put("status","unknown");call.put("endedAt",Instant.now().toString());
                    call.put("reportedUsage",AipptRules.copy(usage));
                    call.put("usage_status",usage.size()==3?"available":usage.isEmpty()?"unavailable":"partial");
                    db.save("task",task,task.getLongValue("revision"));
                }
                return null;
            });
        } catch(RuntimeException unavailable) {
            // 原预占记录仍为inflight，同样禁止重放；不把底层异常、提示词或凭据写入日志。
        }
    }

    private void validateTask(JSONObject task) {
        if(!AipptTextRouteService.TEXT_TYPES.contains(task.getString("type")))throw new AipptException(403,"TEXT_TASK_FORBIDDEN","当前任务类型不允许调用文本模型");
        if(task.getBooleanValue("cancel_requested"))throw new AipptException(409,"TEXT_CALL_CANCELLED","任务已取消，禁止后续文本调用");
        if(task.getString("worker_id")==null||task.getString("worker_id").isBlank())throw new AipptException(403,"TEXT_WORKER_UNBOUND","任务尚未绑定Worker");
        JSONObject project=db.get("project",task.getString("project_id"));
        if(project==null||"deleted".equals(project.getString("status"))||project.getLongValue("owner_user_id")!=task.getLongValue("owner_user_id"))
            throw new AipptException(403,"TEXT_PROJECT_FORBIDDEN","任务与项目所有者归属不一致");
    }

    private static void validateRequest(JSONObject request) {
        AipptRules.require(request!=null&&request.keySet().equals(FIELDS),"文本请求字段不完整或含未知字段");
        strictInteger(request.get("attempt"),"attempt");strictInteger(request.get("fencingToken"),"fencingToken");
        requestText(request.get("leaseToken"),1,128,"leaseToken");
        requestText(request.get("callId"),36,36,"callId");
        AipptRules.require(request.getString("callId").matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"),"文本调用ID须为完整UUID");
        request.put("callId",request.getString("callId").toLowerCase(java.util.Locale.ROOT));
        requestText(request.get("system"),1,65536,"system");requestText(request.get("user"),1,2*1024*1024,"user");
        requestText(request.get("purpose"),1,100,"purpose");
        AipptRules.require(request.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2*1024*1024,"文本请求超过字节预算");
    }

    private static void requestText(Object value,int min,int max,String name) {
        AipptRules.require(value instanceof String,name+"须为文字");
        String text=(String)value;
        int length=text.codePointCount(0,text.length());
        AipptRules.require(length>=min&&length<=max,name+"长度超限");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {
                AipptRules.require(i+1<text.length()&&Character.isLowSurrogate(text.charAt(++i)),name+"包含非法Unicode字符");
            } else AipptRules.require(!Character.isLowSurrogate(c),name+"包含非法Unicode字符");
        }
    }

    private static void strictInteger(Object value,String name) {
        AipptRules.require(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger,name+"须为JSON整数");
        AipptRules.integer(value,1,Long.MAX_VALUE,name);
        if("attempt".equals(name))AipptRules.integer(value,1,Integer.MAX_VALUE,name);
    }

    private static AipptException conflict(String message) { return new AipptException(409,"TEXT_CALL_CONFLICT",message); }
}
