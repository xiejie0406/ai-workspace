package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PersonalDirectoryContextTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;

    @Test void defaultDirectoryUsesStableOwnerAndRealLocalProof()throws Exception{
        var first=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        var again=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        var other=new PersonalDirectoryContext(temp.resolve("personal"),"8");
        assertEquals(temp.resolve("personal/users/7/files").toRealPath(),first.home());
        assertEquals(first.home(),again.home());assertNotEquals(first.home(),other.home());
        assertEquals(new ResourceFingerprint(first.home()).rootFingerprint(),first.proof().path("rootFingerprint").asText());
        assertFalse(first.proof().has("ownerUserId"));
        assertThrows(IOException.class,()->new PersonalDirectoryContext(temp.resolve("personal"),"../8"));
    }

    @Test void frozenDirectoryIsRealAndOwnedNotAnArbitraryBrowserPath()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Path project=RunnerPrivateDirectory.createDirectories(directories.home().resolve("project"));
        ObjectNode value=envelope(project,"7");assertEquals(project.toRealPath(),directories.resolve(value));
        ObjectNode foreign=value.deepCopy();foreign.put("ownerUserId","8");assertThrows(IOException.class,()->directories.resolve(foreign));
        ObjectNode changed=value.deepCopy();((ObjectNode)changed.path("directoryContext")).put("rootFingerprint","f".repeat(64));assertThrows(IOException.class,()->directories.resolve(changed));
        ObjectNode different=envelope(temp,"7");assertThrows(IOException.class,()->directories.resolve(different));
        assertThrows(IOException.class,()->directories.proof(directories.home().resolve("missing")));
    }

    @Test void everyDirectoryAncestorMustBeRealEvenWhenTheLeafIsNotASymlink()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Path target=RunnerPrivateDirectory.createDirectories(directories.home().resolve("target/child"));
        Path alias=directories.home().resolve("alias");
        try { Files.createSymbolicLink(alias,target.getParent()); }
        catch(IOException|UnsupportedOperationException unavailable){org.junit.jupiter.api.Assumptions.assumeTrue(false,"系统未提供创建符号链接权限");}
        assertThrows(IOException.class,()->directories.proof(alias.resolve("child")));
    }

    @Test void personalTerminalStartResolvesItsFrozenDirectoryAndRejectsMissingProofOrForeignOwner()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Path conversation=RunnerPrivateDirectory.createDirectories(directories.home().resolve("conversations/300"));
        String fingerprint=new ResourceFingerprint(conversation).rootFingerprint();
        // 对照中心START帧构造，不复用task/envelope帮助方法，避免遗漏帧自身的冻结字段。
        ObjectNode start=CanonicalJson.MAPPER.createObjectNode().put("type","START").put("terminalId","301")
            .put("hostEpoch",1).put("ownerId","7").put("ownerUserId","7").put("sessionId","300")
            .put("directoryContextId","11").put("runnerId","22").put("rootFingerprint",fingerprint)
            .put("startNonce","fixture-start-nonce").put("renderer","inline").put("cols",100).put("rows",30)
            .put("capabilityToken","fixture-short-capability").put("capabilityExpiresAt",Instant.now().plusSeconds(90).toString());
        start.putObject("directoryContext").put("id","11").put("ownerUserId","7").put("runnerId","22")
            .put("path",conversation.toRealPath().toString()).put("rootFingerprint",fingerprint).put("version",1).put("state","VERIFIED");
        // PtyProcessManager.start在启动任何PTY前调用的同一目录解析入口。
        assertEquals(conversation.toRealPath(),directories.resolve(start));
        ObjectNode missingFingerprint=start.deepCopy();missingFingerprint.remove("rootFingerprint");
        assertThrows(IOException.class,()->directories.resolve(missingFingerprint));
        ObjectNode changedFingerprint=start.deepCopy();changedFingerprint.put("rootFingerprint","f".repeat(64));
        assertThrows(IOException.class,()->directories.resolve(changedFingerprint));
        ObjectNode foreignOwner=start.deepCopy();foreignOwner.put("ownerUserId","8");
        assertThrows(IOException.class,()->directories.resolve(foreignOwner));
        ObjectNode foreignContext=start.deepCopy();((ObjectNode)foreignContext.path("directoryContext")).put("ownerUserId","8");
        assertThrows(IOException.class,()->directories.resolve(foreignContext));
    }

    @Test void samePhysicalDirectoryAndOverlappingChildrenCannotRunConcurrently()throws Exception{
        Path root=RunnerPrivateDirectory.createDirectories(temp.resolve("project")),child=RunnerPrivateDirectory.createDirectories(root.resolve("child"));
        Path locks=RunnerPrivateDirectory.createDirectories(temp.resolve("locks"));
        try(var held=PhysicalDirectoryLease.acquire(root,locks)){
            assertThrows(IOException.class,()->PhysicalDirectoryLease.acquire(root,locks));
            assertThrows(IOException.class,()->PhysicalDirectoryLease.acquire(child,locks));
        }
        try(var held=PhysicalDirectoryLease.acquire(child,locks)){assertNotNull(held);}
    }

    @Test void taskDirectoryCachesArePartitionedByOwnerAndCanonicalRoot()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Path child=RunnerPrivateDirectory.createDirectories(directories.home().resolve("child"));
        try(var center=new CenterHttpClient("http://127.0.0.1:1","fixture",true);
            var executor=new NativeTaskExecutor(center,temp.resolve("state"))){
            Path first=executor.derivedState(new RunnerLease(claim(directories.home(),"7")),directories.home());
            Path second=executor.derivedState(new RunnerLease(claim(child,"7")),child);
            assertNotEquals(first,second);assertFalse(first.startsWith(directories.home()));
            assertEquals(first,executor.derivedState(new RunnerLease(claim(directories.home(),"7")),directories.home()));
        }
    }

    @Test void modernLeaseRequiresOwnerAndKeepsItInEveryIdentityRequest()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        ObjectNode value=claim(directories.home(),"7");RunnerLease lease=new RunnerLease(value);
        assertEquals("7",lease.identity().path("ownerUserId").asText());assertEquals("11",lease.identity().path("directoryContextId").asText());
        value.remove("ownerUserId");assertThrows(IOException.class,()->new RunnerLease(value));
    }

    @Test void exchangeUsesPersonalHomeButExecutionUsesTaskSubdirectory()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Path child=RunnerPrivateDirectory.createDirectories(directories.home().resolve("project"));
        AtomicReference<String> executed=new AtomicReference<>();
        try(var server=new MockWebServer()){
            server.start();ObjectNode response=envelope(directories.home(),"7").put("sessionToken","session-fixture").put("expiresAt",Instant.now().plusSeconds(3600).toString());
            response.put("runtimeCanonicalPath",directories.runtime().toString()).put("runtimeFingerprint",new ResourceFingerprint(directories.runtime()).rootFingerprint());
            server.enqueue(ok(response));server.enqueue(ok(claim(child,"7")));server.enqueue(ok(CanonicalJson.MAPPER.createObjectNode()));
            try(var center=new CenterHttpClient(server.url("/").toString(),"device-fixture",true);
                var engine=new RunnerEngine(center,temp.resolve("state"),(lease,tools)->{executed.set(tools.getProjectPath());return "done";},"device-fixture",null,directories,RunnerPrivateDirectory.createDirectories(temp.resolve("locks")))){
                engine.connect();assertTrue(engine.runOnce());assertEquals(child.toRealPath().toString(),executed.get());
                JsonNode exchange=CanonicalJson.MAPPER.readTree(server.takeRequest().getBody().readUtf8());
                assertEquals(directories.home().toString(),exchange.path("canonicalRootPath").asText());
                assertEquals(new ResourceFingerprint(directories.home()).rootFingerprint(),exchange.path("rootFingerprint").asText());
                assertEquals(directories.runtime().toString(),exchange.path("runtimeCanonicalPath").asText());
                assertEquals(new ResourceFingerprint(directories.runtime()).rootFingerprint(),exchange.path("runtimeFingerprint").asText());
                assertEquals("7",exchange.path("ownerUserId").asText());server.takeRequest();
                JsonNode event=CanonicalJson.MAPPER.readTree(server.takeRequest().getBody().readUtf8());assertEquals("7",event.path("ownerUserId").asText());
            }
        }
    }

    @Test void unavailableFilesDoNotBlockPrivateTextRuntimeAndCannotGrantTools()throws Exception{
        var directories=new PersonalDirectoryContext(temp.resolve("personal"),"7");
        Files.delete(directories.home());try(var ignored=RunnerPrivateDirectory.createFile(directories.home())){}
        var unavailable=new PersonalDirectoryContext(temp.resolve("personal"),"7");assertFalse(unavailable.filesReady());assertNotNull(unavailable.filesFailure());
        ObjectNode value=claim(unavailable.runtime(),"7");value.put("textOnly",true).putNull("directoryContextId").putNull("directoryContext");
        assertEquals(unavailable.runtime(),unavailable.resolveRuntime(value));RunnerLease lease=new RunnerLease(value);
        try(var server=new MockWebServer()){
            server.start();try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true)){
                Path state=RunnerPrivateDirectory.createDirectories(temp.resolve("state"));
                var registry=new CenterToolRegistry(center,lease,new ReceiptSpool(state.resolve("receipts")),unavailable.runtime());
                assertTrue(registry.getToolDefinitions().isEmpty());assertFalse(registry.executeToolOutput("write_file","{\"path\":\"forbidden.txt\",\"content\":\"x\"}").successful());
                assertFalse(Files.exists(unavailable.runtime().resolve("forbidden.txt")));assertEquals(0,server.getRequestCount());
            }
        }
        value.put("ownerUserId","8");assertThrows(IOException.class,()->unavailable.resolveRuntime(value));
    }

    static ObjectNode envelope(Path path,String owner)throws IOException{
        String hash=new ResourceFingerprint(path).rootFingerprint();
        ObjectNode value=CanonicalJson.MAPPER.createObjectNode().put("directoryContextId","11").put("runnerId","22").put("ownerUserId",owner).put("rootFingerprint",hash);
        value.putObject("directoryContext").put("id","11").put("ownerUserId",owner).put("runnerId","22").put("path",path.toRealPath().toString()).put("rootFingerprint",hash).put("version",1).put("state","VERIFIED");return value;
    }
    static ObjectNode claim(Path path,String owner)throws IOException{
        return envelope(path,owner).put("taskId","1").put("sessionId","2").put("leaseId","3").put("fencingToken",1).put("version",1).put("policyVersion",1).put("expiresAt",Instant.now().plusSeconds(120).toString());
    }
    private static MockResponse ok(JsonNode value){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+value+"}");}
}
