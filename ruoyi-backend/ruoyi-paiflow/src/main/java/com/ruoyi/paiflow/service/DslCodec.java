package com.ruoyi.paiflow.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 转换执行边与节点，同时独立保全原画布；不处理身份、密钥绑定或派发。 */
public final class DslCodec {
    public static final String V1="paiflow.execution.v1",V2="paiflow.execution.v2",V3="paiflow.execution.v3",V4="paiflow.execution.v4";
    public record Normalized(String sourceFormat, JSONObject original, JSONObject execution, String executionHash) {}
    public Normalized normalize(JSONObject source) { return normalize(source,V2); }
    /** 旧不可变版本与已固定execution只做原结构转换，不能被当前编译算法重新解释。 */
    public Normalized normalizeV1(JSONObject source) { return normalize(source,V1); }
    public Normalized normalize(JSONObject source,String version) {
        boolean current=V2.equals(version) || V3.equals(version) || V4.equals(version);
        if(version!=null && !V1.equals(version) && !current)throw new IllegalArgumentException("未知执行编译格式");
        if (source == null) throw new IllegalArgumentException("工作流不能为空");
        JSONObject original = JSON.parseObject(current?source.toJSONString(JSONWriter.Feature.WriteMapNullValue):source.toJSONString());
        JSONObject graph = original.getJSONObject("flowData");
        String format = "paiflow.canvas.v1";
        if (graph == null) {
            graph = original;
            format = "paiflow.execution.v1";
        }
        JSONArray nodes = graph.getJSONArray("nodes");
        JSONArray edges = graph.getJSONArray("edges");
        if (nodes == null || edges == null) throw new IllegalArgumentException("工作流必须包含nodes与edges数组");
        JSONObject execution = new JSONObject();
        JSONArray executableNodes = new JSONArray();
        JSONArray executableEdges = new JSONArray();
        boolean canvas=original.containsKey("flowData");
        for(Object raw:nodes)if(raw instanceof JSONObject node && (node.containsKey("position") || node.containsKey("parentId") || node.containsKey("type") || node.get("data") instanceof JSONObject data && data.containsKey("label")))canvas=true;
        for(Object raw:edges)if(raw instanceof JSONObject edge && (edge.containsKey("source") || edge.containsKey("target")))canvas=true;
        for (Object item : nodes) {
            if (!(item instanceof JSONObject node)) throw new IllegalArgumentException("节点必须为对象");
            JSONObject converted = new JSONObject();
            converted.put("id", node.get("id"));
            converted.put("data", current && node.get("data") instanceof JSONObject data?CanvasExecutionCompiler.data(data,canvas):node.get("data"));
            executableNodes.add(converted);
        }
        for (Object item : edges) {
            if (!(item instanceof JSONObject edge)) throw new IllegalArgumentException("连线必须为对象");
            JSONObject converted = JSON.parseObject(current?edge.toJSONString(JSONWriter.Feature.WriteMapNullValue):edge.toJSONString());
            Object from = edge.containsKey("sourceNodeId") ? edge.get("sourceNodeId") : edge.get("source");
            Object to = edge.containsKey("targetNodeId") ? edge.get("targetNodeId") : edge.get("target");
            converted.put("sourceNodeId", from);
            converted.put("targetNodeId", to);
            if(current && edge.get("sourceHandle") instanceof String handle)converted.put("sourceHandle",CanvasExecutionCompiler.handle(handle,canvas));
            Object targetHandle=edge.get("targetHandle");
            for (String ui : List.of("source", "target", "targetHandle", "id", "type", "markerEnd", "selected", "animated", "style", "data")) converted.remove(ui);
            if(current && targetHandle!=null)converted.put("targetHandle",targetHandle);
            executableEdges.add(converted);
        }
        execution.put("nodes", executableNodes);
        execution.put("edges", executableEdges);
        // 分开复制，调用者修改execution不会损坏保全的原画布及未知字段。
        execution = JSON.parseObject(current?execution.toJSONString(JSONWriter.Feature.WriteMapNullValue):execution.toJSONString());
        return new Normalized(format, original, execution, fixedExecutionHash(execution,version));
    }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(String.valueOf(key), canonical(item)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }
    public static String fixedExecutionHash(JSONObject execution,String version) {
        try {
            byte[] data = canonicalBytes(execution,version);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少SHA-256", ex);
        }
    }
    private static byte[] canonicalBytes(JSONObject execution,String version) {
        if(version!=null && !V1.equals(version) && !V2.equals(version) && !V3.equals(version) && !V4.equals(version))throw new IllegalArgumentException("未知执行编译格式");
        String encoded=V2.equals(version) || V3.equals(version) || V4.equals(version)?JSON.toJSONString(canonical(execution),JSONWriter.Feature.WriteMapNullValue):JSON.toJSONString(canonical(execution));
        return encoded.getBytes(StandardCharsets.UTF_8);
    }
    /** Worker校验这些原始字节，禁止按另一种JSON浮点/Unicode序列化重新计算哈希。 */
    public static String canonicalDslBase64(JSONObject execution) {
        return canonicalDslBase64(execution,V2);
    }
    public static String canonicalDslBase64(JSONObject execution,String version) {return Base64.getEncoder().encodeToString(canonicalBytes(execution,version));}
}
