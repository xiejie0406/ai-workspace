package com.iflytek.astron.workflow.engine.integration.plugins.aitools;

import com.iflytek.astron.workflow.engine.domain.NodeState;
import com.iflytek.astron.workflow.engine.domain.chain.Node;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 调用python版内置的 aitools 服务/Java 版的 link 服务，实现工具的能力调用
 *
 * @author YiHui
 * @date 2025/12/4
 */
@Slf4j
@Component
public class AiToolsIntegration {

    @Value("${paiflow.runtime.workflow.services.aitools-url:}")
    private String aitoolsUrl;

    public Map<String, Object> call(NodeState nodeState, Map<String, Object> inputs) throws Exception {
        Node node = nodeState.node();
        log.info("Executing plugin node: {}", node.getId());

        Map<String, Object> nodeParam = node.getData().getNodeParam();
        if (nodeParam == null) {
            throw new IllegalArgumentException("Node parameters are null");
        }

        String pluginId = getString(nodeParam, "pluginId");
        String operationId = getString(nodeParam, "operationId");
        String appId = getString(nodeParam, "appId");

        if (pluginId == null || pluginId.isEmpty()) {
            throw new IllegalArgumentException("Plugin ID is required");
        }

        if (operationId == null || operationId.isEmpty()) {
            throw new IllegalArgumentException("Operation ID is required");
        }

        if (appId == null || appId.isEmpty()) {
            throw new IllegalArgumentException("App ID is required");
        }

        String version = getString(nodeParam, "version");
        if (version == null || version.isEmpty()) {
            version = "V1.0";
        }

        List<String> businessInput = getList(nodeParam, "businessInput");
        if (businessInput == null) {
            businessInput = new ArrayList<>();
        }

        String resourceId = getString(nodeParam, "toolResourceId");
        if (resourceId == null || resourceId.isBlank()) {
            throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "工具节点缺少中心固定资源绑定");
        }
        com.ruoyi.paiflow.worker.service.ExecutionScope scope = com.ruoyi.paiflow.worker.service.ExecutionScope.current();
        Map<String, Object> resource = scope.boundResource(resourceId, Map.of("kind", "tool", "provider", "spark-link",
                "pluginId", pluginId, "operationId", operationId, "legacyVersion", version, "legacyAppId", appId));
        if (!(resource.get("openApiSchema") instanceof Map<?, ?> schema)
                || !java.util.Set.of("read", "write").contains(String.valueOf(resource.get("effectClass")))) {
            throw new com.ruoyi.paiflow.worker.service.WorkerException("RESOURCE_UNAVAILABLE", "工具资源缺少原 schema 或读写分级");
        }
        HashMap<String, Object> originalSchema = new HashMap<>();
        originalSchema.put("id", pluginId);
        originalSchema.put("schema", com.alibaba.fastjson2.JSON.toJSONString(schema));
        Link link = new Link(appId, Arrays.asList(pluginId), version, List.of(originalSchema));

        Map<String, Object> actionInputs = inputs != null ? new HashMap<>(inputs) : new HashMap<>();
        Map<String, Object> outputs = new HashMap<>();

        // Find and execute the matching tool operation
        boolean toolFound = false;
        if (link.getTools() != null) {
            for (Tool tool : link.getTools()) {
                if (tool != null && operationId.equals(tool.getOperationId())) {
                    toolFound = true;
                    tool.bindCenter(node.getId(), resourceId, resource);
                    Map<String, Object> businessInputMap = getBusinessInput(actionInputs, businessInput);

                    try {
                        // Execute the tool operation
                        Map<String, Object> res = tool.run(actionInputs, businessInputMap);
                        outputs = res != null ? res : new HashMap<>();
                        break;
                    } catch (Exception e) {
                        if (e instanceof com.ruoyi.paiflow.worker.service.WorkerException controlled) throw controlled;
                        log.error("Error executing plugin: {}", e.getMessage(), e);
                        throw new RuntimeException("Plugin execution failed: " + e.getMessage(), e);
                    }
                }
            }
        }

        if (!toolFound) {
            // Handle case where plugin operation is not found
            log.error("Plugin not found: {}", operationId);
            throw new UnsupportedOperationException("Plugin not found: " + operationId);
        }

        log.info("Plugin node completed: {}", node.getId());
        return outputs;
    }

    /**
     * Extract business input parameters from action input using recursive traversal.
     *
     * @param actionInput   The input data structure to search through
     * @param businessInput List of keys to extract
     * @return Map containing extracted business input parameters
     */
    private Map<String, Object> getBusinessInput(Map<String, Object> actionInput, List<String> businessInput) {
        if (businessInput == null || businessInput.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, Object> businessInputMap = new HashMap<>();

        // Iterate through each business input key to extract
        for (String inputKey : businessInput) {
            if (inputKey == null || inputKey.isEmpty()) {
                continue;
            }

            // Use iterative approach with queue for breadth-first search
            Queue<Object> iterQueue = new LinkedList<>();
            if (actionInput != null) {
                iterQueue.offer(actionInput);
            }

            while (!iterQueue.isEmpty()) {
                Object iterOne = iterQueue.poll();

                if (iterOne instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> iterMap = (Map<String, Object>) iterOne;

                    // Check if current key matches the target business input key
                    if (iterMap.containsKey(inputKey)) {
                        businessInputMap.put(inputKey, iterMap.get(inputKey));
                        break;
                    }

                    // Add nested structures to queue for further processing
                    for (Object value : iterMap.values()) {
                        if (value instanceof Map || value instanceof List) {
                            iterQueue.offer(value);
                        }
                    }
                } else if (iterOne instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Object> iterList = (List<Object>) iterOne;

                    // Add all nested structures from list to queue
                    for (Object content : iterList) {
                        if (content instanceof Map || content instanceof List) {
                            iterQueue.offer(content);
                        }
                    }
                }
            }
        }

        return businessInputMap;
    }

    public String getString(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private List<String> getList(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) value;
            return list;
        }
        return null;
    }
}
