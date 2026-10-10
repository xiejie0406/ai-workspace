package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 真实控制执行器、动态托管凭据及 HTTP 客户端；中心和模型仅使用本机合成服务。 */
class Sub2ApiVerificationIntegrationTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;

    private static MockResponse ok(JsonNode body) {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":200,\"data\":" + body + "}");
    }

    private static Dispatcher center(List<JsonNode> events) {
        return new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    ObjectNode input = (ObjectNode) CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                    if (request.getPath().endsWith("/proposals"))
                        return ok(input.put("decision", "PERMITTED").put("permitToken", "synthetic-permit")
                                .put("expiresAt", Instant.now().plusSeconds(60).toString()));
                    if (request.getPath().endsWith("/consume")) return ok(input.put("consumed", true));
                    if (request.getPath().endsWith("/events")) {
                        events.add(input.deepCopy()); return ok(CanonicalJson.MAPPER.createObjectNode());
                    }
                    if (request.getPath().endsWith("/receipts")) {
                        input.remove("permitToken");
                        return ok(input.put("accepted", true).put("acknowledged", true).put("archived", false));
                    }
                    return new MockResponse().setResponseCode(404);
                } catch (IOException failure) { return new MockResponse().setResponseCode(500); }
            }
        };
    }

    private static ObjectNode context(MockWebServer model, String api, int output, long budget) {
        ObjectNode ctx = CanonicalJson.MAPPER.createObjectNode().put("provider", "sub2api").put("model", "exact-model");
        ctx.putObject("providerConfig").put("id", "5").put("version", 1).put("provider", "sub2api")
                .put("wireApi", api).put("baseUrl", model.url("/v1").toString());
        ctx.putObject("modelConfig").put("id", "6").put("version", 1).put("modelId", "exact-model")
                .put("providerId", "5").put("supportsImages", false);
        ctx.putObject("managedSecret").put("secretRef", "ai.paicli.7.sub2api.text").put("secretVersion", 1)
                .put("apiKey", "synthetic-only-key").put("baseUrl", model.url("/v1").toString())
                .put("expiresAt", Instant.now().plusSeconds(90).toString());
        ctx.putObject("policy").put("taskTokenBudget", budget).put("dailyRemainingTokenBudget", budget)
                .put("maxOutputTokens", output);
        ctx.putObject("taskPayload").put("operation", "PROVIDER_VERIFY").putObject("arguments")
                .put("configId", "5").put("configVersion", 1).put("modelId", "6").put("modelVersion", 1);
        return ctx;
    }

    private RunnerLease lease(Path root) throws IOException {
        return new RunnerLease(CanonicalJson.MAPPER.createObjectNode().put("taskId", "1").put("sessionId", "2")
                .put("leaseId", "3").put("fencingToken", 8).put("version", 4).put("policyVersion", 1)
                .put("rootFingerprint", new ResourceFingerprint(root).rootFingerprint())
                .put("expiresAt", Instant.now().plusSeconds(90).toString()));
    }

    private static MockResponse stream(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static void probeResponses(MockWebServer model, String api) {
        if ("responses".equals(api)) {
            model.enqueue(stream("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_probe\",\"summary\":[],\"encrypted_content\":\"synthetic-state\"},{\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_probe\",\"name\":\"paicli_probe_echo\",\"arguments\":\"{\\\"value\\\":\\\"probe\\\"}\"}],\"usage\":{\"input_tokens\":10,\"output_tokens\":3}}}\n\n"));
            model.enqueue(stream("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"probe\"}\n\nevent: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"probe\"}]}],\"usage\":{\"input_tokens\":20,\"output_tokens\":3}}}\n\n"));
        } else {
            model.enqueue(stream("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_probe\",\"type\":\"function\",\"function\":{\"name\":\"paicli_probe_echo\",\"arguments\":\"{\\\"value\\\":\\\"probe\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"));
            model.enqueue(stream("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"probe\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"));
        }
    }

    private String execute(CenterHttpClient client, Path root, ObjectNode context) throws Exception {
        RunnerLease lease = lease(root);
        CenterToolRegistry tools = new CenterToolRegistry(client, lease, new ReceiptSpool(temp.resolve("receipts")), root);
        try (var executor = new NativeControlExecutor(client, temp.resolve("state"), null)) {
            return executor.execute(lease, tools, context);
        }
    }

    @ParameterizedTest @CsvSource({"responses,128", "responses,4096", "chat_completions,128", "chat_completions,4096"})
    void verificationUsesTwoBoundedCallsAndAcknowledgedUsage(String api, int allowed) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project")); List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (var model = new MockWebServer(); var control = new MockWebServer()) {
            model.start(); control.setDispatcher(center(events)); control.start(); probeResponses(model, api);
            try (var client = new CenterHttpClient(control.url("/").toString(), "synthetic-device", true)) {
                assertTrue(execute(client, root, context(model, api, allowed, 10000)).contains("兼容验证完成"));
            }
            assertEquals(2, model.getRequestCount());
            for (int i = 0; i < 2; i++) {
                var sent = model.takeRequest(2, TimeUnit.SECONDS); assertNotNull(sent);
                assertEquals("responses".equals(api) ? "/v1/responses" : "/v1/chat/completions", sent.getPath());
                assertEquals("Bearer synthetic-only-key", sent.getHeader("Authorization"));
                JsonNode request = CanonicalJson.MAPPER.readTree(sent.getBody().readUtf8());
                assertEquals("exact-model", request.path("model").asText());
                assertEquals(Math.min(allowed, 256), request.path("responses".equals(api) ? "max_output_tokens" : "max_completion_tokens").asInt());
                if (i == 1) {
                    assertTrue(request.toString().contains("call_probe"));
                    assertTrue(request.toString().contains("responses".equals(api) ? "function_call_output" : "tool_call_id"));
                    if ("responses".equals(api)) assertTrue(request.toString().contains("synthetic-state"));
                }
            }
            var usage = events.stream().filter(e -> "USAGE".equals(e.path("type").asText())).toList();
            assertEquals(2, usage.size());
            assertTrue(usage.stream().allMatch(e -> e.path("payload").path("usagePresent").asBoolean()));
            assertEquals(36, usage.stream().mapToLong(e -> e.path("payload").path("totalTokens").asLong()).sum());
            var proof = events.stream().filter(e -> "CONNECTION_RESULT".equals(e.path("type").asText())).toList();
            assertEquals(1, proof.size());
            assertEquals(2, proof.get(0).path("payload").path("verification").path("calls").asInt());
            assertEquals(api, proof.get(0).path("payload").path("wireApi").asText());
            assertTrue(new ReceiptSpool(temp.resolve("receipts")).pending().isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses", "chat_completions"})
    void inadequateBudgetSendsNoModelRequestOrSuccessEvidence(String api) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project")); List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (var model = new MockWebServer(); var control = new MockWebServer()) {
            model.start(); control.setDispatcher(center(events)); control.start();
            try (var client = new CenterHttpClient(control.url("/").toString(), "synthetic-device", true)) {
                assertThrows(IOException.class, () -> execute(client, root, context(model, api, 256, 1)));
            }
            assertEquals(0, model.getRequestCount());
            assertEquals(1, events.size());
            assertEquals("CONNECTION_RESULT", events.get(0).path("type").asText());
            assertEquals("FAILED", events.get(0).path("payload").path("status").asText());
            assertFalse(events.get(0).path("payload").has("verification"));
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses", "chat_completions"})
    void reportedUsageSurvivesFailureWithoutFinalChargeOrVerification(String api) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project")); List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (var model = new MockWebServer(); var control = new MockWebServer()) {
            model.start(); control.setDispatcher(center(events)); control.start();
            model.enqueue(stream("responses".equals(api)
                    ? "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}}\n\n"
                    : "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n"));
            try (var client = new CenterHttpClient(control.url("/").toString(), "synthetic-device", true)) {
                assertThrows(IOException.class, () -> execute(client, root, context(model, api, 256, 10000)));
            }
            assertEquals(1, model.getRequestCount());
            assertEquals(2, events.size());
            assertEquals("FAILED", events.get(1).path("payload").path("status").asText());
            assertFalse(events.get(1).path("payload").has("verification"));
            assertEquals("USAGE", events.get(0).path("type").asText());
            JsonNode usage = events.get(0).path("payload");
            assertFalse(usage.path("usagePresent").asBoolean());
            assertEquals("UNCERTAIN", usage.path("status").asText());
            assertTrue(usage.path("reserved").asLong() > 7);
            assertEquals(usage.path("reserved").asLong(), usage.path("totalTokens").asLong());
            assertEquals(5, usage.path("reportedUsage").path("inputTokens").asInt());
            assertEquals(2, usage.path("reportedUsage").path("outputTokens").asInt());
            assertEquals(7, usage.path("reportedUsage").path("totalTokens").asInt());
            assertEquals("FAILED", usage.path("reportedUsage").path("status").asText());
        }
    }

    @ParameterizedTest @ValueSource(strings={"responses", "chat_completions"})
    void uncertainHttpFailureDoesNotReplayOrPublishVerification(String api) throws Exception {
        Path root = Files.createDirectory(temp.resolve("project")); List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (var model = new MockWebServer(); var control = new MockWebServer()) {
            model.start(); control.setDispatcher(center(events)); control.start();
            model.enqueue(new MockResponse().setResponseCode(503).setBody("synthetic-sensitive-body"));
            try (var client = new CenterHttpClient(control.url("/").toString(), "synthetic-device", true)) {
                var error = assertThrows(IOException.class, () -> execute(client, root, context(model, api, 256, 10000)));
                assertFalse(error.toString().contains("synthetic-sensitive-body"));
            }
            assertEquals(1, model.getRequestCount());
            assertEquals(2, events.size());
            assertEquals("FAILED", events.get(1).path("payload").path("status").asText());
            assertFalse(events.get(1).path("payload").has("verification"));
            assertEquals("USAGE", events.get(0).path("type").asText());
            assertFalse(events.get(0).path("payload").path("usagePresent").asBoolean());
            assertEquals("UNCERTAIN", events.get(0).path("payload").path("status").asText());
            assertTrue(events.get(0).path("payload").path("reportedUsage").isMissingNode()
                    || events.get(0).path("payload").path("reportedUsage").isNull());
        }
    }

    @org.junit.jupiter.api.Test
    void indexAndSemanticEventsUseFrozenLeaseRootWithNestedDirectoryContext() throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));Files.writeString(root.resolve("source.md"),"UniquePermissionService");
        List<JsonNode> events=new CopyOnWriteArrayList<>();
        try(var model=new MockWebServer();var control=new MockWebServer()) {
            model.start();control.setDispatcher(center(events));control.start();
            for(int i=0;i<2;i++)model.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"model\":\"embed\",\"data\":[{\"embedding\":[1,2]}],\"usage\":{\"prompt_tokens\":8}}"));
            ObjectNode ctx=context(model,"responses",256,10000);String url=model.url("/v1").toString();
            String hash=new com.paicli.rag.EmbeddingClient("openai","embed",url,"",2).getIdentity().fingerprint();
            ctx.putObject("directoryContext").put("rootFingerprint",new ResourceFingerprint(root).rootFingerprint());
            ctx.putObject("embeddingConfig").put("id","11").put("configurationVersion",2).put("provider","openai").put("model","embed")
                .put("baseUrl",url).put("expectedDimensions",2).put("configFingerprint",hash);
            ctx.putObject("embeddingSecret").put("secretRef","ai.paicli.user.7.embedding").put("secretVersion",3)
                .put("apiKey","synthetic-vector-key").put("expiresAt",Instant.now().plusSeconds(90).toString());
            ctx.putObject("taskPayload").put("operation","INDEX_BUILD").putObject("arguments").put("path",".");
            try(var client=new CenterHttpClient(control.url("/").toString(),"synthetic-device",true)) {
                assertTrue(execute(client,root,ctx).contains("索引完成"));
                ctx.putObject("taskPayload").put("operation","REPOSITORY_SEARCH").putObject("arguments").put("query","permission").put("mode","SEMANTIC");
                assertTrue(execute(client,root,ctx).contains("UniquePermissionService"));
            }
            assertEquals(2,model.getRequestCount());
            for(String type:List.of("INDEX_MANIFEST","SEARCH_RESULT")) {
                JsonNode payload=events.stream().filter(e->type.equals(e.path("type").asText())).findFirst().orElseThrow().path("payload");
                assertEquals(new ResourceFingerprint(root).rootFingerprint(),payload.path("rootFingerprint").asText());
                assertEquals(hash,payload.path("embeddingFingerprint").asText());
            }
        }
    }

    @ParameterizedTest @ValueSource(ints={1024,768,0})
    void embeddingProbeUsesFrozenModelAndReportsFiniteDimensionsAndActualUsage(int responseDimensions) throws Exception {
        Path root=Files.createDirectory(temp.resolve("project"));List<JsonNode> events=new CopyOnWriteArrayList<>();
        try(var model=new MockWebServer();var control=new MockWebServer()) {
            model.start();control.setDispatcher(center(events));control.start();
            ObjectNode response=CanonicalJson.MAPPER.createObjectNode().put("model","text-embedding-v4");
            var vector=response.putArray("data").addObject().putArray("embedding");
            for(int i=0;i<(responseDimensions==0?1024:responseDimensions);i++)vector.add(responseDimensions==0?0.0:0.25);
            response.putObject("usage").put("prompt_tokens",responseDimensions==1024?8:80).put("total_tokens",responseDimensions==1024?8:80);
            model.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(response.toString()));
            ObjectNode ctx=context(model,"responses",256,10000);
            String url=model.url("/compatible-mode/v1").toString();
            String fingerprint=new com.paicli.rag.EmbeddingClient("openai","text-embedding-v4",url,"",1024).getIdentity().fingerprint();
            ctx.putObject("embeddingConfig").put("id","11").put("configurationVersion",2).put("sourceOwnerId","7")
                .put("provider","openai").put("model","text-embedding-v4").put("baseUrl",url)
                .put("expectedDimensions",1024).put("configFingerprint",fingerprint);
            ctx.putObject("embeddingSecret").put("secretRef","ai.paicli.user.7.aliyun.embedding").put("secretVersion",3)
                .put("apiKey","synthetic-vector-key").put("expiresAt",Instant.now().plusSeconds(90).toString());
            ctx.putObject("taskPayload").put("operation","EMBEDDING_VERIFY").putObject("arguments")
                .put("configId","11").put("configVersion",2).put("configFingerprint",fingerprint).put("expectedDimensions",1024).put("secretVersion",3);
            try(var client=new CenterHttpClient(control.url("/").toString(),"synthetic-device",true)) {
                if(responseDimensions==1024)assertTrue(execute(client,root,ctx).contains("向量响应"));
                else assertThrows(IOException.class,()->execute(client,root,ctx));
            }
            assertEquals(1,model.getRequestCount());var request=model.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);
            assertEquals("/compatible-mode/v1/embeddings",request.getPath());
            assertEquals("Bearer synthetic-vector-key",request.getHeader("Authorization"));
            assertEquals("text-embedding-v4",CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()).path("model").asText());
            var usage=events.stream().filter(e->"USAGE".equals(e.path("type").asText())).findFirst().orElseThrow().path("payload");
            assertEquals("EMBEDDING",usage.path("capability").asText());assertEquals("text-embedding-v4",usage.path("model").asText());
            assertEquals(fingerprint,usage.path("configFingerprint").asText());assertEquals(0,usage.path("outputTokens").asInt());
            var proof=events.stream().filter(e->"CONNECTION_RESULT".equals(e.path("type").asText())).findFirst().orElseThrow().path("payload");
            assertEquals("11",proof.path("configId").asText());assertEquals(2,proof.path("configVersion").asInt());
            assertEquals(3,proof.path("secretVersion").asInt());assertEquals(fingerprint,proof.path("configFingerprint").asText());
            if(responseDimensions==1024) {
                assertEquals("READY",proof.path("status").asText());assertEquals(1024,proof.path("verification").path("dimensions").asInt());
                assertEquals(1,proof.path("verification").path("calls").asInt());assertEquals(8,usage.path("totalTokens").asInt());
            } else {
                assertEquals("FAILED",proof.path("status").asText());assertFalse(proof.has("verification"));
                assertEquals("UNCERTAIN",usage.path("status").asText());
                assertTrue(usage.path("totalTokens").asInt()>=80);
                assertEquals(80,usage.path("reportedUsage").path("inputTokens").asInt());
            }
            assertFalse(events.toString().contains("synthetic-vector-key"));
        }
    }
}
