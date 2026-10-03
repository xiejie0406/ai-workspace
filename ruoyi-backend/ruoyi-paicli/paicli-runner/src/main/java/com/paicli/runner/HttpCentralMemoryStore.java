package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.memory.CentralMemoryStore;
import com.paicli.memory.MemoryEntry;
import com.paicli.memory.MemoryWriteResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** 中心持有记忆真相；本类只有当前调用的已限定工作区投递视图，不写本机JSON。 */
public final class HttpCentralMemoryStore implements CentralMemoryStore {
    private final CenterHttpClient center;
    private final RunnerLease lease;
    private final String submittedInput;
    private final String projectKey;
    private final Supplier<ObjectNode> permitBinding;
    private JsonNode controlArguments;
    public HttpCentralMemoryStore withControlArguments(JsonNode value) { this.controlArguments = value.deepCopy(); return this; }

    public HttpCentralMemoryStore(CenterHttpClient center, RunnerLease lease, String submittedInput,
                                 String projectKey, Supplier<ObjectNode> permitBinding) {
        this.center = center; this.lease = lease; this.submittedInput = submittedInput;
        this.projectKey = projectKey; this.permitBinding = permitBinding;
    }

    @Override public List<MemoryEntry> list() {
        JsonNode result = exchange("list", null);
        JsonNode values = result.isArray() ? result : result.path("entries");
        List<MemoryEntry> entries = new ArrayList<>();
        for (JsonNode value : values) entries.add(entry(value));
        return List.copyOf(entries);
    }

    @Override public MemoryWriteResult write(MemoryEntry incoming, String replaceId,
                                              boolean allowConflict, String ignoredProjectKey) {
        ObjectNode body = lease.identity();
        ObjectNode current = permitBinding.get();
        if (current == null || !current.hasNonNull("toolCallId") || !current.hasNonNull("permitToken")) {
            throw new IllegalStateException("记忆正式写入缺少已消费的中心工具许可");
        }
        body.setAll(current);
        body.put("fact", incoming.getContent()); body.put("scope", scope(incoming));
        if (replaceId != null) body.put("id", replaceId);
        body.put("allowConflict", allowConflict);
        JsonNode response = exchange("write", body);
        String status = response.path("status").asText();
        MemoryWriteResult.Status parsed;
        try { parsed = MemoryWriteResult.Status.valueOf(status); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("中心记忆结果状态无效"); }
        MemoryEntry stored = response.path("entry").isObject() ? entry(response.path("entry")) : incoming;
        List<MemoryEntry> conflicts = new ArrayList<>();
        for (JsonNode conflict : response.path("conflicts")) conflicts.add(entry(conflict));
        return new MemoryWriteResult(parsed, stored, conflicts,
                response.path("replaced").isObject() ? entry(response.path("replaced")) : null,
                response.path("scope").asText(scope(incoming)), replaceId);
    }

    @Override public boolean proposeAutomatic(MemoryEntry entry) {
        ObjectNode body = lease.identity(); body.put("fact", entry.getContent()); body.put("scope", "project");
        body.put("originalUserText", submittedInput);
        JsonNode response = exchange("proposeAutomatic", body);
        return response.path("accepted").asBoolean(false);
    }

    @Override public Optional<MemoryEntry> verify(String id, Instant verifiedAt) {
        ObjectNode body = lease.identity(); body.put("id", id); body.put("verifiedAt", verifiedAt.toString());
        JsonNode response = exchange("verify", body);
        return response.path("entry").isObject() ? Optional.of(entry(response.path("entry"))) : Optional.empty();
    }
    @Override public boolean delete(String id) {
        ObjectNode body = lease.identity(); body.put("id", id);
        return exchange("delete", body).path("deleted").asBoolean(false);
    }
    @Override public void clear() {
        if (!exchange("clear", null).path("cleared").asBoolean(false)) throw new IllegalStateException("中心未确认清除记忆");
    }

    private JsonNode exchange(String action, ObjectNode body) {
        try {
            lease.ensureActive();
            ObjectNode request = body == null ? lease.identity() : body;
            if (List.of("verify", "delete", "clear").contains(action)) {
                ObjectNode consumed = permitBinding.get();
                if (controlArguments == null || consumed == null) throw new IOException("记忆管理缺少冻结中心控制参数或已消费许可");
                request.setAll(consumed);
                for (String field : List.of("scope", "version", "id")) if (controlArguments.has(field)) request.set(field, controlArguments.get(field));
            }
            request.put("action", action);
            return center.post("/paicli/runner/v1/memory", request, UUID.randomUUID().toString());
        } catch (IOException failure) {
            lease.invalidate();
            throw new UncheckedIOException("中心记忆请求未确认，任务停止", failure);
        }
    }

    private MemoryEntry entry(JsonNode value) {
        String id = value.path("id").asText();
        if (id.isBlank() || !value.hasNonNull("content")) throw new IllegalStateException("中心记忆条目无效");
        Map<String, String> metadata = new LinkedHashMap<>();
        value.path("metadata").fields().forEachRemaining(field -> metadata.put(field.getKey(), field.getValue().asText()));
        if (!"global".equalsIgnoreCase(metadata.get("scope"))) {
            metadata.put("scope", "project"); metadata.put("project", projectKey);
        }
        if (value.has("version")) metadata.put("version", value.path("version").asText());
        MemoryEntry.MemoryType type;
        try { type = MemoryEntry.MemoryType.valueOf(value.path("type").asText("FACT").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { throw new IllegalStateException("中心记忆类型无效"); }
        String content = value.path("content").asText();
        return new MemoryEntry(id, content, type,
                Instant.parse(value.path("timestamp").asText()), Instant.parse(value.path("lastVerifiedAt").asText()),
                metadata, value.path("tokenCount").asInt(MemoryEntry.estimateTokens(content)));
    }
    private static String scope(MemoryEntry entry) { return "global".equals(entry.getMetadata().get("scope")) ? "global" : "project"; }
}
