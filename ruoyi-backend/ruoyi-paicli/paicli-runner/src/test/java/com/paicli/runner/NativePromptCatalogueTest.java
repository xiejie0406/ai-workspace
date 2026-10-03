package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Native控制器与MCP客户端经过消费边界上报目录，再以该目录约束提示参数。 */
class NativePromptCatalogueTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path container;

    @Test void nativeCatalogueSelectsPromptAndSendsOnlyFrozenApprovedArguments() throws Exception {
        try (Fixture f = new Fixture(false, false)) {
            f.execute("MCP_START", "review", Map.of());
            assertEquals(f.definition, f.catalogue.get(0));
            f.execute("MCP_READ_PROMPT", "review", Map.of("file", "src/Demo.java", "style", "strict"));
            assertEquals(1, f.promptCalls.get());
            assertEquals("review", f.lastPromptRequest.path("name").asText());
            assertEquals("src/Demo.java", f.lastPromptRequest.path("arguments").path("file").asText());
            assertEquals("strict", f.lastPromptRequest.path("arguments").path("style").asText());
            assertEquals(2, f.receipts.get());
            assertThrows(IOException.class, () -> f.execute("MCP_READ_PROMPT", "unknown", Map.of("file", "src/Demo.java")));
            assertThrows(IOException.class, () -> f.execute("MCP_READ_PROMPT", "review", Map.of("style", "strict")));
            assertThrows(IOException.class, () -> f.execute("MCP_READ_PROMPT", "review", Map.of("file", "src/Demo.java", "undeclared", "denied")));
            assertEquals(1, f.promptCalls.get(), "未知引用、缺必填或未登记参数不能产生MCP RPC");
            assertEquals(2, f.receipts.get(), "拒绝不消费也不生成成功回执");
        }
    }

    @Test void nativeLargePromptCatalogueKeepsArgumentsAndUnknownFieldsInFullArtifact() throws Exception {
        try (Fixture f = new Fixture(true, false)) {
            f.execute("MCP_START", "review", Map.of());
            assertEquals(f.definition, f.catalogue.get(0));
            assertTrue(f.events.get(0).has("eventArtifactId"));
            assertFalse(f.events.get(0).has("prompts"), "小事件只用正式目录digest和完整产物引用");
            JsonNode digest = f.events.get(0).path("catalogueDigests").path("prompts");
            assertEquals(CanonicalJson.hash(f.catalogue), digest.path("sha256").asText());
            assertEquals(CanonicalJson.sorted(f.catalogue).toString().getBytes(StandardCharsets.UTF_8).length, digest.path("size").asLong());
            f.execute("MCP_READ_PROMPT", "review", Map.of("file", "src/Large.java"));
            assertEquals("src/Large.java", f.lastPromptRequest.path("arguments").path("file").asText());
            assertEquals(1, f.promptCalls.get());
        }
    }

    @Test void malformedNativePromptCataloguePreservesCompleteResponseAndStopsWithoutReceipt() throws Exception {
        try (Fixture f = new Fixture(false, true)) {
            assertThrows(IOException.class, () -> f.execute("MCP_START", "review", Map.of()));
            assertThrows(IOException.class, f.lastLease::ensureActive);
            assertEquals(0, f.receipts.get());
            assertTrue(f.events.isEmpty());
            assertTrue(f.lastSpool.hasUncertainExecution(f.lastLease.taskId()));
            try (var files = Files.list(f.state.resolve("pending-derived-images"))) {
                assertTrue(files.filter(Files::isRegularFile).anyMatch(path -> {
                    try { String text = Files.readString(path); return text.contains("complete actual catalogue") && text.contains("vendorExtension") && text.contains("required"); }
                    catch (IOException invalid) { throw new UncheckedIOException(invalid); }
                }), "已收到的完整目录响应必须保留，不只保留失败文案");
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path root = RunnerPrivateDirectory.createDirectories(container.resolve("workspace"));
        final Path state = RunnerPrivateDirectory.createDirectories(container.resolve("state"));
        final MockWebServer server = new MockWebServer();
        final AtomicInteger promptCalls = new AtomicInteger(), receipts = new AtomicInteger(), tasks = new AtomicInteger(), artifactIds = new AtomicInteger(90);
        final ObjectNode definition, config;
        final Map<String,JsonNode> artifacts = new HashMap<>();
        final Map<String,ByteArrayOutputStream> chunks = new HashMap<>();
        final List<JsonNode> events = new ArrayList<>();
        final CenterHttpClient center;
        final NativeControlExecutor controls;
        JsonNode catalogue = CanonicalJson.MAPPER.createArrayNode(), lastPromptRequest;
        RunnerLease lastLease; ReceiptSpool lastSpool;
        Fixture(boolean large, boolean malformed) throws Exception {
            definition = CanonicalJson.MAPPER.createObjectNode().put("name", "review").put("title", "Review").put("description", "Review source");
            definition.putArray("arguments").addObject().put("name", "file").put("description", "Source file").put("required", true).put("vendorHint", "preserve argument field");
            ((com.fasterxml.jackson.databind.node.ArrayNode)definition.get("arguments")).addObject().put("name", "style").put("required", false);
            definition.putObject("vendorExtension").put("actual", "complete actual catalogue").put("large", large ? "x".repeat(4 * 1024 * 1024 + 128) : "preserve unknown field");
            if (malformed) ((ObjectNode)definition.path("arguments").get(0)).put("required", "true");
            config = CanonicalJson.MAPPER.createObjectNode().put("id", "5").put("workspaceId", "7").put("version", 6).put("name", "prompt-fixture")
                    .put("configHash", "b".repeat(64)).put("trustedHash", "b".repeat(64)).put("transport", "STDIO").put("command", "fixture-no-process").put("enabled", true);
            config.putArray("args"); config.putObject("envSecretRefs"); config.putObject("headerSecretRefs"); config.putObject("headers");
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    ObjectNode body = (ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()); String path = request.getPath();
                    if (path.endsWith("/proposals")) {
                        boolean allowed = !body.path("toolName").asText().equals("control__MCP_READ_PROMPT") || selectedArgumentsAllowed(body.path("arguments"));
                        body.put("decision", allowed ? "PERMITTED" : "DENIED");
                        if (allowed) body.put("permitToken", "fixture-consumed-permit").put("expiresAt", Instant.now().plusSeconds(60).toString());
                        else body.put("feedback", "提示词引用或必填参数未登记");
                        return ok(body);
                    }
                    if (path.endsWith("/consume")) return ok(body.put("consumed", true));
                    if (path.endsWith("/mcp-context")) {
                        ObjectNode grant = body.deepCopy(); grant.put("expiresAt", Instant.now().plusSeconds(50).toString());
                        for (String field : List.of("envSecretRefs", "headerSecretRefs", "secretVersions", "env", "headers")) grant.putObject(field);
                        ObjectNode bound = CanonicalJson.MAPPER.createObjectNode();
                        for (String field : List.of("configId", "configVersion", "configHash", "envSecretRefs", "headerSecretRefs", "secretVersions", "taskId", "leaseId", "fencingToken", "version")) bound.set(field, grant.get(field));
                        return ok(grant.put("grantHash", CanonicalJson.hash(bound)));
                    }
                    if (path.endsWith("/chunks/begin")) { String id = Integer.toString(artifactIds.incrementAndGet()); chunks.put(id, new ByteArrayOutputStream()); return ok(body.put("uploadId", id)); }
                    if (path.endsWith("/chunks")) { String id = path.split("/")[5]; byte[] bytes = Base64.getDecoder().decode(body.path("base64").asText()); chunks.get(id).write(bytes); return ok(body.put("size", bytes.length)); }
                    if (path.endsWith("/complete")) { String id = path.split("/")[5]; byte[] bytes = chunks.get(id).toByteArray(); artifacts.put(id, CanonicalJson.MAPPER.readTree(bytes)); return ok(CanonicalJson.MAPPER.createObjectNode().put("artifactId", id).put("size", bytes.length).put("sha256", CanonicalJson.sha256(bytes))); }
                    if (path.endsWith("/tool-outputs")) { String id = Integer.toString(artifactIds.incrementAndGet()); String text = body.path("content").asText(); artifacts.put(id, CanonicalJson.MAPPER.readTree(text)); return ok(body.put("artifactId", id).put("size", text.getBytes(StandardCharsets.UTF_8).length)); }
                    if (path.endsWith("/events")) {
                        JsonNode payload = body.path("payload"); events.add(payload.deepCopy());
                        JsonNode full = payload.has("eventArtifactId") ? artifacts.get(payload.path("eventArtifactId").asText()).path("payload") : payload;
                        catalogue = full.path("prompts").deepCopy(); return ok(CanonicalJson.MAPPER.createObjectNode());
                    }
                    if (path.endsWith("/receipts")) { receipts.incrementAndGet(); return ok(body.put("accepted", true).put("acknowledged", true).put("archived", false)); }
                    throw new AssertionError("未登记中心fixture路径：" + path);
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
            }});
            server.start(); center = new CenterHttpClient(server.url("/").toString(), "fixture-only", true);
            controls = new NativeControlExecutor(center, state, null, (approved, grant, tools) -> new McpTransport() {
                Consumer<JsonNode> listener;
                public void onReceive(Consumer<JsonNode> listener) { this.listener = listener; }
                public void send(JsonNode request) {
                    if (!request.has("id")) return;
                    ObjectNode response = CanonicalJson.MAPPER.createObjectNode().put("jsonrpc", "2.0"); response.set("id", request.get("id")); ObjectNode result = response.putObject("result");
                    switch (request.path("method").asText()) {
                        case "initialize" -> result.put("protocolVersion", "2025-03-26").putObject("capabilities").putObject("prompts");
                        case "tools/list" -> result.putArray("tools");
                        case "prompts/list" -> result.putArray("prompts").add(definition);
                        case "prompts/get" -> { promptCalls.incrementAndGet(); lastPromptRequest = request.path("params").deepCopy(); result.putArray("messages").addObject().put("role", "user").putObject("content").put("type", "text").put("text", "review " + lastPromptRequest.path("arguments").path("file").asText()); }
                        default -> throw new AssertionError("未登记MCP fixture请求：" + request.path("method").asText());
                    }
                    listener.accept(response);
                }
                public String transportName() { return "synthetic-memory"; }
                public void close() { }
            });
        }
        boolean selectedArgumentsAllowed(JsonNode frozen) {
            JsonNode selected = null; for (JsonNode prompt : catalogue) if (prompt.isObject() && frozen.path("reference").asText().equals(prompt.path("name").asText())) selected = prompt;
            if (selected == null) return false; JsonNode values = frozen.path("arguments"); if (!values.isObject()) return false;
            Set<String> names = new HashSet<>(); for (JsonNode arg : selected.path("arguments")) { names.add(arg.path("name").asText()); if (arg.path("required").asBoolean() && !values.has(arg.path("name").asText())) return false; }
            for (var fields = values.fields(); fields.hasNext();) { var entry = fields.next(); if (!names.contains(entry.getKey()) || !entry.getValue().isTextual() || entry.getValue().asText().length() > 65536) return false; }
            return true;
        }
        void execute(String operation, String reference, Map<String,String> arguments) throws Exception {
            lastLease = new RunnerLease(CanonicalJson.MAPPER.createObjectNode().put("taskId", Integer.toString(tasks.incrementAndGet())).put("sessionId", "10").put("workspaceId", "7").put("leaseId", "20").put("fencingToken", 3).put("version", 4).put("policyVersion", 1).put("rootFingerprint", new ResourceFingerprint(root).rootFingerprint()).put("expiresAt", Instant.now().plusSeconds(120).toString()));
            lastSpool = new ReceiptSpool(state.resolve("receipts")); var tools = new CenterToolRegistry(center, lastLease, lastSpool, root);
            tools.setToolResultOffloader(new HttpToolResultOffloader(center, lastLease, tools, state.resolve("tool-outputs")));
            var context = CanonicalJson.MAPPER.createObjectNode(); context.putObject("workspace").put("sandboxVerified", true); context.putArray("mcpConfigs").add(config);
            var task = context.putObject("taskPayload").put("operation", operation); var args = task.putObject("arguments").put("configId", "5").put("configVersion", 6); args.set("config", config);
            if (operation.equals("MCP_READ_PROMPT")) { args.put("reference", reference); args.set("arguments", CanonicalJson.MAPPER.valueToTree(arguments)); }
            controls.execute(lastLease, tools, context);
        }
        public void close() throws Exception { controls.close(); center.close(); server.close(); }
    }
    private static MockResponse ok(JsonNode data) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(CanonicalJson.MAPPER.createObjectNode().put("code", 200).set("data", data).toString()); }
}
