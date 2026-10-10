package com.paicli.runner;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.BudgetedLlmClient;
import com.paicli.rag.EmbeddingClient;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

/** 配置、发布来源和用量身份；不连接实际供应商或读取真实凭据。 */
class ModelCapabilityBindingsTest {
    private ObjectNode claim() {
        return CanonicalJson.MAPPER.createObjectNode().put("taskId","1").put("workspaceId","7").put("ownerUserId","7")
            .put("runnerId","3").put("attemptId","4").put("leaseId","5").put("fencingToken","6")
            .put("version",1).put("policyVersion",1)
            .put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(120).toString());
    }
    private ObjectNode config() {
        var c=CanonicalJson.MAPPER.createObjectNode().put("id","11").put("configurationVersion",2)
            .put("provider","openai").put("baseUrl","https://dashscope.aliyuncs.com/compatible-mode/v1")
            .put("model","text-embedding-v4").put("expectedDimensions",1024);
        c.put("configFingerprint",new EmbeddingClient("openai","text-embedding-v4",c.path("baseUrl").asText(),"",1024).getIdentity().fingerprint());
        return c;
    }
    @Test void embeddingProbeRejectsAlteredDimensionFingerprintOrVersionBeforeCalling() throws Exception {
        var c=config();var args=CanonicalJson.MAPPER.createObjectNode().put("configId","11").put("configVersion",2)
            .put("expectedDimensions",1024).put("configFingerprint",c.path("configFingerprint").asText());
        NativeControlExecutor.verifyEmbeddingBinding(args,c);
        assertThrows(IOException.class,()->NativeControlExecutor.verifyEmbeddingBinding(args,c.deepCopy().put("expectedDimensions",768)));
        assertThrows(IOException.class,()->NativeControlExecutor.verifyEmbeddingBinding(args,c.deepCopy().put("configurationVersion",3)));
        var forged=c.deepCopy().put("model","other");
        assertThrows(IOException.class,()->NativeControlExecutor.verifyEmbeddingBinding(args,forged));
    }
    @Test void publishedEmbeddingRequiresFrozenSourceAndReleaseWithoutChangingUser() throws Exception {
        var context=CanonicalJson.MAPPER.createObjectNode();var c=config().put("sourceOwnerId","8").put("releaseId","9").put("releaseVersion",3);
        context.set("embeddingConfig",c);context.putObject("embeddingSecret").put("secretRef","ai.paicli.user.8.aliyun.embedding")
            .put("apiKey","synthetic-only").put("expiresAt",Instant.now().plusSeconds(60).toString());
        var claim=claim();
        try(var center=new CenterHttpClient("http://127.0.0.1:1","synthetic",true)) {
            assertThrows(IOException.class,()->new ManagedProviderGrant(center,new RunnerLease(claim),context,"embeddingSecret").currentKey());
            claim.putObject("payload").put("embeddingSecretOwnerId","8").put("embeddingReleaseId","9").put("embeddingReleaseVersion",3);
            var lease=new RunnerLease(claim);
            assertEquals("synthetic-only",new ManagedProviderGrant(center,lease,context,"embeddingSecret").currentKey());
            assertEquals("7",lease.identity().path("ownerUserId").asText());
            claim.withObject("/payload").put("embeddingReleaseVersion",4);
            assertThrows(IOException.class,()->new ManagedProviderGrant(center,new RunnerLease(claim),context,"embeddingSecret").currentKey());
        }
    }
    @Test void usageKeepsActualEmbeddingIdentityAndDoesNotLeakCredential() {
        var context=CanonicalJson.MAPPER.createObjectNode().put("provider","sub2api").put("model","main");
        context.putObject("managedSecret").put("apiKey","synthetic-only");
        var identity=new BudgetedLlmClient.CallIdentity("EMBEDDING","openai","text-embedding-v4",2,"b".repeat(64),"9",3);
        var payload=NativeTaskExecutor.usagePayload(context,new BudgetedLlmClient.Usage(8,0,0,8,10,true,"COMPLETED",null,identity));
        assertEquals("EMBEDDING",payload.path("capability").asText());assertEquals("text-embedding-v4",payload.path("model").asText());
        assertEquals(3,payload.path("releaseVersion").asInt());assertEquals(0,payload.path("outputTokens").asInt());
        assertFalse(payload.toString().contains("synthetic-only"));assertFalse(payload.has("callIdentity"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void textGrantAllowsImageRevisionButEffectiveImageHistoryKeepsProofFrozen(boolean historyHasImages) throws Exception {
        var context=CanonicalJson.MAPPER.createObjectNode().put("provider","sub2api").put("model","main");
        context.putObject("providerConfig").put("ownerUserId","7").put("version",1).put("configurationVersion",1);
        context.putObject("modelConfig").put("ownerUserId","7").put("version",1).put("configurationVersion",1).put("supportsImages",true).put("imageInput",true);
        context.putObject("managedSecret").put("secretRef","ai.paicli.user.7.provider.1").put("secretVersion",1).put("apiKey","synthetic-only")
            .put("expiresAt",Instant.now().plusSeconds(1).toString());
        if(historyHasImages)context.putArray("history").addObject().putArray("contentParts").addObject().put("type","image_artifact").put("artifactId","9");
        var renewed=context.deepCopy();renewed.withObject("/modelConfig").put("version",3).put("supportsImages",false).put("imageInput",false);
        renewed.withObject("/managedSecret").put("expiresAt",Instant.now().plusSeconds(90).toString());
        try(var server=new okhttp3.mockwebserver.MockWebServer()) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+renewed+"}"));server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"synthetic",true)) {
                var grant=new ManagedProviderGrant(center,new RunnerLease(claim()),context,"managedSecret");
                if(historyHasImages)assertThrows(IOException.class,grant::currentKey);else assertEquals("synthetic-only",grant.currentKey());
            }
            assertEquals(1,server.getRequestCount());
        }
    }
}
