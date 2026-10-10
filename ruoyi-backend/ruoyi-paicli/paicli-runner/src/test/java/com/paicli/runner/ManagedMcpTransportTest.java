package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ManagedMcpTransportTest {
    private RunnerLease lease() throws IOException { return new RunnerLease(CanonicalJson.MAPPER.createObjectNode()
            .put("taskId","1").put("directoryContextId","7").put("leaseId","2").put("fencingToken",3).put("version",4)
            .put("policyVersion",1).put("rootFingerprint","a".repeat(64)).put("expiresAt",Instant.now().plusSeconds(90).toString())); }
    private ObjectNode config() { var c=CanonicalJson.MAPPER.createObjectNode().put("id","5").put("directoryContextId","7").put("version",6)
            .put("configHash","b".repeat(64)).put("trustedHash","b".repeat(64)).put("transport","STDIO"); c.putObject("envSecretRefs").put("SERVICE_TOKEN","ai.paicli.7.service");c.putObject("headerSecretRefs"); return c; }
    private ObjectNode response(RunnerLease lease) { var c=config();var r=lease.identity();r.put("configId","5").put("configVersion",6).put("configHash","b".repeat(64));
        r.set("envSecretRefs",c.get("envSecretRefs"));r.set("headerSecretRefs",c.get("headerSecretRefs"));r.putObject("secretVersions").put("env.SERVICE_TOKEN",1);
        r.putObject("env").put("SERVICE_TOKEN","fixture-service-secret");r.putObject("headers");r.put("expiresAt",Instant.now().plusSeconds(50).toString());
        var bound=CanonicalJson.MAPPER.createObjectNode();for(String f:List.of("configId","configVersion","configHash","envSecretRefs","headerSecretRefs","secretVersions","taskId","leaseId","fencingToken","version"))bound.set(f,r.get(f));r.put("grantHash",CanonicalJson.hash(bound));return r; }
    private MockResponse ok(JsonNode r) { return new MockResponse().setHeader("Content-Type","application/json").setBody("{\"code\":200,\"data\":"+r+"}"); }
    @Test void incompleteTransportStopsLeaseAndCannotRetryUnknownSideEffect() throws Exception {
        var lease=lease();var sent=new AtomicInteger();var stopped=new AtomicInteger();
        try(var server=new MockWebServer()) {server.enqueue(ok(response(lease)));server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                var grant=new ManagedMcpGrant(center,lease,()->lease.identity().put("toolCallId","call").put("permitToken","permit"),config(),stopped::incrementAndGet)) {
                McpTransport raw=new McpTransport() {public void send(JsonNode value)throws IOException {sent.incrementAndGet();throw new IOException("broken fixture-service-secret");}public void onReceive(Consumer<JsonNode> l){}public void close(){}};
                var transport=new ManagedMcpTransport(raw,grant);
                IOException failure=assertThrows(IOException.class,()->transport.send(CanonicalJson.MAPPER.createObjectNode()));
                assertFalse(failure.getMessage().contains("fixture-service-secret"));assertThrows(IOException.class,lease::ensureActive);
                assertThrows(IOException.class,()->transport.send(CanonicalJson.MAPPER.createObjectNode()));assertEquals(1,sent.get());assertEquals(1,stopped.get());assertEquals(1,server.getRequestCount());
            }
        }
    }
    @Test void managedValuesCannotLeakInReturnedTextOrStderr() throws Exception {
        var lease=lease();
        try(var server=new MockWebServer()) {server.enqueue(ok(response(lease)));server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);
                var grant=new ManagedMcpGrant(center,lease,()->lease.identity().put("toolCallId","call").put("permitToken","permit"),config(),()->{})) {
                class Raw implements McpTransport {Consumer<JsonNode> listener;public void send(JsonNode m){}public void onReceive(Consumer<JsonNode> l){listener=l;}public List<String> stderrLines(){return List.of("fixture-service-secret");}public void close(){}}
                var raw=new Raw();var transport=new ManagedMcpTransport(raw,grant);var observed=new java.util.ArrayList<JsonNode>();transport.onReceive(observed::add);transport.send(CanonicalJson.MAPPER.createObjectNode());
                raw.listener.accept(CanonicalJson.MAPPER.createObjectNode().put("id",1).put("text","fixture-service-secret"));
                assertEquals(1,observed.get(0).path("id").asInt());assertFalse(observed.get(0).toString().contains("fixture-service-secret"));assertFalse(transport.stderrLines().toString().contains("fixture-service-secret"));transport.close();
            }
        }
    }
    @Test void objectKeysAreRedactedAndCollisionsStopInsteadOfOverwritingEvidence() throws Exception {
        callbackFixture((raw, observed, lease) -> {
            raw.accept(CanonicalJson.MAPPER.createObjectNode().put("id",2).set("result",CanonicalJson.MAPPER.createObjectNode().put("fixture-service-secret","value")));
            assertFalse(observed.get(0).toString().contains("fixture-service-secret"));
            var collision=CanonicalJson.MAPPER.createObjectNode().put("id",3);collision.putObject("result").put("fixture-service-secret",1).put("*",2);
            raw.accept(collision);assertEquals(-32098,observed.get(1).path("error").path("code").asInt());assertThrows(IOException.class,lease::ensureActive);
        });
    }
    @Test void contentAndImageCardinalityRejectBeforeDeliveryWithoutTruncatingPrefix() throws Exception {
        callbackFixture((raw, observed, lease) -> {
            var many=CanonicalJson.MAPPER.createObjectNode().put("id",4);var content=many.putObject("result").putArray("content");for(int i=0;i<129;i++)content.addObject().put("type","text").put("text","valid-prefix");
            raw.accept(many);assertEquals(-32098,observed.get(0).path("error").path("code").asInt());
            assertFalse(observed.get(0).has("result")); assertFalse(observed.get(0).path("error").path("message").asText().contains("valid-prefix"));
            var evidence=CanonicalJson.MAPPER.readTree(observed.get(0).path("error").path("data").path("mcpIncompleteEvidence").asText());
            assertEquals(129,evidence.path("result").path("content").size(),"内部待对账证据保留全部块，不能把合法前缀当普通result");
            assertThrows(IOException.class,lease::ensureActive);
        });
        callbackFixture((raw, observed, lease) -> {
            var many=CanonicalJson.MAPPER.createObjectNode().put("id",5);var content=many.putObject("result").putArray("content");for(int i=0;i<11;i++)content.addObject().put("type","image").put("data","fixture");
            raw.accept(many);assertEquals(-32098,observed.get(0).path("error").path("code").asInt());assertThrows(IOException.class,lease::ensureActive);
        });
    }
    @Test void discoveryMetadataIsBoundedBeforeCachingAndTypedResultsKeepSeparateLimits() throws Exception {
        assertThrows(IOException.class,()->ManagedMcpTransport.requireMetadata(java.util.Map.of("description","x".repeat(ManagedMcpTransport.MAX_METADATA_BYTES))));
        assertEquals("x",ManagedMcpTransport.requireMetadata("x"));
        callbackFixture((raw, observed, lease) -> {
            var response=CanonicalJson.MAPPER.createObjectNode().put("id",7);response.putObject("result").put("description","x".repeat(ManagedMcpTransport.MAX_METADATA_BYTES));raw.accept(response);
            assertEquals(-32098,observed.get(0).path("error").path("code").asInt());assertThrows(IOException.class,lease::ensureActive);
        },true);
    }
    @Test void invalidImageStopsLeaseInsteadOfBecomingSuccessfulFallbackText() throws Exception {
        var lease = lease();
        try (var server = new MockWebServer()) {
            server.start();
            try (var center = new CenterHttpClient(server.url("/").toString(), "fixture", true);
                 var grant = new ManagedMcpGrant(center, lease, () -> lease.identity().put("toolCallId", "call").put("permitToken", "permit"), config(), () -> {})) {
                var invalid = new com.paicli.mcp.protocol.McpCallToolResult(
                        List.of(new com.paicli.mcp.protocol.McpContent("image", null, "invalid!", "image/png")), false);
                var saved = new java.util.ArrayList<com.paicli.tool.ToolOutput>();
                assertThrows(IOException.class, () -> ManagedMcpTransport.strictOutput(invalid, grant, saved::add));
                assertEquals(1, saved.size()); assertTrue(saved.get(0).text().contains("invalid!"));
                assertThrows(IOException.class, lease::ensureActive);
                assertEquals(0, server.getRequestCount());
            }
        }
    }
    @Test void singleCharacterSecretCannotTurnContentFieldIntoSuccessfulEmptyResult() throws Exception {
        var lease = lease();
        try (var server = new MockWebServer()) {
            var granted = response(lease); ((ObjectNode) granted.path("env")).put("SERVICE_TOKEN", "c");
            server.enqueue(ok(granted)); server.start();
            try (var center = new CenterHttpClient(server.url("/").toString(), "fixture", true);
                 var grant = new ManagedMcpGrant(center, lease, () -> lease.identity().put("toolCallId", "call").put("permitToken", "permit"), config(), () -> {})) {
                class Raw implements McpTransport {
                    Consumer<JsonNode> callback; public void send(JsonNode n) {} public void onReceive(Consumer<JsonNode> value) { callback = value; } public void close() {}
                }
                var raw = new Raw(); var observed = new java.util.ArrayList<JsonNode>();
                var transport = new ManagedMcpTransport(raw, grant); transport.onReceive(observed::add);
                transport.send(CanonicalJson.MAPPER.createObjectNode());
                var completed = CanonicalJson.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1);
                var blocks = completed.putObject("result").putArray("content");
                blocks.addObject().put("type", "text").put("text", "first result");
                blocks.addObject().put("type", "text").put("text", "second result c");
                raw.callback.accept(completed);
                assertEquals(-32098, observed.get(0).path("error").path("code").asInt());
                assertFalse(observed.get(0).has("result")); assertThrows(IOException.class, lease::ensureActive);
                String evidence = observed.get(0).path("error").path("data").path("mcpIncompleteEvidence").asText();
                var preserved = CanonicalJson.MAPPER.readTree(evidence).path("result").path("content");
                assertEquals(2, preserved.size()); assertEquals("first result", preserved.get(0).path("text").asText());
                assertEquals("se*ond result *", preserved.get(1).path("text").asText());
                transport.close();
            }
        }
    }
    @FunctionalInterface private interface Callback {void run(Consumer<JsonNode> incoming,List<JsonNode> observed,RunnerLease lease)throws Exception;}
    private void callbackFixture(Callback check)throws Exception {
        callbackFixture(check,false);
    }
    private void callbackFixture(Callback check,boolean discovery)throws Exception {
        var lease=lease();try(var server=new MockWebServer()){server.enqueue(ok(response(lease)));server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"fixture",true);var grant=new ManagedMcpGrant(center,lease,()->lease.identity().put("toolCallId","call").put("permitToken","permit"),config(),()->{})) {
                class Raw implements McpTransport{Consumer<JsonNode> callback;public void send(JsonNode n){}public void onReceive(Consumer<JsonNode> l){callback=l;}public void close(){}}
                var raw=new Raw();var transport=new ManagedMcpTransport(raw,grant);var observed=new java.util.ArrayList<JsonNode>();transport.onReceive(observed::add);var request=CanonicalJson.MAPPER.createObjectNode();if(discovery)request.put("id",7).put("method","tools/list");transport.send(request);check.run(raw.callback,observed,lease);transport.close();
            }
        }
    }
}
