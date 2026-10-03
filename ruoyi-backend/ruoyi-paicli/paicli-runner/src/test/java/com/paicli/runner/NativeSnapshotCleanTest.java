package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.snapshot.*;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 假中心许可与真实私有SideGit归档组合，不调用任何外部模型。 */
class NativeSnapshotCleanTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;
    @AfterEach void writableFixtureObjects()throws IOException {
        try(var walk=Files.walk(container)){for(Path node:walk.filter(Files::isRegularFile).toList()){var view=Files.getFileAttributeView(node,java.nio.file.attribute.DosFileAttributeView.class);if(view!=null)view.setReadOnly(false);}}
    }
    @Test void approvedCompletePreviewArchivesKnownAndUnknownCacheWithoutDeletingWorktree()throws Exception {
        try(var fixture=new Fixture()) {
            var proof=fixture.preview();assertEquals("CLEAN_PREVIEW",proof.path("operation").asText());assertEquals(2,proof.path("commits").size());assertEquals(1,proof.path("unregistered").size());assertEquals(fixture.unknown,proof.path("unregistered").get(0).asText());
            fixture.run("SNAPSHOT_CLEAN",fixture.arguments(proof));JsonNode result=fixture.events.get(1).path("payload");assertEquals("CLEAN",result.path("operation").asText());
            JsonNode backup=result.path("backup");Path archive=fixture.state.resolve("snapshot-archives").resolve(backup.path("archiveId").asText());
            assertTrue(backup.path("restorable").asBoolean());assertEquals(proof.path("repositoryManifestHash"),backup.path("manifestHash"));assertEquals(CanonicalJson.sha256(Files.readAllBytes(archive.resolve("manifest.json"))),backup.path("sha256").asText());
            assertTrue(Files.isRegularFile(archive.resolve("repository/.git/config")));assertTrue(fixture.snapshots.listSnapshots(50).isEmpty());assertEquals("later",Files.readString(fixture.root.resolve("a.txt")));assertEquals(2,fixture.receipts);assertTrue(fixture.spool.pending().isEmpty());
        }
    }
    @Test void newCommitAfterApprovalRefusesCleanAndPreservesBothCacheAndFiles()throws Exception {
        try(var fixture=new Fixture()) {
            var proof=fixture.preview();Files.writeString(fixture.root.resolve("a.txt"),"newer");var latest=fixture.snapshots.postTurnSnapshot("3","after approval");
            assertThrows(IOException.class,()->fixture.run("SNAPSHOT_CLEAN",fixture.arguments(proof)));assertEquals(1,fixture.events.size());assertFalse(Files.exists(fixture.state.resolve("snapshot-archives")));assertEquals(latest.commitId(),fixture.snapshots.listSnapshots(1).get(0).commitId());assertEquals("newer",Files.readString(fixture.root.resolve("a.txt")));
        }
    }
    @Test void failedCompletionEventKeepsActualArchiveEvidenceAndDoesNotAcknowledgeUnknownCleanup()throws Exception {
        try(var fixture=new Fixture()) {
            var proof=fixture.preview();fixture.failEvents=true;assertThrows(Exception.class,()->fixture.run("SNAPSHOT_CLEAN",fixture.arguments(proof)));
            assertEquals(1,fixture.receipts);assertTrue(fixture.spool.hasUncertainExecution("1"));assertTrue(fixture.snapshots.listSnapshots(50).isEmpty());
            try(var walk=Files.list(fixture.state.resolve("pending-derived-images"))){assertTrue(walk.anyMatch(path->{try{String data=Files.readString(path);return data.contains("archiveId")&&data.contains("restorable")&&data.contains("CLEAN");}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}}));}
            try(var archives=Files.list(fixture.state.resolve("snapshot-archives"))){assertEquals(1,archives.filter(path->Files.isRegularFile(path.resolve("repository/.git/config"))).count());}
        }
    }
    @Test void allRangeAndOtherRunnerCannotAuthorizeAnyLocalCleanup()throws Exception {
        try(var fixture=new Fixture()) {
            var args=fixture.arguments(null);args.put("range","all");assertThrows(IOException.class,()->fixture.run("SNAPSHOT_CLEAN_PREVIEW",args));
            args.put("range","current").put("runnerId","another-runner");assertThrows(IOException.class,()->fixture.run("SNAPSHOT_CLEAN_PREVIEW",args));assertTrue(fixture.events.isEmpty());assertEquals(2,fixture.snapshots.listSnapshots(50).size());assertFalse(Files.exists(fixture.state.resolve("snapshot-archives")));
        }
    }
    private final class Fixture implements AutoCloseable {
        final Path root=RunnerPrivateDirectory.createDirectories(container.resolve("workspace")),state=RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        final SideGitManager snapshots=new SideGitManager(root,new SnapshotConfig(true,state.resolve("snapshots"),500,List.of(".git")));final String known,unknown;
        final MockWebServer server=new MockWebServer();final CenterHttpClient center;final ReceiptSpool spool;final NativeControlExecutor executor;
        final List<JsonNode> events=new ArrayList<>();int receipts;boolean failEvents;
        Fixture()throws Exception {
            Files.writeString(root.resolve("a.txt"),"original");known=snapshots.preTurnSnapshot("1","known").commitId();Files.writeString(root.resolve("a.txt"),"later");unknown=snapshots.postTurnSnapshot("2","not registered").commitId();
            server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest request){try{
                var body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());String path=request.getPath();
                if(path.endsWith("/proposals"))return ok(body.put("decision","PERMITTED").put("permitToken","fixture-permit").put("expiresAt",Instant.now().plusSeconds(120).toString()));
                if(path.endsWith("/consume"))return ok(body.put("consumed",true));
                if(path.endsWith("/events")){events.add(body.deepCopy());return failEvents?new MockResponse().setResponseCode(503):ok(CanonicalJson.MAPPER.createObjectNode());}
                if(path.endsWith("/receipts")){receipts++;return ok(body.put("accepted",true).put("acknowledged",true).put("archived",false));}
                return new MockResponse().setResponseCode(404);
            }catch(IOException error){throw new IllegalStateException(error);}}});server.start();center=new CenterHttpClient(server.url("/").toString(),"fixture",true);spool=new ReceiptSpool(state.resolve("receipts"));executor=new NativeControlExecutor(center,state,ignored->{throw new AssertionError("不能调用模型");});
        }
        ObjectNode arguments(JsonNode proof)throws IOException {
            var args=CanonicalJson.MAPPER.createObjectNode().put("range","current").put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("runnerId","7");
            args.putArray("targets").addObject().put("snapshotMetadataId","8").put("snapshotId",known).put("snapshotVersion",1);
            if(proof!=null){args.set("cleanPreview",proof);args.put("cleanPreviewRecordId","10");args.putObject("backupPolicy").put("mode","PRIVATE_ARCHIVE");}return args;
        }
        JsonNode preview()throws Exception {return CanonicalJson.MAPPER.readTree(run("SNAPSHOT_CLEAN_PREVIEW",arguments(null)));}
        String run(String operation,JsonNode args)throws Exception {
            var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("sessionId","2").put("workspaceId","3").put("runnerId","7").put("leaseId",UUID.randomUUID().toString()).put("fencingToken",5).put("version",6).put("policyVersion",1).put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint()).put("expiresAt",Instant.now().plusSeconds(120).toString());
            var lease=new RunnerLease(claim);var tools=new CenterToolRegistry(center,lease,spool,root);var context=CanonicalJson.MAPPER.createObjectNode();context.putObject("taskPayload").put("operation",operation).set("arguments",args);return executor.execute(lease,tools,context);
        }
        public void close()throws Exception{executor.close();center.close();server.close();}
    }
    private static MockResponse ok(JsonNode data){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+data+"}");}
}
