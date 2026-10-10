package com.ruoyi.paiflow.worker.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.iflytek.astron.workflow.engine.integration.plugins.aitools.Link;
import com.ruoyi.paiflow.worker.adapter.CenterClient;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Assignment;
import com.ruoyi.paiflow.worker.domain.WorkerProtocol.Lease;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 普通Java Link工具的正式中心消费者；只使用固定公开资源和同一操作账本。 */
public final class CenterToolConsumer {
    private static final Set<String> RESOURCE_FIELDS = Set.of("kind", "provider", "version", "enabled", "pluginId",
            "operationId", "legacyVersion", "legacyAppId", "effectClass", "openApiSchema");
    private static final Set<String> ACK_FIELDS = Set.of("runId", "leaseId", "attempt", "workflowVersionId", "dslHash", "nodeId",
            "iterationId", "idempotencyKey", "operationId", "operation", "kind", "provider", "resourceId", "resourceVersion",
            "resourceBindings", "state", "failureKind", "legacyResponse", "stopped", "proofId");
    private static final Set<String> FAILURES = Set.of("provider", "authorization", "protocol", "transport", "deadline", "lease", "cancelled", "budget");
    private static final Set<String> STATES = Set.of("RUNNING", "COMPLETED", "FAILED", "CANCELLED", "EFFECT_UNKNOWN");
    private final ExecutionScope scope;
    private final CenterClient center;
    private final Assignment assignment;
    private final Map<String, Call> calls = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean unknown = new java.util.concurrent.atomic.AtomicBoolean();

    CenterToolConsumer(ExecutionScope scope, CenterClient center, Assignment assignment) {
        this.scope = scope; this.center = center; this.assignment = assignment;
    }

    private static final class Call {
        final Map<String, Object> expected;
        final String requestHash;
        volatile String operationId;
        volatile Map<String, Object> terminal;
        volatile boolean pending = true;
        Call(Map<String, Object> expected, String requestHash) { this.expected = expected; this.requestHash = requestHash; }
    }

    /** 本地固定快照预检必须在begin与原引擎之前；不向中心申请任何新许可。 */
    public static void validateAssignment(Assignment task) {
        if (!"java".equals(task.engineProfile())) throw invalid("此消费者只接原Java执行器的固定图");
        if (!(task.dsl().get("nodes") instanceof List<?> nodes)) throw invalid("固定工具图缺少nodes");
        Set<String> used = new HashSet<>();
        for (Object raw : nodes) {
            if (!(raw instanceof Map<?, ?> node) || !(node.get("id") instanceof String id) || !id.startsWith("plugin::")) continue;
            Map<?, ?> data = map(node.get("data"));
            Map<?, ?> parameters = map(data.get("nodeParam"));
            if ("tool@8b2262bef821000".equals(parameters.get("pluginId"))) {
                throw new WorkerException("RESOURCE_UNAVAILABLE", "TTS受控资源和成果中心接口尚未接通，保留原实现待迁移");
            }
            String reference = text(parameters.get("toolResourceId"), 1024);
            Map<String, Object> descriptor = task.resourceSnapshots().get(reference);
            validateResource(reference, descriptor);
            Object version = parameters.get("version");
            if (version == null || version.toString().isEmpty()) version = "V1.0";
            if (!Objects.equals(parameters.get("pluginId"), descriptor.get("pluginId"))
                    || !Objects.equals(parameters.get("operationId"), descriptor.get("operationId"))
                    || !Objects.equals(parameters.get("appId"), descriptor.get("legacyAppId"))
                    || !Objects.equals(version, descriptor.get("legacyVersion"))) throw invalid("原Java工具节点与固定资源不一致");
            String operation = "read".equals(descriptor.get("effectClass")) ? "tools.read" : "tools.invoke";
            if (!task.effectPolicy().allowedOperations().contains(operation)
                    || "tools.invoke".equals(operation) && !"approved_write".equals(task.effectPolicy().mode())) {
                throw new WorkerException("TOOL_POLICY_DENIED", "固定Java工具用途未获运行许可");
            }
            // 复用原Link解析以核实operation实际可消费，不使用Python参数算法替代Java。
            HashMap<String, Object> original = new HashMap<>();
            original.put("id", descriptor.get("pluginId")); original.put("schema", JSON.toJSONString(descriptor.get("openApiSchema")));
            try {
                Link link = new Link(descriptor.get("legacyAppId").toString(), List.of(descriptor.get("pluginId").toString()),
                        descriptor.get("legacyVersion").toString(), List.of(original));
                if (link.getTools().stream().filter(tool -> descriptor.get("operationId").equals(tool.getOperationId())).count() != 1) {
                    throw invalid("固定schema未提供唯一的原Java工具operation");
                }
            } catch (WorkerException error) { throw error; }
            catch (RuntimeException error) { throw new WorkerException("RESOURCE_UNAVAILABLE", "固定schema尚不能由原Java Link消费", error); }
            used.add(reference);
        }
        Set<String> resources = new HashSet<>();
        task.resourceSnapshots().forEach((id, value) -> { if (id.startsWith("tool:") || "tool".equals(value.get("kind"))) resources.add(id); });
        if (!used.equals(resources)) throw invalid("固定工具快照与原Java节点引用不一致");
    }

