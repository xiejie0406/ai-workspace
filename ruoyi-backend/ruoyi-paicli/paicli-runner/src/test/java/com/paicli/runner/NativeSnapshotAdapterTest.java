package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.snapshot.*;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native消费冻结上下文的独立fixture；两阶段许可本身由RunnerBoundary覆盖。 */
class NativeSnapshotAdapterTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class)Path container;
    @Test void previewAndFrozenRestoreUseActualCommitAndKeepFullResultBeforeCliSummary()throws Exception{run(false,false);}
    @Test void changeAfterFrozenPreviewCannotOverwriteLaterUserFile()throws Exception{run(true,false);}
    @Test void completedRestoreEvidenceIsSavedEvenWhenLeaseBecomesInvalidDuringRestore()throws Exception{run(false,true);}
    @Test void largeDiffUsesCompleteBareArtifactAndKeepsSixFieldProofInReceipt()throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("large-workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("large-state"));
        Files.writeString(root.resolve("file.txt"),"a".repeat(40000)+"\n");
        var snapshots=new SideGitManager(root,new SnapshotConfig(true,state.resolve("snapshots"),50,List.of(".git")));var target=snapshots.preTurnSnapshot("before","original");Files.writeString(root.resolve("file.txt"),"b".repeat(40000)+"\n");
        var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("workspaceId","3").put("leaseId","4").put("fencingToken",5).put("version",6).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(120).toString());var lease=new RunnerLease(claim);
        try(var server=new MockWebServer()) {server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
            assertEquals("/paicli/runner/v1/tool-outputs",request.getPath());var input=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());byte[] content=input.path("content").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8);var full=CanonicalJson.MAPPER.readTree(content);
            assertFalse(full.has("payload"));assertEquals("snapshot__preview",input.path("toolName").asText());assertTrue(full.path("restorePreview").path("files").get(0).path("diff").asText().length()>64000);
            return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",CanonicalJson.MAPPER.createObjectNode().put("artifactId","11").put("sha256",CanonicalJson.sha256(content)).put("size",content.length)).toString());
        }catch(IOException error){throw new UncheckedIOException(error);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)) {
                var tools=new BindingRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);tools.setToolResultOffloader(new HttpToolResultOffloader(center,lease,tools,state.resolve("tool-outputs")));
                var arguments=CanonicalJson.MAPPER.createObjectNode().put("snapshotMetadataId","9").put("snapshotId",target.commitId()).put("snapshotVersion",10);tools.bind(lease,"snapshot__preview",arguments);
                var output=new NativeSnapshotAdapter(center,lease,tools,snapshots).preview(arguments.toString());assertTrue(output.successful(),output.text());var body=CanonicalJson.MAPPER.readTree(output.text());assertEquals("11",body.path("previewArtifactId").asText());assertTrue(body.path("previewArtifactSha256").asText().matches("[a-f0-9]{64}"));
                assertEquals(6,body.path("restorePreview").path("files").get(0).size());assertFalse(body.path("restorePreview").path("files").get(0).has("diff"));assertEquals(1,tools.preserved.size());assertTrue(tools.preserved.get(0).text().contains("--- current/file.txt"));
            }
        }
    }
    private void run(boolean changed,boolean expiresAfterExecution)throws Exception {
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));Files.writeString(root.resolve("file.txt"),"original\n");
        var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("workspaceId","3").put("leaseId","4").put("fencingToken",5).put("version",6).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(120).toString());var lease=new RunnerLease(claim);
        SideGitManager snapshots=new SideGitManager(root,new SnapshotConfig(true,state.resolve("snapshots"),50,List.of(".git"))) {
            @Override public synchronized RestoreResult restoreSnapshot(String id)throws IOException,org.eclipse.jgit.api.errors.GitAPIException {var result=super.restoreSnapshot(id);if(expiresAfterExecution)lease.invalidate();return result;}
        };
        var target=snapshots.preTurnSnapshot("before","original");Files.writeString(root.resolve("file.txt"),"current\n");Files.writeString(root.resolve("extra.txt"),"remove\n");
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
                assertEquals("/paicli/runner/v1/revert-context",request.getPath());ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                body.put("sessionId",lease.claim().path("sessionId").asText()).put("workspaceId",lease.claim().path("workspaceId").asText());
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            }catch(IOException error){throw new UncheckedIOException(error);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)) {
                var tools=new BindingRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);var adapter=new NativeSnapshotAdapter(center,lease,tools,snapshots);adapter.install();
                var previewArgs=CanonicalJson.MAPPER.createObjectNode().put("snapshotMetadataId","9").put("snapshotId",target.commitId()).put("snapshotVersion",10);tools.bind(lease,"snapshot__preview",previewArgs);
                var previewOutput=tools.executeSnapshotPreview(previewArgs.toString());assertTrue(previewOutput.successful());var preview=CanonicalJson.MAPPER.readTree(previewOutput.text()).path("restorePreview");
                assertTrue(preview.path("canRestore").asBoolean());assertEquals(2,preview.path("files").size());assertTrue(preview.path("files").get(1).path("currentMode").isIntegralNumber());assertEquals(17,tools.getToolDefinitions().size());
                var frozen=previewArgs.deepCopy().put("offset",1).put("previewToolCallId","confirmed-preview").put("currentFingerprint",preview.path("currentFingerprint").asText());frozen.set("restorePreview",preview);tools.bind(lease,"revert_turn",frozen);
                if(changed){Files.writeString(root.resolve("file.txt"),"later user\n");assertThrows(IOException.class,()->adapter.restore(1));assertEquals("later user\n",Files.readString(root.resolve("file.txt")));assertTrue(Files.exists(root.resolve("extra.txt")));assertTrue(tools.preserved.isEmpty());}
                else{assertTrue(adapter.restore(1).success());assertEquals("original\n",Files.readString(root.resolve("file.txt")));assertFalse(Files.exists(root.resolve("extra.txt")));assertEquals(1,tools.preserved.size());var result=CanonicalJson.MAPPER.readTree(tools.preserved.get(0).text());assertEquals(target.commitId(),result.path("commitId").asText());assertTrue(result.toString().contains("extra.txt"));if(expiresAfterExecution)assertThrows(IOException.class,lease::ensureActive);}
                tools.bind(lease,"revert_turn",frozen.deepCopy().put("snapshotId","a".repeat(40)));tools.binding.put("argsHash",CanonicalJson.hash(frozen));assertThrows(IOException.class,()->adapter.restore(1));
            }
        }
    }
    private static final class BindingRegistry extends CenterToolRegistry {
        ObjectNode binding;final List<ToolOutput> preserved=new ArrayList<>();
        BindingRegistry(CenterHttpClient center,RunnerLease lease,ReceiptSpool spool,Path root)throws IOException{super(center,lease,spool,root);setCentralStateMode(true);}
        void bind(RunnerLease lease,String name,ObjectNode arguments){binding=lease.identity();binding.put("toolName",name).put("toolCallId","fixture").put("permitToken","fixture").put("argsHash",CanonicalJson.hash(arguments));binding.set("arguments",arguments);}
        @Override public ObjectNode currentExecutionBinding(){return binding.deepCopy();}
        @Override public void preserveCurrentOutput(ToolOutput output){preserved.add(output);}
    }
}
