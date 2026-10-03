package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DslCompatibilityTest {
    @Test void canonicalBytesMatchTheSharedCrossLanguageFixture() throws Exception {
        var source = JSON.parseObject(java.nio.file.Files.readString(
            java.nio.file.Path.of("../../contracts/paiflow/fixtures/dsl-canonical.json"), StandardCharsets.UTF_8));
        var normalized = new DslCodec().normalizeV1(source.getJSONObject("dsl"));
        assertEquals(source.getString("dslHash"), normalized.executionHash());
        String bytes = DslCodec.canonicalDslBase64(normalized.execution(),DslCodec.V1);
        assertEquals(source.getString("dslCanonicalBase64"), bytes);
        assertEquals(normalized.execution(), JSON.parseObject(new String(java.util.Base64.getDecoder().decode(bytes), StandardCharsets.UTF_8)));
        normalized.execution().getJSONArray("nodes").getJSONObject(0).getJSONObject("data").put("tiny", 2.0E-7);
        assertNotEquals(bytes, DslCodec.canonicalDslBase64(normalized.execution(),DslCodec.V1));
    }
    private JSONObject fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input, name);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Test void catalogMatchesIndependentPythonRegistryAndJavaExecutors() throws Exception {
        JSONObject source = fixture("source-node-catalog.json");
        var actual = new HashSet<String>();
        var java = new HashSet<String>();
        NodeCatalog.entries().forEach(e -> { actual.add(e.type()); if (e.engines().contains("java")) java.add(e.type()); });
        assertEquals(new HashSet<>(source.getList("types", String.class)), actual);
        assertEquals(new HashSet<>(source.getList("javaSupported", String.class)), java);
        assertEquals(21, NodeCatalog.entries().size());
    }
    @Test void originalPodcastPreservesWholeCanvasAndConvertsEveryEdge() throws Exception {
        JSONObject source = fixture("podcast-original.json");
        String before = source.toJSONString();
        var converted = new DslCodec().normalizeV1(source);
        assertEquals(source, converted.original());
        assertEquals(before, source.toJSONString());
        JSONArray originalNodes = source.getJSONObject("flowData").getJSONArray("nodes");
        JSONArray runtimeNodes = converted.execution().getJSONArray("nodes");
        assertEquals(originalNodes.size(), runtimeNodes.size());
        for (int i = 0; i < originalNodes.size(); i++) {
            assertEquals(originalNodes.getJSONObject(i).get("id"), runtimeNodes.getJSONObject(i).get("id"));
            assertEquals(originalNodes.getJSONObject(i).get("data"), runtimeNodes.getJSONObject(i).get("data"));
        }
        JSONArray oldEdges = source.getJSONObject("flowData").getJSONArray("edges");
        JSONArray newEdges = converted.execution().getJSONArray("edges");
        assertEquals(oldEdges.size(), newEdges.size());
        for (int i = 0; i < oldEdges.size(); i++) {
            assertEquals(oldEdges.getJSONObject(i).get("source"), newEdges.getJSONObject(i).get("sourceNodeId"));
            assertEquals(oldEdges.getJSONObject(i).get("target"), newEdges.getJSONObject(i).get("targetNodeId"));
            assertEquals(oldEdges.getJSONObject(i).get("sourceHandle"), newEdges.getJSONObject(i).get("sourceHandle"));
        }
        assertTrue(new DslValidationService().validate(source, "python").valid());
        assertTrue(new DslValidationService().validate(source, "java").valid());
        runtimeNodes.getJSONObject(0).getJSONObject("data").put("changed", true);
        assertFalse(converted.original().getJSONObject("flowData").getJSONArray("nodes").getJSONObject(0).getJSONObject("data").containsKey("changed"));
    }
    @Test void layoutChangeDoesNotChangeExecutionHashAndUnknownFieldsAreRetained() throws Exception {
        JSONObject source = fixture("podcast-original.json");
        var first = new DslCodec().normalize(source);
        source.put("futureExtension", new JSONObject().fluentPut("note", "保留"));
        source.getJSONObject("flowData").getJSONArray("nodes").getJSONObject(0).put("position", new JSONObject().fluentPut("x", 999).fluentPut("y", 555));
        var second = new DslCodec().normalize(source);
        assertEquals(first.executionHash(), second.executionHash());
        assertEquals("保留", second.original().getJSONObject("futureExtension").getString("note"));
        assertEquals(999, second.original().getJSONObject("flowData").getJSONArray("nodes").getJSONObject(0).getJSONObject("position").getIntValue("x"));
    }
    @Test void incompatibleNodesMalformedEdgesAndCyclesAreRejectedBeforeDispatch() {
        var node = new JSONObject().fluentPut("id", "rpa::x").fluentPut("data", new JSONObject());
        var graph = new JSONObject().fluentPut("nodes", JSONArray.of(node)).fluentPut("edges", new JSONArray());
        var service = new DslValidationService();
        assertTrue(service.validate(graph, "java").issues().stream().anyMatch(i -> i.code().equals("UNSUPPORTED_NODE")));
        node.put("id", "future-node::x");
        assertTrue(service.validate(graph, "python").issues().stream().anyMatch(i -> i.code().equals("UNKNOWN_NODE")));
        node.put("id", "node-start::x");
        graph.put("edges", JSONArray.of(new JSONObject().fluentPut("sourceNodeId", "node-start::x").fluentPut("targetNodeId", "missing::y")));
        assertTrue(service.validate(graph, "python").issues().stream().anyMatch(i -> i.code().equals("DANGLING_EDGE")));
        graph.put("edges", JSONArray.of(new JSONObject().fluentPut("sourceNodeId", "node-start::x").fluentPut("targetNodeId", "node-start::x")));
        assertTrue(service.validate(graph, "python").issues().stream().anyMatch(i -> i.code().equals("CYCLE")));
        assertFalse(service.validate(graph, "other").valid());
        assertFalse(service.validate(null, "python").valid());
    }
    @Test void missingStartAndEndDuplicateIdsAndScalarNodesCannotPassPrecheck() {
        var service = new DslValidationService();
        var node = new JSONObject().fluentPut("id", "plugin::x").fluentPut("data", new JSONObject());
        var graph = new JSONObject().fluentPut("nodes", JSONArray.of(node, node)).fluentPut("edges", new JSONArray());
        var report = service.validate(graph, "python");
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("START_COUNT")));
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("MISSING_END")));
        assertTrue(report.issues().stream().anyMatch(i -> i.code().equals("DUPLICATE_ID")));
        graph.put("nodes", JSONArray.of("not a node"));
        assertTrue(service.validate(graph, "python").issues().stream().anyMatch(i -> i.code().equals("INVALID_FORMAT")));
    }
}
