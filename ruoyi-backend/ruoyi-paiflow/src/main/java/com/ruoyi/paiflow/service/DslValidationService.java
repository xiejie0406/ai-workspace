package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.paiflow.domain.NodeCatalog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 中心结构与引擎兼容预检；资源授权和每类节点参数验证由后续资源/节点契约层追加。 */
public final class DslValidationService {
    public record Issue(String nodeId, String field, String code, String message) {}
    public record Report(boolean valid, String engineProfile, List<Issue> issues) {}
    public Report validate(JSONObject source, String engine) {
        List<Issue> issues = new ArrayList<>();
        if (!NodeCatalog.ENGINES.contains(engine == null ? "" : engine)) {
            issues.add(new Issue(null, "engineProfile", "UNKNOWN_ENGINE", "请选择java或python引擎"));
            return new Report(false, engine, List.copyOf(issues));
        }
        JSONObject graph;
        try { graph = new DslCodec().normalize(source).execution(); }
        catch (RuntimeException ex) {
            issues.add(new Issue(null, "workflow", "INVALID_FORMAT", "工作流必须包含有效的nodes与edges对象数组"));
            return new Report(false, engine, List.copyOf(issues));
        }
        JSONArray nodes = graph.getJSONArray("nodes");
        JSONArray edges = graph.getJSONArray("edges");
        Set<String> ids = new HashSet<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        List<String> startIds = new ArrayList<>();
        List<String> endIds = new ArrayList<>();
        if (nodes.isEmpty()) issues.add(new Issue(null, "nodes", "EMPTY_GRAPH", "工作流至少包含开始与结束节点"));
        for (Object item : nodes) {
            JSONObject node = (JSONObject) item;
            String id = node.getString("id");
            if (id == null || id.isBlank()) { issues.add(new Issue(id, "id", "MISSING_ID", "节点ID不能为空")); continue; }
            if (!ids.add(id)) issues.add(new Issue(id, "id", "DUPLICATE_ID", "节点ID重复"));
            outgoing.putIfAbsent(id, new ArrayList<>());
            indegree.putIfAbsent(id, 0);
            NodeCatalog.Entry type = NodeCatalog.find(NodeCatalog.typeOf(id));
            if (type == null) issues.add(new Issue(id, "id", "UNKNOWN_NODE", "未知节点类型，保全原字段但禁止运行"));
            else if (!type.engines().contains(engine)) issues.add(new Issue(id, "engineProfile", "UNSUPPORTED_NODE", "所选引擎不支持" + type.label()));
            if (NodeCatalog.typeOf(id).equals("node-start")) startIds.add(id);
            if (NodeCatalog.typeOf(id).equals("node-end")) endIds.add(id);
            if (!(node.get("data") instanceof JSONObject)) issues.add(new Issue(id, "data", "MISSING_DATA", "节点data必须为对象"));
        }
        if (startIds.size() != 1) issues.add(new Issue(null, "nodes", "START_COUNT", "主画布必须有一个开始节点"));
        if (endIds.isEmpty()) issues.add(new Issue(null, "nodes", "MISSING_END", "主画布缺少结束节点"));
        for (Object item : edges) {
            JSONObject edge = (JSONObject) item;
            String from = edge.getString("sourceNodeId");
            String to = edge.getString("targetNodeId");
            if (!ids.contains(from) || !ids.contains(to)) issues.add(new Issue(from, "edges", "DANGLING_EDGE", "连线指向不存在的节点"));
            else {
                outgoing.get(from).add(to);
                indegree.put(to, indegree.get(to) + 1);
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove(); visited++;
            for (String next : outgoing.get(id)) if (indegree.merge(next, -1, Integer::sum) == 0) queue.add(next);
        }
        if (visited != ids.size()) issues.add(new Issue(null, "edges", "CYCLE", "普通连线存在循环；迭代应使用迭代节点"));
        return new Report(issues.isEmpty(), engine, List.copyOf(issues));
    }
}
