package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMcpGrantTest {
    @Test void personalMcpUsesOwnerSecretAndCanBeSharedAcrossOwnDirectories()throws Exception{
        RunnerLease lease=new RunnerLease(claim().put("ownerUserId","41"));
        ObjectNode personal=config().put("ownerUserId","41");personal.putNull("directoryContextId");
        personal.putObject("headerSecretRefs").put("Authorization","ai.paicli.user.41.mcp");
        ObjectNode result=response(lease,1,Instant.now().plusSeconds(50));result.set("headerSecretRefs",personal.get("headerSecretRefs"));hash(result);
        try(var server=new MockWebServer()){
            server.enqueue(ok(result));server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                var grant=new ManagedMcpGrant(center,lease,()->consumed(lease),personal,()->{})){
                assertEquals("Bearer fixture-managed-secret",grant.current().headers().get("Authorization"));
            }
        }
    }
    @Test void personalMcpRejectsOtherOwnerAndOldSecretNamespaceBeforeAnyRpc()throws Exception{
        for(boolean otherOwner:List.of(true,false)){
            RunnerLease lease=new RunnerLease(claim().put("ownerUserId","41"));
            ObjectNode personal=config().put("ownerUserId",otherOwner?"42":"41");personal.putNull("directoryContextId");
            if(otherOwner)personal.putObject("headerSecretRefs").put("Authorization","ai.paicli.user.42.mcp");
            try(var server=new MockWebServer()){
                server.start();try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                    var grant=new ManagedMcpGrant(center,lease,()->consumed(lease),personal,()->{})){
                    assertThrows(IOException.class,grant::current);assertEquals(0,server.getRequestCount());
                }
            }
        }
    }
    private static ObjectNode claim(){return CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("directoryContextId","7").put("leaseId","2").put("fencingToken",3).put("version",4).put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(120).toString());}
    private static ObjectNode config(){ObjectNode value=CanonicalJson.MAPPER.createObjectNode().put("id","5").put("directoryContextId","7").put("version",6).put("configHash","b".repeat(64)).put("trustedHash","b".repeat(64)).put("transport","HTTP");value.putObject("headerSecretRefs").put("Authorization","ai.paicli.7.mcp");value.putObject("envSecretRefs");value.putObject("headers").put("Accept","application/json");return value;}
    private static ObjectNode consumed(RunnerLease lease){return lease.identity().put("toolCallId","mcp-call").put("permitToken","fixture-permit");}
    private static ObjectNode response(RunnerLease lease,int secretVersion,Instant expiry){ObjectNode value=CanonicalJson.MAPPER.createObjectNode().put("configId","5").put("configVersion",6).put("configHash","b".repeat(64)).put("expiresAt",expiry.toString());value.set("envSecretRefs",config().get("envSecretRefs"));value.set("headerSecretRefs",config().get("headerSecretRefs"));value.putObject("secretVersions").put("header.Authorization",secretVersion);value.putObject("env");value.putObject("headers").put("Authorization","Bearer fixture-managed-secret").put("Accept","application/json");for(String field:List.of("taskId","leaseId","fencingToken","version"))value.set(field,lease.identity().get(field));hash(value);return value;}
    private static void hash(ObjectNode value){ObjectNode binding=CanonicalJson.MAPPER.createObjectNode();for(String field:List.of("configId","configVersion","configHash","envSecretRefs","headerSecretRefs","secretVersions","taskId","leaseId","fencingToken","version"))binding.set(field,value.get(field));value.put("grantHash",CanonicalJson.hash(binding));}
    private static MockResponse ok(JsonNode value){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+value+"}");}
    @Test void eachRpcRenewsOnlyConsumedFrozenReferencesAndRedactsValues()throws Exception{
        RunnerLease lease=new RunnerLease(claim());AtomicInteger stops=new AtomicInteger();try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(lease,1,Instant.now().plusSeconds(50))));server.enqueue(ok(response(lease,1,Instant.now().plusSeconds(55))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),stops::incrementAndGet)){
            assertEquals("Bearer fixture-managed-secret",grant.current().headers().get("Authorization"));ManagedMcpGrant.Grant current=grant.current();assertEquals("Bearer fixture-managed-secret",current.headers().get("Authorization"));assertEquals(2,server.getRequestCount());RecordedRequest request=server.takeRequest();assertEquals("/paicli/runner/v1/mcp-context",request.getPath());JsonNode body=CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());assertEquals("fixture-permit",body.path("permitToken").asText());assertEquals("5",body.path("configId").asText());assertFalse(body.toString().contains("fixture-managed-secret"));assertFalse(grant.toString().contains("fixture-managed-secret"));assertFalse(current.toString().contains("fixture-managed-secret"));assertEquals("* / * / application/json",grant.redact("Bearer fixture-managed-secret / fixture-managed-secret / application/json"));
        }}assertEquals(1,stops.get());
    }
    @Test void missingConsumedBindingRejectsBeforeSendingAndCloses()throws Exception{
        RunnerLease lease=new RunnerLease(claim());AtomicInteger stops=new AtomicInteger();try(MockWebServer server=new MockWebServer()){server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->null,config(),stops::incrementAndGet)){assertThrows(IOException.class,grant::current);assertEquals(0,server.getRequestCount());assertEquals(1,stops.get());assertThrows(IOException.class,lease::ensureActive);}}
    }
    @Test void rotatedVersionAndMismatchedGrantHashCannotContinue()throws Exception{
        RunnerLease lease=new RunnerLease(claim());AtomicInteger stops=new AtomicInteger();try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(lease,1,Instant.now().plusSeconds(50))));server.enqueue(ok(response(lease,2,Instant.now().plusSeconds(50))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),stops::incrementAndGet)){grant.current();assertThrows(IOException.class,grant::current);assertEquals(1,stops.get());assertThrows(IOException.class,lease::ensureActive);}}
        RunnerLease next=new RunnerLease(claim());try(MockWebServer server=new MockWebServer()){ObjectNode bad=response(next,1,Instant.now().plusSeconds(50));bad.put("grantHash","c".repeat(64));server.enqueue(ok(bad));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,next,()->consumed(next),config(),()->{})){assertThrows(IOException.class,grant::current);}}
    }
    @Test void expiryStopsProcessWithoutABackgroundCredentialRefresh()throws Exception{
        RunnerLease lease=new RunnerLease(claim());CountDownLatch closed=new CountDownLatch(1);try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(lease,1,Instant.now().plusMillis(1500))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),closed::countDown)){grant.current();assertTrue(closed.await(3,TimeUnit.SECONDS));assertEquals(1,server.getRequestCount());assertThrows(IOException.class,grant::current);assertThrows(IOException.class,()->grant.bind(new RunnerLease(claim()),()->consumed(lease)));}}
    }
    @Test void protectedEnvironmentAndCrossWorkspaceReferencesAreRejectedLocally()throws Exception{
        for(String name:List.of("PAICLI_RUNNER_TOKEN","JAVA_TOOL_OPTIONS","PATH")){RunnerLease lease=new RunnerLease(claim());ObjectNode bad=config().put("transport","STDIO");bad.putObject("headerSecretRefs");bad.putObject("headers");bad.putObject("envSecretRefs").put(name,"ai.paicli.7.mcp");try(MockWebServer server=new MockWebServer()){server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),bad,()->{})){assertThrows(IOException.class,grant::current);assertEquals(0,server.getRequestCount());}}}
        RunnerLease lease=new RunnerLease(claim());ObjectNode other=config();other.putObject("headerSecretRefs").put("Authorization","ai.paicli.8.mcp");try(MockWebServer server=new MockWebServer()){server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),other,()->{})){assertThrows(IOException.class,grant::current);assertEquals(0,server.getRequestCount());}}
    }
    @Test void unknownRpcOutcomeInvalidatesLeaseAndCannotBeRebound()throws Exception{
        RunnerLease lease=new RunnerLease(claim());AtomicInteger stops=new AtomicInteger();try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(lease,1,Instant.now().plusSeconds(50))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),stops::incrementAndGet)){grant.current();grant.uncertainExecution();assertEquals(1,stops.get());assertThrows(IOException.class,lease::ensureActive);assertThrows(IOException.class,grant::current);assertThrows(IOException.class,()->grant.bind(new RunnerLease(claim()),()->consumed(lease)));}}
    }
    @Test void repeatedSingleCharacterCredentialsDoNotAmplifyRedactedUtf8()throws Exception{
        for(String secret:List.of("a","*","\u4e2d")){
            RunnerLease lease=new RunnerLease(claim());try(MockWebServer server=new MockWebServer()){
                ObjectNode result=response(lease,1,Instant.now().plusSeconds(50));((ObjectNode)result.get("headers")).put("Authorization","Bearer "+secret);server.enqueue(ok(result));server.start();
                try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),()->{})){
                    grant.current();String original=("Bearer "+secret+" "+secret+" ").repeat(10000),redacted=grant.redact(original);
                    assertFalse(redacted.contains(secret));assertTrue(redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);assertEquals(40000,redacted.length());
                }
            }
        }
    }
    @Test void grantCannotOutliveCurrentLeaseButCanFollowAnActualHeartbeatRenewal()throws Exception{
        RunnerLease shortLease=new RunnerLease(claim().put("expiresAt",Instant.now().plusSeconds(10).toString()));
        try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(shortLease,1,Instant.now().plusSeconds(50))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,shortLease,()->consumed(shortLease),config(),()->{})){assertThrows(IOException.class,grant::current);assertThrows(IOException.class,shortLease::ensureActive);}}
        RunnerLease renewed=new RunnerLease(claim().put("expiresAt",Instant.now().plusSeconds(10).toString()));renewed.heartbeat(CanonicalJson.MAPPER.createObjectNode().put("version",4).put("expiresAt",Instant.now().plusSeconds(90).toString()));
        try(MockWebServer server=new MockWebServer()){server.enqueue(ok(response(renewed,1,Instant.now().plusSeconds(50))));server.start();try(CenterHttpClient center=new CenterHttpClient(server.url("/").toString(),"fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,renewed,()->consumed(renewed),config(),()->{})){assertTrue(grant.current().expiresAt().isBefore(renewed.expiresAt()));}}
    }
    @Test void noSafePrintableMaskFallsBackToRemovalWithoutReturningASecret()throws Exception{
        RunnerLease lease=new RunnerLease(claim());try(CenterHttpClient center=new CenterHttpClient("http://127.0.0.1:1/","fixture",true);ManagedMcpGrant grant=new ManagedMcpGrant(center,lease,()->consumed(lease),config(),()->{})){
            var field=ManagedMcpGrant.class.getDeclaredField("redactions");field.setAccessible(true);@SuppressWarnings("unchecked")var values=(java.util.Set<String>)field.get(grant);for(char character=33;character<127;character++)values.add(String.valueOf(character));
            String original="all printable credentials * # !",redacted=grant.redact(original);assertTrue(redacted.isBlank());assertTrue(redacted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }
}
