package com.ruoyi.paiflow.worker.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.iflytek.astron.workflow.engine.*;
import com.iflytek.astron.workflow.engine.domain.WorkflowDSL;
import com.iflytek.astron.workflow.engine.integration.model.*;
import com.iflytek.astron.workflow.engine.integration.model.bo.*;
import com.iflytek.astron.workflow.engine.node.NodeExecutor;
import com.iflytek.astron.workflow.engine.node.impl.*;
import com.iflytek.astron.workflow.engine.node.impl.llm.LLMNodeExecutor;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.*;
import com.ruoyi.paiflow.worker.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原Java SDK真实隔离SSE与正式HttpCenterClient Exchange；不代表已获中心生产准入。 */
class JavaModelConsumerTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC);
    private static final String REF="model:1:version:2",OP="model-op-original";
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test void originalSdkActualWireAndCallbacksMatchCentreConsumerIncludingNullableOverrideAndMultipleChoices() throws Exception {
        List<ObjectNode> goldens=new ArrayList<>();
        for(int variant=0;variant<5;variant++) {
            Fixture fixture=new Fixture(); LlmReqBo req=request();
            if(variant==0)req.setMaxTokens(null);
            if(variant==1) {req.setTopK(8);req.setIsThink(false);req.setExtraParams(Map.of("maxTokens",12,"temperature",0,"topP",0.0,"presencePenalty",0,"frequencyPenalty",0,"n",1));}
            if(variant==2) {req.setTopK(8);req.setExtraParams(Map.of("maxTokens","bad","temperature","bad","topP","bad","n","bad"));}
            if(variant==3) {req.setExtraParams(Map.of("n",2));fixture.multipleChoices=true;}
            if(variant==4)fixture.reason="length";
            List<ObjectNode> frames=fixture.rawFrames(); List<ObjectNode> oldCallbacks=new ArrayList<>(),newCallbacks=new ArrayList<>();
            ObjectNode[] wire=new ObjectNode[1]; LlmResVo original;
            HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            server.createContext("/v1/chat/completions",exchange->{
                wire[0]=(ObjectNode)mapper.readTree(exchange.getRequestBody());exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                StringBuilder sse=new StringBuilder();for(ObjectNode frame:frames)sse.append("data: ").append(frame).append("\n\n");sse.append("data: [DONE]\n\n");
                byte[] bytes=sse.toString().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}
            });server.start();
            try {
                String url="http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions";req.setUrl(url);req.setApiKey("isolated-sdk-fixture");
                CenterClient oldCenter=mock(CenterClient.class);when(oldCenter.authorizeEffect(any(),any())).thenReturn(new EffectGrant(true,"old-fixture-grant"));
                Assignment old=task(Map.of("nodes",List.of()),Map.of("old-model",Map.of("endpoint",url)),new EffectPolicy("approved_write",List.of("model.infer"),"fixture-only"));
                try(ExecutionScope scope=new ExecutionScope(oldCenter,old,CLOCK)){scope.bind();original=new OpenAiStyleLlmIntegration().call(req,response->oldCallbacks.add(mapper.valueToTree(response)));}
            } finally {server.stop(0);}
            req.setUrl(null);req.setApiKey(null);
            LlmResVo actual;
            try(ExecutionScope scope=new ExecutionScope(fixture.client,fixture.assignment,CLOCK)){scope.bind();actual=new ModelServiceClient().chatCompletion(req,response->newCallbacks.add(mapper.valueToTree(response)));assertFalse(scope.hasUnconfirmedEffect());}
            assertEquals(original.content(),actual.content());assertEquals(original.thinkContent(),actual.thinkContent());
            assertEquals(original.usage().getPromptTokens(),actual.usage().getPromptTokens());assertEquals(original.usage().getCompletionTokens(),actual.usage().getCompletionTokens());assertEquals(original.usage().getTotalTokens(),actual.usage().getTotalTokens());
            assertEquals(oldCallbacks,newCallbacks,"保留原SDK实际callback而非重写Python解码");
            JsonNode p=fixture.registered.path("parameters"),o=p.path("options");
            assertEquals(wire[0].path("messages"),p.path("messages"));
            Map<String,String> field=Map.of("maxTokens","max_tokens","temperature","temperature","topP","top_p","presencePenalty","presence_penalty","frequencyPenalty","frequency_penalty","n","n");
            for(var entry:field.entrySet())assertEquals(wire[0].has(entry.getValue())?wire[0].get(entry.getValue()):NullNode.instance,o.get(entry.getKey()),entry.getKey());
            assertTrue(wire[0].path("enable_thinking").asBoolean());assertTrue(wire[0].path("stream_options").path("include_usage").asBoolean());
            assertTrue(o.path("enableThinking").asBoolean());assertTrue(o.path("streamUsage").asBoolean());assertFalse(wire[0].has("top_k"));
            assertEquals(1,fixture.starts.get());assertEquals(2,fixture.pages.get());assertEquals(0,fixture.cancels.get());
            ObjectNode sample=mapper.createObjectNode().put("variant",variant).put("scope","原Java SDK实际本机SSE与中心Exchange技术夹具，非真实Provider/非中心准入");
            sample.set("originalWire",wire[0]);sample.set("rawFrames",mapper.valueToTree(frames));sample.set("actualRequest",fixture.registered);sample.set("actualCallbacks",mapper.valueToTree(newCallbacks));sample.set("actualOutput",mapper.valueToTree(actual));goldens.add(sample);
        }
        String path=System.getProperty("paiflow.worker.modelGoldenPath");if(path!=null)Files.writeString(Path.of(path),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(goldens),StandardCharsets.UTF_8);
    }

    @Test void originalStartLlmEndGraphKeepsPromptReasonFirstOutputUsageAndNodeEvents() throws Exception {
        Fixture f=new Fixture();List<NodeExecutor> executors=List.of(new StartNodeExecutor(),new LLMNodeExecutor(new ModelServiceClient()),new EndNodeExecutor());
        ParallelWorkflowEngine parallel=new ParallelWorkflowEngine(executors);
        try(WorkerTaskRunner runner=new WorkerTaskRunner(f.client,new WorkflowEngine(executors),parallel,CLOCK,30,60,mapper)) {
            Result r=runner.execute(new Lease("java-model-run","java-model-lease",1));assertEquals("COMPLETED",r.state());
            assertEquals("结果：原文本",((Map<?,?>)r.output().get("node-end::3")).get("content"));
            JsonNode completed=f.events.stream().filter(e->"spark-llm::2".equals(e.path("nodeId").asText())&&"node.completed".equals(e.path("eventType").asText())).findFirst().orElseThrow();
            JsonNode output=completed.path("payload").path("result").path("outputs");assertEquals("原文本",output.path("answer").asText());assertEquals("思考",output.path("reason").asText());assertFalse(output.has("unused"));
            JsonNode result=completed.path("payload").path("result");assertEquals("原文本",result.path("rawOutput").asText());assertEquals(7,result.path("tokenCost").path("total_tokens").asInt());
            assertEquals("系统：值",f.registered.path("parameters").path("messages").get(0).path("content").asText());
            assertEquals("问题：值/{{missing}}",f.registered.path("parameters").path("messages").get(1).path("content").asText());
            assertEquals(1,f.begins.get());assertEquals(1,f.starts.get());
            String path=System.getProperty("paiflow.worker.modelGraphGoldenPath");if(path!=null){ObjectNode sample=mapper.createObjectNode();sample.set("assignment",mapper.valueToTree(f.assignment));sample.set("actualRequest",f.registered);sample.set("actualEvents",mapper.valueToTree(f.events));sample.set("actualResult",mapper.valueToTree(r));Files.writeString(Path.of(path),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample),StandardCharsets.UTF_8);}
        } finally {parallel.close();}
    }

    @Test void wrongBindingCursorAndMissingFinishRemainStickyUnknownWithoutReinvoke() throws Exception {
        for(String mutation:List.of("leaseId","attempt","nodeId","resourceVersion","iterationId","nextCursor","finishSeq")) {
            Fixture f=new Fixture();f.mutation=mutation;
            try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
                scope.bind();assertThrows(RuntimeException.class,()->new ModelServiceClient().chatCompletion(request(),ignored->{}));
                assertTrue(scope.hasUnconfirmedEffect());assertEquals("EFFECT_UNKNOWN",assertThrows(WorkerException.class,scope::check).getCode());assertEquals(1,f.starts.get());assertEquals(1,f.cancels.get());
            }
        }
    }
    @Test void cancelledReadUsesSameOperationAndRequiresActualStopProof() throws Exception {
        for(boolean proof:List.of(false,true)) {
            Fixture f=new Fixture();f.stopProof=proof;
            try(ExecutionScope scope=new ExecutionScope(f.client,f.assignment,CLOCK)) {
                scope.bind();f.cancelScope=scope;
                assertEquals("RUN_CANCELLED",assertThrows(WorkerException.class,()->new ModelServiceClient().chatCompletion(request(),ignored->{})).getCode());
                assertEquals(!proof,scope.hasUnconfirmedEffect());assertEquals(1,f.starts.get());assertEquals(1,f.cancels.get());
            }
        }
    }
    @Test void historyAndUnboundModelRejectBeforeBeginOrOriginalEngine() throws Exception {
        for(boolean history:List.of(false,true)) {
            Fixture f=new Fixture();Map<String,Object> dsl=mapper.convertValue(f.assignment.dsl(),Map.class);
            Map<String,Object> param=(Map<String,Object>)((Map<?,?>)((Map<?,?>)((List<?>)dsl.get("nodes")).get(1)).get("data")).get("nodeParam");
            if(history)param.put("enableChatHistoryV2",Map.of("isEnabled",true,"rounds",2));else param.remove("modelResourceId");
            f.assignment=task(dsl,f.assignment.resourceSnapshots(),f.assignment.effectPolicy());
            List<NodeExecutor> executors=List.of(new StartNodeExecutor(),new LLMNodeExecutor(new ModelServiceClient()),new EndNodeExecutor());ParallelWorkflowEngine parallel=new ParallelWorkflowEngine(executors);
            try(WorkerTaskRunner runner=new WorkerTaskRunner(f.client,new WorkflowEngine(executors),parallel,CLOCK,30,60,mapper)) {
                assertThrows(WorkerException.class,()->runner.execute(new Lease("java-model-run","java-model-lease",1)));assertEquals(0,f.begins.get());assertEquals(0,f.starts.get());assertTrue(f.events.isEmpty());
            } finally {parallel.close();}
        }
    }
    @Test void historyRoundTypesFollowOriginalJacksonIntegerLimitBeforeBegin() throws Exception {
        record HistoryCase(Object enabled,Object rounds,boolean rejected) {}
        List<HistoryCase> cases=List.of(new HistoryCase(true,2.5,false),new HistoryCase(true,"2",false),
                new HistoryCase(true,2147483648L,false),new HistoryCase(true,null,false),
                new HistoryCase(true,0,false),new HistoryCase(false,2,false),new HistoryCase("false",2,false),
                new HistoryCase("TRUE",2,false),new HistoryCase("true","2",false),
                new HistoryCase(true,2,true),new HistoryCase("true",2,true),new HistoryCase(true,-2,true));
        var originalLimit=LLMNodeExecutor.class.getDeclaredMethod("historyLimit",Map.class);originalLimit.setAccessible(true);
        for(HistoryCase sample:cases) {
            Fixture f=new Fixture();Map<String,Object> dsl=mapper.convertValue(f.assignment.dsl(),Map.class);
            Map<String,Object> param=(Map<String,Object>)((Map<?,?>)((Map<?,?>)((List<?>)dsl.get("nodes")).get(1)).get("data")).get("nodeParam");
            Map<String,Object> history=new LinkedHashMap<>();history.put("isEnabled",sample.enabled());history.put("rounds",sample.rounds());param.put("enableChatHistoryV2",history);
            // 正式 Assignment.dsl 经过 Jackson JSON 解码；不靠手放小 Long 制造实际不存在的类型。
            dsl=mapper.readValue(mapper.writeValueAsBytes(dsl),Map.class);
            WorkflowDSL original=mapper.convertValue(dsl,WorkflowDSL.class);
            Integer limit=(Integer)originalLimit.invoke(new LLMNodeExecutor(new ModelServiceClient()),original.getNodes().get(1).getData().getNodeParam());
            assertEquals(sample.rejected(),limit!=null && limit!=0,"原 Integer/catch0 判定："+history);
            f.assignment=task(dsl,f.assignment.resourceSnapshots(),f.assignment.effectPolicy());
            List<NodeExecutor> executors=List.of(new StartNodeExecutor(),new LLMNodeExecutor(new ModelServiceClient()),new EndNodeExecutor());ParallelWorkflowEngine parallel=new ParallelWorkflowEngine(executors);
            try(WorkerTaskRunner runner=new WorkerTaskRunner(f.client,new WorkflowEngine(executors),parallel,CLOCK,30,60,mapper)) {
                if(sample.rejected()) {
                    var error=assertThrows(WorkerException.class,()->runner.execute(new Lease("java-model-run","java-model-lease",1)));
                    assertEquals("RESOURCE_UNAVAILABLE",error.getCode());assertEquals(0,f.begins.get());assertEquals(0,f.starts.get());assertTrue(f.events.isEmpty());
                } else {
                    Result result=runner.execute(new Lease("java-model-run","java-model-lease",1));
                    if(limit==null) {
                        // 原 historyLimit 返回 null，getChatHistory 解箱失败；不得伪改成无历史成功。
                        assertEquals("FAILED",result.state());assertEquals(1,f.begins.get());assertEquals(0,f.starts.get());
                        assertTrue(f.events.stream().anyMatch(e->"spark-llm::2".equals(e.path("nodeId").asText())&&e.path("payload").path("legacyFrame").path("code").asInt()!=0),"保全原 interrupt 错误帧，不假定原算法发 node.failed Map");
                    } else {
                        assertEquals("COMPLETED",result.state(),history.toString());assertEquals("结果：原文本",((Map<?,?>)result.output().get("node-end::3")).get("content"));
                        assertEquals(1,f.begins.get());assertEquals(1,f.starts.get());assertEquals(0,f.cancels.get());
                    }
                }
            } finally {parallel.close();}
        }
    }
    private LlmReqBo request(){LlmReqBo req=new LlmReqBo();req.setNodeId("spark-llm::2");req.setModel("original-model");req.setMaxTokens(32);req.setSystemMsg("系统");req.setUserMsg("问题");return req;}
    private Assignment task(Map<String,Object> dsl,Map<String,Map<String,Object>> resources,EffectPolicy policy)throws Exception {
        byte[] canonical=mapper.writeValueAsBytes(dsl);return new Assignment("paiflow.worker.v1","java-model-run","java-model-version",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)),Base64.getEncoder().encodeToString(canonical),"java",Map.of("x","值"),resources,1,"java-model-lease",CLOCK.instant().plusSeconds(60),policy,dsl,0);
    }
    final class Fixture {
        Assignment assignment; final HttpCenterClient client; final AtomicInteger starts=new AtomicInteger(),pages=new AtomicInteger(),cancels=new AtomicInteger(),begins=new AtomicInteger();
        JsonNode registered;String reason="stop",mutation;boolean multipleChoices,stopProof;ExecutionScope cancelScope;final List<JsonNode> events=Collections.synchronizedList(new ArrayList<>());
        ObjectNode historyResponse;int historyStatus=200;final AtomicInteger historyOpens=new AtomicInteger();JsonNode historyRequest;
        Fixture()throws Exception {
            Map<String,Object> dsl=mapper.readValue("""
              {"flowId":"original-model-flow","uuid":"java-model-chat","nodes":[
               {"id":"node-start::1","data":{"nodeMeta":{"aliasName":"开始"},"nodeParam":{},"inputs":[],"outputs":[]}},
               {"id":"spark-llm::2","data":{"nodeMeta":{"aliasName":"模型"},"nodeParam":{"modelId":1,"domain":"original-model","modelResourceId":"model:1:version:2","maxTokens":32,"template":"问题：{{x}}/{{missing}}","systemTemplate":"系统：{{x}}"},
                "inputs":[{"name":"x","schema":{"type":"string","value":{"type":"ref","content":{"nodeId":"node-start::1","name":"x"}}}}],
                "outputs":[{"name":"answer","schema":{"type":"object"}},{"name":"reason","schema":{"type":"string"}},{"name":"unused","schema":{"type":"string"}}]}},
               {"id":"node-end::3","data":{"nodeMeta":{"aliasName":"结束"},"nodeParam":{"outputMode":1,"template":"结果：{{answer}}"},"inputs":[{"name":"answer","schema":{"type":"string","value":{"type":"ref","content":{"nodeId":"spark-llm::2","name":"answer"}}}}],"outputs":[]}}],
               "edges":[{"sourceNodeId":"node-start::1","targetNodeId":"spark-llm::2"},{"sourceNodeId":"spark-llm::2","targetNodeId":"node-end::3"}]}
              """,Map.class);
            assignment=task(dsl,Map.of(REF,Map.of("kind","model","provider","openai","version","2","modelName","original-model","enabled",true,"capabilities",List.of("chat"))),new EffectPolicy("approved_write",List.of("model.invoke"),"fixture-only"));
            client=new HttpCenterClient(URI.create("https://centre.invalid"),"isolated-model-worker",mapper,this::exchange);
        }
        List<ObjectNode> rawFrames()throws Exception {
            List<ObjectNode> result=new ArrayList<>();for(String[] pair:List.of(new String[]{" ","思"},new String[]{"原文本","考"},new String[]{"",null})) {
                ObjectNode frame=mapper.createObjectNode().put("id","fixture-id").put("object","chat.completion.chunk").put("created",1).put("model","original-model");
                ObjectNode delta=mapper.createObjectNode().put("role","assistant").put("content",pair[0]);if(pair[1]!=null)delta.put("reasoning_content",pair[1]);
                ObjectNode choice=mapper.createObjectNode().put("index",0);choice.set("delta",delta);if(result.size()==2)choice.put("finish_reason",reason);else choice.putNull("finish_reason");
                ArrayNode choices=frame.putArray("choices");choices.add(choice);
                if(multipleChoices){ObjectNode second=choice.deepCopy();second.put("index",1);((ObjectNode)second.get("delta")).put("content",result.size()==2?"":"第二choice");choices.add(second);}
                result.add(frame);
            }
            ObjectNode tail=mapper.createObjectNode().put("id","fixture-id").put("object","chat.completion.chunk").put("created",1).put("model","original-model");tail.putArray("choices");tail.set("usage",mapper.readTree("{\"prompt_tokens\":4,\"completion_tokens\":3,\"total_tokens\":7}"));result.add(tail);return result;
        }
        HttpCenterClient.WireResponse exchange(HttpRequest request)throws IOException {
            try {
                JsonNode body=mapper.readTree(requestBody(request));String path=request.uri().getRawPath();ObjectNode data;
                if(path.endsWith("/history/java/open")){
                    historyOpens.incrementAndGet();historyRequest=body;
                    if(historyStatus!=200)return new HttpCenterClient.WireResponse(historyStatus,"{}".getBytes(StandardCharsets.UTF_8));
                    if(historyResponse==null)throw new AssertionError("历史夹具未配置");data=historyResponse.deepCopy();
                }
                else if(path.endsWith("/lease/verify")){
                    data=mapper.createObjectNode().put("valid",true).put("cancelRequested",false).put("dslHash",assignment.dslHash()).put("workflowVersionId",assignment.workflowVersionId()).put("lastEventSeq",0);
                    ObjectNode raw=mapper.valueToTree(assignment);raw.remove("executionContext");data.set("assignment",raw);
                    if(assignment.executionContext()!=null)data.set("executionContext",mapper.valueToTree(assignment.executionContext()));
                }
                else if(path.endsWith("/execution/begin")){begins.incrementAndGet();data=mapper.createObjectNode().put("begun",true).put("runId",assignment.runId()).put("leaseId",assignment.leaseId()).put("attempt",1).put("workflowVersionId",assignment.workflowVersionId()).put("dslHash",assignment.dslHash());}
                else if(path.endsWith("/heartbeat"))data=mapper.createObjectNode().put("valid",true).put("cancelRequested",false);
                else if(path.contains("/resources/"))data=mapper.valueToTree(assignment.resourceSnapshots().get(REF));
                else if(path.endsWith("/operations")){registered=body;starts.incrementAndGet();data=binding().put("state","RUNNING");}
                else if(path.endsWith("/frames")) {
                    pages.incrementAndGet();boolean finalPage=body.path("afterCursor").asInt()==2;
                    data=binding().put("state",finalPage?("stop".equals(reason)?"COMPLETED":"FAILED"):"RUNNING").put("operationState","stop".equals(reason)?"COMPLETED":"FAILED")
                        .put("nextCursor",finalPage?4:2).put("lastSeq",4).put("finishSeq",3).put("done",finalPage);
                    if("stop".equals(reason))data.putNull("failureKind");else data.put("failureKind","provider");
                    List<ObjectNode> frames=rawFrames();data.set("frames",mapper.valueToTree(frames.subList(finalPage?2:0,finalPage?4:2)));
                    if(mutation!=null){if("iterationId".equals(mutation))data.put("iterationId","unexpected");else if("attempt".equals(mutation))data.put("attempt","1");else if(Set.of("nextCursor","finishSeq").contains(mutation))data.put(mutation,1);else data.put(mutation,"wrong");}
                    if(cancelScope!=null)cancelScope.cancel();
                } else if(path.endsWith("/cancel")){cancels.incrementAndGet();data=binding().put("state",stopProof?"CANCELLED":"EFFECT_UNKNOWN").put("stopped",stopProof);if(stopProof)data.put("proofId","not-dispatched:"+OP);else data.putNull("proofId");}
                else if(path.endsWith("/events")){events.add(body);data=mapper.createObjectNode().put("accepted",true).put("seq",body.path("seq").asLong());}
                else if(path.endsWith("/result"))data=mapper.createObjectNode().put("accepted",true).put("state",body.path("state").asText());
                else throw new AssertionError("未授权接口："+path);
                ObjectNode envelope=mapper.createObjectNode().put("code",200);envelope.set("data",data);return new HttpCenterClient.WireResponse(200,mapper.writeValueAsBytes(envelope));
            }catch(Exception e){if(e instanceof IOException io)throw io;throw new IOException(e);}
        }
        ObjectNode binding(){ObjectNode data=mapper.createObjectNode().put("runId",assignment.runId()).put("leaseId",assignment.leaseId()).put("attempt",1).put("nodeId","spark-llm::2").putNull("iterationId").put("workflowVersionId",assignment.workflowVersionId()).put("dslHash",assignment.dslHash()).put("operationId",OP).put("resourceId",REF).put("resourceVersion","2").put("provider","openai");return data;}
    }
    private byte[] requestBody(HttpRequest request)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();CompletableFuture<Void> done=new CompletableFuture<>();request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>(){
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] bytes=new byte[b.remaining()];b.get(bytes);out.writeBytes(bytes);}public void onError(Throwable t){done.completeExceptionally(t);}public void onComplete(){done.complete(null);}});done.get(2,TimeUnit.SECONDS);return out.toByteArray();
    }
}