    static void validateResource(String id, Map<String, Object> resource) {
        if (resource == null || !resource.keySet().equals(RESOURCE_FIELDS) || !"tool".equals(resource.get("kind"))
                || !"spark-link".equals(resource.get("provider")) || !Boolean.TRUE.equals(resource.get("enabled"))
                || !(resource.get("openApiSchema") instanceof Map<?, ?> schema) || !(schema.get("paths") instanceof Map<?, ?>)
                || schema.containsKey("servers") || !(resource.get("effectClass") instanceof String effect)
                || !Set.of("read", "write").contains(effect)) throw invalid("工具公开快照形状无效");
        for (String field : List.of("version", "pluginId", "operationId", "legacyVersion", "legacyAppId")) text(resource.get(field), "operationId".equals(field) ? 128 : 256);
        String version = resource.get("version").toString();
        String operation = Base64.getUrlEncoder().withoutPadding().encodeToString(resource.get("operationId").toString().getBytes(StandardCharsets.UTF_8));
        if (!version.matches("[1-9][0-9]*") || !id.matches("tool:[1-9][0-9]*:version:" + version + ":operation:[A-Za-z0-9_-]+")
                || !id.endsWith(":operation:" + operation)) throw invalid("工具资源标识与固定版本/operation不一致");
    }

    Map<String, Object> execute(String nodeId, String operation, Map<String, Object> parameters) {
        scope.check();
        if (nodeId == null || !nodeId.startsWith("plugin::")) throw invalid("工具操作必须属于原Java插件节点");
        if (!Set.of("tools.read", "tools.invoke").contains(operation)) throw invalid("工具用途无效");
        validateAssignment(assignment);
        Map<?, ?> node = ((List<?>) assignment.dsl().get("nodes")).stream().filter(raw -> raw instanceof Map<?, ?> item && nodeId.equals(item.get("id")))
                .map(raw -> (Map<?, ?>) raw).findFirst().orElseThrow(() -> invalid("工具节点不属于固定图"));
        String resource = text(map(map(node.get("data")).get("nodeParam")).get("toolResourceId"), 1024);
        Map<String, Object> descriptor = assignment.resourceSnapshots().get(resource);
        if (!parameters.keySet().equals(Set.of("kind", "provider", "resourceId", "resourceVersion", "resourceBindings", "legacyPayload"))
                || !resource.equals(parameters.get("resourceId")) || !descriptor.get("version").equals(parameters.get("resourceVersion"))
                || !"tool".equals(parameters.get("kind")) || !"spark-link".equals(parameters.get("provider"))
                || !(parameters.get("resourceBindings") instanceof Map<?, ?> bindings) || !bindings.isEmpty()
                || !(parameters.get("legacyPayload") instanceof Map<?, ?>)
                || !operation.equals("read".equals(descriptor.get("effectClass")) ? "tools.read" : "tools.invoke")) throw invalid("工具请求与固定用途不一致");
        String hash = hash(parameters);
        String key = assignment.runId() + ":" + assignment.attempt() + ":" + nodeId + ":" + operation + ":" + hash;
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("runId", assignment.runId()); expected.put("leaseId", assignment.leaseId()); expected.put("attempt", assignment.attempt());
        expected.put("workflowVersionId", assignment.workflowVersionId()); expected.put("dslHash", assignment.dslHash());
        expected.put("nodeId", nodeId); expected.put("iterationId", null); expected.put("idempotencyKey", key); expected.put("operation", operation);
        for (String field : List.of("kind", "provider", "resourceId", "resourceVersion", "resourceBindings")) expected.put(field, parameters.get(field));
        Call call = new Call(Collections.unmodifiableMap(expected), hash);
        Call previous = calls.putIfAbsent(nodeId, call);
        if (previous != null) {
            if (!hash.equals(previous.requestHash) || !expected.equals(previous.expected)) throw invalid("同一固定Java工具节点不能换载荷重做供应商调用");
            if (previous.terminal != null) return previous.terminal;
            throw unknown("同一工具操作尚未确认，不能重新派发", null);
        }
        try {
            Map<String, Object> ack = center.operation(lease(), nodeId, operation, parameters, key);
            accept(call, ack);
            while ("RUNNING".equals(ack.get("state"))) {
                scope.check();
                try { Thread.sleep(25); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new WorkerException("RUN_CANCELLED", "等待工具回执时请求停止", error); }
                scope.check();
                ack = center.operationStatus(lease(), call.operationId);
                accept(call, ack);
            }
            if ("EFFECT_UNKNOWN".equals(ack.get("state"))) throw unknown("中心工具结果未知，禁止重发或假定停止", null);
            if ("CANCELLED".equals(ack.get("state"))) throw new WorkerException("RUN_CANCELLED", "中心已确认此工具操作停止");
            scope.check();
            return ack;
        } catch (WorkerException error) {
            if (call.pending && !Set.of("RUN_CANCELLED", "DEPENDENCY_TIMEOUT", "LEASE_LOST").contains(error.getCode())) {
                unknown.set(true); throw unknown("工具登记或回读回执未确认，禁止重发", error);
            }
            throw error;
        } catch (RuntimeException error) { unknown.set(true); throw unknown("工具登记或回读回执未确认，禁止重发", error); }
    }

