package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class NativeEvidenceTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    private ObjectNode claim(Path workspace) throws IOException {
        return CanonicalJson.MAPPER.createObjectNode().put("taskId", "1").put("sessionId", "2").put("leaseId", "3").put("fencingToken", 4)
                .put("version", 1).put("policyVersion", 1).put("rootFingerprint", new ResourceFingerprint(workspace).rootFingerprint()).put("expiresAt", Instant.now().plusSeconds(120).toString());
    }
    private static MockResponse ok(JsonNode value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":200,\"data\":" + value + "}"); }
    private ObjectNode context() {
        ObjectNode ctx = CanonicalJson.MAPPER.createObjectNode().put("provider", "deepseek").put("model", "fixture");
        ctx.putObject("providerConfig").put("id", 1).put("version", 1); ctx.putObject("modelConfig").put("id", 2).put("version", 1);
        ctx.putObject("managedSecret").put("apiKey", "old-fixture-key").put("secretRef", "fixture-ref").put("expiresAt", Instant.now().plusSeconds(1).toString()); return ctx;
    }
    @Test void expiringGrantRefreshesOnlyFrozenConfigurationAndRejectsChanges() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            ObjectNode fresh = context(); fresh.with("managedSecret").put("apiKey", "new-fixture-key").put("expiresAt", Instant.now().plusSeconds(90).toString());
            server.enqueue(ok(fresh)); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "fixture", true)) {
                ManagedProviderGrant grant = new ManagedProviderGrant(center, new RunnerLease(claim(temp)), context(), "managedSecret");
                assertEquals("new-fixture-key", grant.currentKey()); assertEquals("new-fixture-key", grant.currentKey());
                assertEquals("/paicli/runner/v1/provider-context", server.takeRequest().getPath()); assertEquals(1, server.getRequestCount());
                ObjectNode changed = fresh.deepCopy(); changed.put("model", "changed"); server.enqueue(ok(changed));
                RunnerLease lease = new RunnerLease(claim(temp));
                assertThrows(IOException.class, () -> new ManagedProviderGrant(center, lease, context(), "managedSecret").currentKey());
                assertThrows(IOException.class, lease::ensureActive);
            }
        }
    }
    @Test void outputUpload503KeepsFullPrivateEvidenceAndDoesNotWriteWorkspace() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace")); Path state = Files.createDirectory(temp.resolve("state"));
        String raw = "完整中文工具输出\n".repeat(5000);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) {
                try {
                    ObjectNode value = (ObjectNode) CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                    if (request.getPath().endsWith("/proposals")) { value.put("decision", "PERMITTED").put("permitToken", "fixture-permit").put("expiresAt", Instant.now().plusSeconds(90).toString()); return ok(value); }
                    if (request.getPath().endsWith("/consume")) { value.put("consumed", true); return ok(value); }
                    if (request.getPath().endsWith("/tool-outputs")) return new MockResponse().setResponseCode(503).setBody("unavailable");
                    if (request.getPath().endsWith("/receipts")) { value.put("accepted", true).put("acknowledged", true).put("archived", false); return ok(value); }
                    return ok(CanonicalJson.MAPPER.createObjectNode());
                } catch (IOException failure) { throw new IllegalStateException(failure); }
            }}); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "fixture", true)) {
                RunnerLease lease = new RunnerLease(claim(workspace));
                CenterToolRegistry tools = new CenterToolRegistry(center, lease, new ReceiptSpool(state.resolve("receipts")), workspace);
                tools.setToolResultOffloader(new HttpToolResultOffloader(center, lease, tools, state.resolve("tool-outputs")));
                tools.executeControl("SKILLS_RELOAD", "{}", () -> ToolOutput.text(raw));
                try (var files = Files.list(state.resolve("pending-derived-outputs"))) {
                    Path content = files.filter(file -> file.toString().endsWith(".txt")).findFirst().orElseThrow();
                    assertEquals(raw, Files.readString(content));
                    ObjectNode metadata = (ObjectNode) CanonicalJson.MAPPER.readTree(Files.readString(Path.of(content + ".json")));
                    assertEquals(CanonicalJson.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)), metadata.path("sha256").asText());
                    assertFalse(metadata.has("permitToken"));
                }
                assertThrows(IOException.class, lease::ensureActive);
                try (var files = Files.list(workspace)) { assertEquals(0, files.count()); }
            }
        }
    }
}
