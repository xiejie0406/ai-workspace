package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.llm.Sub2ApiResponsesClient;
import com.paicli.memory.ConversationHistoryCompactor;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 经实际Agent/工具/账本/HTTP消费链验证，协议项不能在工具回灌前丢失。 */
class Sub2ApiHistoryIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final PrintStream QUIET=new PrintStream(OutputStream.nullOutputStream());
    private static final String ENCRYPTED="synthetic-encrypted-history-state";
    @TempDir Path tempDir;

    @ParameterizedTest @ValueSource(strings={"react","worker","plan"})
    void actualExecutionAndSerializedLedgerPreserveReasoningAndCallId(String mode)throws Exception{
        try(var server=new MockWebServer()){
            Files.writeString(tempDir.resolve("fixture.txt"),"synthetic-tool-result");
            var args=JSON.createObjectNode().put("path",tempDir.resolve("fixture.txt").toString());
            var call=JSON.createObjectNode().put("type","function_call").put("status","completed")
                    .put("call_id","call_history").put("name","read_file").put("arguments",args.toString());
            server.enqueue(response(reasoning()+","+call));server.enqueue(response(text("fixture finished")));
            var client=new Sub2ApiResponsesClient("synthetic-key","fixture-model",server.url("/v1").toString());
            ToolRegistry tools=new ToolRegistry();tools.setProjectPath(tempDir.toString());
            var manager=new MemoryManager(client,4096,128000,new LongTermMemory(tempDir.resolve("memory-"+mode).toFile()));
            var ledger=ConversationLedger.open(tempDir.resolve("ledger"),"history-"+mode);
            if(mode.equals("react")){
                var agent=new Agent(client,tools,manager);agent.setConversationLedger(ledger);
                assertTrue(agent.runExplicitTask("Read fixture.txt", "Read fixture.txt").contains("fixture finished"));
                assertEquals(Agent.RunOutcome.COMPLETED,agent.getLastRunOutcome());
            }else if(mode.equals("worker")){
                var worker=new SubAgent("fixture-worker",AgentRole.WORKER,client,tools);worker.setConversationLedger(ledger);
                assertTrue(worker.execute(AgentMessage.task("orchestrator","Read fixture.txt"),QUIET).content().contains("fixture finished"));
            }else{
                var planner=new Planner(client,QUIET){
                    @Override public ExecutionPlan createPlan(String goal){
                        var plan=new ExecutionPlan("fixture-plan",goal);plan.addTask(new Task("fixture-task","Read fixture.txt",Task.TaskType.FILE_READ));
                        plan.computeExecutionOrder();return plan;
                    }
                };
                var agent=new PlanExecuteAgent(client,tools,planner,manager,(goal,plan)->PlanExecuteAgent.PlanReviewDecision.execute(),QUIET);
                agent.setConversationLedger(ledger);
                assertTrue(agent.runExplicitTask("Read fixture.txt","Read fixture.txt").contains("计划执行完成"));
            }
            var first=server.takeRequest(2,TimeUnit.SECONDS);var second=server.takeRequest(2,TimeUnit.SECONDS);
            assertNotNull(first);assertNotNull(second);assertEquals(2,server.getRequestCount());
            assertProtocolPair(JSON.readTree(second.getBody().readUtf8()).path("input"));
            var entries=ledger.readAll();
            var original=entries.stream().map(ConversationLedger.Entry::message).filter(m->m!=null&&m.responseItems()!=null)
                    .filter(m->m.toolCalls()!=null&&!m.toolCalls().isEmpty()).findFirst().orElseThrow();
            assertTrue(original.responseItems().toString().contains(ENCRYPTED));
            assertEquals("call_history",original.toolCalls().get(0).id());
            // 使用真正从JSONL恢复的消息，不能只证明Java对象还留着字段。
            var history=new ArrayList<LlmClient.Message>();history.add(LlmClient.Message.user("resume"));history.add(original);
            history.add(entries.stream().map(ConversationLedger.Entry::message).filter(m->m!=null&&"tool".equals(m.role())).findFirst().orElseThrow());
            server.enqueue(response(text("resumed")));client.chat(history,null);
            assertProtocolPair(JSON.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8()).path("input"));
        }
    }

    @Test void realTextCompactionRetainsRecentProtocolPairWithoutSendingEncryptedStateToSummary()throws Exception{
        try(var server=new MockWebServer()){
            String call="{\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_history\",\"name\":\"read_file\",\"arguments\":\"{}\"}";
            server.enqueue(response(reasoning()+","+call));
            String summary="## 当前目标与成功条件\n继续读取\n## 用户要求与已确认决定\n中文\n## 已完成工作及证据\n旧轮次完成\n## 未解决问题与下一步\n继续";
            server.enqueue(response(text(summary)));server.enqueue(response(text("continued")));
            var client=new Sub2ApiResponsesClient("synthetic-key","fixture-model",server.url("/v1").toString());
            var recent=client.chat(List.of(LlmClient.Message.user("recent")),null);
            var history=new ArrayList<>(List.of(LlmClient.Message.system("rules"),LlmClient.Message.user("old"+"x".repeat(16000)),
                    LlmClient.Message.assistant("old answer"),LlmClient.Message.user("recent"),LlmClient.Message.assistant(recent),LlmClient.Message.tool("call_history","synthetic-tool-result")));
            assertTrue(new ConversationHistoryCompactor(client,1).compactNow(history));
            assertTrue(history.stream().anyMatch(m->m.responseItems()!=null&&m.responseItems().toString().contains(ENCRYPTED)));
            client.chat(history,null);server.takeRequest(2,TimeUnit.SECONDS);
            var summaryRequest=server.takeRequest(2,TimeUnit.SECONDS);assertFalse(summaryRequest.getBody().readUtf8().contains(ENCRYPTED));
            assertProtocolPair(JSON.readTree(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8()).path("input"));
            assertEquals(3,server.getRequestCount());
        }
    }

    private static void assertProtocolPair(com.fasterxml.jackson.databind.JsonNode input){
        assertTrue(input.toString().contains(ENCRYPTED));
        var items=java.util.stream.StreamSupport.stream(input.spliterator(),false).toList();
        assertEquals(1,items.stream().filter(n->"function_call".equals(n.path("type").asText())&&"call_history".equals(n.path("call_id").asText())).count());
        assertEquals(1,items.stream().filter(n->"function_call_output".equals(n.path("type").asText())&&"call_history".equals(n.path("call_id").asText())&&n.path("output").asText().contains("synthetic-tool-result")).count());
    }
    private static String reasoning(){return "{\"type\":\"reasoning\",\"id\":\"rs_history\",\"summary\":[],\"encrypted_content\":\""+ENCRYPTED+"\"}";}
    private static String text(String text){var message=JSON.createObjectNode().put("type","message").put("role","assistant").put("status","completed");message.putArray("content").addObject().put("type","output_text").put("text",text);return message.toString();}
    private static MockResponse response(String items){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"status\":\"completed\",\"model\":\"fixture-model\",\"output\":["+items+"],\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}");}
}
