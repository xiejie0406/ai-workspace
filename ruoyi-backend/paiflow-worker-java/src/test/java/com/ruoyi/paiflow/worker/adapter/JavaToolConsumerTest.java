package com.ruoyi.paiflow.worker.adapter;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iflytek.astron.workflow.engine.*;
import com.iflytek.astron.workflow.engine.domain.*;
import com.iflytek.astron.workflow.engine.domain.chain.*;
import com.iflytek.astron.workflow.engine.integration.plugins.PluginServiceClient;
import com.iflytek.astron.workflow.engine.integration.plugins.aitools.AiToolsIntegration;
import com.iflytek.astron.workflow.engine.node.impl.*;
import com.iflytek.astron.workflow.engine.node.impl.plugin.PluginNodeExecutor;
import com.iflytek.astron.workflow.engine.node.NodeExecutor;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.*;
import com.ruoyi.paiflow.worker.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 原Java执行器和实际HttpCenterClient；Exchange仅为离线中心回执，不代表当前Java已获中心准入。 */
class JavaToolConsumerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String RESOURCE = "tool:1:version:2:operation:ZWNobw";
    private static final String OPERATION = "tool-op:e34337c2-0a20-4b15-8eba-5148f71e72f2";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void actualOriginalPluginGraphKeepsBfsFourLocationsDefaultsNestedBodyAndOutputs() throws Exception {
        Fixture fixture = new Fixture();
        PluginNodeExecutor plugin = plugin();
        List<NodeExecutor> executors = List.of(new StartNodeExecutor(), plugin, new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(executors);
        try (WorkerTaskRunner runner = new WorkerTaskRunner(fixture.client, new WorkflowEngine(executors), parallel, CLOCK, 30, 60, mapper)) {
            Result result = runner.execute(new Lease("java-run", "java-lease", 1));
            assertEquals("COMPLETED", result.state());
            assertEquals("结果：原Java工具结果", result.output().get("node-end::3") instanceof Map<?, ?> output ? output.get("content") : null);
            assertEquals(1, fixture.starts.get());
            assertEquals(1, fixture.begins.get());
            JsonNode request = fixture.startRequests.getFirst();
            assertTrue(request.has("iterationId") && request.get("iterationId").isNull());
            JsonNode message = request.path("parameters").path("legacyPayload").path("payload").path("message");
            assertEquals(mapper.readTree("{\"hBiz\":\"BFS第一项\"}"), decode(message, "header"));
            assertEquals(mapper.readTree("{\"q\":\"原查询默认\"}"), decode(message, "query"));
            assertEquals(mapper.readTree("{\"route\":\"原透传路径\"}"), decode(message, "path"));
            assertEquals(mapper.readTree("{\"optional\":\"空输入默认\",\"flag\":false,\"count\":0,\"nested\":{\"leaf\":\"实际嵌套\"}}"), decode(message, "body"));
            assertFalse(fixture.events.isEmpty());
            assertTrue(fixture.events.stream().anyMatch(event -> "plugin::2".equals(event.path("nodeId").asText())));
            String export = System.getProperty("paiflow.worker.toolGoldenPath");
            if (export != null) {
                ObjectNode golden = mapper.createObjectNode();
                golden.put("scope", "实际原Java START/PLUGIN/END与HttpCenterClient；离线Exchange，无Java中心准入/真实Provider");
                golden.set("assignment", mapper.valueToTree(fixture.assignment));
                golden.set("actualRequests", mapper.valueToTree(fixture.requests));
                golden.set("actualResponses", mapper.valueToTree(fixture.responses));
                golden.set("actualStartRequest", request);
                golden.set("actualEvents", mapper.valueToTree(fixture.events));
                golden.set("actualResult", mapper.valueToTree(result));
                Files.writeString(Path.of(export), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(golden), StandardCharsets.UTF_8);
            }
            assertOriginalFrames(fixture.events);
        } finally { parallel.close(); }
    }

    private void assertOriginalFrames(List<JsonNode> events) {
        long sequence=0;
        for(JsonNode event:events) {
            assertEquals(++sequence,event.path("seq").asLong());
            assertFalse("flow_obj".equals(event.path("nodeId").asText()));
            if(event.path("eventType").asText().startsWith("node."))assertFalse(event.path("payload").has("legacyFrame"),"生命周期仍仅原Map事实");
        }
        for(String nodeId:List.of("node-start::1","plugin::2","node-end::3")) {
            assertEquals(1,events.stream().filter(e->nodeId.equals(e.path("nodeId").asText())&&"node.started".equals(e.path("eventType").asText())).count());
            assertEquals(1,events.stream().filter(e->nodeId.equals(e.path("nodeId").asText())&&"node.completed".equals(e.path("eventType").asText())).count());
            List<JsonNode> frames=events.stream().filter(e->nodeId.equals(e.path("nodeId").asText())&&e.path("payload").has("legacyFrame")).map(e->e.path("payload").path("legacyFrame")).toList();
            assertEquals(2,frames.size(),"原节点开始/完成帧即使没有文本也保留："+nodeId);
            JsonNode start=frames.getFirst(),end=frames.getLast();
            assertEquals(nodeId,start.path("workflow_step").path("node").path("id").asText());
            assertTrue(start.path("workflow_step").path("node").has("finish_reason"));assertTrue(start.path("workflow_step").path("node").get("finish_reason").isNull());
            assertTrue(start.has("usage")&&start.get("usage").isNull());assertTrue(start.has("event_data")&&start.get("event_data").isNull());
            assertEquals("stop",end.path("workflow_step").path("node").path("finish_reason").asText());
            assertTrue(end.has("id"));assertTrue(end.has("choices"));
        }
    }

    @Test
    void runningPollsOnlyOneRegisteredOperationAndRepeatUsesSameTerminalReceipt() throws Exception {
        Fixture fixture = new Fixture(); fixture.pollsRemaining = 2;
        try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
            scope.bind();
            Map<String, Object> first = call(fixture);
            Map<String, Object> second = call(fixture);
            assertEquals(first, second);
            assertEquals(1, fixture.starts.get());
            assertEquals(2, fixture.statuses.get());
            assertFalse(scope.hasUnconfirmedEffect());
            assertTrue(fixture.requests.stream().filter(item -> item.path("path").asText().endsWith("/status"))
                    .allMatch(item -> item.path("path").asText().contains("tool-op%3A")));
        }
    }

    @Test
    void originalJavaBfsKeepsRootMatchIncludingNullInsteadOfOverwritingFromNestedList() throws Exception {
        List<ObjectNode> samples = new ArrayList<>();
        for (Object rootValue : new Object[]{"根命中", null}) {
            Fixture fixture = new Fixture(rootValue, true);
            try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
                scope.bind(); Map<String, Object> output = call(fixture);
                JsonNode request = fixture.startRequests.getFirst();
                JsonNode headers = decode(request.path("parameters").path("legacyPayload").path("payload").path("message"), "header");
                if (rootValue == null) assertFalse(headers.has("hBiz")); else assertEquals(rootValue, headers.path("hBiz").asText());
                ObjectNode sample = mapper.createObjectNode();
                sample.put("scope", "原Java AiToolsIntegration/Link/Tool直接调用；离线Exchange，无中心Java准入");
                sample.set("assignment", mapper.valueToTree(fixture.assignment)); sample.set("actualInputs", mapper.valueToTree(fixture.assignment.input()));
                sample.set("actualOperationRequest", request); sample.set("actualOutput", mapper.valueToTree(output));
                sample.set("actualResponses", mapper.valueToTree(fixture.responses));
                sample.put("source", "src/main/java/com/iflytek/astron/workflow/engine/integration/plugins/aitools/AiToolsIntegration.java#getBusinessInput; Tool.java#assembleParameters/assembleBody/dumps");
                samples.add(sample);
            }
        }
        String export = System.getProperty("paiflow.worker.toolGoldenPath");
        if (export != null) Files.writeString(Path.of(export).resolveSibling("original-java-tool-bfs-golden.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(samples), StandardCharsets.UTF_8);
    }

    @Test
    void originalPluginKeepsUndeclaredAndMismatchedOutputsAndJavaArrayIndexReference() throws Exception {
        Fixture fixture = new Fixture();
        fixture.outputJson = "{\"answer\":\"原Java工具结果\",\"count\":\"原字符串\",\"extra\":\"未声明输出\",\"nullable\":null,\"data\":[{\"x\":\"数组索引实际值\"}]}";
        Map<String, Object> dsl = mapper.convertValue(fixture.assignment.dsl(), Map.class);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) dsl.get("nodes");
        Map<String, Object> toolData = (Map<String, Object>) nodes.get(1).get("data");
        toolData.put("outputs", List.of(Map.of("name", "count", "schema", Map.of("type", "integer", "default", 9), "required", true),
                Map.of("name", "missing", "schema", Map.of("type", "string", "default", "原声明默认"), "required", true)));
        Map<String, Object> endData = (Map<String, Object>) nodes.get(2).get("data");
        endData.put("inputs", List.of(Map.of("name", "answer", "schema", Map.of("type", "string", "value", Map.of("type", "ref", "content", Map.of("nodeId", "plugin::2", "name", "data[0].x")))),
                Map.of("name", "missing", "schema", Map.of("type", "string", "value", Map.of("type", "ref", "content", Map.of("nodeId", "plugin::2", "name", "missing"))))));
        fixture.assignment = task(dsl, fixture.assignment.input(), fixture.assignment.resourceSnapshots());
        List<NodeExecutor> executors = List.of(new StartNodeExecutor(), plugin(), new EndNodeExecutor());
        ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(executors);
        try (WorkerTaskRunner runner = new WorkerTaskRunner(fixture.client, new WorkflowEngine(executors), parallel, CLOCK, 30, 60, mapper)) {
            Result result = runner.execute(new Lease("java-run", "java-lease", 1));
            assertEquals("COMPLETED", result.state());
            assertEquals("结果：数组索引实际值", ((Map<?, ?>) result.output().get("node-end::3")).get("content"));
            JsonNode original = fixture.responses.stream().filter(response -> response.path("data").has("legacyResponse")).findFirst().orElseThrow()
                    .path("data").path("legacyResponse").path("payload").path("text").path("text");
            JsonNode decoded = mapper.readTree(original.asText());
            assertEquals("原字符串", decoded.path("count").asText()); assertTrue(decoded.has("extra")); assertFalse(decoded.has("missing"));
            String export = System.getProperty("paiflow.worker.toolGoldenPath");
            if (export != null) {
                ObjectNode sample = mapper.createObjectNode(); sample.put("scope", "原Java Plugin返回整Map/原VariablePool数组索引；离线Exchange");
                sample.set("assignment", mapper.valueToTree(fixture.assignment)); sample.set("actualRequests", mapper.valueToTree(fixture.requests));
                sample.set("actualResponses", mapper.valueToTree(fixture.responses)); sample.set("actualEvents", mapper.valueToTree(fixture.events));
                sample.set("actualResult", mapper.valueToTree(result));
                Files.writeString(Path.of(export).resolveSibling("original-java-tool-output-reference-golden.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample), StandardCharsets.UTF_8);
            }
        } finally { parallel.close(); }
    }

    @Test
    void providerFailureKeepsOriginalCodeMessageSidAndConfirmedTerminal() throws Exception {
        Fixture fixture = new Fixture(); fixture.failure = "provider";
        try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
            scope.bind();
            Exception failure = assertThrows(Exception.class, () -> call(fixture));
            assertTrue(failure.getMessage().contains("30202"));
            assertTrue(failure.getMessage().contains("原供应商错误"));
            assertTrue(failure.getMessage().contains("original-sid"));
            assertFalse(scope.hasUnconfirmedEffect());
            assertEquals(1, fixture.starts.get());
        }
    }

    @Test
    void lostStartAckAndUnknownReadStayStickyWithoutSecondStartOrInventedCancel() throws Exception {
        for (boolean lostAck : List.of(false, true)) {
            Fixture fixture = new Fixture(); fixture.lostStart = lostAck; fixture.failure = "transport";
            try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
                scope.bind();
                assertEquals("EFFECT_UNKNOWN", assertThrows(WorkerException.class, () -> call(fixture)).getCode());
                assertTrue(scope.hasUnconfirmedEffect());
                assertEquals("EFFECT_UNKNOWN", assertThrows(WorkerException.class, () -> call(fixture)).getCode());
                assertEquals(1, fixture.starts.get());
                scope.cancel(); scope.stopPendingOperations();
                assertTrue(scope.hasUnconfirmedEffect());
                assertEquals(lostAck ? 0 : 1, fixture.cancels.get());
            }
        }
    }

    @Test
    void cancellationReadsExactOperationAndRequiresRealStoppedProof() throws Exception {
        for (boolean proof : List.of(false, true)) {
            Fixture fixture = new Fixture(); fixture.pollsRemaining = 1;
            try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
                scope.bind(); fixture.cancelScope = scope; fixture.stopProof = proof;
                assertEquals("RUN_CANCELLED", assertThrows(WorkerException.class, () -> call(fixture)).getCode());
                assertTrue(scope.hasUnconfirmedEffect());
                scope.stopPendingOperations();
                assertEquals(!proof, scope.hasUnconfirmedEffect());
                assertEquals(1, fixture.starts.get());
                assertEquals(1, fixture.cancels.get());
            }
        }
    }

    @Test
    void wrongScopeOrMalformedReceiptCannotClearPendingOrPermitAnotherNode() throws Exception {
        for (String mutation : List.of("leaseId", "workflowVersionId", "nodeId", "attempt", "extraField")) {
            Fixture fixture = new Fixture(); fixture.mutation = mutation;
            try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
                scope.bind();
                assertEquals("EFFECT_UNKNOWN", assertThrows(WorkerException.class, () -> call(fixture)).getCode());
                assertTrue(scope.hasUnconfirmedEffect());
                assertEquals("EFFECT_UNKNOWN", assertThrows(WorkerException.class, scope::check).getCode());
                assertEquals(1, fixture.starts.get());
            }
        }
    }

    @Test
    void cancelReadsAnAlreadySettledOperationWithoutInventingStopProofOrChildExit() throws Exception {
        for (String terminal : List.of("COMPLETED", "FAILED")) {
            Fixture fixture = new Fixture(); fixture.pollsRemaining = 1; fixture.cancelTerminal = terminal;
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            try (ExecutionScope scope = new ExecutionScope(fixture.client, fixture.assignment, CLOCK)) {
                scope.bind(); fixture.cancelScope = scope;
                Thread child = new Thread(ExecutionScope.wrap(() -> {
                    entered.countDown();
                    boolean waiting = true;
                    while (waiting) { try { release.await(); waiting = false; } catch (InterruptedException ignored) {} }
                }));
                child.setDaemon(true); child.start();
                try {
                    assertTrue(entered.await(1, TimeUnit.SECONDS));
                    assertEquals("RUN_CANCELLED", assertThrows(WorkerException.class, () -> call(fixture)).getCode());
                    scope.stopPendingOperations();
                    assertFalse(scope.hasUnconfirmedEffect(), "完整绑定的既有终态结算本操作");
                    JsonNode receipt = fixture.responses.get(fixture.responses.size() - 1).path("data");
                    assertEquals(terminal, receipt.path("state").asText());
                    assertFalse(receipt.path("stopped").asBoolean()); assertTrue(receipt.path("proofId").isNull());
                    assertFalse(scope.awaitChildrenStopped(java.time.Duration.ofMillis(20)), "账本终态不能代替真实子活动退出");
                    assertEquals(1, fixture.starts.get()); assertEquals(1, fixture.cancels.get());
                } finally { release.countDown(); child.join(1000); }
                assertTrue(scope.awaitChildrenStopped(java.time.Duration.ofSeconds(1)));
            } finally { release.countDown(); }
        }
    }

    @Test
    void unboundToolAndTtsAreRejectedBeforeBeginEngineEventsOrProvider() throws Exception {
        for (boolean tts : List.of(false, true)) {
            Fixture fixture = new Fixture();
            var raw = mapper.convertValue(fixture.assignment.dsl(), Map.class);
            var nodes = (List<Map<String, Object>>) raw.get("nodes");
            var parameter = (Map<String, Object>) ((Map<?, ?>) nodes.get(1).get("data")).get("nodeParam");
            if (tts) parameter.put("pluginId", "tool@8b2262bef821000"); else parameter.remove("toolResourceId");
            fixture.assignment = task(raw, fixture.assignment.input(), fixture.assignment.resourceSnapshots());
            PluginNodeExecutor plugin = plugin();
            List<NodeExecutor> executors = List.of(new StartNodeExecutor(), plugin, new EndNodeExecutor());
            ParallelWorkflowEngine parallel = new ParallelWorkflowEngine(executors);
            try (WorkerTaskRunner runner = new WorkerTaskRunner(fixture.client, new WorkflowEngine(executors), parallel, CLOCK, 30, 60, mapper)) {
                assertThrows(WorkerException.class, () -> runner.execute(new Lease("java-run", "java-lease", 1)));
                assertEquals(0, fixture.begins.get()); assertEquals(0, fixture.starts.get()); assertTrue(fixture.events.isEmpty());
            } finally { parallel.close(); }
        }
    }

    private Map<String, Object> call(Fixture fixture) throws Exception {
        Node node = mapper.convertValue(((List<?>) fixture.assignment.dsl().get("nodes")).get(1), Node.class);
        return new AiToolsIntegration().call(new NodeState(node, new VariablePool(), null), fixture.assignment.input());
    }

    private PluginNodeExecutor plugin() {
        PluginServiceClient service = new PluginServiceClient();
        ReflectionTestUtils.setField(service, "aiToolsIntegration", new AiToolsIntegration());
        ReflectionTestUtils.setField(service, "smartTTSIntegration", List.of());
        PluginNodeExecutor plugin = new PluginNodeExecutor(); ReflectionTestUtils.setField(plugin, "pluginServiceClient", service); return plugin;
    }

    private JsonNode decode(JsonNode message, String name) throws Exception {
        return mapper.readTree(Base64.getDecoder().decode(message.path(name).asText()));
    }

    private Assignment task(Map<String, Object> dsl, Map<String, Object> input, Map<String, Map<String, Object>> resources) throws Exception {
        byte[] canonical = mapper.writeValueAsBytes(dsl);
        return new Assignment("paiflow.worker.v1", "java-run", "java-version", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical)),
                Base64.getEncoder().encodeToString(canonical), "java", input, resources, 1, "java-lease", CLOCK.instant().plusSeconds(60),
                new EffectPolicy("read_only", List.of("tools.read"), null), dsl, 0);
    }

    private final class Fixture {
        Assignment assignment;
        final AtomicInteger begins = new AtomicInteger(), starts = new AtomicInteger(), statuses = new AtomicInteger(), cancels = new AtomicInteger();
        final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>()), responses = Collections.synchronizedList(new ArrayList<>()),
                events = Collections.synchronizedList(new ArrayList<>()), startRequests = Collections.synchronizedList(new ArrayList<>());
        final HttpCenterClient client;
        int pollsRemaining; String failure, mutation, cancelTerminal; boolean lostStart, stopProof; ExecutionScope cancelScope;
        String outputJson = "{\"answer\":\"原Java工具结果\",\"count\":0,\"flag\":false}";
        JsonNode registered;
        Fixture() throws Exception { this(null, false); }
        Fixture(Object rootBusiness, boolean includeRoot) throws Exception {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("h", null); input.put("optional", null); input.put("flag", false); input.put("count", 0);
            input.put("nested", Map.of("leaf", "实际嵌套"));
            input.put("outer", List.of(Map.of("hBiz", "BFS第一项", "route", "原透传路径"), Map.of("hBiz", "BFS第二项")));
            if (includeRoot) input.put("hBiz", rootBusiness);
            Map<String, Object> schema = mapper.readValue("""
                {"openapi":"3.0.3","paths":{"/echo":{"post":{"operationId":"echo","parameters":[
                  {"name":"h","in":"header","schema":{"type":"string","x-from":0,"default":"默认头"}},
                  {"name":"hBiz","in":"header","schema":{"type":"string","x-from":1}},
                  {"name":"q","in":"query","schema":{"type":"string","default":"原查询默认"}},
                  {"name":"route","in":"path","schema":{"type":"string","x-from":1}}],
                  "requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{
                    "optional":{"type":"string","default":"空输入默认"},"flag":{"type":"boolean","default":true},
                    "count":{"type":"integer","default":8},"nested":{"type":"object","properties":{"leaf":{"type":"string"}}}}}}}}}}}}
                """, Map.class);
            Map<String, Object> resource = new LinkedHashMap<>(Map.of("kind", "tool", "provider", "spark-link", "version", "2", "enabled", true,
                    "pluginId", "original-tool", "operationId", "echo", "legacyVersion", "V1.0", "legacyAppId", "original-app", "effectClass", "read", "openApiSchema", schema));
            Map<String, Object> operationSchema = (Map<String, Object>) ((Map<?, ?>) ((Map<?, ?>) schema.get("paths")).get("/echo")).get("post");
            operationSchema.put("responses", mapper.readValue("""
                {"200":{"description":"原工具JSON对象输出","content":{"application/json":{"schema":{"type":"object","required":["answer"],"properties":{
                  "answer":{"type":"string"},"count":{"anyOf":[{"type":"integer"},{"type":"string"}]},"flag":{"type":"boolean"},
                  "extra":{"type":"string"},"nullable":{"anyOf":[{"type":"string"},{"type":"null"}]},
                  "data":{"type":"array","items":{"type":"object","properties":{"x":{"type":"string"}}}}}}}}}}
                """, Map.class));
            Node start = node("node-start::1", Map.of());
            List<InputItem> startInputs = new ArrayList<>(); List<OutputItem> startOutputs = new ArrayList<>();
            for (String name : input.keySet()) {
                String type = "flag".equals(name) ? "boolean" : "count".equals(name) ? "integer" : "outer".equals(name) ? "array" : "nested".equals(name) ? "object" : "string";
                InputItem item = new InputItem(); item.setName(name); item.setSchema(new InputSchema(type, new Value("literal", input.get(name)))); startInputs.add(item);
                startOutputs.add(new OutputItem(name, name, Map.of("type", type), false));
            }
            start.getData().setInputs(startInputs); start.getData().setOutputs(startOutputs);
            Node tool = node("plugin::2", Map.of("pluginId", "original-tool", "operationId", "echo", "appId", "original-app", "toolResourceId", RESOURCE,
                    "businessInput", List.of("hBiz", "route")));
            List<InputItem> inputs = new ArrayList<>();
            for (String name : input.keySet()) {
                InputItem item = new InputItem(); item.setName(name); item.setSchema(new InputSchema("object", new Value("ref", Map.of("nodeId", start.getId(), "name", name)))); inputs.add(item);
            }
            tool.getData().setInputs(inputs);
            Node end = node("node-end::3", Map.of("outputMode", 1, "template", "结果：{{answer}}"));
            InputItem answer = new InputItem(); answer.setName("answer"); answer.setSchema(new InputSchema("string", new Value("ref", Map.of("nodeId", tool.getId(), "name", "answer"))));
            end.getData().setInputs(List.of(answer));
            WorkflowDSL dsl = new WorkflowDSL(); dsl.setFlowId("original-java-flow"); dsl.setUuid("java-chat"); dsl.setNodes(List.of(start, tool, end));
            dsl.setEdges(List.of(new Edge(start.getId(), tool.getId(), null), new Edge(tool.getId(), end.getId(), null)));
            Map<String, Object> rawDsl = mapper.convertValue(dsl, Map.class);
            // 固定执行图只携带原定义，Node.getNodeType与运行状态是引擎派生信息，不能作为画布字段往返。
            for (Object rawNode : (List<?>) rawDsl.get("nodes")) {
                Map<?, ?> rawNodeMap = (Map<?, ?>) rawNode; rawNodeMap.keySet().retainAll(Set.of("id", "data"));
                for (Object rawInput : (List<?>) ((Map<?, ?>) rawNodeMap.get("data")).get("inputs")) {
                    Map<?, ?> rawValue = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) rawInput).get("schema")).get("value");
                    rawValue.keySet().retainAll(Set.of("type", "content"));
                }
            }
            for (Object rawEdge : (List<?>) rawDsl.get("edges")) ((Map<?, ?>) rawEdge).keySet().retainAll(Set.of("sourceNodeId", "targetNodeId", "sourceHandle"));
            assignment = task(rawDsl, input, Map.of(RESOURCE, resource));
            client = new HttpCenterClient(URI.create("https://center.invalid"), "offline-java-worker", mapper, this::exchange);
        }

        private HttpCenterClient.WireResponse exchange(HttpRequest request) throws IOException {
            try {
                JsonNode body = mapper.readTree(requestBody(request)); String path = request.uri().getRawPath();
                ObjectNode capture = mapper.createObjectNode().put("path", path); capture.set("body", body); requests.add(capture);
                ObjectNode data;
                if (path.endsWith("/lease/verify")) {
                    data = mapper.createObjectNode().put("valid", true).put("cancelRequested", false).put("dslHash", assignment.dslHash())
                            .put("workflowVersionId", assignment.workflowVersionId()).put("lastEventSeq", 0);
                    data.set("assignment", mapper.valueToTree(assignment));
                } else if (path.endsWith("/execution/begin")) {
                    begins.incrementAndGet(); data = mapper.createObjectNode().put("begun", true).put("runId", assignment.runId()).put("leaseId", assignment.leaseId())
                            .put("attempt", 1).put("workflowVersionId", assignment.workflowVersionId()).put("dslHash", assignment.dslHash());
                } else if (path.endsWith("/heartbeat")) data = mapper.createObjectNode().put("valid", true).put("cancelRequested", false);
                else if (path.contains("/resources/")) data = mapper.valueToTree(assignment.resourceSnapshots().get(RESOURCE));
                else if (path.endsWith("/events")) { events.add(body); data = mapper.createObjectNode().put("accepted", true).put("seq", body.path("seq").asLong()); }
                else if (path.endsWith("/result")) data = mapper.createObjectNode().put("accepted", true).put("state", body.path("state").asText());
                else if (path.endsWith("/operations")) {
                    starts.incrementAndGet(); registered = body; startRequests.add(body);
                    if (lostStart) throw new IOException("真实序列化后隔离模拟start回执丢失");
                    data = ack(pollsRemaining > 0 ? "RUNNING" : failure == null ? "COMPLETED" : "provider".equals(failure) ? "FAILED" : "EFFECT_UNKNOWN");
                    if (mutation != null) { if ("attempt".equals(mutation)) data.put("attempt", "1"); else data.put(mutation, "wrong-binding"); }
                } else if (path.endsWith("/status")) {
                    statuses.incrementAndGet(); pollsRemaining--;
                    if (cancelScope != null) { cancelScope.cancel(); data = ack("RUNNING"); }
                    else data = ack(pollsRemaining > 0 ? "RUNNING" : "COMPLETED");
                } else if (path.endsWith("/cancel")) {
                    cancels.incrementAndGet(); failure = "FAILED".equals(cancelTerminal) ? "provider" : "cancelled";
                    data = ack(cancelTerminal != null ? cancelTerminal : stopProof ? "CANCELLED" : "EFFECT_UNKNOWN");
                    if (stopProof) data.put("stopped", true).put("proofId", "not-dispatched:" + OPERATION);
                } else throw new AssertionError("不允许旧控制面或供应商调用：" + path);
                ObjectNode envelope = mapper.createObjectNode().put("code", 200); envelope.set("data", data); responses.add(envelope);
                return new HttpCenterClient.WireResponse(200, mapper.writeValueAsBytes(envelope));
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException(error); }
        }

        private ObjectNode ack(String state) throws Exception {
            ObjectNode ack = mapper.createObjectNode().put("runId", assignment.runId()).put("leaseId", assignment.leaseId()).put("attempt", 1)
                    .put("workflowVersionId", assignment.workflowVersionId()).put("dslHash", assignment.dslHash()).put("nodeId", "plugin::2")
                    .putNull("iterationId").put("idempotencyKey", registered.path("idempotencyKey").asText()).put("operationId", OPERATION)
                    .put("operation", "tools.read").put("kind", "tool").put("provider", "spark-link").put("resourceId", RESOURCE).put("resourceVersion", "2")
                    .put("state", state).put("stopped", false).putNull("proofId");
            ack.set("resourceBindings", mapper.createObjectNode());
            if ("RUNNING".equals(state) || "COMPLETED".equals(state)) ack.putNull("failureKind"); else ack.put("failureKind", failure);
            if ("COMPLETED".equals(state)) ack.set("legacyResponse", mapper.valueToTree(Map.of("header", Map.of("code", 0, "sid", "original-sid"),
                    "payload", Map.of("text", Map.of("text", outputJson)))));
            else if ("provider".equals(failure)) ack.set("legacyResponse", mapper.readTree("{\"header\":{\"code\":30202,\"message\":\"原供应商错误\",\"sid\":\"original-sid\"}}"));
            else ack.putNull("legacyResponse");
            return ack;
        }
    }

    private static Node node(String id, Map<String, Object> params) {
        Node node = new Node(); node.setId(id); NodeData data = new NodeData(); data.setNodeMeta(new NodeMeta(id.split("::")[0], id)); data.setNodeParam(params); node.setData(data); return node;
    }

    private static byte[] requestBody(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer value) { byte[] chunk = new byte[value.remaining()]; value.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get(5, TimeUnit.SECONDS);
    }
}
