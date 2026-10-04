package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 只在有效租约下续取固定用途能力；版本或归属变化会暂停，绝不读宿主环境。 */
public final class ManagedProviderGrant {
    private final CenterHttpClient center; private final RunnerLease lease; private final String kind;
    private final JsonNode frozen; private JsonNode context;
    public ManagedProviderGrant(CenterHttpClient center, RunnerLease lease, JsonNode context, String kind) {
        this.center = center; this.lease = lease; this.context = context.deepCopy(); this.frozen = context.deepCopy(); this.kind = kind;
        if (!List.of("managedSecret", "searchSecret", "embeddingSecret").contains(kind)) throw new IllegalArgumentException("grant kind");
    }
    public synchronized JsonNode currentContext() throws IOException {
        try {
            lease.ensureActive(); JsonNode secret = context.path(kind);
            if (!RunnerLease.expiry(secret.path("expiresAt")).isAfter(Instant.now().plusSeconds(5))) {
                JsonNode next = center.post("/paicli/runner/v1/provider-context", lease.identity(), UUID.randomUUID().toString());
                for (String field : switch (kind) { case "managedSecret" -> List.of("provider", "model", "providerConfig", "modelConfig"); case "searchSecret" -> List.of("searchConfig"); default -> List.of("embeddingConfig"); }) {
                    if (!CanonicalJson.sorted(definition(frozen.path(field))).equals(CanonicalJson.sorted(definition(next.path(field))))) throw new IOException("中心固定供应商配置已变化: " + field);
                }
                if (!frozen.path(kind).path("secretRef").asText().equals(next.path(kind).path("secretRef").asText())) throw new IOException("中心用途凭证引用已变化");
                if (!frozen.path(kind).path("secretVersion").equals(next.path(kind).path("secretVersion"))) throw new IOException("中心用途凭证版本已变化");
                context = next.deepCopy(); secret = context.path(kind);
            }
            if (secret.path("secretRef").asText().isBlank() || secret.path("apiKey").asText().isBlank() || !RunnerLease.expiry(secret.path("expiresAt")).isAfter(Instant.now())) throw new IOException("中心供应商能力缺失或已过期");
            return context.deepCopy();
        } catch (IOException failure) { lease.invalidate(); throw failure; }
    }
    public String currentKey() throws IOException { return currentContext().path(kind).path("apiKey").asText(); }
    private static JsonNode definition(JsonNode value) {
        if (!value.isObject()) return value;
        var result = CanonicalJson.MAPPER.createObjectNode();
        for (String field : List.of("id", "version", "provider", "modelId", "model", "baseUrl", "wireApi", "secretRef", "supportsImages", "supportsThinking", "supportsTools", "contextWindow", "providerId", "searchEngine", "transport", "configHash", "loraId", "thinking", "maxOutputTokens", "reasoningEffort"))
            if (value.has(field)) result.set(field, value.get(field));
        return result;
    }
}
