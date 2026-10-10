package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolRegistry;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeDirectoryControlTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    @Test void conversationDirectoryIsCreatedThenProvenWithSameOwnerAndNoProvider()throws Exception{
        var directories=new PersonalDirectoryContext(container.resolve("personal"),"7");
        Path state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));List<JsonNode> proofs=new ArrayList<>(),events=new ArrayList<>();
        try(var server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals"))body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());
                if(request.getPath().endsWith("/consume"))body.put("consumed",true);
                if(request.getPath().endsWith("/personal-directory-proof")){
                    proofs.add(body.deepCopy());Path actual=Path.of(body.path("canonicalPath").asText());assertTrue(Files.isDirectory(actual));
                    body=PersonalDirectoryContextTest.envelope(actual,"7").withObject("/directoryContext").deepCopy();
                }
                if(request.getPath().endsWith("/events"))events.add(body.deepCopy());
                if(request.getPath().endsWith("/receipts"))body.put("accepted",true).put("acknowledged",true).put("archived",false);
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            }catch(IOException failed){throw new UncheckedIOException(failed);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                var controls=new NativeControlExecutor(center,state,context->{throw new AssertionError("Directory preparation cannot invoke LLM");})){
                var claim=PersonalDirectoryContextTest.claim(directories.home(),"7");
                // 使用中心claim中冻结的任务形状，走task.payload回退分支；不另造taskPayload操作名。
                claim.putObject("task").put("kind","CONTROL").putObject("payload")
                    .put("operation","DIR_PREPARE").putObject("arguments").put("sessionId",2);
                var lease=new RunnerLease(claim);
                var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),directories.home());
                ObjectNode context=CanonicalJson.MAPPER.createObjectNode();
                String result=controls.execute(lease,tools,context);assertTrue(result.contains("VERIFIED"));assertEquals(1,proofs.size());assertEquals(1,events.size());
                assertEquals(directories.home().resolve("conversations/2").toRealPath().toString(),proofs.get(0).path("canonicalPath").asText());
                assertFalse(proofs.get(0).has("ownerUserId"));assertEquals("2",events.get(0).path("payload").path("sessionId").asText());
                assertEquals("DIRECTORY_RESULT",events.get(0).path("type").asText());
                assertEquals("DIR_PREPARE",events.get(0).path("payload").path("operation").asText());
            }
        }
    }
    @Test void approvedDirectoryShowsActualRunnerEntriesWithPermitReceiptAndNoModel() throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")), state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        Files.writeString(root.resolve("实际项目.java"),"class Actual {}");Files.createDirectory(root.resolve("source"));
        List<JsonNode> events=new ArrayList<>(), receipts=new ArrayList<>();
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals")){assertEquals("control__REPOSITORY_LIST",body.path("toolName").asText());body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());}
                if(request.getPath().endsWith("/consume"))body.put("consumed",true);
                if(request.getPath().endsWith("/events"))events.add(body.deepCopy());
                if(request.getPath().endsWith("/receipts")){receipts.add(body.deepCopy());body.put("accepted",true).put("acknowledged",true).put("archived",false);}
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            } catch(IOException e){throw new UncheckedIOException(e);}}});server.start();
            var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("directoryContextId","7").put("leaseId","3").put("fencingToken",4).put("version",5).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(90).toString());
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);var controls=new NativeControlExecutor(center,state,context->{throw new AssertionError("Directory read cannot invoke a provider");})) {
                var lease=new RunnerLease(claim);var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);
                var context=CanonicalJson.MAPPER.createObjectNode();context.putObject("taskPayload").put("operation","REPOSITORY_LIST").putObject("arguments").put("path",".");
                assertTrue(controls.execute(lease,tools,context).contains("实际项目.java"));
                assertEquals(1,events.size());assertEquals("DIRECTORY_RESULT",events.get(0).path("type").asText());
                JsonNode listing=events.get(0).path("payload");assertEquals("RUNNER",listing.path("source").asText());
                assertEquals(2,listing.path("entries").size());assertEquals(1,receipts.size());
                assertTrue(listing.path("entries").toString().contains("DIRECTORY"));assertTrue(listing.path("entries").toString().contains("FILE"));
            }
        }
    }
    @Test void directoryMetadataUsesSamePathGuardAndDoesNotTraverseOutsideWorkspace() throws Exception {
        Path root=Files.createDirectory(container.resolve("root"));Files.writeString(container.resolve("outside.txt"),"outside");
        var tools=new ToolRegistry();tools.setProjectPath(root.toString());
        assertThrows(com.paicli.policy.PolicyException.class,()->tools.listDirectoryEntries(".."));
        assertTrue(tools.listDirectoryEntries(".").isEmpty());
    }
}
