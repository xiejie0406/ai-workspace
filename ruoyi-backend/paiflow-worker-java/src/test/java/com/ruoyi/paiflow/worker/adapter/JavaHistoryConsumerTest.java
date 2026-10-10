package com.ruoyi.paiflow.worker.adapter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.iflytek.astron.workflow.engine.*;
import com.iflytek.astron.workflow.engine.context.EngineContextHolder;
import com.iflytek.astron.workflow.engine.integration.model.*;
import com.iflytek.astron.workflow.engine.integration.model.LlmChatHistory.ChatItem;
import com.iflytek.astron.workflow.engine.integration.model.bo.*;
import com.iflytek.astron.workflow.engine.node.*;
import com.iflytek.astron.workflow.engine.node.impl.*;
import com.iflytek.astron.workflow.engine.node.impl.llm.LLMNodeExecutor;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.*;
import com.ruoyi.paiflow.worker.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 实际HttpCenterClient序列化/回执与原执行器隔离技术检查；真实中心Mapper闭环另由父测试执行。 */
class JavaHistoryConsumerTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC);
    private static final String NODE="spark-llm::2",CHAT="centre-chat";

    @Test void actualHttpOpenKeepsThreeFieldsAndAcceptsBoundCanonicalUnicodeSnapshotOnce() throws Exception {
        var f=fixture();var req=request();req.setHistory(old(2));ObjectNode response=response(f.assignment,req.getHistory(),originalMessages(req));
        AtomicInteger opens=new AtomicInteger();AtomicReference<JsonNode> body=new AtomicReference<>();AtomicReference<String> auth=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/internal/paiflow/v1/runs/java-model-run/history/java/open",exchange->{
            opens.incrementAndGet();auth.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(mapper.readTree(exchange.getRequestBody()));
            ObjectNode envelope=mapper.createObjectNode().put("code",200);envelope.set("data",response);byte[] bytes=mapper.writeValueAsBytes(envelope);
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            CenterClient client=new HttpCenterClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"history-fixture",mapper);
            try(ExecutionScope scope=new ExecutionScope(client,f.assignment,CLOCK)) {
                scope.bind();EngineContextHolder.initContext("original-model-flow","old-dsl-chat",null);
                assertEquals(CHAT,EngineContextHolder.get().getChatId());assertEquals("java-model-chat",f.assignment.dsl().get("uuid"));
                List<ChatItem> before=LlmChatHistory.getHistory(CHAT,NODE,2);LlmChatHistory.newChat(CHAT,NODE);
                LlmChatHistory.addMessage(CHAT,NODE,com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.USER,"当前临时问题");
                assertEquals(before,LlmChatHistory.getHistory(CHAT,NODE,2));assertEquals(2,before.size());assertEquals(1,opens.get());
                assertEquals(Map.of("leaseId","java-model-lease","attempt",1,"nodeId",NODE),mapper.convertValue(body.get(),Map.class));
                assertEquals("Bearer history-fixture",auth.get());scope.verifyJavaHistory(req,CenterModelConsumer.messages(req));
                before.get(0).userInputs().clear();assertEquals(2,LlmChatHistory.getHistory(CHAT,NODE,2).get(0).userInputs().size(),"返回副本不能修改冻结快照");
            }
        } finally {EngineContextHolder.remove();server.stop(0);}
    }

    @Test void originalPromptSkipsOldSystemThinkingAndExtraAssistantButKeepsAllOldUsersAndEmptyText() throws Exception {
        var f=fixture();var req=request();List<ChatItem> history=old(2);
        history.get(0).userInputs().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.USER,"",1));
        history.get(0).llmResponses().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.ASSISTANT,"第二个不发送",1));
        req.setHistory(history);List<Map<String,Object>> original=originalMessages(req);f.historyResponse=response(f.assignment,history,original);
        try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
            scope.bind();LlmChatHistory.getHistory(CHAT,NODE,2);LlmChatHistory.newChat(CHAT,NODE);
            LlmResVo output=new ModelServiceClient().chatCompletion(req,r->{});assertEquals("原文本",output.content());
            assertEquals(mapper.valueToTree(original),f.registered.path("parameters").path("messages"));assertEquals(1,f.historyOpens.get());assertEquals(1,f.starts.get());
            assertEquals(0,f.cancels.get());assertFalse(scope.hasUnconfirmedEffect());
        }
        assertTrue(original.stream().anyMatch(m->"assistant".equals(m.get("role"))));
        assertFalse(original.toString().contains("旧系统"));assertFalse(original.toString().contains("旧思考"));assertFalse(original.toString().contains("第二个不发送"));
    }

    @Test void wrongScopeHashDuplicateProjectionAndRoleCannotReachModelStart() throws Exception {
        for(String mutation:List.of("runId","leaseId","attempt","nodeId","chatId","engineProfile","workflowVersionId","dslHash","appId","ownerUserId","historyHash","history","role")) {
            var f=fixture();var req=request();req.setHistory(old(2));f.historyResponse=response(f.assignment,req.getHistory(),originalMessages(req));
            ObjectNode snapshot=(ObjectNode)f.historyResponse.get("snapshot");
            switch(mutation) {
                case "historyHash" -> f.historyResponse.put("historyHash","0".repeat(64));
                case "history" -> f.historyResponse.putArray("history");
                case "role" -> ((ObjectNode)snapshot.path("history").get(0).path("userInputs").get(0)).put("role","ASSISTANT");
                case "attempt","workflowVersionId","ownerUserId" -> snapshot.put(mutation,99);
                default -> snapshot.put(mutation,"wrong");
            }
            if(mutation.equals("role"))f.historyResponse.set("history",snapshot.get("history").deepCopy());
            if(!mutation.equals("historyHash"))f.historyResponse.put("historyHash",hash(mapper.convertValue(snapshot,Map.class)));
            try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
                scope.bind();assertThrows(WorkerException.class,()->new ModelServiceClient().chatCompletion(req,r->{}),mutation);
                assertEquals(0,f.starts.get());assertEquals(1,f.historyOpens.get());
            }
        }
    }

    @Test void arbitraryHistoryWithoutContextAndChangedRequestAfterOpenCannotCreateFees() throws Exception {
        var legacy=new JavaModelConsumerTest().new Fixture();var req=request();req.setHistory(old(2));
        try(ExecutionScope scope=new ExecutionScope(legacy.client,legacy.assignment,CLOCK)) {
            scope.bind();assertThrows(WorkerException.class,()->new ModelServiceClient().chatCompletion(req,r->{}));assertEquals(0,legacy.starts.get());assertEquals(0,legacy.historyOpens.get());
        }
        var f=fixture();f.historyResponse=response(f.assignment,req.getHistory(),originalMessages(req));
        try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
            scope.bind();req.setHistory(LlmChatHistory.getHistory(CHAT,NODE,2));req.setUserMsg("篡改当前问题");
            assertThrows(WorkerException.class,()->new ModelServiceClient().chatCompletion(req,r->{}));assertEquals(0,f.starts.get());assertEquals(1,f.historyOpens.get());
        }
    }

    @Test void originalHistoryTwentyOneConfigurationsKeepNullableCastOverflowAndReadBeforeQueueOrder() throws Exception {
        Object missing=new Object();List<Object> configs=new ArrayList<>();configs.add(missing);configs.add(null);configs.add("bad");
        for(Object[] pair:new Object[][]{{false,2},{true,0},{true,2},{true,10},{true,20},{true,-2},{true,Integer.MIN_VALUE},{true,null},{true,2.5},{true,"2"},{true,2147483648L},{"true",2},{"TRUE",2},{"false",2},{"true","2"},{1,2},{true,true}}) {
            Map<String,Object> c=new LinkedHashMap<>();c.put("isEnabled",pair[0]);c.put("rounds",pair[1]);configs.add(c);
        }
        configs.add(Map.of("isEnabled",true));assertEquals(21,configs.size());
        var limitMethod=LLMNodeExecutor.class.getDeclaredMethod("historyLimit",Map.class);limitMethod.setAccessible(true);
        for(Object config:configs) {
            var f=fixture();Map<String,Object> dsl=mapper.convertValue(f.assignment.dsl(),Map.class);
            Map<String,Object> p=(Map<String,Object>)((Map<?,?>)((Map<?,?>)((List<?>)dsl.get("nodes")).get(1)).get("data")).get("nodeParam");
            if(config!=missing)p.put("enableChatHistoryV2",config);dsl=mapper.readValue(mapper.writeValueAsBytes(dsl),Map.class);
            p=(Map<String,Object>)((Map<?,?>)((Map<?,?>)((List<?>)dsl.get("nodes")).get(1)).get("data")).get("nodeParam");
            Integer limit=(Integer)limitMethod.invoke(new LLMNodeExecutor(new ModelServiceClient()),p);
            f.assignment=copy(f.assignment,dsl,f.assignment.executionContext());List<ChatItem> all=old(10),selected=List.of();boolean invalid=limit==null;
            if(limit!=null&&limit!=0){int from=Math.max(0,all.size()-limit);if(from>all.size())invalid=true;else selected=all.subList(from,all.size());}
            var req=request();req.setHistory(selected);f.historyResponse=response(f.assignment,selected,originalMessages(req));if(invalid&&limit!=null)f.historyStatus=400;
            List<NodeExecutor> executors=List.of(new StartNodeExecutor(),new LLMNodeExecutor(new ModelServiceClient()),new EndNodeExecutor());ParallelWorkflowEngine parallel=new ParallelWorkflowEngine(executors);
            try(WorkerTaskRunner runner=new WorkerTaskRunner(f.client,new WorkflowEngine(executors),parallel,CLOCK,30,60,mapper)) {
                Result result=runner.execute(new Lease(f.assignment.runId(),f.assignment.leaseId(),1));assertEquals(invalid?"FAILED":"COMPLETED",result.state(),String.valueOf(config));
                assertEquals(invalid?0:1,f.starts.get());assertEquals(limit==null?0:1,f.historyOpens.get());
                if(!invalid)assertEquals(mapper.valueToTree(originalMessages(req)),f.registered.path("parameters").path("messages"));
            }finally{parallel.close();}
        }
    }

    @Test void originalTenItemSnapshotRemainsWholeAfterCurrentTurnDisplacesOldest() throws Exception {
        var f=fixture();var req=request();req.setHistory(old(10));f.historyResponse=response(f.assignment,req.getHistory(),originalMessages(req));
        try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
            scope.bind();List<ChatItem> before=LlmChatHistory.getHistory(CHAT,NODE,Integer.MIN_VALUE);LlmChatHistory.newChat(CHAT,NODE);
            LlmChatHistory.addMessage(CHAT,NODE,com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.USER,"当前问题");
            assertEquals(10,before.size());assertEquals("旧问题0",before.get(0).userInputs().get(1).content());assertEquals(before,LlmChatHistory.getHistory(CHAT,NODE,Integer.MIN_VALUE));assertEquals(1,f.historyOpens.get());
        }
    }

    @Test void stoppedScopeCannotOpenHistoryOrDispatchModel() throws Exception {
        var f=fixture();var req=request();req.setHistory(old(2));f.historyResponse=response(f.assignment,req.getHistory(),originalMessages(req));
        try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
            scope.bind();scope.cancel();assertThrows(WorkerException.class,()->LlmChatHistory.getHistory(CHAT,NODE,2));assertEquals(0,f.historyOpens.get());assertEquals(0,f.starts.get());
        }
    }

    private JavaModelConsumerTest.Fixture fixture() throws Exception {var f=new JavaModelConsumerTest().new Fixture();f.assignment=copy(f.assignment,f.assignment.dsl(),Map.of("flowId","original-model-flow","userId","ruoyi:1","appId","debug:9","chatId",CHAT));return f;}
    private Assignment copy(Assignment a,Map<String,Object> dsl,Map<String,Object> context)throws Exception {
        byte[] canonical=mapper.writeValueAsBytes(dsl);return new Assignment(a.schemaVersion(),a.runId(),"51",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)),Base64.getEncoder().encodeToString(canonical),a.engineProfile(),a.input(),a.resourceSnapshots(),a.attempt(),a.leaseId(),a.deadline(),a.effectPolicy(),dsl,a.lastEventSeq(),context);
    }
    private LlmReqBo request(){var req=new LlmReqBo();req.setNodeId(NODE);req.setModel("original-model");req.setMaxTokens(32);req.setSystemMsg("系统：值");req.setUserMsg("问题：值/{{missing}}");return req;}
    private List<ChatItem> old(int count) {
        var result=new ArrayList<ChatItem>();for(int i=0;i<count;i++) {
            var item=new ChatItem(CHAT,NODE);item.userInputs().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.SYSTEM,"旧系统",1));
            item.userInputs().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.USER,"旧问题"+i,1));
            item.llmThinking().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.THINKING,"旧思考",1));
            item.llmResponses().add(new LlmChatHistory.ChatMessage(com.iflytek.astron.workflow.engine.constants.MsgTypeEnum.ASSISTANT,"旧答案"+i,1));result.add(item);
        }return result;
    }
    private List<Map<String,Object>> originalMessages(LlmReqBo req)throws Exception {
        var method=OpenAiStyleLlmIntegration.class.getDeclaredMethod("buildPrompt",LlmReqBo.class);method.setAccessible(true);Prompt prompt=(Prompt)method.invoke(new OpenAiStyleLlmIntegration(),req);
        List<Map<String,Object>> result=new ArrayList<>();for(var message:prompt.getInstructions())result.add(Map.of("role",message.getMessageType().getValue(),"content",message.getText()));return result;
    }
    private ObjectNode response(Assignment a,List<ChatItem> history,List<Map<String,Object>> messages)throws Exception {
        ObjectNode snapshot=mapper.createObjectNode().put("runId",a.runId()).put("leaseId",a.leaseId()).put("attempt",a.attempt()).put("nodeId",NODE).put("engineProfile","java")
                .put("spaceId",1).put("workflowId",9).put("ownerUserId",1).put("workflowVersionId",51).put("dslHash",a.dslHash()).put("appId","debug:9").put("chatId",CHAT).put("inputHash","a".repeat(64));
        snapshot.set("history",mapper.valueToTree(history));snapshot.set("messages",mapper.valueToTree(messages));
        ObjectNode response=mapper.createObjectNode().put("kind","conversation-history").put("nodeId",NODE).put("runId",a.runId()).put("leaseId",a.leaseId()).put("attempt",a.attempt()).put("chatId",CHAT).put("historyRef","1").put("historyHash",hash(mapper.convertValue(snapshot,Map.class)));
        response.set("history",snapshot.get("history").deepCopy());response.set("snapshot",snapshot);return response;
    }
    // 契约参考独立沿中心DslCodec.V2的TreeMap/List/显式null定义，不调用被测consumer的hash。
    private static Object ordered(Object v){if(v instanceof Map<?,?>m){Map<String,Object>r=new TreeMap<>();m.forEach((k,x)->r.put((String)k,ordered(x)));return r;}if(v instanceof List<?>l)return l.stream().map(JavaHistoryConsumerTest::ordered).toList();return v;}
    private static String hash(Object v)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(ordered(v),JSONWriter.Feature.WriteMapNullValue).getBytes(StandardCharsets.UTF_8)));}
}
