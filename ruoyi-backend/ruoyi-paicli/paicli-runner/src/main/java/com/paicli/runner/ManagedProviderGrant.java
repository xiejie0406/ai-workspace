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
            lease.ensureActive();validateModelPurpose(); validateEmbeddingPurpose(); JsonNode secret = context.path(kind);
            if (!RunnerLease.expiry(secret.path("expiresAt")).isAfter(Instant.now().plusSeconds(5))) {
                JsonNode next = center.post("/paicli/runner/v1/provider-context", lease.identity(), UUID.randomUUID().toString());
                for (String field : switch (kind) { case "managedSecret" -> List.of("provider", "model", "providerConfig", "modelConfig"); case "searchSecret" -> List.of("searchConfig"); default -> List.of("embeddingConfig"); }) {
                    if (!CanonicalJson.sorted(definition(frozen.path(field))).equals(CanonicalJson.sorted(definition(next.path(field))))) throw new IOException("中心固定供应商配置已变化: " + field);
                }
                if (!frozen.path(kind).path("secretRef").asText().equals(next.path(kind).path("secretRef").asText())) throw new IOException("中心用途凭证引用已变化");
                if (!frozen.path(kind).path("secretVersion").equals(next.path(kind).path("secretVersion"))) throw new IOException("中心用途凭证版本已变化");
                if (!frozen.path(kind).path("approvedHttpRoot").equals(next.path(kind).path("approvedHttpRoot"))) throw new IOException("中心HTTP根地址许可已变化");
                context = next.deepCopy(); secret = context.path(kind);
                validateModelPurpose(); validateEmbeddingPurpose();
            }
            if (secret.path("secretRef").asText().isBlank() || secret.path("apiKey").asText().isBlank() || !RunnerLease.expiry(secret.path("expiresAt")).isAfter(Instant.now())) throw new IOException("中心供应商能力缺失或已过期");
            return context.deepCopy();
        } catch (IOException failure) { lease.invalidate(); throw failure; }
    }
    public String currentKey() throws IOException { return currentContext().path(kind).path("apiKey").asText(); }
    private void validateModelPurpose()throws IOException{
        if(!"managedSecret".equals(kind)||!lease.claim().hasNonNull("ownerUserId"))return;
        String owner=PersonalDirectoryContext.id(lease.claim().path("ownerUserId").asText());
        JsonNode payload=lease.claim().path("payload");String source=payload.path("modelSecretOwnerId").asText(owner);
        if(!source.equals(owner)&&(payload.path("modelReleaseId").asText().isBlank()||payload.path("modelReleaseVersion").asLong()<1))throw new IOException("跨本人模型能力缺少中心冻结发布版本");
        if(!source.equals(context.path("providerConfig").path("ownerUserId").asText())
            ||!source.equals(context.path("modelConfig").path("ownerUserId").asText())
            ||!context.path(kind).path("secretRef").asText().startsWith("ai.paicli.user."+source+"."))throw new IOException("模型供应商和固定用途凭证不属于本人或已发布来源");
    }
    private void validateEmbeddingPurpose()throws IOException{
        if(!"embeddingSecret".equals(kind)||!lease.claim().hasNonNull("ownerUserId"))return;
        String owner=PersonalDirectoryContext.id(lease.claim().path("ownerUserId").asText());
        JsonNode config=context.path("embeddingConfig"),payload=lease.claim().path("payload");
        String source=config.path("sourceOwnerId").asText(owner);
        String fixedSource=payload.path("embeddingSecretOwnerId").asText(owner);
        if(!source.equals(fixedSource)||!context.path(kind).path("secretRef").asText().startsWith("ai.paicli.user."+source+"."))
            throw new IOException("向量用途凭证不属于本人或中心冻结发布来源");
        if(!source.equals(owner)&&(payload.path("embeddingReleaseId").asText().isBlank()
            ||payload.path("embeddingReleaseVersion").asLong()<1
            ||!payload.path("embeddingReleaseId").asText().equals(config.path("releaseId").asText())
            ||payload.path("embeddingReleaseVersion").asLong()!=config.path("releaseVersion").asLong()))
            throw new IOException("跨本人向量能力缺少有效固定发布版本");
    }
    private JsonNode definition(JsonNode value) {
        if (!value.isObject()) return value;
        var result = CanonicalJson.MAPPER.createObjectNode();
        for (String field : List.of("id", "version", "configurationVersion", "ownerUserId", "directoryContextId", "provider", "modelId", "model", "baseUrl", "wireApi", "secretRef", "supportsImages", "imageInput", "supportsThinking", "supportsTools", "contextWindow", "providerId", "searchEngine", "transport", "configHash", "configFingerprint", "expectedDimensions", "sourceOwnerId", "releaseId", "releaseVersion", "loraId", "thinking", "maxOutputTokens", "reasoningEffort"))
            if (value.has(field)) result.set(field, value.get(field));
        if(value.has("configurationVersion"))result.set("version",value.path("configurationVersion"));
        JsonNode task=frozen.has("taskPayload")?frozen.path("taskPayload"):lease.claim().path("payload");
        boolean images=task.path("requiresImageInput").asBoolean()
            ||task.path("attachments").isArray()&&!task.path("attachments").isEmpty()
            ||frozen.path("attachments").isArray()&&!frozen.path("attachments").isEmpty()
            ||containsImages(frozen.path("history"))||containsImages(frozen.path("messages"))
            ||"IMAGES".equals(task.path("arguments").path("capability").asText());
        if(!images){result.remove("supportsImages");result.remove("imageInput");}
        return result;
    }
    private static boolean containsImages(JsonNode value) {
        if(value.isObject()&&(List.of("image_artifact","image_base64","image_url","input_image").contains(value.path("type").asText())
            ||value.path("attachments").isArray()&&!value.path("attachments").isEmpty()))return true;
        if(value.isContainerNode())for(JsonNode child:value)if(containsImages(child))return true;
        return false;
    }
}
