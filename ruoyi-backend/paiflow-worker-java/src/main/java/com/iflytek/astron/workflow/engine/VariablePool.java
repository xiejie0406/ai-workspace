package com.iflytek.astron.workflow.engine;

import cn.hutool.core.util.ClassUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fixme 这里需要调整为实例级别，全局共享会存在OOM风险
 * <p>
 * Variable pool for managing node outputs and resolving template references
 * Handles variable references in format: {{node-id.output-name}}
 * <p>
 * Example:
 * - Template: "User said: {{node-start::001.user_input}}"
 * - After resolution: "User said: 介绍一下 Java"
 */
@Slf4j
public class VariablePool {

    /**
     * Storage for node outputs
     * Key: "node-id.output-name" (e.g., "node-start::001.user_input")
     * Value: actual output value
     */
    private static final Object NULL_VALUE = new Object();
    private static final Pattern SEGMENT = Pattern.compile("([^\\[\\].]+)((?:\\[\\d+\\])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)\\]");
    private final Map<String, Map<String, Object>> variables = new ConcurrentHashMap<>();

    /**
     * Set a variable in the pool
     *
     * @param nodeId     node ID (e.g., "node-start::001")
     * @param outputName output name (e.g., "user_input")
     * @param value      actual value
     */
    public void set(String nodeId, String outputName, Object value) {
        // 基本数据类型、Number、String格式，直接存储； 非基本数据类型，如列表、Map、对象，则转换为JsonObject，JsonArray的方式进行保存
        // 以JsonArray, JsonObject的方式持有变量；方便后续的变量解析
        if (value == null) {
            variables.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>()).put(outputName, NULL_VALUE);
            return;
        }
        if (ClassUtil.isPrimitiveWrapper(value.getClass()) || value instanceof Number || value instanceof String
                || value instanceof JSONArray || value instanceof JSONObject || value instanceof UUID) {

        } else if (value instanceof List<?>) {
            value = JSON.parseArray(JSON.toJSONString(value));
        } else {
            value = JSON.parseObject(JSON.toJSONString(value));
        }

        variables.computeIfAbsent(nodeId, k -> new ConcurrentHashMap<>()).put(outputName, value);
    }

    /**
     * Get a variable from the pool
     *
     * @param nodeId     node ID
     * @param outputName output name
     * @return variable value, or null if not found
     */
    public Object get(String nodeId, String outputName) {
        Map target = variables.getOrDefault(nodeId, Map.of());
        Object value = getVal(target, outputName);
        return value == NULL_VALUE ? null : value;
    }

    public Map<String, Object> get(String nodeId) {
        Map<String, Object> snapshot = new HashMap<>();
        variables.getOrDefault(nodeId, Map.of()).forEach((key, value) -> snapshot.put(key, value == NULL_VALUE ? null : value));
        return snapshot;
    }


    /**
     * 参数提取
     *
     * @param map
     * @param key - data.voice_url 表示从Map中获取key = data的map，然后再从这个map中获取key = voice_url的value
     *            - data[0].voice_url 表示从Map中获取key = data的列表，取第一个元素（是个map），然后从这个map中获取key = voice_url的value
     * @return
     */
    @SuppressWarnings("unchecked")
    private Object getVal(Map map, String key) {
        if (key == null || key.isBlank()) return null;
        Object current = map;
        for (String part : key.split("\\.", -1)) {
            Matcher segment = SEGMENT.matcher(part);
            if (!segment.matches()) throw new IllegalArgumentException("Invalid variable path");
            if (!(current instanceof Map<?, ?> object)) return null;
            current = object.get(segment.group(1));
            Matcher indices = INDEX.matcher(segment.group(2));
            while (indices.find()) {
                int position;
                try { position = Integer.parseInt(indices.group(1)); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid variable array index"); }
                if (!(current instanceof List<?> values) || position >= values.size()) return null;
                current = values.get(position);
            }
            if (current == NULL_VALUE || current == null) return null;
        }
        return current;
    }

    /**
     * Clear all variables (used between workflow executions)
     */
    public void clear() {
        variables.clear();
    }
}
