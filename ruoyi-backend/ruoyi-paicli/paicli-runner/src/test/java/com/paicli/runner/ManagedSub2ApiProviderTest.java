package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManagedSub2ApiProviderTest {
    private static RunnerLease lease() throws IOException {
        return new RunnerLease(CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("leaseId","2")
                .put("fencingToken",3).put("version",4).put("policyVersion",1).put("rootFingerprint","a".repeat(64))
                .put("expiresAt",Instant.now().plusSeconds(90).toString()));
    }
    private static ObjectNode context(String root,String wireApi,int secretVersion,Instant expiry) {
        ObjectNode value=CanonicalJson.MAPPER.createObjectNode().put("provider","sub2api").put("model","exact-model");
        value.putObject("providerConfig").put("id","5").put("version",1).put("provider","sub2api").put("wireApi",wireApi).put("baseUrl",root);
        value.putObject("modelConfig").put("id","6").put("version",1).put("modelId","exact-model").put("providerId","5").put("supportsImages",false);
        value.putObject("managedSecret").put("secretRef","ai.paicli.7.sub2api.text").put("secretVersion",secretVersion)
                .put("apiKey","synthetic-only-key").put("baseUrl",root).put("expiresAt",expiry.toString());
        return value;
    }
    private static MockResponse ok(ObjectNode value){return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+value+"}");}
    @Test void centerSelectionUsesExplicitWireApiAndExactModelBeforeDispatch() throws Exception {
        try(var api=new MockWebServer()) {
            api.start();var ctx=context(api.url("/v1").toString(),"responses",1,Instant.now().plusSeconds(60));
            LlmClient client=NativeTaskExecutor.managedProvider(ctx);assertEquals("exact-model",client.getModelName());assertFalse(client.supportsImageInput());
            api.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"ready\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            assertEquals("ready",client.chat(List.of(LlmClient.Message.user("hello")),List.of()).content());
            var sent=api.takeRequest();assertEquals("/v1/responses",sent.getPath());assertEquals("Bearer synthetic-only-key",sent.getHeader("Authorization"));
            assertEquals("exact-model",CanonicalJson.MAPPER.readTree(sent.getBody().readUtf8()).path("model").asText());
            ((ObjectNode)ctx.get("providerConfig")).remove("wireApi");assertThrows(IOException.class,()->NativeTaskExecutor.managedProvider(ctx));assertEquals(1,api.getRequestCount());
        }
    }
    @Test void renewalPreservesProtocolButRejectsProtocolOrSecretVersionChanges() throws Exception {
        for(boolean protocolChange:List.of(false,true))try(var centerApi=new MockWebServer()) {
            centerApi.start();RunnerLease lease=lease();String root="https://sub2api.example/v1";
            var frozen=context(root,"responses",1,Instant.now().plusSeconds(2));
            var next=context(root,protocolChange?"chat_completions":"responses",protocolChange?1:2,Instant.now().plusSeconds(50));centerApi.enqueue(ok(next));
            try(var center=new CenterHttpClient(centerApi.url("/").toString(),"synthetic-device",true)) {
                var grant=new ManagedProviderGrant(center,lease,frozen,"managedSecret");assertThrows(IOException.class,grant::currentKey);assertThrows(IOException.class,lease::ensureActive);
                var request=centerApi.takeRequest();assertEquals("/paicli/runner/v1/provider-context",request.getPath());assertFalse(request.getBody().readUtf8().contains("synthetic-only-key"));
            }
        }
    }
    @Test void renewalOfSameFrozenDefinitionWorksAndLeaseCancellationStopsLocally() throws Exception {
        try(var centerApi=new MockWebServer()) {
            centerApi.start();RunnerLease lease=lease();String root="https://sub2api.example/v1";
            var frozen=context(root,"responses",1,Instant.now().plusSeconds(2));var next=context(root,"responses",1,Instant.now().plusSeconds(50));
            next.withObject("managedSecret").put("apiKey","renewed-synthetic-key");centerApi.enqueue(ok(next));
            try(var center=new CenterHttpClient(centerApi.url("/").toString(),"synthetic-device",true)) {
                var grant=new ManagedProviderGrant(center,lease,frozen,"managedSecret");assertEquals("renewed-synthetic-key",grant.currentKey());assertEquals("renewed-synthetic-key",grant.currentKey());assertEquals(1,centerApi.getRequestCount());
                lease.heartbeat(CanonicalJson.MAPPER.createObjectNode().put("cancelRequested",true).put("expiresAt",Instant.now().plusSeconds(60).toString()));
                assertThrows(IOException.class,grant::currentKey);assertEquals(1,centerApi.getRequestCount());
            }
        }
    }
}
