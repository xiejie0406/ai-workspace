package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.snapshot.SideGitManager;
import com.paicli.snapshot.SnapshotConfig;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Registry/Native/SideGit组合，假中心只负责许可和正式冻结上下文。 */
class NativeSnapshotFlowTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    @Test void actualPreviewReceiptAndFrozenContextRestoreActualFilesWithoutExposingAnExtraModelTool()throws Exception {
        run(false,false);
    }
    @Test void safeLinkRestoreUsesTheSameFormalPreviewPermitAndReceiptFlow() throws Exception {
        run(true,false);
    }
    @Test void changedLinkTargetWhileApprovingIsRejectedBeforeRestorePermitConsumption() throws Exception {
        run(true,true);
    }
    private void run(boolean withLink,boolean changeLinkWhileApproving)throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        Files.writeString(root.resolve("a.txt"),"snapshot original\n");
        if(withLink) {
            try {Files.createSymbolicLink(root.resolve("link"),Path.of("a.txt"));}
            catch(UnsupportedOperationException|FileSystemException unsupported) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false,"当前宿主无法建立真实符号链接："+unsupported.getClass().getSimpleName()+": "+unsupported.getMessage());
            }
        }
        var snapshots=new SideGitManager(root,new SnapshotConfig(true,state.resolve("snapshots"),50,List.of(".git")));var target=snapshots.preTurnSnapshot("before","original");
        Files.writeString(root.resolve("a.txt"),"later current\n");Files.writeString(root.resolve("extra.txt"),"created later\n");
        if(withLink) {Files.delete(root.resolve("link"));Files.createSymbolicLink(root.resolve("link"),Path.of("extra.txt"));}
        var resources=new ResourceFingerprint(root);
        ObjectNode claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("directoryContextId","3").put("leaseId","4").put("fencingToken",5).put("version",6).put("policyVersion",1).put("rootFingerprint",resources.rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(120).toString());
        var lease=new RunnerLease(claim);var failure=new AtomicReference<Throwable>();var approved=new AtomicReference<JsonNode>();var previewBody=new AtomicReference<JsonNode>();List<String> receipts=new ArrayList<>(),consumed=new ArrayList<>();
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try {
                ObjectNode input=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());String path=request.getPath(),id=input.path("toolCallId").asText();
                if(path.endsWith("/proposals")) {
                    String name=input.path("toolName").asText();JsonNode arguments=input.path("arguments");assertEquals(CanonicalJson.hash(arguments),input.path("argsHash").asText());
                    if(name.equals("revert_turn")&&!arguments.has("previewToolCallId")) {
                        assertTrue(consumed.isEmpty());ObjectNode response=CanonicalJson.MAPPER.createObjectNode().put("decision","PREVIEW_REQUIRED").put("toolCallId",id).put("originalArgsHash",input.path("argsHash").asText()).put("version",6).put("previewToolName","snapshot__preview");
                        response.putObject("snapshotContext").put("snapshotMetadataId","9").put("snapshotId",target.commitId()).put("snapshotVersion",10);return ok(response);
                    }
                    if(name.equals("snapshot__preview")){assertEquals(3,arguments.size());assertTrue(receipts.isEmpty());}
                    else {assertEquals("revert_turn",name);assertEquals(1,receipts.size());assertEquals(previewBody.get().path("restorePreview"),arguments.path("restorePreview"));assertEquals(receipts.get(0),arguments.path("previewToolCallId").asText());approved.set(arguments.deepCopy());
                        if(changeLinkWhileApproving) {Files.delete(root.resolve("link"));Files.createSymbolicLink(root.resolve("link"),Path.of("a.txt"));}
                    }
                    return ok(input.deepCopy().put("decision","PERMITTED").put("permitToken","permit-"+id).put("expiresAt",Instant.now().plusSeconds(120).toString()));
                }
                if(path.endsWith("/permits/consume")){assertFalse(input.has("arguments"));consumed.add(id);return ok(input.deepCopy().put("consumed",true));}
                if(path.endsWith("/revert-context")){assertEquals(approved.get(),input.path("arguments"));assertEquals(1,receipts.size());input.put("sessionId","2").put("directoryContextId","3");return ok(input);}
                assertTrue(path.endsWith("/receipts"));JsonNode result=input.path("result");assertTrue(result.path("successful").asBoolean());
                if(receipts.isEmpty()){var body=CanonicalJson.MAPPER.readTree(result.path("text").asText());assertEquals(target.commitId(),body.path("commitId").asText());assertEquals(withLink?3:2,body.path("restorePreview").path("files").size());assertTrue(body.path("restorePreview").path("canRestore").asBoolean());previewBody.set(body);}
                else {assertEquals("snapshot original\n",Files.readString(root.resolve("a.txt")));assertFalse(Files.exists(root.resolve("extra.txt")));if(withLink){assertTrue(Files.isSymbolicLink(root.resolve("link")));assertEquals(Path.of("a.txt"),Files.readSymbolicLink(root.resolve("link")));}}
                receipts.add(id);var ack=input.deepCopy();ack.remove("permitToken");return ok(ack.put("accepted",true).put("acknowledged",true).put("archived",false));
            }catch(Throwable invalid){failure.compareAndSet(null,invalid);return new MockResponse().setResponseCode(500);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)) {
                var spool=new ReceiptSpool(state.resolve("receipts"));var tools=new CenterToolRegistry(center,lease,spool,root);tools.setCentralStateMode(true);new NativeSnapshotAdapter(center,lease,tools,snapshots).install();
                var output=tools.executeToolOutput("revert_turn","{\"offset\":1}");assertNull(failure.get());
                if(changeLinkWhileApproving){assertFalse(output.successful());assertEquals("later current\n",Files.readString(root.resolve("a.txt")));assertTrue(Files.exists(root.resolve("extra.txt")));assertEquals(1,consumed.size());}
                else {assertTrue(output.successful(),output.text());assertEquals("snapshot original\n",Files.readString(root.resolve("a.txt")));assertFalse(Files.exists(root.resolve("extra.txt")));assertEquals(2,consumed.size());assertNotEquals(consumed.get(0),consumed.get(1));}
                assertEquals(consumed,receipts);assertTrue(spool.pending().isEmpty());assertEquals(17,tools.getToolDefinitions().size());
            }
        }
    }
    private static MockResponse ok(JsonNode value){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+value+"}");}
}
