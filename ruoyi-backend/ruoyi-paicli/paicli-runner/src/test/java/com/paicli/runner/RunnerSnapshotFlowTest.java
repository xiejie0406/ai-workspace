package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 实际HTTP提案/消费/回执顺序：初始模型offset不能直接取得恢复执行权。 */
class RunnerSnapshotFlowTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    private static final String COMMIT="a".repeat(40);

    @Test void completePreviewReceiptPrecedesFreshRestoreCallAndRetainsFullJson() throws Exception {
        try (Fixture fixture=new Fixture(false,false)) {
            ToolOutput output=fixture.tools.invoke();
            assertTrue(output.successful(),output.text());assertNull(fixture.failure.get());
            assertEquals("恢复后的原文",Files.readString(fixture.root.resolve("a.txt")));
            assertEquals(1,fixture.restores.get());assertEquals(2,fixture.consumed.size());assertEquals(2,fixture.receipts.size());
            assertNotEquals(fixture.originalId,fixture.previewId);assertNotEquals(fixture.originalId,fixture.finalId);assertNotEquals(fixture.previewId,fixture.finalId);
            assertEquals(List.of(fixture.previewId,fixture.finalId),fixture.consumed);
            assertTrue(fixture.spool.pending().isEmpty());
            assertEquals("revert_turn",fixture.executedBinding.path("toolName").asText());
            assertEquals(CanonicalJson.hash(fixture.executedBinding.path("arguments")),fixture.executedBinding.path("argsHash").asText());
        }
    }

    @Test void unconfirmedPreviewReceiptNeverProposesOrExecutesRestore() throws Exception {
        try (Fixture fixture=new Fixture(true,false)) {
            ToolOutput output=fixture.tools.invoke();
            assertFalse(output.successful());assertNull(fixture.failure.get());
            assertEquals("后来编辑的正文",Files.readString(fixture.root.resolve("a.txt")));
            assertEquals(0,fixture.restores.get());assertNull(fixture.finalId);assertEquals(1,fixture.consumed.size());
            assertEquals(1,fixture.spool.pending().size());assertThrows(IOException.class,fixture.lease::ensureActive);
        }
    }

    @Test void changedTreeAfterConfirmedPreviewCannotObtainFinalRestorePermit() throws Exception {
        try (Fixture fixture=new Fixture(false,true)) {
            ToolOutput output=fixture.tools.invoke();
            assertFalse(output.successful());assertTrue(output.text().contains("预览后工作区已改变"),output.text());
            assertNull(fixture.failure.get());assertEquals("预览后新增的正文",Files.readString(fixture.root.resolve("a.txt")));
            assertEquals(0,fixture.restores.get());assertNull(fixture.finalId);assertEquals(1,fixture.consumed.size());
            assertTrue(fixture.spool.pending().isEmpty());assertDoesNotThrow(fixture.lease::ensureActive);
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path root=Files.createDirectory(temp.resolve("root"));
        final MockWebServer server=new MockWebServer();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger restores=new AtomicInteger();
        final List<String> consumed=new CopyOnWriteArrayList<>();
        final List<String> receipts=new CopyOnWriteArrayList<>();
        final ReceiptSpool spool=new ReceiptSpool(temp.resolve("receipts"));
        final RunnerLease lease;
        final CenterHttpClient client;
        final TestRegistry tools;
        final String current;
        volatile String originalId,previewId,finalId;
        volatile ObjectNode executedBinding;

        Fixture(boolean badAcknowledgement,boolean changeAfterPreview) throws Exception {
            Files.writeString(root.resolve("a.txt"),"后来编辑的正文");
            ResourceFingerprint resources=new ResourceFingerprint(root);
            current=resources.capture("revert_turn",CanonicalJson.MAPPER.createObjectNode());
            ObjectNode claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("workspaceId","7")
                    .put("leaseId","3").put("fencingToken",8).put("version",4).put("policyVersion",1)
                    .put("rootFingerprint",resources.rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(120).toString());
            lease=new RunnerLease(claim);
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        JsonNode input=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                        String path=request.getPath(),id=input.path("toolCallId").asText();
                        assertEquals(CanonicalJson.sha256("original-model-call".getBytes(java.nio.charset.StandardCharsets.UTF_8)),input.path("modelToolCallIdHash").asText());
                        if(path.endsWith("/proposals")) {
                            assertEquals(CanonicalJson.hash(input.path("arguments")),input.path("argsHash").asText());
                            if("snapshot__preview".equals(input.path("toolName").asText())) {
                                previewId=id;assertNotEquals(originalId,id);
                                assertEquals(3,input.path("arguments").size());assertEquals(COMMIT,input.path("arguments").path("snapshotId").asText());
                            } else if(!input.path("arguments").has("previewToolCallId")) {
                                originalId=id;assertEquals(1,input.path("arguments").size());
                                ObjectNode response=CanonicalJson.MAPPER.createObjectNode().put("toolCallId",id).put("decision","PREVIEW_REQUIRED")
                                        .put("version",4).put("previewToolName","snapshot__preview").put("originalArgsHash",input.path("argsHash").asText());
                                response.putObject("snapshotContext").put("snapshotMetadataId","9").put("snapshotId",COMMIT).put("commitId",COMMIT).put("snapshotVersion",10);
                                return ok(response);
                            } else {
                                finalId=id;assertEquals(List.of(previewId),receipts);assertNotEquals(previewId,id);assertNotEquals(originalId,id);
                                assertEquals(previewId,input.path("arguments").path("previewToolCallId").asText());
                                assertEquals(current,input.path("resourceHash").asText());assertEquals(COMMIT,input.path("arguments").path("snapshotId").asText());
                            }
                            ObjectNode permit=input.deepCopy();permit.put("decision","PERMITTED").put("permitToken","permit-"+id).put("expiresAt",Instant.now().plusSeconds(120).toString());return ok(permit);
                        }
                        if(path.endsWith("/permits/consume")) {
                            assertFalse(input.has("arguments"));assertFalse(input.has("toolName"));assertNotEquals(originalId,id);
                            consumed.add(id);ObjectNode response=input.deepCopy();response.put("consumed",true);return ok(response);
                        }
                        assertTrue(path.endsWith("/receipts"));receipts.add(id);
                        if(id.equals(previewId)) {
                            JsonNode body=CanonicalJson.MAPPER.readTree(input.path("result").path("text").asText());
                            assertEquals(35000,body.path("restorePreview").path("files").get(0).path("diff").asText().length());
                            assertEquals(COMMIT,body.path("commitId").asText());
                            if(changeAfterPreview)Files.writeString(root.resolve("a.txt"),"预览后新增的正文");
                        }
                        ObjectNode acknowledgement=input.deepCopy();acknowledgement.remove("permitToken");
                        acknowledgement.put("accepted",true).put("acknowledged",true).put("archived",false);
                        if(badAcknowledgement)acknowledgement.put("toolCallId","wrong-call");return ok(acknowledgement);
                    } catch(Throwable invalid) {failure.compareAndSet(null,invalid);return new MockResponse().setResponseCode(500);}
                }
            });
            server.start();client=new CenterHttpClient(server.url("/").toString(),"test-session",true);tools=new TestRegistry(this);
            tools.setSnapshotPreviewer(argumentsJson->{
                try {
                    JsonNode args=CanonicalJson.parseObject(argumentsJson),binding=tools.currentExecutionBinding();
                    assertEquals(args,binding.path("arguments"));assertEquals("snapshot__preview",binding.path("toolName").asText());
                    ObjectNode body=CanonicalJson.MAPPER.createObjectNode().put("commitId",COMMIT).put("snapshotMetadataId","9").put("snapshotVersion",10).put("currentFingerprint",current);
                    ObjectNode proof=body.putObject("restorePreview").put("snapshotId",COMMIT).put("currentFingerprint",current).put("targetManifestHash","b".repeat(64)).put("canRestore",true);
                    proof.putArray("conflicts");
                    proof.putArray("files").addObject().put("path","a.txt").put("status","MODIFY").put("currentHash","c".repeat(64)).put("targetHash","d".repeat(64)).put("currentMode",33188).put("targetMode",33188).put("diff","x".repeat(35000));
                    return ToolOutput.text(body.toString());
                } catch(IOException invalid) {throw new java.io.UncheckedIOException(invalid);}
            });
        }
        @Override public void close() throws Exception {client.close();server.close();}
    }

    private static final class TestRegistry extends CenterToolRegistry {
        final Fixture fixture;
        TestRegistry(Fixture fixture) throws IOException {super(fixture.client,fixture.lease,fixture.spool,fixture.root);this.fixture=fixture;}
        ToolOutput invoke() {return executeInvocation(new ToolInvocation("original-model-call","revert_turn","{\"offset\":1}"));}
        @Override protected ToolOutput executeAuthorized(String name,String arguments) {
            if(!"revert_turn".equals(name))return super.executeAuthorized(name,arguments);
            try {
                assertEquals(List.of(fixture.previewId),fixture.receipts);
                fixture.executedBinding=currentExecutionBinding();assertEquals(CanonicalJson.parseObject(arguments),fixture.executedBinding.path("arguments"));
                fixture.restores.incrementAndGet();Files.writeString(fixture.root.resolve("a.txt"),"恢复后的原文");return ToolOutput.text("实际恢复已完成");
            } catch(IOException invalid) {throw new java.io.UncheckedIOException(invalid);}
        }
    }
    private static MockResponse ok(JsonNode data) {return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+data+"}");}
}
