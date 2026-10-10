package com.ruoyi.paiflow.worker.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.workflow.engine.integration.model.LlmChatHistory.ChatItem;
import com.iflytek.astron.workflow.engine.integration.model.bo.LlmReqBo;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只缓存当前租约的中心冻结历史。正式轮次、消息与答案始终由中心保存及重建。 */
public final class CenterHistoryConsumer {
    private static final Set<String> CONTEXT=Set.of("flowId","userId","appId","chatId");
    private static final Set<String> RESPONSE=Set.of("kind","nodeId","runId","leaseId","attempt","chatId","historyRef","historyHash","history","snapshot");
    private static final Set<String> SNAPSHOT=Set.of("runId","leaseId","attempt","nodeId","engineProfile","spaceId","workflowId","ownerUserId",
            "workflowVersionId","dslHash","appId","chatId","history","messages","inputHash");
    private final ExecutionScope scope;
    private final CenterClient center;
    private final Assignment task;
    private final Map<String,Opened> opened=new HashMap<>();
    private record Opened(String historyJson,String messagesJson) {}
    CenterHistoryConsumer(ExecutionScope scope,CenterClient center,Assignment task) {this.scope=scope;this.center=center;this.task=task;}

    public static void validateAssignment(Assignment task) {
        Map<String,Object> context=task.executionContext();if(context==null)return;
        if(!context.keySet().equals(CONTEXT))throw invalid("中心执行上下文字段无效");
        for(String key:CONTEXT)text(context.get(key),255);
        if(!((String)context.get("userId")).matches("ruoyi:[1-9][0-9]{0,18}"))throw invalid("中心历史主体标识无效");
    }
    String chatId(String legacy) {validateAssignment(task);return task.executionContext()==null?legacy:(String)task.executionContext().get("chatId");}
    List<ChatItem> history(String chatId,String nodeId) {
        if(task.executionContext()==null||!Objects.equals(chatId,task.executionContext().get("chatId")))throw invalid("历史会话不是当前执行上下文");
        return JSON.parseArray(open(nodeId).historyJson(),ChatItem.class);
    }
    void verify(LlmReqBo request,List<Map<String,Object>> messages) {
        if(task.executionContext()==null) {
            if(request.getHistory()!=null&&!request.getHistory().isEmpty())throw invalid("非空Java历史缺少中心冻结来源");
            return;
        }
        Opened fixed=open(request.getNodeId());
        if(!fixed.historyJson().equals(canonical(request.getHistory()==null?List.of():request.getHistory()))
                ||!fixed.messagesJson().equals(canonical(messages)))throw invalid("Java请求历史或消息偏离中心冻结快照");
    }
    private synchronized Opened open(String nodeId) {
        scope.check();validateAssignment(task);
        if(!(task.dsl().get("nodes") instanceof List<?> nodes)||nodes.stream().noneMatch(n->n instanceof Map<?,?> m&&nodeId.equals(m.get("id"))&&nodeId.startsWith("spark-llm::")))
            throw invalid("Java历史节点不是当前固定模型节点");
        Opened old=opened.get(nodeId);if(old!=null)return old;
        Map<String,Object> response=center.openJavaHistory(scope.lease(),nodeId);scope.check();
        if(response==null||!response.keySet().equals(RESPONSE)||canonical(response).getBytes(StandardCharsets.UTF_8).length>2*1024*1024)
            throw invalid("Java历史回执字段或预算无效");
        same("conversation-history",response.get("kind"));same(task.runId(),response.get("runId"));same(task.leaseId(),response.get("leaseId"));
        same(nodeId,response.get("nodeId"));same(task.executionContext().get("chatId"),response.get("chatId"));
        if(integer(response.get("attempt"),1)!=task.attempt())throw invalid("Java历史attempt不一致");
        String ref=text(response.get("historyRef"),19);if(!ref.matches("[1-9][0-9]{0,18}"))throw invalid("Java历史引用无效");
        try{Long.parseLong(ref);}catch(NumberFormatException e){throw invalid("Java历史引用超出范围");}
        String hash=text(response.get("historyHash"),64);if(!hash.matches("[0-9a-f]{64}"))throw invalid("Java历史hash无效");
        Map<?,?> snapshot=map(response.get("snapshot"));if(!snapshot.keySet().equals(SNAPSHOT)||!hash.equals(digest(snapshot)))throw invalid("Java历史完整快照hash不一致");
        same(task.runId(),snapshot.get("runId"));same(task.leaseId(),snapshot.get("leaseId"));same(nodeId,snapshot.get("nodeId"));same("java",snapshot.get("engineProfile"));
        same(task.dslHash(),snapshot.get("dslHash"));same(task.executionContext().get("chatId"),snapshot.get("chatId"));same(task.executionContext().get("appId"),snapshot.get("appId"));
        if(integer(snapshot.get("attempt"),1)!=task.attempt()||!task.workflowVersionId().equals(String.valueOf(integer(snapshot.get("workflowVersionId"),1))))throw invalid("Java历史固定版本或attempt不一致");
        integer(snapshot.get("spaceId"),0);integer(snapshot.get("workflowId"),1);
        same(task.executionContext().get("userId"),"ruoyi:"+integer(snapshot.get("ownerUserId"),1));
        if(!text(snapshot.get("inputHash"),64).matches("[0-9a-f]{64}"))throw invalid("Java历史输入hash无效");
        List<?> history=list(snapshot.get("history"));if(history.size()>10||!canonical(history).equals(canonical(response.get("history"))))throw invalid("Java历史窗口或顶层快照不一致");
        for(Object raw:history)validateItem(raw,nodeId);
        List<?> messages=list(snapshot.get("messages"));if(messages.isEmpty())throw invalid("Java历史缺少当前消息");
        for(Object raw:messages) {
            Map<?,?> message=map(raw);if(!message.keySet().equals(Set.of("role","content"))||!Set.of("system","user","assistant").contains(message.get("role")))throw invalid("Java历史模型消息角色无效");
            text(message.get("content"),2*1024*1024);
        }
        Opened result=new Opened(canonical(history),canonical(messages));opened.put(nodeId,result);return result;
    }
    private void validateItem(Object raw,String nodeId) {
        Map<?,?> item=map(raw);if(!item.keySet().equals(Set.of("chatId","nodeId","userInputs","llmThinking","llmResponses")))throw invalid("Java旧轮次字段无效");
        same(task.executionContext().get("chatId"),item.get("chatId"));same(nodeId,item.get("nodeId"));
        for(String key:List.of("userInputs","llmThinking","llmResponses"))for(Object r:list(item.get(key))) {
            Map<?,?> m=map(r);Set<String> roles=key.equals("userInputs")?Set.of("SYSTEM","USER"):key.equals("llmThinking")?Set.of("THINKING"):Set.of("ASSISTANT");
            if(!m.keySet().equals(Set.of("role","content","timestamp"))||!roles.contains(m.get("role")))throw invalid("Java旧轮次消息字段无效");
            text(m.get("content"),2*1024*1024);integer(m.get("timestamp"),0);
        }
    }
    synchronized void clear(){opened.clear();}
    /** 与中心DslCodec.V2相同：递归TreeMap、原List顺序、显式null、FastJSON2 UTF8。仅用于历史文本快照，不重算DSL。 */
    static String canonical(Object value){return JSON.toJSONString(ordered(JSON.toJSON(value),0),JSONWriter.Feature.WriteMapNullValue);}
    private static Object ordered(Object value,int depth) {
        if(depth>32)throw invalid("Java历史结构超过深度预算");
        if(value instanceof Map<?,?> map){Map<String,Object> sorted=new TreeMap<>();map.forEach((k,v)->{if(!(k instanceof String s))throw invalid("Java历史对象键无效");sorted.put(s,ordered(v,depth+1));});return sorted;}
        if(value instanceof List<?> list){List<Object> result=new ArrayList<>();for(Object v:list)result.add(ordered(v,depth+1));return result;}
        return value;
    }
    static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static long integer(Object value,long min){if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<min)throw invalid("Java历史整数无效");return ((Number)value).longValue();}
    private static String text(Object value,int max){if(!(value instanceof String s)||s.length()>max)throw invalid("Java历史字符串无效");return s;}
    private static Map<?,?> map(Object value){if(!(value instanceof Map<?,?> m))throw invalid("Java历史对象无效");return m;}
    private static List<?> list(Object value){if(!(value instanceof List<?> l))throw invalid("Java历史数组无效");return l;}
    private static void same(Object expected,Object value){if(!Objects.equals(expected,value))throw invalid("Java历史完整作用域不一致");}
    private static WorkerException invalid(String message){return new WorkerException("HISTORY_PROTOCOL_INVALID",message);}
}
