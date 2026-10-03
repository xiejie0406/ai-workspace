package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeLargeControlEventTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    @Test void twelveMbControlResultUsesChunksAndSmallEventReferenceWithoutLosingOriginalContent() throws Exception { run(false); }
    @Test void mismatchedChunkAckKeepsCompletePrivateEvidenceAndNeverSendsEventOrReceipt() throws Exception { run(true); }
    @Test void largeCleanPreviewKeepsEveryFrozenFieldAlongsideCompleteArtifact()throws Exception {run(false,true);}
    @Test void largeSingleFileDiffIsPreservedAsArtifactWhileCompleteNumericProofRemainsInEvent() throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("diff-workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("diff-state"));
        String diff="中".repeat(23000);ObjectNode[] uploaded=new ObjectNode[1],observed=new ObjectNode[1];
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());String path=request.getPath();
                if(path.endsWith("/proposals")){body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());return ok(body);}
                if(path.endsWith("/consume")){body.put("consumed",true);return ok(body);}
                if(path.endsWith("/tool-outputs")){uploaded[0]=body.deepCopy();return ok(CanonicalJson.MAPPER.createObjectNode().put("artifactId","99").put("sha256",body.path("sha256").asText()).put("size",body.path("content").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length));}
                if(path.endsWith("/events")){observed[0]=body.deepCopy();return ok(CanonicalJson.MAPPER.createObjectNode());}
                if(path.endsWith("/receipts")){body.put("accepted",true).put("acknowledged",true).put("archived",false);return ok(body);}
                return ok(body);
            }catch(IOException failed){throw new UncheckedIOException(failed);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)) {
                var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","10").put("workspaceId","7").put("leaseId","2").put("fencingToken",3).put("version",4).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(90).toString());
                var lease=new RunnerLease(claim);var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);tools.setToolResultOffloader(new HttpToolResultOffloader(center,lease,tools,state.resolve("tool-outputs")));
                var payload=CanonicalJson.MAPPER.createObjectNode().put("snapshotMetadataId","5").put("snapshotId","a".repeat(40));var proof=payload.putObject("preview");proof.put("currentFingerprint","b".repeat(64)).put("canRestore",true).put("targetManifestHash","c".repeat(64));proof.putArray("conflicts");
                proof.putArray("files").addObject().put("path","actual.txt").put("status","MODIFY").put("currentHash","d".repeat(64)).put("targetHash","e".repeat(64)).put("currentMode",33188).put("targetMode",33188).put("diff",diff);
                assertTrue(tools.executeControl("SNAPSHOT_PREVIEW","{}",()->{try{NativeControlExecutor.sendControlEvent(center,lease,tools,"SNAPSHOT",payload);return ToolOutput.text("complete");}catch(IOException e){throw new UncheckedIOException(e);}}).successful());
                JsonNode actual=CanonicalJson.MAPPER.readTree(uploaded[0].path("content").asText());assertEquals(diff,actual.path("payload").path("preview").path("files").get(0).path("diff").asText());
                JsonNode result=observed[0].path("payload");assertEquals("99",result.path("eventArtifactId").asText());assertTrue(result.path("preview").path("canRestore").asBoolean());assertEquals("b".repeat(64),result.path("preview").path("currentFingerprint").asText());
                JsonNode file=result.path("preview").path("files").get(0);assertFalse(file.has("diff"));assertEquals(33188,file.path("currentMode").asInt());assertEquals("e".repeat(64),file.path("targetHash").asText());
            }
        }
    }
    private void run(boolean badAck) throws Exception {
        run(badAck,false);
    }
    private void run(boolean badAck,boolean cleanPreview) throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")); Path state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        String content="完整实际结果:"+"中".repeat(4*1024*1024); var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","10").put("workspaceId","7").put("leaseId","2").put("fencingToken",3).put("version",4).put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(120).toString());
        claim.put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint());
        AtomicInteger events=new AtomicInteger(),receipts=new AtomicInteger(),chunks=new AtomicInteger(); ByteArrayOutputStream actual=new ByteArrayOutputStream(); ObjectNode[] begin=new ObjectNode[1];
        try(MockWebServer server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){ public MockResponse dispatch(RecordedRequest request) {try {
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()); String path=request.getPath();
                if(path.endsWith("/proposals")){body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());return ok(body);}
                if(path.endsWith("/consume")){body.put("consumed",true);return ok(body);}
                if(path.endsWith("/chunks/begin")){begin[0]=body.deepCopy();return ok(CanonicalJson.MAPPER.createObjectNode().put("uploadId","55").put("sha256",body.path("sha256").asText()).put("size",body.path("size").asLong()));}
                if(path.endsWith("/chunks")){chunks.incrementAndGet();byte[] chunk=java.util.Base64.getDecoder().decode(body.path("base64").asText());actual.write(chunk);return ok(CanonicalJson.MAPPER.createObjectNode().put("index",body.path("index").asInt()).put("size",chunk.length).put("sha256",badAck?"c".repeat(64):body.path("sha256").asText()));}
                if(path.endsWith("/complete"))return ok(CanonicalJson.MAPPER.createObjectNode().put("artifactId","99").put("sha256",begin[0].path("sha256").asText()).put("size",actual.size()));
                if(path.endsWith("/events")){events.incrementAndGet();assertEquals("99",body.path("payload").path("eventArtifactId").asText());
                    if(cleanPreview){JsonNode original=CanonicalJson.MAPPER.readTree(actual.toByteArray()).path("payload");for(String field:java.util.List.of("head","refs","commits","knownTargets","unregistered","repositoryManifestHash","fileCount","totalBytes","previewVersion","range"))assertEquals(original.path(field),body.path("payload").path(field),field);}
                    else {assertTrue(request.getBodySize()<10000);for(String field:java.util.List.of("tools","resources","prompts")) {assertFalse(body.path("payload").has(field));assertTrue(body.path("payload").path("catalogueDigests").path(field).path("sha256").asText().matches("[a-f0-9]{64}"));}}
                    return ok(CanonicalJson.MAPPER.createObjectNode());}
                if(path.endsWith("/receipts")){receipts.incrementAndGet();body.put("accepted",true).put("acknowledged",true).put("archived",false);return ok(body);}
                return ok(CanonicalJson.MAPPER.createObjectNode());
            }catch(IOException error){throw new UncheckedIOException(error);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)) {
                RunnerLease lease=new RunnerLease(claim); var spool=new ReceiptSpool(state.resolve("receipts")); var tools=new CenterToolRegistry(center,lease,spool,root);
                tools.setToolResultOffloader(new HttpToolResultOffloader(center,lease,tools,state.resolve("tool-outputs")));
                var payload=CanonicalJson.MAPPER.createObjectNode().put("kind","MCP").put("configId","5").put("configVersion","6").put("large",content);
                payload.putArray("tools").addObject().put("name","actual_tool").putObject("inputSchema").put("type","object");
                payload.putArray("resources").addObject().put("uri","fixture://actual").put("name","actual resource");payload.putArray("prompts").add("actual prompt");
                if(cleanPreview){payload.remove(java.util.List.of("large","tools","resources","prompts"));payload.put("operation","CLEAN_PREVIEW").put("range","current").put("head","a".repeat(40)).put("repositoryManifestHash","b".repeat(64)).put("fileCount",25000).put("totalBytes",123456).put("previewVersion",1);payload.putArray("commits").add("a".repeat(40));payload.putArray("knownTargets");payload.putArray("unregistered").add("a".repeat(40));var refs=payload.putArray("refs");for(int n=0;n<25000;n++)refs.addObject().put("name","refs/heads/"+"a".repeat(160)+n).put("objectId","a".repeat(40));}
                String operation=cleanPreview?"SNAPSHOT_CLEAN_PREVIEW":"MCP_START",eventType=cleanPreview?"SNAPSHOT":"CONNECTION_RESULT";
                if(badAck) {
                    assertFalse(tools.executeControl("MCP_START","{}",()->{try{NativeControlExecutor.sendControlEvent(center,lease,tools,"CONNECTION_RESULT",payload);return ToolOutput.text("complete");}catch(IOException e){throw new UncheckedIOException(e);}}).successful());
                    assertThrows(IOException.class,lease::ensureActive);
                    assertEquals(0,events.get());assertEquals(0,receipts.get());assertTrue(spool.hasUncertainExecution("1"));
                    try(var files=Files.list(state.resolve("pending-derived-images"))){assertTrue(files.anyMatch(path->{try{return Files.readString(path).contains("完整实际结果");}catch(IOException e){throw new UncheckedIOException(e);}}));}
                } else {
                    assertTrue(tools.executeControl(operation,"{}",()->{try{NativeControlExecutor.sendControlEvent(center,lease,tools,eventType,payload);return ToolOutput.text("complete");}catch(IOException e){throw new UncheckedIOException(e);}}).successful());
                    assertTrue(chunks.get()>=(cleanPreview?2:4));assertEquals(1,events.get());assertEquals(1,receipts.get());
                    JsonNode stored=CanonicalJson.MAPPER.readTree(actual.toByteArray());assertEquals(payload,stored.path("payload"));assertEquals(begin[0].path("sha256").asText(),CanonicalJson.sha256(actual.toByteArray()));
                }
            }
        }
    }
    private static MockResponse ok(JsonNode data){return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",data).toString());}
}
