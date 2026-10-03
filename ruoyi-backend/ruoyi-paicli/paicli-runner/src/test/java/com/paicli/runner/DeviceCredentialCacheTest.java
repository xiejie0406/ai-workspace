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
        assertNotEquals(cache.exchangeKey("bound-device"),cache.exchangeKey("bound-device"));
        assertThrows(java.io.IOException.class,()->new DeviceCredentialCache(state,"https://other.invalid","root-a").select(null));
        assertThrows(java.io.IOException.class,()->new DeviceCredentialCache(state,"https://center.invalid","root-b").select(null));
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
