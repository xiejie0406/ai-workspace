package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.snapshot.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSnapshotProofTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class)Path container;
    @Test void realPreviewFreezesActualHashesAndRestoreAppliesOnlyMatchingApprovedProof()throws Exception{run(false);}
    @Test void changeAfterPreviewRefusesRestoreWithoutOverwritingLaterUserWork()throws Exception{run(true);}
    void run(boolean changed)throws Exception{
        Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        Files.writeString(root.resolve("actual.txt"),"original\n");var manager=new SideGitManager(root,new SnapshotConfig(true,state.resolve("snapshots"),500,List.of(".git",".paicli/snapshots","target","node_modules","dist",".idea","*.class","*.jar")));var snapshot=manager.preTurnSnapshot("test","original");Files.writeString(root.resolve("actual.txt"),"current\n");Files.writeString(root.resolve("remove.txt"),"extra\n");
        List<JsonNode> events=new ArrayList<>();try(var server=new MockWebServer()){
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try{
                ObjectNode body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals"))body.put("decision","PERMITTED").put("permitToken","fixture").put("expiresAt",Instant.now().plusSeconds(60).toString());
                if(request.getPath().endsWith("/consume"))body.put("consumed",true);
                if(request.getPath().endsWith("/events"))events.add(body.path("payload").deepCopy());
                if(request.getPath().endsWith("/receipts"))body.put("accepted",true).put("acknowledged",true).put("archived",false);
                return new MockResponse().setHeader("Content-Type","application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code",200).set("data",body).toString());
            }catch(IOException error){throw new UncheckedIOException(error);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);var controls=new NativeControlExecutor(center,state,context->{throw new AssertionError("Preview/restore is not a model task");})){
                var lease=new RunnerLease(claim(root,"1"));var tools=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),root);
                var args=CanonicalJson.MAPPER.createObjectNode().put("snapshotId",snapshot.commitId()).put("snapshotMetadataId","99");var context=CanonicalJson.MAPPER.createObjectNode();context.putObject("taskPayload").put("operation","SNAPSHOT_PREVIEW").set("arguments",args);
                assertTrue(controls.execute(lease,tools,context).contains("currentFingerprint"));JsonNode preview=events.get(0).path("preview");assertEquals(2,preview.path("files").size());assertTrue(preview.path("canRestore").asBoolean());assertTrue(preview.path("files").get(0).path("diff").asText().contains("+original"));
                assertEquals("current\n",Files.readString(root.resolve("actual.txt")));assertTrue(Files.exists(root.resolve("remove.txt")));
                args.put("currentFingerprint",preview.path("currentFingerprint").asText());args.putObject("snapshot").set("restorePreview",preview);
                if(changed)Files.writeString(root.resolve("actual.txt"),"later user change\n");
                var restoreLease=new RunnerLease(claim(root,"2"));var restoreTools=new CenterToolRegistry(center,restoreLease,new ReceiptSpool(state.resolve("receipts")),root);context.putObject("taskPayload").put("operation","SNAPSHOT_RESTORE").set("arguments",args);
                if(changed){assertThrows(IOException.class,()->controls.execute(restoreLease,restoreTools,context));assertEquals("later user change\n",Files.readString(root.resolve("actual.txt")));assertTrue(Files.exists(root.resolve("remove.txt")));assertEquals(1,events.size());}
                else{assertTrue(controls.execute(restoreLease,restoreTools,context).contains("已恢复"));assertEquals("original\n",Files.readString(root.resolve("actual.txt")));assertFalse(Files.exists(root.resolve("remove.txt")));assertEquals(args.path("currentFingerprint").asText(),events.get(1).path("currentFingerprint").asText());}
            }
        }
    }
    private ObjectNode claim(Path root,String id)throws IOException{return CanonicalJson.MAPPER.createObjectNode().put("taskId",id).put("sessionId","2").put("workspaceId","7").put("leaseId","3").put("fencingToken",4).put("version",5).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(90).toString());}
}
