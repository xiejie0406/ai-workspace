package com.paicli.runner;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManagedSub2ApiHttpApprovalTest {
    @Test void renewalRejectsRemovedOrChangedHttpApprovalAndInvalidatesLease()throws Exception {
        String root="http://39.102.215.183:8080/v1";
        for(String approved:List.of("","http://39.102.215.183:8081/v1"))try(var fakeCenter=new okhttp3.mockwebserver.MockWebServer()){
            fakeCenter.start();var claim=CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("leaseId","2").put("fencingToken",3).put("version",1)
                .put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(60).toString());
            RunnerLease lease=new RunnerLease(claim);var frozen=CanonicalJson.MAPPER.createObjectNode().put("provider","sub2api").put("model","exact-model");
            frozen.putObject("providerConfig").put("baseUrl",root).put("wireApi","responses");
            frozen.putObject("managedSecret").put("secretRef","ai.paicli.user.1.sub2api.text").put("secretVersion",1).put("apiKey","fixture")
                .put("baseUrl",root).put("approvedHttpRoot",root).put("expiresAt",Instant.now().plusSeconds(2).toString());
            var next=frozen.deepCopy();next.withObject("/managedSecret").put("expiresAt",Instant.now().plusSeconds(60).toString());
            if(approved.isEmpty())next.withObject("/managedSecret").remove("approvedHttpRoot");else next.withObject("/managedSecret").put("approvedHttpRoot",approved);
            fakeCenter.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+next+"}"));
            try(var center=new CenterHttpClient(fakeCenter.url("/").toString(),"fixture-device",true)){
                var grant=new ManagedProviderGrant(center,lease,frozen,"managedSecret");assertThrows(java.io.IOException.class,grant::currentKey);
                assertThrows(java.io.IOException.class,lease::ensureActive);assertThrows(java.io.IOException.class,grant::currentKey);assertEquals(1,fakeCenter.getRequestCount());
            }
        }
    }
    @Test void runnerUsesOnlyTheHttpRootCapabilityGrantedByCenter()throws Exception {
        String root="http://39.102.215.183:8080/v1";
        var context=CanonicalJson.MAPPER.createObjectNode().put("provider","sub2api").put("model","exact-model");
        var config=context.putObject("providerConfig");var secret=context.putObject("managedSecret").put("apiKey","fixture")
            .put("secretRef","ai.paicli.user.1.sub2api.text").put("baseUrl",root).put("expiresAt",Instant.now().plusSeconds(60).toString());
        for(String wire:List.of("responses","chat_completions")){
            config.put("wireApi",wire);secret.remove("approvedHttpRoot");assertThrows(java.io.IOException.class,()->NativeTaskExecutor.managedProvider(context));
            secret.put("approvedHttpRoot",root);assertEquals("exact-model",NativeTaskExecutor.managedProvider(context).getModelName());
            secret.put("approvedHttpRoot","http://39.102.215.183:8081/v1");assertThrows(java.io.IOException.class,()->NativeTaskExecutor.managedProvider(context));
        }
    }
}
