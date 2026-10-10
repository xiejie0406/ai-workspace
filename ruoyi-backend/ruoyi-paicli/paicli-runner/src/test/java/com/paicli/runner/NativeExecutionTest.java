package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolOutput;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeExecutionTest {
    @TempDir(factory=RunnerPrivateTempDirFactory.class) Path temp;
    private final java.util.List<Path> ownStates=new java.util.ArrayList<>();
    private Path state(String label) {Path value=temp.resolveSibling(temp.getFileName()+"-"+label);ownStates.add(value);return value;}
    @org.junit.jupiter.api.AfterEach void removeOnlyOwnPrivateStates()throws IOException {
        for(Path state:ownStates)if(java.nio.file.Files.exists(state,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            assertEquals(temp.getParent(),state.getParent());assertTrue(state.getFileName().toString().startsWith(temp.getFileName()+"-"));
            try(var nodes=java.nio.file.Files.walk(state)){for(Path node:nodes.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if(java.nio.file.Files.isRegularFile(node,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    var dos=java.nio.file.Files.getFileAttributeView(node,java.nio.file.attribute.DosFileAttributeView.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if(dos!=null)dos.setReadOnly(false); // JGit只读对象属于本测试的私有快照，恢复后清理。
                }
                java.nio.file.Files.delete(node);
            }}
        }
    }
    private ObjectNode claim() throws IOException {
        ObjectNode result = CanonicalJson.MAPPER.createObjectNode();
        result.put("taskId", "1"); result.put("sessionId", "2"); result.put("leaseId", "3"); result.put("fencingToken", 4);
        result.put("version", 1); result.put("policyVersion", 1); result.put("rootFingerprint", new ResourceFingerprint(temp).rootFingerprint());
        result.put("expiresAt", Instant.now().plusSeconds(120).toString()); return result;
    }
    private static MockResponse ok(JsonNode value) { return new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":200,\"data\":" + value + "}"); }
    @Test void controlTaskDoesNotRequireTextOrCreateModelAndUsesPermitReceipt() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger(), proposals = new AtomicInteger(), receipts = new AtomicInteger();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                try {
                    ObjectNode value = (ObjectNode) CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                    if (request.getPath().endsWith("/context")) { ObjectNode context = CanonicalJson.MAPPER.createObjectNode().put("mode", "CONTROL"); ObjectNode task = context.putObject("taskPayload"); task.put("operation", "SKILLS_RELOAD"); task.putObject("arguments"); return ok(context); }
                    if (request.getPath().endsWith("/proposals")) { proposals.incrementAndGet(); assertEquals("control__SKILLS_RELOAD", value.path("toolName").asText()); value.put("decision", "PERMITTED"); value.put("permitToken", "permit"); value.put("expiresAt", Instant.now().plusSeconds(90).toString()); return ok(value); }
                    if (request.getPath().endsWith("/consume")) { value.put("consumed", true); return ok(value); }
                    if (request.getPath().endsWith("/receipts")) { receipts.incrementAndGet(); value.put("accepted", true); value.put("acknowledged", true); value.put("archived", false); return ok(value); }
                    return ok(CanonicalJson.MAPPER.createObjectNode());
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }}); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                RunnerLease lease = new RunnerLease(claim());
                Path state=state("control"); CenterToolRegistry tools = new CenterToolRegistry(center, lease, new ReceiptSpool(state.resolve("receipts")), temp);
                NativeTaskExecutor nativeTask = new NativeTaskExecutor(center, state, context -> { modelCalls.incrementAndGet(); throw new AssertionError("CONTROL cannot call LLM"); });
                assertTrue(nativeTask.execute(lease, tools).contains("中心批准技能"));
                assertEquals(0, modelCalls.get()); assertEquals(1, proposals.get()); assertEquals(1, receipts.get());
            }
        }
    }
    @Test void skillMustMatchApprovedVersionAndCompleteContentHash() throws Exception {
        String content = "---\nname: review\ndescription: review code\n---\n仅检查代码。\n";
        ObjectNode context = CanonicalJson.MAPPER.createObjectNode();
        ObjectNode skill = context.putArray("skillsConfigs").addObject();
        String sha = CanonicalJson.sha256(content.getBytes(StandardCharsets.UTF_8));
        skill.put("name", "review"); skill.put("content", content); skill.put("sha256", sha); skill.put("trustHash", sha); skill.put("version", 1); skill.put("enabled", true); skill.put("manifestApproved", true);
        assertEquals(1, NativeTaskExecutor.approvedSkills(context).allSkills().size());
        skill.put("content", content + "隐藏的新指令"); assertThrows(IOException.class, () -> NativeTaskExecutor.approvedSkills(context));
        skill.put("content", content); skill.put("manifestApproved", false); assertThrows(IOException.class, () -> NativeTaskExecutor.approvedSkills(context));
    }
    @Test void controlEventFailureRetainsActualPayloadAndNeverAcknowledgesFailedSubstitute() throws Exception {
        var receipts=new AtomicInteger(); var events=new AtomicInteger();
        try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
                var body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/context")) {
                    var context=CanonicalJson.MAPPER.createObjectNode().put("mode","CONTROL");context.putObject("taskPayload").put("operation","SKILLS_RELOAD").putObject("arguments");
                    String content="---\nname: retained-skill\ndescription: 实际批准技能\n---\n仅检查代码。\n";String sha=CanonicalJson.sha256(content.getBytes(StandardCharsets.UTF_8));
                    context.putArray("skillsConfigs").addObject().put("name","retained-skill").put("content",content).put("sha256",sha).put("trustHash",sha).put("version",1).put("enabled",true).put("manifestApproved",true);return ok(context);
                }
                if(request.getPath().endsWith("/proposals")){body.put("decision","PERMITTED").put("permitToken","permit").put("expiresAt",Instant.now().plusSeconds(90).toString());return ok(body);}
                if(request.getPath().endsWith("/consume")){body.put("consumed",true);return ok(body);}
                if(request.getPath().endsWith("/events")){events.incrementAndGet();return new MockResponse().setResponseCode(503);}
                if(request.getPath().endsWith("/receipts")){receipts.incrementAndGet();throw new AssertionError("不确定事件不能FAILED收据获得ACK");}
                return ok(CanonicalJson.MAPPER.createObjectNode());
            }catch(IOException failure){throw new IllegalStateException(failure);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"device",true)) {
                var lease=new RunnerLease(claim());var state=state("control-evidence");var spool=new ReceiptSpool(state.resolve("receipts"));
                var tools=new CenterToolRegistry(center,lease,spool,temp);var executor=new NativeTaskExecutor(center,state,ignored->{throw new AssertionError("CONTROL不能调用模型");});
                assertThrows(IOException.class,()->executor.execute(lease,tools));assertEquals(1,events.get());assertEquals(0,receipts.get());assertTrue(spool.hasUncertainExecution("1"));
                try(var files=java.nio.file.Files.list(state.resolve("pending-derived-images"))) {
                    var evidence=files.map(path->{try{return java.nio.file.Files.readString(path);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).toList();
                    assertTrue(evidence.stream().anyMatch(value->value.contains("retained-skill")&&value.contains("CONNECTION_RESULT")));assertFalse(evidence.stream().anyMatch(value->value.contains("permitToken")));
                }
            }
        }
    }
    @Test void streamedUtf8RetainsPartialCharactersAt4096ByteBoundary() throws Exception {
        String expected = "a".repeat(4095) + "中文输出😀结束"; StringBuilder observed = new StringBuilder();
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                try { observed.append(CanonicalJson.MAPPER.readTree(request.getBody().readUtf8()).path("payload").path("delta").asText()); return ok(CanonicalJson.MAPPER.createObjectNode()); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }}); server.start();
            try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                NativeTaskExecutor executor = new NativeTaskExecutor(center, context -> null);
                Class<?> outputType = java.util.Arrays.stream(NativeTaskExecutor.class.getDeclaredClasses()).filter(type -> type.getSimpleName().equals("EventOutput")).findFirst().orElseThrow();
                var constructor = outputType.getDeclaredConstructor(NativeTaskExecutor.class, RunnerLease.class); constructor.setAccessible(true);
                try (OutputStream output = (OutputStream) constructor.newInstance(executor, new RunnerLease(claim()))) { output.write(expected.getBytes(StandardCharsets.UTF_8)); }
                assertEquals(expected, observed.toString()); assertFalse(observed.toString().contains("�"));
            }
        }
    }
    @Test void leaseInvalidatedAfterExecutionStillRetainsPayloadBeforeEvidenceGuard() throws Exception {
        var posted=new AtomicInteger();try(var server=new MockWebServer()) {
            server.setDispatcher(new Dispatcher(){public MockResponse dispatch(RecordedRequest request){try {
                var body=(ObjectNode)CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if(request.getPath().endsWith("/proposals")){body.put("decision","PERMITTED").put("permitToken","permit").put("expiresAt",Instant.now().plusSeconds(90).toString());return ok(body);}
                if(request.getPath().endsWith("/consume")){body.put("consumed",true);return ok(body);}
                posted.incrementAndGet();return ok(body);
            }catch(IOException failure){throw new IllegalStateException(failure);}}});server.start();
            try(var center=new CenterHttpClient(server.url("/").toString(),"device",true)) {
                var lease=new RunnerLease(claim());var state=state("expired-result");var spool=new ReceiptSpool(state.resolve("receipts"));var tools=new CenterToolRegistry(center,lease,spool,temp);
                var result=tools.executeControl("SKILLS_RELOAD","{}",()->{
                    var payload=CanonicalJson.MAPPER.createObjectNode().put("kind","SKILL").put("actualResult","retained-after-rpc");lease.invalidate();
                    try {NativeControlExecutor.sendControlEvent(center,lease,tools,"CONNECTION_RESULT",payload);throw new AssertionError();}catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
                });
                assertFalse(result.successful());assertEquals(0,posted.get());assertTrue(spool.hasUncertainExecution("1"));
                try(var files=java.nio.file.Files.list(state.resolve("pending-derived-images"))){assertTrue(files.anyMatch(path->{try{return java.nio.file.Files.readString(path).contains("retained-after-rpc");}catch(IOException e){throw new java.io.UncheckedIOException(e);}}));}
            }
        }
    }
    @Test void browserProbeUsesApprovedEndpointPortAndRejectsRemoteHost() throws Exception {
        assertEquals(9333, NativeControlExecutor.browserPort(CanonicalJson.MAPPER.readTree("{\"endpoint\":\"http://127.0.0.1:9333\"}")));
        assertThrows(IOException.class, () -> NativeControlExecutor.browserPort(CanonicalJson.MAPPER.readTree("{\"endpoint\":\"https://example.com:9333\"}")));
    }
    @Test void incompleteMcpStructureRetainsAllSafeRpcBlocksInPrivateEvidenceWithoutReceipt() throws Exception {
        var receipts = new AtomicInteger();
        var config = CanonicalJson.MAPPER.createObjectNode().put("id", "5").put("directoryContextId", "7").put("version", 6)
                .put("configHash", "b".repeat(64)).put("trustedHash", "b".repeat(64)).put("transport", "STDIO");
        config.putObject("envSecretRefs").put("SERVICE_TOKEN", "ai.paicli.7.service"); config.putObject("headerSecretRefs");
        try (var server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() { public MockResponse dispatch(RecordedRequest request) { try {
                var body = (ObjectNode) CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                if (request.getPath().endsWith("/proposals")) { body.put("decision", "PERMITTED").put("permitToken", "permit").put("expiresAt", Instant.now().plusSeconds(90).toString()); return ok(body); }
                if (request.getPath().endsWith("/consume")) { body.put("consumed", true); return ok(body); }
                if (request.getPath().endsWith("/mcp-context")) {
                    body.set("envSecretRefs", config.get("envSecretRefs")); body.set("headerSecretRefs", config.get("headerSecretRefs"));
                    body.putObject("secretVersions").put("env.SERVICE_TOKEN", 1); body.putObject("env").put("SERVICE_TOKEN", "c"); body.putObject("headers");
                    body.put("expiresAt", Instant.now().plusSeconds(50).toString()); var bound = CanonicalJson.MAPPER.createObjectNode();
                    for (String field : java.util.List.of("configId", "configVersion", "configHash", "envSecretRefs", "headerSecretRefs", "secretVersions", "taskId", "leaseId", "fencingToken", "version")) bound.set(field, body.get(field));
                    body.put("grantHash", CanonicalJson.hash(bound)); return ok(body);
                }
                if (request.getPath().endsWith("/receipts")) receipts.incrementAndGet(); return ok(body);
            } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); } } }); server.start();
            try (var center = new CenterHttpClient(server.url("/").toString(), "fixture", true)) {
                var lease = new RunnerLease(claim().put("directoryContextId", "7")); Path state = state("mcp-structure"); var spool = new ReceiptSpool(state.resolve("receipts"));
                var tools = new CenterToolRegistry(center, lease, spool, temp);
                var result = tools.executeControl("MCP_READ_RESOURCE", "{}", () -> {
                    class Raw implements com.paicli.mcp.transport.McpTransport {
                        java.util.function.Consumer<JsonNode> callback; public void onReceive(java.util.function.Consumer<JsonNode> value) { callback = value; } public void close() {}
                        public void send(JsonNode request) {
                            var completed = CanonicalJson.MAPPER.createObjectNode().put("jsonrpc", "2.0"); completed.set("id", request.get("id"));
                            var content = completed.putObject("result").putArray("content");
                            for (String text : java.util.List.of("first-result", "other-result", "credential c")) content.addObject().put("type", "text").put("text", text);
                            callback.accept(completed);
                        }
                    }
                    try (var grant = new ManagedMcpGrant(center, lease, tools, config, () -> {});
                         var rpc = new com.paicli.mcp.jsonrpc.JsonRpcClient(new ManagedMcpTransport(new Raw(), grant))) {
                        rpc.request("tools/call", CanonicalJson.MAPPER.createObjectNode()); throw new AssertionError("must stop");
                    } catch (com.paicli.mcp.jsonrpc.JsonRpcException incomplete) {
                        try { ManagedMcpTransport.preserveIncompleteException(incomplete, tools); }
                        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                        throw new java.io.UncheckedIOException(new IOException("MCP_RESULT_INCOMPLETE"));
                    } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
                assertFalse(result.successful()); assertEquals(0, receipts.get()); assertTrue(spool.hasUncertainExecution("1"));
                try (var files = java.nio.file.Files.list(state.resolve("pending-derived-images"))) {
                    assertTrue(files.anyMatch(path -> { try {
                        String saved = java.nio.file.Files.readString(path);
                        return saved.contains("first-result") && saved.contains("other-result") && saved.contains("*redential *") && !saved.contains("credential c");
                    } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); } }));
                }
            }
        }
    }
    @Test void threeNativePathsRestoreCenterHistoryAndPlanTeamWaitForApproval() throws Exception {
        for (String mode : java.util.List.of("react", "plan", "team")) {
            AtomicInteger calls = new AtomicInteger(), reviews = new AtomicInteger(), finalPlanSteps = new AtomicInteger();
            java.util.List<java.util.List<com.paicli.llm.LlmClient.Message>> requests = new java.util.ArrayList<>();
            java.util.Queue<String> answers = new java.util.ArrayDeque<>();
            if (mode.equals("plan")) answers.add("{\"summary\":\"检查\",\"tasks\":[{\"id\":\"t1\",\"description\":\"完成检查\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}");
            if (mode.equals("team")) answers.add("{\"summary\":\"检查\",\"steps\":[{\"id\":\"t1\",\"description\":\"完成检查\",\"type\":\"ANALYSIS\",\"dependencies\":[]}]}");
            answers.add("检查完成"); if (mode.equals("team")) answers.add("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}");
            com.paicli.llm.LlmClient client = new com.paicli.llm.LlmClient() {
                public ChatResponse chat(java.util.List<Message> messages, java.util.List<Tool> tools) throws IOException { return chat(messages, tools, StreamListener.NO_OP); }
                public ChatResponse chat(java.util.List<Message> messages, java.util.List<Tool> tools, StreamListener listener) {
                    calls.incrementAndGet(); requests.add(java.util.List.copyOf(messages));
                    return new ChatResponse("assistant", answers.remove(), null, null, 10, 5);
                }
                public String getModelName() { return "fixture"; } public String getProviderName() { return "fixture"; }
            };
            try (MockWebServer server = new MockWebServer()) {
                server.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        ObjectNode body = (ObjectNode) CanonicalJson.MAPPER.readTree(request.getBody().readUtf8());
                        if (request.getPath().endsWith("/context")) {
                            ObjectNode context = CanonicalJson.MAPPER.createObjectNode().put("mode", mode).put("text", "本轮检查");
                            context.putObject("policy").put("taskTokenBudget", 100000).put("dailyRemainingTokenBudget", 100000).put("maxOutputTokens", 100);
                            context.putArray("history").addObject().put("role", "user").put("content", "历史约束:使用中文");
                            context.withArray("history").addObject().put("role", "assistant").put("content", "已确认历史约束");
                            context.withArray("history").addObject().put("role", "user").put("content", "本轮检查");
                            return ok(context);
                        }
                        if (request.getPath().endsWith("/plans/review")) {
                            assertEquals(1, calls.get(), "计划正式批准前不能开始任何worker模型或工具");
                            return ok(CanonicalJson.MAPPER.createObjectNode().put("decision", reviews.incrementAndGet() == 1 ? "pending" : "execute").put("planVersion", 2));
                        }
                        if (request.getPath().endsWith("/memory")) return ok(CanonicalJson.MAPPER.createObjectNode().putArray("entries"));
                        if ("PLAN_STEP".equals(body.path("type").asText()) && "COMPLETED".equals(body.path("payload").path("status").asText())) finalPlanSteps.incrementAndGet();
                        return ok(CanonicalJson.MAPPER.createObjectNode());
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }}); server.start();
                try (CenterHttpClient center = new CenterHttpClient(server.url("/").toString(), "device", true)) {
                    RunnerLease lease = new RunnerLease(claim());
                    Path state=state(mode); CenterToolRegistry tools = new CenterToolRegistry(center, lease, new ReceiptSpool(state.resolve("receipts")), temp);
                    String result = new NativeTaskExecutor(center, state, ignored -> client).execute(lease, tools);
                    assertTrue(result.contains("完成")); assertTrue(answers.isEmpty());
                    assertTrue(requests.stream().flatMap(java.util.List::stream).anyMatch(message -> message.content() != null && message.content().contains("历史约束:使用中文")));
                    if (mode.equals("react")) assertEquals(1, requests.get(0).stream().filter(message -> "user".equals(message.role()) && "本轮检查".equals(message.content())).count());
                    else { assertEquals(2, reviews.get()); assertEquals(1, finalPlanSteps.get()); }
                }
            }
        }
    }
}
