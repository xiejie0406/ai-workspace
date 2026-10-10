package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeTextOnlyTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    @Test void textQuestionUsesOriginalAgentWithoutFileToolsSnapshotsOrMcp()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        ObjectNode claim=PersonalDirectoryContextTest.claim(directories.runtime(),"7");claim.put("textOnly",true).putNull("directoryContextId").putNull("directoryContext");
        RunnerLease lease=new RunnerLease(claim);List<JsonNode> events=new ArrayList<>();List<String> paths=new ArrayList<>();AtomicInteger modelCalls=new AtomicInteger();
        LlmClient model=new LlmClient(){
            public ChatResponse chat(List<Message> messages,List<Tool> tools){return chat(messages,tools,StreamListener.NO_OP);}
            public ChatResponse chat(List<Message> messages,List<Tool> tools,StreamListener listener){assertTrue(tools.isEmpty());modelCalls.incrementAndGet();return new ChatResponse("assistant","这里是编码实践的解释",null,null,10,5);}
            public String getModelName(){return "fixture";}public String getProviderName(){return "fixture";}
        };
        try(var server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                paths.add(request.getPath());ObjectNode result=CanonicalJson.MAPPER.createObjectNode();
                if(request.getPath().endsWith("/context")){
                    result.put("textOnly",true).put("ownerUserId","7").put("text","解释一下编码实践").put("mode","react");
                    result.putObject("policy").put("taskTokenBudget",100000).put("dailyRemainingTokenBudget",100000).put("maxOutputTokens",100);
                    result.putArray("history");
                }else if(request.getPath().endsWith("/events"))events.add(CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()));
                else if(request.getPath().endsWith("/memory"))result.putArray("entries");
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",result).toString());
            }catch(Exception invalid){throw new IllegalStateException(invalid);}}});server.start();
            Path state=RunnerPrivateDirectory.createDirectories(temp.resolve("state"));
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                var executor=new NativeTaskExecutor(center,state,ignored->model)){
                var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),directories.runtime());
                assertTrue(executor.execute(lease,tools).contains("编码实践"));assertEquals(1,modelCalls.get());
                assertFalse(events.stream().anyMatch(event->"SNAPSHOT".equals(event.path("type").asText())));
                assertFalse(paths.stream().anyMatch(path->path.endsWith("/mcp-context")||path.endsWith("/proposals")));
                try(var walk=Files.walk(state)){assertFalse(walk.anyMatch(path->path.getFileName().toString().equals("snapshots")));}
            }
        }
    }
}
