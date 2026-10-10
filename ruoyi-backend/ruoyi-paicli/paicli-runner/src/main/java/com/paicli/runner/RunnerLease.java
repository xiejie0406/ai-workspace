package com.paicli.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** lease/fencing/version 缺一不可；失联、取消或过期立即阻止新的副作用。 */
public final class RunnerLease {
    private final JsonNode claim;
    private volatile Instant expiresAt;
    private final AtomicBoolean invalid = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    public RunnerLease(JsonNode claim) throws IOException {
        this.claim = claim.deepCopy();
        for (String field : new String[]{"taskId", "leaseId", "fencingToken", "version", "policyVersion", "rootFingerprint"})
            if (claim.path(field).asText().isBlank()) throw new IOException("中心租约缺少 " + field);
        if(PersonalDirectoryContext.textOnly(claim)){
            PersonalDirectoryContext.id(claim.path("ownerUserId").asText());PersonalDirectoryContext.id(claim.path("runnerId").asText());
            if(claim.hasNonNull("directoryContextId")||claim.hasNonNull("directoryContext"))throw new IOException("纯问答租约不能声明文件目录");
        }else if(claim.hasNonNull("directoryContext")){
            for(String field:new String[]{"directoryContextId","ownerUserId","runnerId"})PersonalDirectoryContext.id(claim.path(field).asText());
            JsonNode context=claim.path("directoryContext");
            if(!context.isObject()||context.path("version").asLong()<1
                ||!claim.path("directoryContextId").asText().equals(context.path("id").asText())
                ||!claim.path("ownerUserId").asText().equals(context.path("ownerUserId").asText())
                ||!claim.path("runnerId").asText().equals(context.path("runnerId").asText())
                ||!claim.path("rootFingerprint").asText().equals(context.path("rootFingerprint").asText()))throw new IOException("个人租约目录身份或冻结摘要不符");
        }
        expiresAt = expiry(claim.path("expiresAt"));
        ensureActive();
    }
    public JsonNode claim() { return claim.deepCopy(); }
    public String taskId() { return claim.path("taskId").asText(); }
    public String sessionId() { return claim.path("sessionId").asText(); }
    public long version() { return claim.path("version").asLong(); }
    public Instant expiresAt() { return expiresAt; }
    public ObjectNode identity() {
        ObjectNode body = CanonicalJson.MAPPER.createObjectNode();
        for (String name : new String[]{"taskId", "leaseId", "fencingToken", "version", "policyVersion", "rootFingerprint"})
            body.set(name, claim.get(name));
        for(String field:new String[]{"directoryContextId","ownerUserId"})if(claim.has(field))body.set(field,claim.get(field));
        return body;
    }
    public void heartbeat(JsonNode response) throws IOException {
        if (response.path("cancelRequested").asBoolean()) cancelRequested.set(true);
        else if (response.path("version").asLong(-1) != version()) { invalidate(); throw new IOException("任务控制版本改变"); }
        Instant next = expiry(response.path("expiresAt"));
        if (!next.isAfter(Instant.now())) { invalidate(); throw new IOException("心跳返回过期租约"); }
        expiresAt = next;
    }
    public void ensureActive() throws IOException {
        ensureEvidenceAllowed();
        if (cancelRequested.get()) throw new IOException("任务已请求取消，不允许新的副作用");
    }
    public void ensureEvidenceAllowed() throws IOException {
        if (invalid.get() || !expiresAt.isAfter(Instant.now())) throw new IOException("任务租约已失效，执行已停止");
    }
    public boolean isCancellationRequested() { return cancelRequested.get(); }
    public void invalidate() { invalid.set(true); }
    public static Instant expiry(JsonNode value) throws IOException {
        try { return value.isNumber() ? Instant.ofEpochMilli(value.asLong()) : Instant.parse(value.asText()); }
        catch (RuntimeException e) { throw new IOException("无效的中心过期时间", e); }
    }
}