    boolean hasPending() { return calls.values().stream().anyMatch(call -> call.pending); }
    boolean hasUnknown() { return unknown.get(); }

    /** 取消/期限之后只回读同一已知操作，不通过此例外创建操作或读取新资源。 */
    void stopPending() {
        for (Call call : calls.values()) {
            if (!call.pending || call.operationId == null) continue;
            try { accept(call, center.cancelOperation(lease(), call.operationId)); }
            catch (RuntimeException ignored) { /* 未确认或错误归属保持pending，由中心裁决。 */ }
        }
    }

    private void accept(Call call, Map<String, Object> ack) {
        if (ack == null || !ack.keySet().equals(ACK_FIELDS) || JSON.toJSONBytes(ack, JSONWriter.Feature.WriteMapNullValue).length > 2 * 1024 * 1024) throw unknown("工具回执字段或预算无效", null);
        for (Map.Entry<String, Object> entry : call.expected.entrySet()) {
            Object actual = ack.get(entry.getKey());
            if ("attempt".equals(entry.getKey())) {
                if (!(actual instanceof Integer || actual instanceof Long) || ((Number) actual).longValue() != assignment.attempt()) throw unknown("工具attempt不一致", null);
            } else if (!Objects.equals(entry.getValue(), actual)) throw unknown("工具回执不属于当前固定运行/节点/资源", null);
        }
        String id = text(ack.get("operationId"), 128);
        if (!id.matches("tool-op:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || call.operationId != null && !call.operationId.equals(id)) throw unknown("工具操作标识改变", null);
        Object state = ack.get("state"), failure = ack.get("failureKind"), response = ack.get("legacyResponse");
        if (!(state instanceof String) || !STATES.contains(state) || failure != null && !FAILURES.contains(failure)
                || Set.of("RUNNING", "COMPLETED").contains(state) && failure != null
                || Set.of("FAILED", "CANCELLED", "EFFECT_UNKNOWN").contains(state) && failure == null
                || "COMPLETED".equals(state) && !(response instanceof Map<?, ?>)
                || "FAILED".equals(state) && "provider".equals(failure) && !(response instanceof Map<?, ?>)
                || "RUNNING".equals(state) && response != null
                || Set.of("FAILED", "CANCELLED", "EFFECT_UNKNOWN").contains(state) && !"provider".equals(failure) && response != null
                || !(ack.get("stopped") instanceof Boolean)) throw unknown("工具终态/原回执不一致", null);
        boolean stopped = Boolean.TRUE.equals(ack.get("stopped"));
        Object proof = ack.get("proofId");
        if (stopped && (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state) || !(proof instanceof String value) || value.isBlank() || value.length() > 256)
                || !stopped && proof != null || "CANCELLED".equals(state) && !stopped) throw unknown("工具停止缺少真实证明", null);
        synchronized (call) {
            if (call.terminal != null && !call.terminal.equals(ack)) throw unknown("工具终态回执改变", null);
            call.operationId = id;
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)) {
                call.terminal = Collections.unmodifiableMap(new LinkedHashMap<>(ack)); call.pending = false;
            }
        }
    }

    private Lease lease() { return new Lease(assignment.runId(), assignment.leaseId(), assignment.attempt()); }
    private static String hash(Map<String, Object> value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONBytes(value, JSONWriter.Feature.MapSortField, JSONWriter.Feature.WriteMapNullValue))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static Map<?, ?> map(Object value) { if (!(value instanceof Map<?, ?> result)) throw invalid("原工具配置缺少对象"); return result; }
    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max || text.codePoints().anyMatch(c -> Character.isISOControl(c))) throw invalid("工具固定字符串无效");
        return text;
    }
    private static WorkerException invalid(String message) { return new WorkerException("VERSION_CONFLICT", message); }
    private static WorkerException unknown(String message, Throwable cause) { return new WorkerException("EFFECT_UNKNOWN", message, cause); }
}
