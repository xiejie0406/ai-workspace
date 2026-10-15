package com.paicli.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import okhttp3.mockwebserver.*;
import static org.junit.jupiter.api.Assertions.*;

class DeviceCredentialCacheTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temporary;
    @Test void bootstrapRetryKeepsKeyAndBoundDeviceSurvivesRestart()throws Exception{
        Path state=temporary.resolve("state");DeviceCredentialCache cache=new DeviceCredentialCache(state,"https://center.invalid","root-a");
        assertThrows(java.io.IOException.class,()->cache.select(null));assertEquals("one-time-code",cache.select("one-time-code"));
        String key=cache.exchangeKey("one-time-code");assertEquals(key,new DeviceCredentialCache(state,"https://center.invalid","root-a").exchangeKey("one-time-code"));
        assertEquals("one-time-code",cache.select("one-time-code"));
        cache.store("bound-device","one-time-code");assertEquals("bound-device",cache.select("one-time-code"));assertEquals("bound-device",cache.select(null));assertEquals("new-code",cache.select("new-code"));
        String boundKey=cache.exchangeKey("bound-device");assertEquals(boundKey,cache.exchangeKey("bound-device"));cache.completeExchange("bound-device");assertNotEquals(boundKey,cache.exchangeKey("bound-device"));
        assertThrows(java.io.IOException.class,()->new DeviceCredentialCache(state,"https://other.invalid","root-a").select(null));
        assertThrows(java.io.IOException.class,()->new DeviceCredentialCache(state,"https://center.invalid","root-b").select(null));
    }
    @Test void interruptedBoundExchangeKeepsCredentialKeyAndOriginalProofAcrossRestart()throws Exception{
        Path state=temporary.resolve("state");DeviceCredentialCache cache=new DeviceCredentialCache(state,"https://center.invalid","root-a");
        cache.store("bound-device","one-time-code");String key=cache.exchangeKey("bound-device");
        var request=CanonicalJson.MAPPER.createObjectNode().put("runtimeFingerprint","original-proof");cache.exchangeRequest("bound-device",request);
        DeviceCredentialCache restarted=new DeviceCredentialCache(state,"https://center.invalid","root-a");
        assertEquals("bound-device",restarted.select(null));assertEquals(key,restarted.exchangeKey("bound-device"));
        assertEquals(request,restarted.exchangeRequest("bound-device",CanonicalJson.MAPPER.createObjectNode().put("runtimeFingerprint","new-proof")));
        restarted.store("rotated-device","bound-device");assertEquals("rotated-device",restarted.select("one-time-code"));assertEquals("rotated-device",restarted.select(null));
        restarted.exchangeKey("different-bootstrap");assertEquals("rotated-device",restarted.select(null));
    }
    @Test void expiredSessionReplayDoesNotDeleteBoundCredentialOrTrapFutureExchange()throws Exception{
        Path root=Files.createDirectory(temporary.resolve("root")),state=temporary.resolve("state"),locks=Files.createDirectory(temporary.resolve("locks"));DeviceCredentialCache.restrict(locks);
        try(MockWebServer server=new MockWebServer()){
            server.start();String url=server.url("/").toString();DeviceCredentialCache cache=new DeviceCredentialCache(state,url,new ResourceFingerprint(root).rootFingerprint());cache.store("bound-device","one-time-code");
            server.enqueue(ok("{\"sessionToken\":\"expired-session\",\"expiresAt\":\""+Instant.now().minusSeconds(1)+"\"}"));
            server.enqueue(ok("{\"sessionToken\":\"fresh-session\",\"deviceToken\":\"rotated-device\",\"expiresAt\":\""+Instant.now().plusSeconds(600)+"\"}"));
            try(CenterHttpClient client=new CenterHttpClient(url,"bound-device",true);RunnerEngine engine=new RunnerEngine(client,root,state,(lease,tools)->"unused","bound-device",cache,locks)){
                assertThrows(java.io.IOException.class,engine::connect);assertEquals("bound-device",cache.select(null));engine.connect();
                assertEquals("rotated-device",cache.select(null));assertNotEquals(server.takeRequest().getHeader("Idempotency-Key"),server.takeRequest().getHeader("Idempotency-Key"));
            }
        }
    }
    @Test void engineRenewsWithBoundDeviceAndClaimsWithNewSession()throws Exception{
        Path root=Files.createDirectory(temporary.resolve("root")),state=temporary.resolve("state");
        Path lockHome=Files.createDirectory(temporary.resolve("lock-home"));DeviceCredentialCache.restrict(lockHome);
        try(MockWebServer server=new MockWebServer()){
            server.start();String url=server.url("/").toString();DeviceCredentialCache cache=new DeviceCredentialCache(state,url,new ResourceFingerprint(root).rootFingerprint());
            server.enqueue(ok("{\"sessionToken\":\"short-session\",\"deviceToken\":\"bound-device\",\"expiresAt\":\""+Instant.now().plusSeconds(30)+"\"}"));
            server.enqueue(ok("{\"sessionToken\":\"renewed-session\",\"expiresAt\":\""+Instant.now().plusSeconds(600)+"\"}"));server.enqueue(ok("{\"available\":false}"));
            try(CenterHttpClient client=new CenterHttpClient(url,"one-time-code",true);RunnerEngine engine=new RunnerEngine(client,root,state,(lease,tools)->"unused","one-time-code",cache,lockHome)){
                engine.connect();assertFalse(engine.runOnce());assertEquals("bound-device",cache.select("one-time-code"));
                assertEquals("one-time-code",server.takeRequest().getHeader("X-PaiCLI-Runner-Token"));
                assertEquals("bound-device",server.takeRequest().getHeader("X-PaiCLI-Runner-Token"));
                assertEquals("renewed-session",server.takeRequest().getHeader("X-PaiCLI-Runner-Token"));
            }
        }
    }
    @Test void temporaryReadinessReadHandleDoesNotFailHealthyClaimLoop()throws Exception{
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name","").toLowerCase().contains("win"));
        Path root=Files.createDirectory(temporary.resolve("root")),state=temporary.resolve("state"),locks=Files.createDirectory(temporary.resolve("locks"));DeviceCredentialCache.restrict(locks);
        try(MockWebServer server=new MockWebServer()){
            server.start();server.enqueue(ok("{\"sessionToken\":\"fresh-session\",\"expiresAt\":\""+Instant.now().plusSeconds(600)+"\"}"));server.enqueue(ok("{\"available\":false}"));
            try(CenterHttpClient client=new CenterHttpClient(server.url("/").toString(),"fixture",true);RunnerEngine engine=new RunnerEngine(client,root,state,(lease,tools)->"unused",null,null,locks)){
                engine.connect();Path file=state.resolve("runner-readiness.json");String original=Files.readString(file);
                var handle=com.sun.jna.platform.win32.Kernel32.INSTANCE.CreateFile(file.toString(),com.sun.jna.platform.win32.WinNT.GENERIC_READ,
                    com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ|com.sun.jna.platform.win32.WinNT.FILE_SHARE_WRITE,null,com.sun.jna.platform.win32.WinNT.OPEN_EXISTING,com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL,null);
                assertNotEquals(com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE,handle);
                try{assertFalse(engine.runOnce());assertEquals(original,Files.readString(file));}
                finally{com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle);}
                assertEquals("/paicli/runner/v1/sessions",server.takeRequest().getPath());assertEquals("/paicli/runner/v1/claims",server.takeRequest().getPath());
            }
        }
    }
    @Test void existingCredentialWithWiderPermissionsIsRejectedWithoutRepair()throws Exception{
        Path state=temporary.resolve("state");DeviceCredentialCache cache=new DeviceCredentialCache(state,"https://center.invalid","root-a");
        cache.store("private-device-value","one-time-code");Path file;
        try(var entries=Files.list(state.resolve("device-credentials"))){file=entries.findFirst().orElseThrow();}
        byte[] original=Files.readAllBytes(file);
        var posix=Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class);
        if(posix!=null){
            var wider=java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");Files.setPosixFilePermissions(file,wider);
            assertThrows(java.io.IOException.class,()->cache.select(null));assertEquals(wider,Files.getPosixFilePermissions(file));
        }else{
            var acl=Files.getFileAttributeView(file,java.nio.file.attribute.AclFileAttributeView.class);
            var everyone=file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
            var wider=new java.util.ArrayList<>(acl.getAcl());wider.add(java.nio.file.attribute.AclEntry.newBuilder()
                    .setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(everyone)
                    .setPermissions(java.nio.file.attribute.AclEntryPermission.READ_DATA).build());acl.setAcl(wider);
            assertThrows(java.io.IOException.class,()->cache.select(null));assertEquals(wider,acl.getAcl());
        }
        assertArrayEquals(original,Files.readAllBytes(file));
    }
    private MockResponse ok(String data){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+data+"}");}
}
